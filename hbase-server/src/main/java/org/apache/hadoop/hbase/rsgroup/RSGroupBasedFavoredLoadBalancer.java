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
package org.apache.hadoop.hbase.rsgroup;

import static org.apache.hadoop.hbase.ServerName.NON_STARTCODE;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.favored.FavoredNodesManager;
import org.apache.hadoop.hbase.favored.FavoredNodesPromoter;
import org.apache.hadoop.hbase.master.balancer.FavoredStochasticBalancer;
import org.apache.hadoop.hbase.net.Address;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;

/**
 * The RSGroupBasedFavoredLoadBalancer class extends RSGroupBasedLoadBalancer and handles various
 * scenarios related to region assignment and balancing.
 * <p>
 * This class ensures that favored nodes are stored in HBase meta during region assignment,
 * leveraging HBase's existing favored node support. When a new HFile is created, favored nodes are
 * passed to the DFS client, and HDFS attempts to place blocks on these favored nodes.
 * </p>
 * <p>
 * Primary scenarios addressed by this class include:
 * </p>
 * <ul>
 * <li><b>Table Creation and Region Assignment:</b> When a table is created, regions are assigned to
 * region servers. The generateFavoredNodesForDaughter method is used to assign favored nodes for
 * new regions.</li>
 * <li><b>Region Split and Merge:</b> For region splits, the generateFavoredNodesForDaughter method
 * is used to generate favored nodes for the daughter regions. For region merges, the
 * generateFavoredNodesForMergedRegion method is used to generate favored nodes for the merged
 * region.</li>
 * <li><b>Cluster Imbalance and Balancer Trigger:</b> The initializeInternal method initializes the
 * internal balancer, which is responsible for handling cluster imbalances based on configured
 * parameters.</li>
 * </ul>
 */
@InterfaceAudience.Private
public class RSGroupBasedFavoredLoadBalancer
  extends RSGroupBasedLoadBalancer implements FavoredNodesPromoter {
  private static final Logger LOG = LoggerFactory.getLogger(RSGroupBasedFavoredLoadBalancer.class);

  private FavoredNodesPromoter favoredNodesPromoter;

  @Override public void initialize() throws IOException {
    super.initializeInternal(FavoredStochasticBalancer.class);
    setFavoredNodesManager(getFavoredNodesManager());
  }

  @Override public void generateFavoredNodesForDaughter(List<ServerName> servers, RegionInfo parent,
    RegionInfo hriA, RegionInfo hriB) throws IOException {
    LOG.info(
      "Generating favored nodes for server list {} with parent region {} and daughter regions {} and {}",
      servers, parent, hriA, hriB);
    String group = rsGroupInfoManager.getRSGroupForTable(parent.getTable()).getName();
    LOG.info("Group of table {} is {}", parent.getTable(), group);
    List<ServerName> groupServers = getGroupServerList(rsGroupInfoManager.getRSGroup(group));
    LOG.info("Group server list: {}", groupServers);
    favoredNodesPromoter.generateFavoredNodesForDaughter(groupServers, parent, hriA, hriB);
    LOG.info("Favored nodes for daughter regions of region {} with group {} are generated", parent,
      group);
  }

  private List<ServerName> getGroupServerList(RSGroupInfo group) {
    List<ServerName> groupServerList = Lists.newArrayList();
    for (Address addr : group.getServers()) {
      groupServerList.add(ServerName.valueOf(addr.getHostname(), addr.getPort(), NON_STARTCODE));
    }
    return groupServerList;
  }

  @Override
  public void generateFavoredNodesForMergedRegion(RegionInfo merged, RegionInfo[] mergeParents)
    throws IOException {
    LOG.info("Generating favored nodes for merged region {} with parents {}", merged, mergeParents);
    favoredNodesPromoter.generateFavoredNodesForMergedRegion(merged, mergeParents);
    LOG.info("Favored nodes for merged region {} with parents {} are generated", merged,
      mergeParents);
  }

  @Override public List<ServerName> getFavoredNodes(RegionInfo regionInfo) {
    return this.favoredNodesPromoter.getFavoredNodes(regionInfo);
  }

  @Override public void setFavoredNodesManager(FavoredNodesManager fnm) {
    favoredNodesPromoter = (FavoredNodesPromoter) getInternalBalancer();
  }

  /**
   * Update the favored nodes for the regions hosted by the server when the server is removed from
   * the cluster.
   *
   * @param serverName ServerName of the server
   * @param impactedRSGroup Old rsGroup of the server
   */
  public void updateFavoredNodeWhenServerRemoved(ServerName serverName, String impactedRSGroup) {
    // Get the list of regions hosted by the server
    Set<RegionInfo> regions = masterServices.getFavoredNodesManager().getRegionsOfFavoredNode(serverName);

    // Get the list of regions to update
    List<RegionInfo> regionsToUpdate = Lists.newArrayList();
    for (RegionInfo region : regions) {
      regionsToUpdate.add(region);
    }

    // Update the favored nodes for the regions
    try {
      updateFavoredNodes(regionsToUpdate, impactedRSGroup);
    } catch (IOException e) {
      LOG.error("Failed to update favored nodes for regions hosted by server {}", serverName, e);
    }
  }

  /**
   * Update the favored nodes for the regions to the destination RSGroup.
   *
   * @param regionsToUpdateFn List of regions for which favored nodes need to be updated
   * @param destinationRSGroup Destination RSGroup for the regions
   * @throws IOException If the destination RSGroup does not exist
   */
  public void updateFavoredNodes(List<RegionInfo> regionsToUpdateFn, String destinationRSGroup)
    throws IOException {
    RSGroupInfo groupInfo = rsGroupInfoManager.getRSGroup(destinationRSGroup);
    if (groupInfo != null) {
      // TODO: We may not need to do this filtering as offline nodes will be taken care of by the
      // balancer
//      List<ServerName> newServers = filterOfflineServers(groupInfo, masterServices.getServerManager().getOnlineServersList());
//      for (RegionInfo regionInfo : regionsToUpdateFn) {
//        getInternalBalancer().randomAssignment(regionInfo, newServers);
//      }
      List<ServerName> onlineServers = getOnlineServersList();
      for (RegionInfo regionInfo : regionsToUpdateFn) {
        super.randomAssignment(regionInfo, onlineServers);
      }
    } else {
      LOG.warn("RSGroup Information found to be null. Some regions might be unassigned.");
    }
  }

  private List<ServerName> getOnlineServersList() {
    return masterServices.getServerManager().getOnlineServersList();
  }
}
