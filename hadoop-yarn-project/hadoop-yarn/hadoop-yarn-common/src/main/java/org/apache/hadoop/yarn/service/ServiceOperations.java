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
import org.apache.hadoop.util.ShutdownHookManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * This class contains a set of methods to work with services, especially
 * to walk them through their lifecycle.
 */
public final class ServiceOperations {
  private static final Log LOG = LogFactory.getLog(AbstractService.class);

  private ServiceOperations() {
  }

  /**
   * Verify that that a service is in a given state.
   * @param state the actual state a service is in
   * @param expectedState the desired state
   * @throws IllegalStateException if the service state is different from
   * the desired state
   */
  public static void ensureCurrentState(Service.STATE state,
                                        Service.STATE expectedState) {
    if (state != expectedState) {
      throw new IllegalStateException("For this operation, the " +
                                          "current service state must be "
                                          + expectedState
                                          + " instead of " + state);
    }
  }

  /**
   * Check that a state tansition is valid and
   * throw an exception if not
   * @param state current state
   * @param proposed proposed new state
   */
  public static void checkStateTransition(Service.STATE state, Service.STATE proposed) {
    ServiceStateModel.checkStateTransition(state, proposed);
  }

  /**
   * Check that a state tansition is valid and 
   * throw an exception if not
   * @param service the service to probe
   * @param proposed proposed new state
   */
  public static void checkStateTransition(Service service, Service.STATE proposed) {
    ServiceStateModel.checkStateTransition(service.getServiceState(), proposed);
  }

  /**
   * Initialize a service.
   * <p/>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service that must be in the state
   *   {@link Service.STATE#NOTINITED}
   * @param configuration the configuration to initialize the service with
   * @throws RuntimeException on a state change failure
   * @throws IllegalStateException if the service is in the wrong state
   */

  public static void init(Service service, Configuration configuration) {
    checkStateTransition(service, Service.STATE.INITED);
    service.init(configuration);
  }

  /**
   * Start a service.
   * <p/>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service that must be in the state 
   *   {@link Service.STATE#INITED}
   * @throws RuntimeException on a state change failure
   * @throws IllegalStateException if the service is in the wrong state
   */

  public static void start(Service service) {
    checkStateTransition(service, Service.STATE.STARTED);
    service.start();
  }

  /**
   * Initialize then start a service.
   * <p/>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service that must be in the state 
   *   {@link Service.STATE#NOTINITED}
   * @param configuration the configuration to initialize the service with
   * @throws RuntimeException on a state change failure
   * @throws IllegalStateException if the service is in the wrong state
   */
  public static void deploy(Service service, Configuration configuration) {
    init(service, configuration);
    start(service);
  }

  /**
   * Stop a service.
   * <p/>Do nothing if the service is null or not
   * in a state in which it can be/needs to be stopped.
   * <p/>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service or null
   */
  public static void stop(Service service) {
    if (service != null) {
      service.stop();
    }
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   */
  public static Throwable stopQuietly(Service service) {
    Log log = LOG;
    try {
      stop(service);
    } catch (Throwable e) {
      log.warn("When stopping the service " + service.getName()
               + " : " + e,
               e);
      return e;
    }
    return null;
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param log the log to warn at
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   * @see ServiceOperations#stopQuietly(Service)
   * 
   */
  protected static Throwable stopQuietly(Log log, Service service) {
    try {
      stop(service);
    } catch (Throwable e) {
      log.warn("When stopping the service " + service.getName()
               + " : " + e,
               e);
      return e;
    }
    return null;
  }


  /**
   * Class to manage a list of {@link ServiceStateChangeListener} instances,
   * including a notification loop that is robust against changes to the list
   * during the notification process.
   */
  public static class ServiceListeners {
    /**
     * List of state change listeners; it is final to guarantee
     * that it will never be null.
     */
    private final List<ServiceStateChangeListener> listeners =
      new ArrayList<ServiceStateChangeListener>();

    public synchronized void add(ServiceStateChangeListener l) {
      listeners.add(l);
    }

    public synchronized void remove(ServiceStateChangeListener l) {
      listeners.remove(l);
    }

    /**
     * Change to a new state and notify all listeners.
     * This is a private method that is only invoked from synchronized methods,
     * which avoid having to clone the listener list. It does imply that
     * the state change listener methods should be short lived, as they
     * will delay the state transition.
     * @param service the service that has changed state
     */
    public void notifyListeners(Service service) {
      //take a very fast snapshot of the callback list
      //very much like CopyOnWriteArrayList, only more minimal
      ServiceStateChangeListener[] callbacks;
      synchronized (this) {
        callbacks = listeners.toArray(new ServiceStateChangeListener[listeners.size()]);
      }
      //iterate through the listeners outside the synchronized method,
      //ensuring that listener registration/unregistration doesn't break anything
      for (ServiceStateChangeListener l : callbacks) {
        l.stateChanged(service);
      }
    }
  }

  /**
   * JVM Shutdown hook for Service which will stop the
   * Service gracefully in case of JVM shutdown.
   * This hook uses a weak reference to the service, so
   * does not cause services to be retained after they have
   * been stopped and deferenced elsewhere.
   */
  public static class ServiceShutdownHook implements Runnable {

    private WeakReference<Service> serviceRef;
    private Thread hook;

    public ServiceShutdownHook(Service service) {
      serviceRef = new WeakReference<Service>(service);
    }

    public void register(int priority) {
      unregister();
      hook = new Thread(this);
      ShutdownHookManager.get().addShutdownHook(hook, priority);
    }

    public void unregister() {
      if (hook != null) {
        try {
          ShutdownHookManager.get().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
          LOG.info("Failed to unregister shutdown hook",e);
        }
        hook = null;
      }
    }

    @Override
    public void run() {
      Service service = serviceRef.get();
      if (service == null) {
        return;
      }
      try {
        // Stop the  Service
        service.stop();
      } catch (Throwable t) {
        LOG.info("Error stopping " + service.getName(), t);
      }
    }
  }
}
