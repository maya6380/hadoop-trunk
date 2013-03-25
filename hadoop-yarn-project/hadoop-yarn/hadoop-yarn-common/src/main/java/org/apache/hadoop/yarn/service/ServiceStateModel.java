/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.yarn.service;

/**
 * Implements the service state model for YARN.
 */
public class ServiceStateModel {

  /**
   * Map of all valid state transitions
   * [current] [proposed1, proposed2, ...]
   * 
   * The 
   */
  private static final boolean[][] statemap =
    {
      //                uninited inited started stopped
      /* uninited  */    {false,  true, false,  true},
      /* initied   */    {false, false,  true,  true},
      /* started   */    {false, false, false,  true},
      /* stopped   */    {false, false, false,  true},
    };

  /**
   * The state of the service
   */
  private volatile Service.STATE state;

  /**
   * Create the service state model in the {@link Service.STATE#NOTINITED}
   * state.
   */
  public ServiceStateModel() {
    this(Service.STATE.NOTINITED);
  }

  /**
   * Create a service state model instance in the chosen state
   * @param state the starting state
   */
  public ServiceStateModel(Service.STATE state) {
    this.state = state;
  }

  /**
   * Query the service state. This is a non-blocking operation.
   * @return the state
   */
  public Service.STATE getState() {
    return state;
  }

  /**
   * Query that the state is in a specific state
   * @param proposed proposed new state
   * @return the state
   */
  public boolean inState(Service.STATE proposed) {
    return state.equals(proposed);
  } 
  
  /**
   * Verify that that a service is in a given state.
   * @param expectedState the desired state
   * @throws ServiceStateException if the service state is different from
   * the desired state
   */
  public void ensureCurrentState(Service.STATE expectedState) {
    if (state != expectedState) {
      throw new ServiceStateException("For this operation, the " +
                                      "current service state must be "
                                      + expectedState
                                      + " instead of " + state);
    }
  }

  /**
   * Enter a state -thread safe. 
   *
   * @param proposed proposed new state
   * @return the original state
   * @throws ServiceStateException if the transition is not permitted
   */
  public synchronized Service.STATE enterState(Service.STATE proposed) {
    checkStateTransition(state, proposed);
    Service.STATE original = state;
    //atomic write of the new state
    state = proposed;
    return original;
  }

  /**
   * Check that a state tansition is valid and 
   * throw an exception if not
   * @param state current state
   * @param proposed proposed new state
   */
  public static void checkStateTransition(Service.STATE state, Service.STATE proposed) {
    if (!isValidStateTransition(state, proposed)) {
      throw new ServiceStateException("Cannot enter state " 
                                      + proposed + " from state " + state);
    }
  }

  /**
   * Is a state transition valid?
   * There are no checks for current=proposed
   * as that is considered a non-transition.
   *
   * using an array kills off all branch misprediction costs, at the expense
   * of cache line misses.
   *
   * @param current current state
   * @param proposed proposed new state
   * @return true if the transition to a new state is valid
   */
  public static boolean isValidStateTransition(Service.STATE current,
                                               Service.STATE proposed) {
    boolean[] row = statemap[current.getValue()];
    return row[proposed.getValue()];
  }

  /**
   * return the state text as the toString() value
   * @return the current state's description
   */
  @Override
  public String toString() {
    return state.toString();
  }
  
}
