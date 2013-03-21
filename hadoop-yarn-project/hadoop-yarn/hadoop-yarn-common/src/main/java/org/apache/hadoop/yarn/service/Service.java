/**
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

package org.apache.hadoop.yarn.service;

import org.apache.hadoop.conf.Configuration;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Service LifeCycle.
 */
public interface Service {

  /**
   * Service states
   */
  public enum STATE {
    /** Constructed but not initialized */
    NOTINITED(0, "NOTINITED"),

    /** Initialized but not started or stopped */
    INITED(1, "INITED"),

    /** started and not stopped */
    STARTED(2, "STARTED"),

    /** stopped. No further state transitions are permitted */
    STOPPED(3, "STOPPED");

    /**
     * An integer value for use in array lookup and JMX interfaces.
     * Although {@link Enum#ordinal()} could do this, explicitly
     * identify the numbers gives more stability guarantees over time.
     */
    private final int value;
    
    /**
     * A name of the state that can be used in messages
     */
    private final String statename;

    private STATE(int value, String name) {
      this.value = value;
      this.statename = name;
    }

    public int getValue() {
      return value;
    }

    @Override
    public String toString() {
      return statename;
    }
  }

  /**
   * Initialize the service.
   *
   * The transition must be from {@link STATE#NOTINITED} to {@link STATE#INITED}
   * unless the operation failed and an exception was raised.
   * @param config the configuration of the service
   */
  void init(Configuration config);


  /**
   * Start the service.
   *
   * The transition should be from {@link STATE#INITED} to {@link STATE#STARTED}
   * unless the operation failed and an exception was raised.
   */

  void start();

  /**
   * Stop the service.
   *
   * This operation must be designed to complete regardless of the initial state
   * of the service, including the state of all its internal fields.
   */
  void stop();

  /**
   * Register an instance of the service state change events.
   * @param listener a new listener
   */
  void register(ServiceStateChangeListener listener);

  /**
   * Unregister a previously instance of the service state change events.
   * @param listener the listener to unregister.
   */
  void unregister(ServiceStateChangeListener listener);

  /**
   * Get the name of this service.
   * @return the service name
   */
  String getName();

  /**
   * Get the configuration of this service.
   * This is normally not a clone and may be manipulated, though there are no
   * guarantees as to what the consequences of such actions may be
   * @return the current configuration, unless a specific implentation chooses
   * otherwise.
   */
  Configuration getConfig();

  /**
   * Get the current service state
   * @return the state of the service
   */
  STATE getServiceState();

  /**
   * Get the service start time
   * @return the start time of the service. This will be zero if the service
   * has not yet been started.
   */
  long getStartTime();

  /**
   * Query to see if the service is in a specific state. 
   * In a multi-threaded system, the state may not hold for very long.
   * @param state the expected state
   * @return true if, at the time of invocation, the service was in that state.
   */
  boolean inState(STATE state);

  /**
   * Get the first exception raised during the service failure. If null, no exception was logged
   * @return the failure logged during a transition to the stopped state

   */
  Throwable getFailureCause();

  /**
   * Get the state in which the failure in {@link #getFailureCause()} occurred.
   * @return the state or null if there was no failure
   */
  STATE getFailureState();

  /**
   * Block waiting for the service to stop; uses the termination notification
   * object to do so. 
   *
   * This method will only return after all the service stop actions
   * have been executed (to success or failure), or the timeout elapsed
   * This method can be called before the service is inited or started; this is
   * to eliminate any race condition with the service stopping before
   * this event occurs.
   * @param timeout timeout in milliseconds. A value of zero means "forever"
   * @return true iff the service stopped in the time period
   */
  boolean waitForServiceToStop(long timeout);

  /**
   * Get a snapshot of the lifecycle history; it is a static list
   * @return a possibly empty but never null list of lifecycle events.
   */
  public List<LifecycleEvent> getLifecycleHistory();

  /**
   * A serializable lifecycle event: the time a state
   * transition occurred, and what state was entered.
   */
  public class LifecycleEvent implements Serializable {
    /**
     * Local time in milliseconds when the event occurred 
     */
    public long time;
    /**
     * new state
     */
    public Service.STATE state;
  }

  /**
   * A serializable structure to describe a block for a service.
   * The {@link #name} field should be constant over versions; 
   * {@link #details} is for people.
   */
  public class Block implements Serializable {
    /**
     * Local time in milliseconds when the event occurred 
     */
    public long time;

    public String name;
    
    public String details;
  }

  /**
   * Get a list of blocks on a service -remote dependencies
   * that are stopping the service from being <i>live</i>.
   * @return a list of blocks. This list is a snapshot.
   */
  public Map<Block,String> listBlocks();
}
