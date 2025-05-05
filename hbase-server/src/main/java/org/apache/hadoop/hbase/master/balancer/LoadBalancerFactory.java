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
package org.apache.hadoop.hbase.master.balancer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.master.LoadBalancer;
import org.apache.hadoop.hbase.rsgroup.RSGroupBasedLoadBalancer;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.yetus.audience.InterfaceAudience;
import static org.apache.hadoop.hbase.master.LoadBalancer.HBASE_RSGROUP_LOADBALANCER_CLASS;

/**
 * The class that creates a load balancer from a conf.
 */
@InterfaceAudience.Private
public final class LoadBalancerFactory {

  private LoadBalancerFactory() {
  }

  /**
   * The default {@link LoadBalancer} class.
   * @return The Class for the default {@link LoadBalancer}.
   */
  public static Class<? extends LoadBalancer> getDefaultLoadBalancerClass() {
    return StochasticLoadBalancer.class;
  }

  /**
   * Create a loadbalancer from the given conf. This will be used by the base load balancer to
   * determine the per group load balancer to use.
   *
   * @return A {@link LoadBalancer}
   */
  public static LoadBalancer getLoadBalancer(Configuration conf) {
    // Create the balancer
    Class<? extends LoadBalancer> balancerKlass =
      conf.getClass(HBASE_RSGROUP_LOADBALANCER_CLASS, getDefaultLoadBalancerClass(),
        LoadBalancer.class);
    return ReflectionUtils.newInstance(balancerKlass);
  }

  /**
   * The default {@link RSGroupBasedLoadBalancer} class.
   *
   * @return The Class for the default {@link RSGroupBasedLoadBalancer}.
   */
  public static Class<? extends RSGroupBasedLoadBalancer> getDefaultRSGroupLoadBalancerClass() {
    return RSGroupBasedLoadBalancer.class;
  }

  /**
   * Create a RSGroup loadbalancer from the given conf. This will be used by the master to
   * determine the base load balancer to use.
   *
   * @return A {@link RSGroupBasedLoadBalancer}
   */
  public static RSGroupBasedLoadBalancer getBaseLoadBalancer(Configuration conf) {
    // Create the balancer
    Class<? extends RSGroupBasedLoadBalancer> balancerKlass =
      conf.getClass(HConstants.HBASE_MASTER_LOADBALANCER_CLASS, getDefaultRSGroupLoadBalancerClass(),
        RSGroupBasedLoadBalancer.class);
    return ReflectionUtils.newInstance(balancerKlass);
  }
}
