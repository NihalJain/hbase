/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.assignment;

import static org.apache.hadoop.hbase.io.hfile.CacheConfig.DEFAULT_EVICT_ON_CLOSE;
import static org.apache.hadoop.hbase.io.hfile.CacheConfig.DEFAULT_EVICT_ON_SPLIT;
import static org.apache.hadoop.hbase.io.hfile.CacheConfig.EVICT_BLOCKS_ON_CLOSE_KEY;
import static org.apache.hadoop.hbase.io.hfile.CacheConfig.EVICT_BLOCKS_ON_SPLIT_KEY;
import static org.apache.hadoop.hbase.master.LoadBalancer.BOGUS_SERVER_NAME;
import static org.apache.hadoop.hbase.master.assignment.AssignmentManager.FORCE_REGION_RETAINMENT;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.client.RetriesExhaustedException;
import org.apache.hadoop.hbase.master.MetricsAssignmentManager;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.master.procedure.AbstractStateMachineRegionProcedure;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.hadoop.hbase.master.procedure.ServerCrashProcedure;
import org.apache.hadoop.hbase.procedure2.ProcedureFutureUtil;
import org.apache.hadoop.hbase.procedure2.ProcedureMetrics;
import org.apache.hadoop.hbase.procedure2.ProcedureStateSerializer;
import org.apache.hadoop.hbase.procedure2.ProcedureSuspendedException;
import org.apache.hadoop.hbase.procedure2.ProcedureUtil;
import org.apache.hadoop.hbase.procedure2.ProcedureYieldException;
import org.apache.hadoop.hbase.util.FutureUtils;
import org.apache.hadoop.hbase.util.RetryCounter;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.RegionStateTransitionState;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.RegionStateTransitionStateData;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.RegionTransitionType;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ProcedureProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RegionServerStatusProtos.RegionStateTransition.TransitionCode;

/**
 * The procedure to deal with the state transition of a region. A region with a TRSP in place is
 * called RIT, i.e, RegionInTransition.
 * <p/>
 * It can be used to assign/unassign/reopen/move a region, and for
 * {@link #unassign(MasterProcedureEnv, RegionInfo)} and
 * {@link #reopen(MasterProcedureEnv, RegionInfo)}, you do not need to specify a target server, and
 * for {@link #assign(MasterProcedureEnv, RegionInfo, ServerName)} and
 * {@link #move(MasterProcedureEnv, RegionInfo, ServerName)}, if you want to you can provide a
 * target server. And for {@link #move(MasterProcedureEnv, RegionInfo, ServerName)}, if you do not
 * specify a targetServer, we will select one randomly.
 * <p/>
 * <p/>
 * The typical state transition for assigning a region is:
 *
 * <pre>
 * GET_ASSIGN_CANDIDATE ------> OPEN -----> CONFIRM_OPENED
 * </pre>
 *
 * Notice that, if there are failures we may go back to the {@code GET_ASSIGN_CANDIDATE} state to
 * try again.
 * <p/>
 * The typical state transition for unassigning a region is:
 *
 * <pre>
 * CLOSE -----> CONFIRM_CLOSED
 * </pre>
 *
 * Here things go a bit different, if there are failures, especially that if there is a server
 * crash, we will go to the {@code GET_ASSIGN_CANDIDATE} state to bring the region online first, and
 * then go through the normal way to unassign it.
 * <p/>
 * The typical state transition for reopening/moving a region is:
 *
 * <pre>
 * CLOSE -----> CONFIRM_CLOSED -----> GET_ASSIGN_CANDIDATE ------> OPEN -----> CONFIRM_OPENED
 * </pre>
 *
 * The retry logic is the same with the above assign/unassign.
 * <p/>
 * Notice that, although we allow specify a target server, it just acts as a candidate, we do not
 * guarantee that the region will finally be on the target server. If this is important for you, you
 * should check whether the region is on the target server after the procedure is finished.
 * </p>
 * Altenatively, for trying retaining assignments, the
 * <b>hbase.master.scp.retain.assignment.force</b> option can be used together with
 * <b>hbase.master.scp.retain.assignment</b>.
 * <p/>
 * When you want to schedule a TRSP, please check whether there is still one for this region, and
 * the check should be under the RegionStateNode lock. We will remove the TRSP from a
 * RegionStateNode when we are done, see the code in {@code reportTransition} method below. There
 * could be at most one TRSP for a give region.
 */
@InterfaceAudience.Private
public class TransitRegionStateProcedure
  extends AbstractStateMachineRegionProcedure<RegionStateTransitionState> {

  private static final Logger LOG = LoggerFactory.getLogger(TransitRegionStateProcedure.class);

  private TransitionType type;

  private RegionStateTransitionState initialState;

  private RegionStateTransitionState lastState;

  // the candidate where we want to assign the region to.
  private ServerName assignCandidate;

  private boolean forceNewPlan;

  private RetryCounter retryCounter;

  private RegionRemoteProcedureBase remoteProc;

  private boolean evictCache;

  private boolean isSplit;

  private RetryCounter forceRetainmentRetryCounter;

  private long forceRetainmentTotalWait;

  private CompletableFuture<Void> future;

  public TransitRegionStateProcedure() {
  }

  private void setInitialAndLastState() {
    switch (type) {
      case ASSIGN:
        initialState = RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE;
        lastState = RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_OPENED;
        break;
      case UNASSIGN:
        initialState = RegionStateTransitionState.REGION_STATE_TRANSITION_CLOSE;
        lastState = RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_CLOSED;
        break;
      case MOVE:
      case REOPEN:
        initialState = RegionStateTransitionState.REGION_STATE_TRANSITION_CLOSE;
        lastState = RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_OPENED;
        break;
      default:
        throw new IllegalArgumentException("Unknown TransitionType: " + type);
    }
  }

  protected TransitRegionStateProcedure(MasterProcedureEnv env, RegionInfo hri,
    ServerName assignCandidate, boolean forceNewPlan, TransitionType type) {
    super(env, hri);
    this.assignCandidate = assignCandidate;
    this.forceNewPlan = forceNewPlan;
    this.type = type;
    setInitialAndLastState();

    // when do reopen TRSP, let the rs know the targetServer so it can keep some info on close
    if (type == TransitionType.REOPEN) {
      this.assignCandidate = getRegionStateNode(env).getRegionLocation();
    }
    evictCache =
      env.getMasterConfiguration().getBoolean(EVICT_BLOCKS_ON_CLOSE_KEY, DEFAULT_EVICT_ON_CLOSE);
    initForceRetainmentRetryCounter(env);
  }

  private void initForceRetainmentRetryCounter(MasterProcedureEnv env) {
    if (env.getAssignmentManager().isForceRegionRetainment()) {
      forceRetainmentRetryCounter =
        new RetryCounter(env.getAssignmentManager().getForceRegionRetainmentRetries(),
          env.getAssignmentManager().getForceRegionRetainmentWaitInterval(), TimeUnit.MILLISECONDS);
      forceRetainmentTotalWait = 0;
    }
  }

  protected TransitRegionStateProcedure(MasterProcedureEnv env, RegionInfo hri,
    ServerName assignCandidate, boolean forceNewPlan, TransitionType type, boolean isSplit) {
    this(env, hri, assignCandidate, forceNewPlan, type);
    this.isSplit = isSplit;
  }

  @Override
  public TableOperationType getTableOperationType() {
    // TODO: maybe we should make another type here, REGION_TRANSITION?
    return TableOperationType.REGION_EDIT;
  }

  @Override
  protected boolean waitInitialized(MasterProcedureEnv env) {
    if (TableName.isMetaTableName(getTableName())) {
      return false;
    }
    // First we need meta to be loaded, and second, if meta is not online then we will likely to
    // fail when updating meta so we wait until it is assigned.
    AssignmentManager am = env.getAssignmentManager();
    return am.waitMetaLoaded(this) || am.waitMetaAssigned(this, getRegion());
  }

  private void checkAndWaitForOriginalServer(MasterProcedureEnv env, ServerName lastHost)
    throws ProcedureSuspendedException {
    ServerManager serverManager = env.getMasterServices().getServerManager();
    ServerName newNameForServer = serverManager.findServerWithSameHostnamePortWithLock(lastHost);
    boolean isOnline = serverManager.createDestinationServersList().contains(newNameForServer);

    if (!isOnline && forceRetainmentRetryCounter.shouldRetry()) {
      int backoff =
        Math.toIntExact(forceRetainmentRetryCounter.getBackoffTimeAndIncrementAttempts());
      forceRetainmentTotalWait += backoff;
      LOG.info(
        "Suspending the TRSP PID={} for {}ms because {} is true and previous host {} "
          + "for region is not yet online.",
        this.getProcId(), backoff, FORCE_REGION_RETAINMENT, lastHost);
      setTimeout(backoff);
      setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
      throw new ProcedureSuspendedException();
    }
    LOG.info(
      "{} is true. TRSP PID={} waited {}ms for host {} to come back online. "
        + "Did host come back online? {}",
      FORCE_REGION_RETAINMENT, this.getProcId(), forceRetainmentTotalWait, lastHost, isOnline);
    initForceRetainmentRetryCounter(env);
  }

  private void queueAssign(MasterProcedureEnv env, RegionStateNode regionNode)
    throws ProcedureSuspendedException {
    boolean retain = false;
    if (forceNewPlan) {
      // set the region location to null if forceNewPlan is true
      regionNode.setRegionLocation(null);
    } else {
      if (assignCandidate != null) {
        retain = assignCandidate.equals(regionNode.getLastHost());
        regionNode.setRegionLocation(assignCandidate);
      } else if (regionNode.getLastHost() != null) {
        retain = true;
        LOG.info("Setting lastHost {} as the location for region {}", regionNode.getLastHost(),
          regionNode.getRegionInfo().getEncodedName());
        regionNode.setRegionLocation(regionNode.getLastHost());
      }
      if (
        regionNode.getRegionLocation() != null
          && env.getAssignmentManager().isForceRegionRetainment()
      ) {
        LOG.warn("{} is set to true. This may delay regions re-assignment "
          + "upon RegionServers crashes or restarts.", FORCE_REGION_RETAINMENT);
        checkAndWaitForOriginalServer(env, regionNode.getRegionLocation());
      }
    }
    LOG.info("Starting {}; {}; forceNewPlan={}, retain={}", this, regionNode.toShortString(),
      forceNewPlan, retain);
    env.getAssignmentManager().queueAssign(regionNode);
    setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_OPEN);
    if (regionNode.getProcedureEvent().suspendIfNotReady(this)) {
      throw new ProcedureSuspendedException();
    }
  }

  private CompletableFuture<Void> getFuture() {
    return future;
  }

  private void setFuture(CompletableFuture<Void> f) {
    future = f;
  }

  private void openRegionAfterUpdatingMeta(ServerName loc) {
    addChildProcedure(new OpenRegionProcedure(this, getRegion(), loc));
    setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_OPENED);
  }

  private void openRegion(MasterProcedureEnv env, RegionStateNode regionNode)
    throws IOException, ProcedureSuspendedException {
    ServerName loc = regionNode.getRegionLocation();
    if (
      ProcedureFutureUtil.checkFuture(this, this::getFuture, this::setFuture,
        () -> openRegionAfterUpdatingMeta(loc))
    ) {
      return;
    }
    if (loc == null || BOGUS_SERVER_NAME.equals(loc)) {
      LOG.warn("No location specified for {}, jump back to state {} to get one", getRegion(),
        RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);
      setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);
      throw new HBaseIOException("Failed to open region, the location is null or bogus.");
    }
    ProcedureFutureUtil.suspendIfNecessary(this, this::setFuture,
      env.getAssignmentManager().regionOpening(regionNode), env,
      () -> openRegionAfterUpdatingMeta(loc));
  }

  private void regionFailedOpenAfterUpdatingMeta(MasterProcedureEnv env,
    RegionStateNode regionNode) {
    setFailure(getClass().getSimpleName(), new RetriesExhaustedException(
      "Max attempts " + env.getAssignmentManager().getAssignMaxAttempts() + " exceeded"));
    regionNode.unsetProcedure(this);
  }

  private Flow confirmOpened(MasterProcedureEnv env, RegionStateNode regionNode)
    throws IOException, ProcedureSuspendedException {
    if (
      ProcedureFutureUtil.checkFuture(this, this::getFuture, this::setFuture,
        () -> regionFailedOpenAfterUpdatingMeta(env, regionNode))
    ) {
      return Flow.NO_MORE_STATE;
    }
    if (regionNode.isInState(State.OPEN)) {
      retryCounter = null;
      if (lastState == RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_OPENED) {
        // we are the last state, finish
        regionNode.unsetProcedure(this);
        ServerCrashProcedure.updateProgress(env, getParentProcId());
        return Flow.NO_MORE_STATE;
      }
      // It is possible that we arrive here but confirm opened is not the last state, for example,
      // when merging or splitting a region, we unassign the region from a RS and the RS is crashed,
      // then there will be recovered edits for this region, we'd better make the region online
      // again and then unassign it, otherwise we have to fail the merge/split procedure as we may
      // loss data.
      setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_CLOSE);
      return Flow.HAS_MORE_STATE;
    }

    int retries = env.getAssignmentManager().getRegionStates().addToFailedOpen(regionNode)
      .incrementAndGetRetries();
    int maxAttempts = env.getAssignmentManager().getAssignMaxAttempts();
    LOG.info("Retry={} of max={}; {}; {}", retries, maxAttempts, this, regionNode.toShortString());

    if (retries >= maxAttempts) {
      ProcedureFutureUtil.suspendIfNecessary(this, this::setFuture,
        env.getAssignmentManager().regionFailedOpen(regionNode, true), env,
        () -> regionFailedOpenAfterUpdatingMeta(env, regionNode));
      return Flow.NO_MORE_STATE;
    }

    // if not giving up, we will not update meta, so the returned CompletableFuture should be a fake
    // one, which should have been completed already
    CompletableFuture<Void> future = env.getAssignmentManager().regionFailedOpen(regionNode, false);
    assert future.isDone();
    // we failed to assign the region, force a new plan
    forceNewPlan = true;
    regionNode.setRegionLocation(null);
    setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);

    if (retries > env.getAssignmentManager().getAssignRetryImmediatelyMaxAttempts()) {
      // Throw exception to backoff and retry when failed open too many times
      throw new HBaseIOException(
        "Failed confirm OPEN of " + regionNode + " (remote log may yield more detail on why).");
    } else {
      // Here we do not throw exception because we want to the region to be online ASAP
      return Flow.HAS_MORE_STATE;
    }
  }

  private void closeRegionAfterUpdatingMeta(MasterProcedureEnv env, RegionStateNode regionNode) {
    LOG.debug("Close region: isSplit: {}: evictOnSplit: {}: evictOnClose: {}", isSplit,
      env.getMasterConfiguration().getBoolean(EVICT_BLOCKS_ON_SPLIT_KEY, DEFAULT_EVICT_ON_SPLIT),
      evictCache);
    // Splits/Merges are special cases, rather than deciding on the cache eviction behaviour here at
    // Master, we just need to tell this close is for a split/merge and let RSes decide on the
    // eviction. See HBASE-28811 for more context.
    CloseRegionProcedure closeProc = new CloseRegionProcedure(this, getRegion(),
      regionNode.getRegionLocation(), assignCandidate, isSplit);
    addChildProcedure(closeProc);
    setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_CLOSED);
  }

  private void closeRegion(MasterProcedureEnv env, RegionStateNode regionNode)
    throws IOException, ProcedureSuspendedException {
    if (
      ProcedureFutureUtil.checkFuture(this, this::getFuture, this::setFuture,
        () -> closeRegionAfterUpdatingMeta(env, regionNode))
    ) {
      return;
    }
    if (regionNode.isInState(State.OPEN, State.CLOSING, State.MERGING, State.SPLITTING)) {
      // this is the normal case
      ProcedureFutureUtil.suspendIfNecessary(this, this::setFuture,
        env.getAssignmentManager().regionClosing(regionNode), env,
        () -> closeRegionAfterUpdatingMeta(env, regionNode));
    } else {
      forceNewPlan = true;
      regionNode.setRegionLocation(null);
      setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);
    }
  }

  private Flow confirmClosed(MasterProcedureEnv env, RegionStateNode regionNode)
    throws IOException {
    if (regionNode.isInState(State.CLOSED)) {
      retryCounter = null;
      if (lastState == RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_CLOSED) {
        // we are the last state, finish
        regionNode.unsetProcedure(this);
        return Flow.NO_MORE_STATE;
      }
      // This means we need to open the region again, should be a move or reopen
      setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);
      return Flow.HAS_MORE_STATE;
    }
    if (regionNode.isInState(State.CLOSING)) {
      // This is possible, think the target RS crashes and restarts immediately, the close region
      // operation will return a NotServingRegionException soon, we can only recover after SCP takes
      // care of this RS. So here we throw an IOException to let upper layer to retry with backoff.
      setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_CLOSE);
      throw new HBaseIOException("Failed to close region");
    }
    // abnormally closed, need to reopen it, no matter what is the last state, see the comment in
    // confirmOpened for more details that why we need to reopen the region first even if we just
    // want to close it.
    // The only exception is for non-default replica, where we do not need to deal with recovered
    // edits. Notice that the region will remain in ABNORMALLY_CLOSED state, the upper layer need to
    // deal with this state. For non-default replica, this is usually the same with CLOSED.
    assert regionNode.isInState(State.ABNORMALLY_CLOSED);
    if (
      !RegionReplicaUtil.isDefaultReplica(getRegion())
        && lastState == RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_CLOSED
    ) {
      regionNode.unsetProcedure(this);
      return Flow.NO_MORE_STATE;
    }
    retryCounter = null;
    setNextState(RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);
    return Flow.HAS_MORE_STATE;
  }

  @Override
  protected void beforeExec(MasterProcedureEnv env) throws ProcedureSuspendedException {
    RegionStateNode regionNode =
      env.getAssignmentManager().getRegionStates().getOrCreateRegionStateNode(getRegion());
    if (!regionNode.isLockedBy(this)) {
      // The wake up action will be called under the lock inside RegionStateNode for implementing
      // RegionStateNodeLock, so if we call ProcedureUtil.wakeUp where we will acquire the procedure
      // execution lock directly, it may cause dead lock since in normal case procedure execution
      // case, we will acquire the procedure execution lock first and then acquire the lock inside
      // RegionStateNodeLock. This is the reason why we need to schedule the task to a thread pool
      // and execute asynchronously.
      regionNode.lock(this,
        () -> env.getAsyncTaskExecutor().execute(() -> ProcedureFutureUtil.wakeUp(this, env)));
    }
  }

  @Override
  protected void afterExec(MasterProcedureEnv env) {
    // only release the lock if there is no pending updating meta operation
    if (future == null) {
      RegionStateNode regionNode =
        env.getAssignmentManager().getRegionStates().getOrCreateRegionStateNode(getRegion());
      // in beforeExec, we may throw ProcedureSuspendedException which means we do not get the lock,
      // in this case we should not call unlock
      if (regionNode.isLockedBy(this)) {
        regionNode.unlock(this);
      }
    }
  }

  private RegionStateNode getRegionStateNode(MasterProcedureEnv env) {
    return env.getAssignmentManager().getRegionStates().getOrCreateRegionStateNode(getRegion());
  }

  @Override
  protected Flow executeFromState(MasterProcedureEnv env, RegionStateTransitionState state)
    throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException {
    RegionStateNode regionNode = getRegionStateNode(env);
    try {
      switch (state) {
        case REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE:
          // Need to do some sanity check for replica region, if the region does not exist at
          // master, do not try to assign the replica region, log error and return.
          if (!RegionReplicaUtil.isDefaultReplica(regionNode.getRegionInfo())) {
            RegionInfo defaultRI =
              RegionReplicaUtil.getRegionInfoForDefaultReplica(regionNode.getRegionInfo());
            if (
              env.getMasterServices().getAssignmentManager().getRegionStates()
                .getRegionStateNode(defaultRI) == null
            ) {
              LOG.error(
                "Cannot assign replica region {} because its primary region {} does not exist.",
                regionNode.getRegionInfo(), defaultRI);
              regionNode.unsetProcedure(this);
              return Flow.NO_MORE_STATE;
            }
          }
          queueAssign(env, regionNode);
          return Flow.HAS_MORE_STATE;
        case REGION_STATE_TRANSITION_OPEN:
          openRegion(env, regionNode);
          return Flow.HAS_MORE_STATE;
        case REGION_STATE_TRANSITION_CONFIRM_OPENED:
          return confirmOpened(env, regionNode);
        case REGION_STATE_TRANSITION_CLOSE:
          closeRegion(env, regionNode);
          return Flow.HAS_MORE_STATE;
        case REGION_STATE_TRANSITION_CONFIRM_CLOSED:
          return confirmClosed(env, regionNode);
        default:
          throw new UnsupportedOperationException("unhandled state=" + state);
      }
    } catch (IOException e) {
      if (retryCounter == null) {
        retryCounter = ProcedureUtil.createRetryCounter(env.getMasterConfiguration());
      }
      long backoff = retryCounter.getBackoffTimeAndIncrementAttempts();
      LOG.warn(
        "Failed transition, suspend {}secs {}; {}; waiting on rectified condition fixed "
          + "by other Procedure or operator intervention",
        backoff / 1000, this, regionNode.toShortString(), e);
      throw suspend(Math.toIntExact(backoff), true);
    }
  }

  /**
   * At end of timeout, wake ourselves up so we run again.
   */
  @Override
  protected synchronized boolean setTimeoutFailure(MasterProcedureEnv env) {
    setState(ProcedureProtos.ProcedureState.RUNNABLE);
    env.getProcedureScheduler().addFront(this);
    return false; // 'false' means that this procedure handled the timeout
  }

  // Should be called with RegionStateNode locked
  public void reportTransition(MasterProcedureEnv env, RegionStateNode regionNode,
    ServerName serverName, TransitionCode code, long seqId, long procId) throws IOException {
    if (remoteProc == null) {
      LOG.warn(
        "There is no outstanding remote region procedure for {}, serverName={}, code={},"
          + " seqId={}, proc={}, should be a retry, ignore",
        regionNode, serverName, code, seqId, this);
      return;
    }
    // The procId could be -1 if it is from an old region server, we need to deal with it so that we
    // can do rolling upgraing.
    if (procId >= 0 && remoteProc.getProcId() != procId) {
      LOG.warn(
        "The pid of remote region procedure for {} is {}, the reported pid={}, serverName={},"
          + " code={}, seqId={}, proc={}, should be a retry, ignore",
        regionNode, remoteProc.getProcId(), procId, serverName, code, seqId, this);
      return;
    }
    remoteProc.reportTransition(env, regionNode, serverName, code, seqId);
  }

  // Should be called with RegionStateNode locked
  public CompletableFuture<Void> serverCrashed(MasterProcedureEnv env, RegionStateNode regionNode,
    ServerName serverName, boolean forceNewPlan) {
    this.forceNewPlan = forceNewPlan;
    if (remoteProc != null) {
      // this means we are waiting for the sub procedure, so wake it up
      try {
        remoteProc.serverCrashed(env, regionNode, serverName);
      } catch (Exception e) {
        return FutureUtils.failedFuture(e);
      }
      return CompletableFuture.completedFuture(null);
    } else {
      if (regionNode.isInState(State.ABNORMALLY_CLOSED)) {
        // should be a retry, where we have already changed the region state to abnormally closed
        return CompletableFuture.completedFuture(null);
      } else {
        // we are in RUNNING state, just update the region state, and we will process it later.
        return env.getAssignmentManager().regionClosedAbnormally(regionNode);
      }
    }
  }

  void attachRemoteProc(RegionRemoteProcedureBase proc) {
    this.remoteProc = proc;
  }

  void unattachRemoteProc(RegionRemoteProcedureBase proc) {
    assert this.remoteProc == proc;
    this.remoteProc = null;
  }

  // will be called after we finish loading the meta entry for this region.
  // used to change the state of the region node if we have a sub procedure, as we may not persist
  // the state to meta yet. See the code in RegionRemoteProcedureBase.execute for more details.
  void stateLoaded(AssignmentManager am, RegionStateNode regionNode) {
    if (remoteProc != null) {
      remoteProc.stateLoaded(am, regionNode);
    }
  }

  @Override
  protected void rollbackState(MasterProcedureEnv env, RegionStateTransitionState state)
    throws IOException, InterruptedException {
    // no rollback
    throw new UnsupportedOperationException();
  }

  @Override
  protected RegionStateTransitionState getState(int stateId) {
    return RegionStateTransitionState.forNumber(stateId);
  }

  @Override
  protected int getStateId(RegionStateTransitionState state) {
    return state.getNumber();
  }

  @Override
  protected RegionStateTransitionState getInitialState() {
    return initialState;
  }

  private static TransitionType convert(RegionTransitionType type) {
    switch (type) {
      case ASSIGN:
        return TransitionType.ASSIGN;
      case UNASSIGN:
        return TransitionType.UNASSIGN;
      case MOVE:
        return TransitionType.MOVE;
      case REOPEN:
        return TransitionType.REOPEN;
      default:
        throw new IllegalArgumentException("Unknown RegionTransitionType: " + type);
    }
  }

  private static RegionTransitionType convert(TransitionType type) {
    switch (type) {
      case ASSIGN:
        return RegionTransitionType.ASSIGN;
      case UNASSIGN:
        return RegionTransitionType.UNASSIGN;
      case MOVE:
        return RegionTransitionType.MOVE;
      case REOPEN:
        return RegionTransitionType.REOPEN;
      default:
        throw new IllegalArgumentException("Unknown TransitionType: " + type);
    }
  }

  @Override
  protected void serializeStateData(ProcedureStateSerializer serializer) throws IOException {
    super.serializeStateData(serializer);
    RegionStateTransitionStateData.Builder builder =
      RegionStateTransitionStateData.newBuilder().setType(convert(type))
        .setForceNewPlan(forceNewPlan).setEvictCache(evictCache).setIsSplit(isSplit);
    if (assignCandidate != null) {
      builder.setAssignCandidate(ProtobufUtil.toServerName(assignCandidate));
    }
    serializer.serialize(builder.build());
  }

  @Override
  protected void deserializeStateData(ProcedureStateSerializer serializer) throws IOException {
    super.deserializeStateData(serializer);
    RegionStateTransitionStateData data =
      serializer.deserialize(RegionStateTransitionStateData.class);
    type = convert(data.getType());
    setInitialAndLastState();
    forceNewPlan = data.getForceNewPlan();
    if (data.hasAssignCandidate()) {
      assignCandidate = ProtobufUtil.toServerName(data.getAssignCandidate());
    }
    evictCache = data.getEvictCache();
    isSplit = data.getIsSplit();
  }

  @Override
  protected ProcedureMetrics getProcedureMetrics(MasterProcedureEnv env) {
    MetricsAssignmentManager metrics = env.getAssignmentManager().getAssignmentManagerMetrics();
    switch (type) {
      case ASSIGN:
        return metrics.getAssignProcMetrics();
      case UNASSIGN:
        return metrics.getUnassignProcMetrics();
      case MOVE:
        return metrics.getMoveProcMetrics();
      case REOPEN:
        return metrics.getReopenProcMetrics();
      default:
        throw new IllegalArgumentException("Unknown transition type: " + type);
    }
  }

  @Override
  public void toStringClassDetails(StringBuilder sb) {
    super.toStringClassDetails(sb);
    if (initialState == RegionStateTransitionState.REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE) {
      sb.append(", ASSIGN");
    } else if (lastState == RegionStateTransitionState.REGION_STATE_TRANSITION_CONFIRM_CLOSED) {
      sb.append(", UNASSIGN");
    } else {
      sb.append(", REOPEN/MOVE");
    }
  }

  private static TransitRegionStateProcedure setOwner(MasterProcedureEnv env,
    TransitRegionStateProcedure proc) {
    proc.setOwner(env.getRequestUser().getShortName());
    return proc;
  }

  public enum TransitionType {
    ASSIGN,
    UNASSIGN,
    MOVE,
    REOPEN
  }

  // Be careful that, when you call these 4 methods below, you need to manually attach the returned
  // procedure with the RegionStateNode, otherwise the procedure will quit immediately without doing
  // anything. See the comment in executeFromState to find out why we need this assumption.
  public static TransitRegionStateProcedure assign(MasterProcedureEnv env, RegionInfo region,
    @Nullable ServerName targetServer) {
    return assign(env, region, false, targetServer);
  }

  public static TransitRegionStateProcedure assign(MasterProcedureEnv env, RegionInfo region,
    boolean forceNewPlan, @Nullable ServerName targetServer) {
    return setOwner(env, new TransitRegionStateProcedure(env, region, targetServer, forceNewPlan,
      TransitionType.ASSIGN));
  }

  public static TransitRegionStateProcedure unassign(MasterProcedureEnv env, RegionInfo region) {
    return setOwner(env,
      new TransitRegionStateProcedure(env, region, null, false, TransitionType.UNASSIGN));
  }

  public static TransitRegionStateProcedure unassignSplitMerge(MasterProcedureEnv env,
    RegionInfo region) {
    return setOwner(env,
      new TransitRegionStateProcedure(env, region, null, false, TransitionType.UNASSIGN, true));
  }

  public static TransitRegionStateProcedure reopen(MasterProcedureEnv env, RegionInfo region) {
    return setOwner(env,
      new TransitRegionStateProcedure(env, region, null, false, TransitionType.REOPEN));
  }

  public static TransitRegionStateProcedure move(MasterProcedureEnv env, RegionInfo region,
    @Nullable ServerName targetServer) {
    return setOwner(env, new TransitRegionStateProcedure(env, region, targetServer,
      targetServer == null, TransitionType.MOVE));
  }
}
