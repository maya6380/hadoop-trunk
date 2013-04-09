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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

public class TestServiceLifecycle extends ServiceAssert {
  private static Log LOG = LogFactory.getLog(TestServiceLifecycle.class);

  /**
   * Walk the {@link BreakableService} through it's lifecycle, 
   * more to verify that service's counters work than anything else
   * @throws Throwable if necessary
   */
  @Test
  public void testWalkthrough() throws Throwable {

    BreakableService svc = new BreakableService();
    assertServiceStateCreated(svc);
    assertStateCount(svc, Service.STATE.NOTINITED, 1);
    assertStateCount(svc, Service.STATE.INITED, 0);
    assertStateCount(svc, Service.STATE.STARTED, 0);
    assertStateCount(svc, Service.STATE.STOPPED, 0);
    svc.init(new Configuration());
    assertServiceStateInited(svc);
    assertStateCount(svc, Service.STATE.INITED, 1);
    svc.start();
    assertServiceStateStarted(svc);
    assertStateCount(svc, Service.STATE.STARTED, 1);
    svc.stop();
    assertServiceStateStopped(svc);
    assertStateCount(svc, Service.STATE.STOPPED, 1);
  }

  /**
   * call init twice
   * @throws Throwable if necessary
   */
  @Test
  public void testInitTwice() throws Throwable {
    BreakableService svc = new BreakableService();
    Configuration conf = new Configuration();
    conf.set("test.init","t");
    svc.init(conf);
    try {
      svc.init(new Configuration());
      fail("Expected a failure, got " + svc);
    } catch (ServiceStateException e) {
      //expected
    }
    assertStateCount(svc, Service.STATE.INITED, 1);
    assertServiceConfigurationContains(svc, "test.init");
  }

  /**
   * Call start twice
   * @throws Throwable if necessary
   */
  @Test
  public void testStartTwice() throws Throwable {
    BreakableService svc = new BreakableService();
    svc.init(new Configuration());
    svc.start();
    try {
      svc.start();
      fail("Expected a failure, got " + svc);
    } catch (ServiceStateException e) {
      //expected
    }
    assertStateCount(svc, Service.STATE.STARTED, 1);
  }


  /**
   * Verify that when a service is stopped more than once, no exception
   * is thrown.
   * @throws Throwable if necessary
   */
  @Test
  public void testStopTwice() throws Throwable {
    BreakableService svc = new BreakableService();
    svc.init(new Configuration());
    svc.start();
    svc.stop();
    assertStateCount(svc, Service.STATE.STOPPED, 1);
    svc.stop();
    assertStateCount(svc, Service.STATE.STOPPED, 1);
  }


  /**
   * Show that if the service failed during an init
   * operation, it stays in the created state, even after stopping it
   * @throws Throwable if necessary
   */

  @Test
  public void testStopFailedInit() throws Throwable {
    BreakableService svc = new BreakableService(true, false, false);
    assertServiceStateCreated(svc);
    try {
      svc.init(new Configuration());
      fail("Expected a failure, got " + svc);
    } catch (BreakableService.BrokenLifecycleEvent e) {
      //expected
    }
    //the service state wasn't passed
    assertServiceStateStopped(svc);
    assertStateCount(svc, Service.STATE.INITED, 1);
    assertStateCount(svc, Service.STATE.STOPPED, 1);
    //now try to stop
    svc.stop();
    assertStateCount(svc, Service.STATE.STOPPED, 1);
  }


  /**
   * Show that if the service failed during an init
   * operation, it stays in the created state, even after stopping it
   * @throws Throwable if necessary
   */

  @Test
  public void testStopFailedStart() throws Throwable {
    BreakableService svc = new BreakableService(false, true, false);
    svc.init(new Configuration());
    assertServiceStateInited(svc);
    try {
      svc.start();
      fail("Expected a failure, got " + svc);
    } catch (BreakableService.BrokenLifecycleEvent e) {
      //expected
    }
    //the service state wasn't passed
    assertServiceStateStopped(svc);
  }

  /**
   * verify that when a service fails during its stop operation,
   * its state does not change.
   * @throws Throwable if necessary
   */
  @Test
  public void testFailingStop() throws Throwable {
    BreakableService svc = new BreakableService(false, false, true);
    svc.init(new Configuration());
    svc.start();
    try {
      svc.stop();
      fail("Expected a failure, got " + svc);
    } catch (BreakableService.BrokenLifecycleEvent e) {
      //expected
    }
    assertStateCount(svc, Service.STATE.STOPPED, 1);
  }

  /**
   * verify that when a service that is not started is stopped, the
   * service enters the stopped state
   * @throws Throwable on a failure
   */
  @Test
  public void testStopUnstarted() throws Throwable {
    BreakableService svc = new BreakableService();
    svc.stop();
    assertServiceStateStopped(svc);
    assertStateCount(svc, Service.STATE.INITED, 0);
    assertStateCount(svc, Service.STATE.STOPPED, 1);
  }

  /**
   * Show that if the service failed during an init
   * operation, stop was called. 
   */

  @Test
  public void testStopFailingInitAndStop() throws Throwable {
    BreakableService svc = new BreakableService(true, false, true);
    svc.register(new LoggingStateChangeListener());
    try {
      svc.init(new Configuration());
      fail("Expected a failure, got " + svc);
    } catch (BreakableService.BrokenLifecycleEvent e) {
      assertEquals(Service.STATE.INITED, e.state);
    }
    //the service state is stopped
    assertServiceStateStopped(svc);
    assertEquals(Service.STATE.INITED, svc.getFailureState());

    Throwable failureCause = svc.getFailureCause();
    assertNotNull("Null failure cause in " + svc, failureCause);
    BreakableService.BrokenLifecycleEvent cause =
      (BreakableService.BrokenLifecycleEvent) failureCause;
    assertNotNull("null state in " + cause + " raised by " + svc, cause.state);
    assertEquals(Service.STATE.INITED, cause.state);
  }

  @Test
  public void testInitNullConf() throws Throwable {
    BreakableService svc = new BreakableService(false, false, false);
    try {
      svc.init(null);
      LOG.warn("Null Configurations are permitted ");
    } catch (ServiceStateException e) {
      //expected
    }
  }

  @Test
  public void testServiceNotifications() throws Throwable {
    BreakableService svc = new BreakableService(false, false, false);
    BreakableStateChangeListener listener = new BreakableStateChangeListener();
    svc.register(listener);
    svc.init(new Configuration());
    assertEquals(1, listener.getEventCount());
    svc.start();
    assertEquals(2, listener.getEventCount());
    svc.stop();
    assertEquals(3, listener.getEventCount());
    svc.stop();
    assertEquals(3, listener.getEventCount());
  }

  @Test
  public void testServiceFailingNotifications() throws Throwable {
    BreakableService svc = new BreakableService(false, false, false);
    BreakableStateChangeListener listener = new BreakableStateChangeListener();
    listener.setFailingState(Service.STATE.STARTED);
    svc.register(listener);
    svc.init(new Configuration());
    assertEquals(1, listener.getEventCount());
    //start this; the listener failed but this won't show
    svc.start();
    //counter went up
    assertEquals(2, listener.getEventCount());
    assertEquals(1, listener.getFailureCount());
    //stop the service -this doesn't fail
    svc.stop();
    assertEquals(3, listener.getEventCount());
    assertEquals(1, listener.getFailureCount());
    svc.stop();
  }

}
