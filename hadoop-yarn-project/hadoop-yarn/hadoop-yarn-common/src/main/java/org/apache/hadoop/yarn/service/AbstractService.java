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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

public abstract class AbstractService implements Service {

  private static final Log LOG = LogFactory.getLog(AbstractService.class);

  /**
   * Service name.
   */
  private final String name;

  /** service state */
  private final ServiceStateModel stateModel;

  /**
   * Service start time. Will be zero until the service is started.
   */
  private long startTime;

  /**
   * The configuration. Will be null until the service is initialized.
   */
  private volatile Configuration config;

  /**
   * List of state change listeners; it is final to ensure
   * that it will never be null.
   */
  private final ServiceOperations.ServiceListeners listeners
    = new ServiceOperations.ServiceListeners();
  /**
   * Static listeners to all events across all services
   */
  private static ServiceOperations.ServiceListeners globalListeners
    = new ServiceOperations.ServiceListeners();

  /**
   * The cause of any failure -will be null.
   * if a service did not stop due to a failure.
   */
  private Exception failureCause;

  /**
   * the state in which the service was when it failed.
   * Only valid when the service is stopped due to a failure
   */
  private STATE failureState = null;

  /**
   * object used to co-ordinate {@link #waitForServiceToStop(long)}
   * across threads.
   */
  private final AtomicBoolean terminationNotification =
    new AtomicBoolean(false);

  /**
   * History of lifecycle transitions
   */
  private final List<LifecycleEvent> lifecycleHistory
    = new ArrayList<LifecycleEvent>(5);

  /**
   * Map of blocking dependencies
   */
  private final Map<String,String> blockerMap = new HashMap<String, String>();

  /**
   * Construct the service.
   * @param name service name
   */
  public AbstractService(String name) {
    this.name = name;
    stateModel = new ServiceStateModel(name);
  }

  @Override
  public final STATE getServiceState() {
    return stateModel.getState();
  }

  @Override
  public final synchronized Throwable getFailureCause() {
    return failureCause;
  }

  @Override
  public synchronized STATE getFailureState() {
    return failureState;
  }

  /**
   * {@inheritDoc} 
   * This invokes {@link #innerInit} 
   * @param conf the configuration of the service. This must not be null
   * @throws ServiceStateException if the configuration was null,
   * the state change not permitted, or something else went wrong
   */
  @Override
  public final void init(Configuration conf) {
    if (conf == null) {
      throw new ServiceStateException("Cannot initialize service "
                                      + getName() + ": null configuration");
    }
    enterState(STATE.INITED);
    this.config = conf;
    try {
      innerInit(conf);
      notifyListeners();
    } catch (Exception e) {
      noteFailure(e);
      stopQuietly(this);
      throw ServiceStateException.convert(e);
    }
  }

  /**
   * {@inheritDoc}
   * @throws ServiceStateException if the current service state does not permit
   * this action
   */
  @Override
  public final void start() {
    //enter the started state
    stateModel.enterState(STATE.STARTED);
    try {
      startTime = System.currentTimeMillis();
      innerStart();
      LOG.info("Service " + getName() + " is started");
      notifyListeners();
    } catch (Exception e) {
      noteFailure(e);
      stopQuietly(this);
      throw ServiceStateException.convert(e);
    }
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public final void stop() {
    //this operation is only invoked if the service is not already stopped;
    // it is not an error
    //to go STOPPED->STOPPED -it is just a no-op
    if (enterState(STATE.STOPPED) != STATE.STOPPED) {
      try {
        innerStop();
      } catch (Exception e) {
        //stop-time exceptions are logged if they are the first one,
        // but not acted on
        noteFailure(e);
      } finally {
        //report that the service has terminated
        synchronized (terminationNotification) {
          terminationNotification.set(true);
          terminationNotification.notifyAll();
        }
        //notify anything listening for events
        notifyListeners();
      }
    } else {
      //already stopped: note it
      if (LOG.isDebugEnabled()) {
        LOG.debug("Ignoring re-entrant call to stop()");
      }
    }
  }

  /**
   * Stop a service, logging problems to this
   * service's log. 
   * 
   * @param service service to stop -can be null
   * @see ServiceOperations#stopQuietly(Log, Service) 
   */
  protected void stopQuietly(Service service) {
    ServiceOperations.stopQuietly(LOG, service);
  }

  /**
   * Stop a service
   * 
   * @param service service to stop -can be null
   * @see ServiceOperations#stop(Service) 
   */
  protected void stopService(Service service) {
    ServiceOperations.stop(service);
  }

  /**
   * Failure handling: record the exception
   * that triggered it -if there was not one already.
   * Services are free to call this themselves.
   * @param exception the exception
   */
  protected final void noteFailure(Exception exception) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("noteFailure " + exception, null);
    }
    if (exception == null) {
      //make sure failure logic doesn't itself cause problems
      return;
    }
    //record the failure details, and log it
    synchronized (this) {
      if (failureCause == null) {
        failureCause = exception;
        failureState = getServiceState();
        LOG.info("Service " + getName()
                 + "failed in state " + failureState
                 + "; cause: " + exception,
                 exception);
      }
    }
  }

  @Override
  public final boolean waitForServiceToStop(long timeout) {
    synchronized (terminationNotification) {
      boolean completed = terminationNotification.get();
      while (!completed) {
        try {
          terminationNotification.wait(timeout);
          //here there has been a timeout, the object has terminated,
          //or there has been a spurious wakeup (which we ignore)
          completed = true;
        } catch (InterruptedException e) {
          //interrupted; have another look at the flag
          completed = terminationNotification.get();
        }
      }
      return terminationNotification.get();
    }
  }
  
  /* ===================================================================== */
  /* Override Points */
  /* ======================================================================= */

  /**
   * All initialization code needed by a service.
   *
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   *
   * Implementations do not need to be synchronized as the logic
   * in {@link #init(Configuration)} prevents re-entrancy.
   *
   * @param conf configuration
   * @throws Exception on a failure -these will be caught,
   * possibly wrapped, and wil; trigger a service stop
   */
  protected void innerInit(Configuration conf) throws Exception {

  }

  /**
   * Actions called during the {@link STATE#INITED} to 
   * {@link STATE#STARTED} transition.
   *
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   *
   * Implementations do not need to be synchronized as the logic
   * in {@link #start()} prevents re-entrancy.
   *
   * @throws Exception if needed -these will be caught,
   * wrapped, and trigger a service stop
   */
  protected void innerStart() throws Exception {

  }

  /**
   * Actions called to any transition to the {@link STATE#STOPPED} state.
   * 
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   * 
   * Implementations do not need to be synchronized as the logic
   * in {@link #stop()} prevents re-entrancy.
   * 
   * Implementations MUST write this to be robust against failures, including 
   * checks for null references -and for the first failure to not stop other
   * attempts to shut down parts of the service.
   * 
   * @throws Exception if needed -these will be caught and logged.
   */
  protected void innerStop() throws Exception {

  }

  @Override
  public synchronized void register(ServiceStateChangeListener l) {
    listeners.add(l);
  }

  @Override
  public synchronized void unregister(ServiceStateChangeListener l) {
    listeners.remove(l);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public synchronized Configuration getConfig() {
    return config;
  }

  @Override
  public long getStartTime() {
    return startTime;
  }

  /**
   * Notify local and global listeners of state changes
   */
  private void notifyListeners() {
    try {
      listeners.notifyListeners(this);
      globalListeners.notifyListeners(this);
    } catch (Throwable e) {
      LOG.warn("Exception while notifying listeners of " + this + ": " + e,
               e);
    }
  }

  /**
   * Add a state change event to the lifecycle history
   */
  private void recordLifecycleEvent() {
    LifecycleEvent event = new LifecycleEvent();
    event.time = System.currentTimeMillis();
    event.state = getServiceState();
    lifecycleHistory.add(event);
  }

  @Override
  public synchronized List<LifecycleEvent> getLifecycleHistory() {
    return new ArrayList<LifecycleEvent>(lifecycleHistory);
  }

  /**
   * Enter a state; record this via {@link #recordLifecycleEvent}
   * and log at the info level.
   * @param newState the proposed new state
   * @return the original state
   * it wasn't already in that state, and the state model permits state re-entrancy. 
   */
  private STATE enterState(STATE newState) {
    STATE original = stateModel.enterState(newState);
    if (original != newState) {
      LOG.info("Service:" + getName() + " entered state " + getServiceState());
      recordLifecycleEvent();
    }
    return original;
  }

  /**
   * Query to see if the service is in a specific state. 
   * In a multi-threaded system, the state may not hold for very long.
   * @param expected the expected state
   * @return true if, at the time of invocation, the service was in that state.
   */
  @Override
  public final boolean inState(Service.STATE expected) {
    return stateModel.inState(expected);
  }

  @Override
  public String toString() {
    return "Service " + name + " in state " + stateModel;
  }

  /**
   * Put a blocker to the blocker map -replacing any
   * with the same name.
   * @param name blocker name
   * @param details any specifics on the block. This must be non-null.
   */
  protected void putBlocker(String name, String details) {
    synchronized (blockerMap) {
      blockerMap.put(name, details);
    }
  }

  /**
   * Remove a blocker from the blocker map -
   * this is a no-op if the blocker is not present
   * @param name the name of the blocker
   */
  public void removeBlocker(String name) {
    synchronized (blockerMap) {
      blockerMap.remove(name);
    }
  }
  
  @Override
  public Map<String, String> getBlockers() {
    synchronized (blockerMap) {
      Map<String, String> map = new HashMap<String, String>(blockerMap);
      return map;
    }
  }
}
