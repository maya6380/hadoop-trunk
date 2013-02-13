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

package org.apache.hadoop.yarn.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.YarnRuntimeException;
import org.apache.hadoop.yarn.service.BreakableService;
import org.apache.hadoop.yarn.service.CompositeService;
import org.apache.hadoop.yarn.service.Service;
import org.apache.hadoop.yarn.service.Service.STATE;
import org.apache.hadoop.yarn.service.ServiceStateException;
import org.junit.Before;
import org.junit.Test;

public class TestCompositeService {

  private static final int NUM_OF_SERVICES = 5;

  private static final int FAILED_SERVICE_SEQ_NUMBER = 2;

  @Before
  public void setup() {
    CompositeServiceImpl.resetCounter();
  }

  @Test
  public void testCallSequence() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");

    // Add services
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      CompositeServiceImpl service = new CompositeServiceImpl(i);
      serviceManager.addTestService(service);
    }

    CompositeServiceImpl[] services = serviceManager.getServices().toArray(
        new CompositeServiceImpl[0]);

    assertEquals("Number of registered services ", NUM_OF_SERVICES,
        services.length);

    Configuration conf = new Configuration();
    // Initialise the composite service
    serviceManager.init(conf);

    //verify they were all inited
    assertInState(services, STATE.INITED);

    // Verify the init() call sequence numbers for every service
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      assertEquals("For " + services[i]
          + " service, init() call sequence number should have been ", i,
          services[i].getCallSequenceNumber());
    }

    // Reset the call sequence numbers
    resetServices(services);

    serviceManager.start();
    //verify they were all started
    assertInState(services, STATE.STARTED);

    // Verify the start() call sequence numbers for every service
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      assertEquals("For " + services[i]
          + " service, start() call sequence number should have been ", i,
          services[i].getCallSequenceNumber());
    }
    resetServices(services);


    serviceManager.stop();
    //verify they were all stopped
    assertInState(services, STATE.STOPPED);

    // Verify the stop() call sequence numbers for every service
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      assertEquals("For " + services[i]
          + " service, stop() call sequence number should have been ",
          ((NUM_OF_SERVICES - 1) - i), services[i].getCallSequenceNumber());
    }

    // Try to stop again. This should be a no-op.
    serviceManager.stop();
    // Verify that stop() call sequence numbers for every service don't change.
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      assertEquals("For " + services[i]
          + " service, stop() call sequence number should have been ",
          ((NUM_OF_SERVICES - 1) - i), services[i].getCallSequenceNumber());
    }
  }

  private void resetServices(CompositeServiceImpl[] services) {
    // Reset the call sequence numbers
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      services[i].reset();
    }
  }

  @Test
  public void testServiceStartup() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");

    // Add services
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      CompositeServiceImpl service = new CompositeServiceImpl(i);
      if (i == FAILED_SERVICE_SEQ_NUMBER) {
        service.setThrowExceptionOnStart(true);
      }
      serviceManager.addTestService(service);
    }

    CompositeServiceImpl[] services = serviceManager.getServices().toArray(
        new CompositeServiceImpl[0]);

    Configuration conf = new Configuration();

    // Initialise the composite service
    serviceManager.init(conf);

    // Start the composite service
    try {
      serviceManager.start();
      fail("Exception should have been thrown due to startup failure of last service");
    } catch (YarnRuntimeException e) {
      for (int i = 0; i < NUM_OF_SERVICES - 1; i++) {
        if (i >= FAILED_SERVICE_SEQ_NUMBER) {
          // Failed service state should be INITED
          assertEquals("Service state should have been ", STATE.INITED,
              services[NUM_OF_SERVICES - 1].getServiceState());
        } else {
          assertEquals("Service state should have been ", STATE.STOPPED,
              services[i].getServiceState());
        }
      }

    }
  }

  @Test
  public void testServiceStop() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");

    // Add services
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      CompositeServiceImpl service = new CompositeServiceImpl(i);
      if (i == FAILED_SERVICE_SEQ_NUMBER) {
        service.setThrowExceptionOnStop(true);
      }
      serviceManager.addTestService(service);
    }

    CompositeServiceImpl[] services = serviceManager.getServices().toArray(
        new CompositeServiceImpl[0]);

    Configuration conf = new Configuration();

    // Initialise the composite service
    serviceManager.init(conf);

    serviceManager.start();

    // Stop the composite service
    try {
      serviceManager.stop();
    } catch (YarnRuntimeException e) {
      assertInState(services, STATE.STOPPED);
    }
  }

  /**
   * Assert that all services are in the same expected state
   * @param services services to examine
   * @param expected expected state value
   */
  private void assertInState(CompositeServiceImpl[] services,
                             STATE expected) {
    for (Service service: services) {
      assertEquals("Service state should have been " + expected + " in "
                   + service,
                   expected,
                   service.getServiceState());
    }
  }

  /**
   * Shut down from not-inited, assert that none were 
   */
  @Test
  public void testServiceStopFromNotInited() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");

    // Add services
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      CompositeServiceImpl service = new CompositeServiceImpl(i);
      serviceManager.addTestService(service);
    }

    CompositeServiceImpl[] services = serviceManager.getServices().toArray(
      new CompositeServiceImpl[0]);
    serviceManager.stop();
    assertInState(services, STATE.STOPPED);
    // Verify the stop() call sequence numbers for every service
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      assertEquals("For " + services[i]
                   + " service, stop() call sequence number should have been ",
                   ((NUM_OF_SERVICES - 1) - i),
                   services[i].getCallSequenceNumber());
    }
  }

  /**
   * Shut down from not-inited, assert that none were 
   */
  @Test
  public void testServiceStopFromInited() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");

    // Add services
    for (int i = 0; i < NUM_OF_SERVICES; i++) {
      CompositeServiceImpl service = new CompositeServiceImpl(i);
      serviceManager.addTestService(service);
    }

    CompositeServiceImpl[] services = serviceManager.getServices().toArray(
      new CompositeServiceImpl[0]);
    serviceManager.init(new Configuration());
    serviceManager.stop();
    assertInState(services, STATE.STOPPED);
    assertEquals(NUM_OF_SERVICES, CompositeServiceImpl.getCounter());
  }

  /**
   * Use a null configuration & expect a failure
   * @throws Throwable
   */
  @Test
  public void testInitNullConf() throws Throwable {
    ServiceManager serviceManager = new ServiceManager("testInitNullConf");

    CompositeServiceImpl service = new CompositeServiceImpl(0);
    serviceManager.addTestService(service);
    try {
      serviceManager.init(null);
      fail("Expected a failure, got " + serviceManager);
    } catch (ServiceStateException e) {
      //expected
    }
  }
  
  /**
   * Shut down from not-inited, assert that none were 
   */
  @Test
  public void testServiceLifecycleNoChildrenl() {
    ServiceManager serviceManager = new ServiceManager("ServiceManager");
    serviceManager.init(new Configuration());
    serviceManager.start();
    serviceManager.stop();
  }

    public static class CompositeServiceImpl extends CompositeService {

    private static int counter = -1;

    private int callSequenceNumber = -1;

    private boolean throwExceptionOnStart;

    private boolean throwExceptionOnStop;

    public CompositeServiceImpl(int sequenceNumber) {
      super(Integer.toString(sequenceNumber));
    }

    @Override
    protected void innerInit(Configuration conf) {
      counter++;
      callSequenceNumber = counter;
      super.innerInit(conf);
    }

    @Override
    protected void innerStart() {
      if (throwExceptionOnStart) {
        throw new YarnRuntimeException("Fake service start exception");
      }
      counter++;
      callSequenceNumber = counter;
      super.innerStart();
    }

    @Override
    protected void innerStop() {
      counter++;
      callSequenceNumber = counter;
      if (throwExceptionOnStop) {
        throw new YarnRuntimeException("Fake service stop exception");
      }
      super.innerStop();
    }

    public static int getCounter() {
      return counter;
    }

    public int getCallSequenceNumber() {
      return callSequenceNumber;
    }

    public void reset() {
      callSequenceNumber = -1;
      counter = -1;
    }

    public static void resetCounter() {
      counter = -1;
    }

    public void setThrowExceptionOnStart(boolean throwExceptionOnStart) {
      this.throwExceptionOnStart = throwExceptionOnStart;
    }

    public void setThrowExceptionOnStop(boolean throwExceptionOnStop) {
      this.throwExceptionOnStop = throwExceptionOnStop;
    }

  }

  public static class ServiceManager extends CompositeService {

    public void addTestService(CompositeService service) {
      addService(service);
    }

    public ServiceManager(String name) {
      super(name);
    }
  }

}
