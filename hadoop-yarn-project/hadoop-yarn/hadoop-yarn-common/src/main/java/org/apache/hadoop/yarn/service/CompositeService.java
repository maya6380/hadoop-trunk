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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

/**
 * Composition of services.
 */
public class CompositeService extends AbstractService {

  private static final Log LOG = LogFactory.getLog(CompositeService.class);

  private List<Service> serviceList = new ArrayList<Service>();
  private int serviceStartedCount = 0;

  public CompositeService(String name) {
    super(name);
  }

  public Collection<Service> getServices() {
    return Collections.unmodifiableList(serviceList);
  }

  protected synchronized void addService(Service service) {
    serviceList.add(service);
  }

  protected synchronized boolean removeService(Service service) {
    return serviceList.remove(service);
  }

  protected void innerInit(Configuration conf) throws Exception {
    for (Service service : serviceList) {
      service.init(conf);
    }
    super.innerInit(conf);
  }

  protected void innerStart() throws Exception {
    for (Service service : serviceList) {
      // start the service. If this fails that service
      // will be stopped and an exception raised
      service.start();
      //after starting the service, increment the service count.
      ++serviceStartedCount;
    }
    super.innerStart();
  }

  protected void innerStop() throws Exception{
    //stop all services in reverse order
    stop(serviceList.size());
  }

  /**
   * Stop the services in reverse order
   * @param numOfServicesStarted index from where the stop should work
   * @throws RuntimeException the first exception raised during the 
   * stop process -<i>after all services are stopped</i>
   */
  private synchronized void stop(int numOfServicesStarted) {
    // stop in reserve order of start
    Exception firstException = null;
    for (int i = numOfServicesStarted - 1; i >= 0; i--) {
      Service service = serviceList.get(i);
      Exception ex = ServiceOperations.stopQuietly(LOG, service);
      if (ex != null && firstException == null) {
        firstException = ex;
      }
    }
    //after stopping all services, rethrow the first exception raised
    if (firstException != null) {
      throw ServiceStateException.convert(firstException);
    }
  }

  /**
   * JVM Shutdown hook for CompositeService which will stop the give
   * CompositeService gracefully in case of JVM shutdown.
   */
  public static class CompositeServiceShutdownHook implements Runnable {

    private CompositeService compositeService;

    public CompositeServiceShutdownHook(CompositeService compositeService) {
      this.compositeService = compositeService;
    }

    @Override
    public void run() {
      ServiceOperations.stopQuietly(compositeService);
    }
  }

}
