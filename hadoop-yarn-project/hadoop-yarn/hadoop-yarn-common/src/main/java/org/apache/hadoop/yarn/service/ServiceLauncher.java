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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.yarn.YarnUncaughtExceptionHandler;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A class to launch any service by name.
 * It is assumed that the service starts 
 * 
 * Workflow
 * <ol>
 *   <li>An instance of the class is created</li>
 *   <li>It's service.init() and service.start() methods are called.</li>
 *   <li></li>
 * </ol>
 */
public class ServiceLauncher
  implements IrqHandler.Interrupted {
  private static final Log LOG = LogFactory.getLog(ServiceLauncher.class);
  protected static final int PRIORITY = 30;

  /**
   * name of class for entry point strings: {@value}
   */

  public static final String ENTRY_POINT =
    "org.apache.hadoop.yarn.service.ServiceLauncher";
  /**
   * Exit code when a client requested service termination: {@value}
   */
  public static final int EXIT_SUCCESS = 0;
  /**
   * Exit code when a client requested service termination: {@value}
   */
  public static final int EXIT_CLIENT_INITIATED_SHUTDOWN = 1;

  /**
   * Exit code when targets could not be launched: {@value}
   */
  public static final int EXIT_TASK_LAUNCH_FAILURE = 2;


  /*
   * Exit codes from here are relative to a base value that
   * we put a fair way up from the the base numbers, so that 
   * applications can have their own set of failures
   */
  /**
   * Exit code when an exception was thrown from the service: {@value}
   */
  public static final int EXIT_EXCEPTION_THROWN = 32;

  /**
   * Exit code when a usage message was printed: {@value}
   */
  public static final int EXIT_USAGE = EXIT_EXCEPTION_THROWN +1;

  /**
   * Exit code when a usage message was printed: {@value}
   */
  public static final int EXIT_OTHER_FAILURE = EXIT_EXCEPTION_THROWN +2;

  /**
   * Exit code when a control-C, kill -3, signal was picked up: {@value}
   */
  public static final int EXIT_INTERRUPTED = EXIT_EXCEPTION_THROWN + 3;
  public static final int SHUTDOWN_TIME_ON_INTERRUPT = 30 * 1000;

  private volatile Service service;
  private volatile int exitCode = EXIT_SUCCESS;
  private final List<IrqHandler> interruptHandlers =
    new ArrayList<IrqHandler>(1);
  private Configuration configuration;
  private String serviceClassName;

  /**
   * Create an instance of the launcher
   * @param serviceClassName classname of the service
   */
  public ServiceLauncher(String serviceClassName) {
    this.serviceClassName = serviceClassName;
  }

  /**
   * Launch the service. All exceptions that occur are propagated upwards.
   *
   *
   * @param conf configuration
   * @param argsList
   * @throws ClassNotFoundException classname not on the classpath
   * @throws IllegalAccessException not allowed at the class
   * @throws InstantiationException not allowed to instantiate it
   * @throws InterruptedException thread interrupted
   * @throws IOException any IO exception
   */
  public int launchService(Configuration conf, List<String> argsList)
    throws Throwable {

    configuration = conf;

    //Instantiate the class -this requires the service to have a public zero-argument constructor
    Class<?> serviceClass =
      this.getClass().getClassLoader().loadClass(serviceClassName);
    Object instance = serviceClass.newInstance();
    if (!(instance instanceof Service)) {
      //not a service
      throw new ServiceStateException("Not a Service: " + serviceClassName);
    }

    service = (Service) instance;

    //Register the interrupt handlers
    interruptHandlers.add(new IrqHandler(IrqHandler.CONTROL_C, this));
    //and the shutdown hook
    ServiceOperations.ServiceShutdownHook shutdownHook =
      new ServiceOperations.ServiceShutdownHook(service);
    ShutdownHookManager.get().addShutdownHook(
      shutdownHook, PRIORITY);
    //some class constructors init; here this is picked up on.
    if (!service.isInState(Service.STATE.INITED)) {
      service.init(configuration);
    }
    service.start();
    if (service instanceof RunService) {
      //assume that runnable services are meant to run from here
      return ((RunService)service).runService(argsList);
    } else {
      //run the service until it stops or an interrupt happens on a different thread.
      service.waitForServiceToStop(0);
    }
    //exit
    return EXIT_SUCCESS;
  }


  /**
   * The service has been interrupted. Again, trigger something resembling an elegant shutdown;
   * give the service time to do this before the exit operation is called 
   * @param interruptData the interrupted flag.
   */
  @Override
  public void interrupted(IrqHandler.InterruptData interruptData) {
    boolean controlC = IrqHandler.CONTROL_C.equals(interruptData.name);
    int shutdownTimeMillis = SHUTDOWN_TIME_ON_INTERRUPT;
    //start an async shutdown thread with a timeout
    boolean serviceStopped;


    ServiceForcedShutdown forcedShutdown =
      new ServiceForcedShutdown(shutdownTimeMillis);
    Thread thread = new Thread(forcedShutdown);
    thread.start();
    //wait for that thread to finish
    try {
      thread.join(shutdownTimeMillis);
    } catch (InterruptedException e) {
      //ignored
    }
    if (!forcedShutdown.isServiceStopped()) {
      LOG.warn("Service did not shut down in time");
    }
    int exitCode = controlC ? EXIT_SUCCESS : EXIT_INTERRUPTED;
    ExitUtil.terminate(exitCode);
  }

  /**
   * forced shutdown runnable.
   */
  private class ServiceForcedShutdown implements Runnable {

    private final int shutdownTimeMillis;
    private boolean serviceStopped;

    public ServiceForcedShutdown(int shutdownTimeoutMillis) {
      this.shutdownTimeMillis = shutdownTimeoutMillis;
    }

    @Override
    public void run() {
      if (service != null) {
        service.stop();
        serviceStopped = service.waitForServiceToStop(shutdownTimeMillis);
      } else {
        serviceStopped = true;
      }
    }

    private boolean isServiceStopped() {
      return serviceStopped;
    }
  }

  /**
   * Get the service name via {@link Service#getName()}.
   * If the service is not instantiated, the classname is returned instead.
   * @return the service name
   */
  public String getServiceName() {
    Service s = service;
    if (s != null) {
      return "service " + s.getName();
    } else {
      return "service classname " + serviceClassName;
    }
  }

  /**
   * Launch a service catching all excpetions and downgrading them to exit codes
   *
   * @param out output stream for printing errors to (alongside the logging back
   * end)
   * @param configuration configuration to use
   * @param argsList
   * @return an exit code.
   */
  public int launchServiceRobustly(PrintStream out,
                                   Configuration configuration,
                                   List<String> argsList) {
    try {
      launchService(configuration, argsList);
      if (service != null) {
        Throwable failure = service.getFailureCause();
        if (failure != null) {
          Service.STATE failureState = service.getFailureState();
          if (failureState == Service.STATE.STOPPED) {
            //the failure occurred during shutdown, not important enough to bother
            //the user as it may just scare them
            LOG.debug("Failure during shutdown", failure);
          } else {
            throw failure;
          }
        }
      }
      //either the service succeeded, or an error was only raised during shutdown, 
      //which we don't worry that much about
      return 0;
    } catch (ExitUtil.ExitException exitException) {
      return exitException.status;
    } catch (Throwable thrown) {
      LOG.error("While running " + getServiceName(), thrown);
      out.println("While running " + getServiceName()
                  + ": " + thrown);
      return EXIT_EXCEPTION_THROWN;
    }
  }

  /**
   * Print a log message for starting up and shutting down. 
   * This was grabbed from the ToolRunner code.
   * @param classname the class of the server
   * @param args arguments
   * @param log the target log object
   */
  public static void startupShutdownMessage(String classname,
                                            String[] args,
                                            Log log) {
    final String hostname = NetUtils.getHostname();
    log.info(
      toStartupShutdownString("STARTUP_MSG: ", new String[]{
        "Starting " + classname,
        "  host = " + hostname,
        "  args = " + Arrays.asList(args),
        "  version = " + VersionInfo.getVersion(),
        "  classpath = " + System.getProperty("java.class.path"),
        "  build = " + VersionInfo.getUrl() + " -r "
        + VersionInfo.getRevision()
        + "; compiled by '" + VersionInfo.getUser()
        + "' on " + VersionInfo.getDate(),
        "  java = " + System.getProperty("java.version")
      }
                             ));
  }

  private static String toStartupShutdownString(String prefix, String[] msg) {
    StringBuilder b = new StringBuilder(prefix);
    b.append("\n/************************************************************");
    for (String s : msg) {
      b.append("\n").append(prefix).append(s);
    }
    b.append("\n************************************************************/");
    return b.toString();
  }
  public static void main(String[] args) {
    if (args.length < 1) {
      System.err
            .println("Usage: ServiceLauncher classname <service arguments>");
      ExitUtil.terminate(EXIT_USAGE);
    }
    String serviceClassName = args[0];

    startupShutdownMessage(serviceClassName, args, LOG);
    Thread.setDefaultUncaughtExceptionHandler(
      new YarnUncaughtExceptionHandler());
    ServiceLauncher serviceLauncher = new ServiceLauncher(serviceClassName);
    
    //convert args to a list
    List<String> argsList = new ArrayList<String>(args.length-1);
    for (String arg:args) {
      argsList.add(arg);
    }
    
    //Currently the config just the default
    Configuration conf = new Configuration();
    serviceLauncher.launchServiceRobustly(System.err, conf, argsList);
  }

  public interface RunService {
    /**
     * Run a service
     * @param argsList list of command line arguments
     * @return the exit code
     * @throws Throwable any exception to report
     */
    public int runService(List<String> argsList) throws Throwable ;
  }
}
