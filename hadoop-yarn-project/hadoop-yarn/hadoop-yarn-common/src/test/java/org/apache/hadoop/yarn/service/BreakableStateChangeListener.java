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
 * A state change listener that logs the number of state change events received,
 * and the last state invoked.
 *
 * It can be configured to fail during a state change event
 */
public class BreakableStateChangeListener
    implements ServiceStateChangeListener {

  private final String name;

  public BreakableStateChangeListener() {
    this( "BreakableStateChangeListener");
  }

  public BreakableStateChangeListener(String name) {
    this.name = name;
  }

  private int eventCount;
  private int failureCount;
  private Service lastService;
  private Service.STATE lastState = Service.STATE.NOTINITED;
  //no callbacks are ever received for this event, so it
  //can be used as an 'undefined'.
  private Service.STATE failingState = Service.STATE.NOTINITED;

  @Override
  public synchronized void stateChanged(Service service) {
    eventCount++;
    lastService = service;
    lastState = service.getServiceState();
    if (lastState == failingState) {
      failureCount++;
      throw new BreakableService.BrokenLifecycleEvent(service,
                                                      "Failure entering "
                                                      + lastState
                                                      + " for "
                                                      + service.getName());
    }
  }

  public synchronized int getEventCount() {
    return eventCount;
  }

  public synchronized Service getLastService() {
    return lastService;
  }

  public synchronized Service.STATE getLastState() {
    return lastState;
  }

  public synchronized void setFailingState(Service.STATE failingState) {
    this.failingState = failingState;
  }

  public synchronized int getFailureCount() {
    return failureCount;
  }

  @Override
  public synchronized String toString() {
    return name + " - event count = " + eventCount + " last state " + lastState;
  }
}
