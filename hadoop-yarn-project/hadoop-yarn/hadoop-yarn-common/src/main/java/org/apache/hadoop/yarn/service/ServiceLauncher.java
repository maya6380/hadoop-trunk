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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
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
  public static final int EXIT_USAGE = EXIT_EXCEPTION_THROWN + 1;

  /**
   * Exit code when a usage message was printed: {@value}
   */
  public static final int EXIT_OTHER_FAILURE = EXIT_EXCEPTION_THROWN + 2;

  /**
   * Exit code when a control-C, kill -3, signal was picked up: {@value}
   */
  public static final int EXIT_INTERRUPTED = EXIT_OTHER_FAILURE + 1;

  /**
   * Exit code when the command line doesn't parse: {@value}
   */
  public static final int EXIT_COMMAND_ARGUMENT_ERROR = EXIT_INTERRUPTED + 1;
  
  public static final int SHUTDOWN_TIME_ON_INTERRUPT = 30 * 1000;
  public static final String USAGE_MESSAGE =
    "Usage: ServiceLauncher classname [--conf <conf file>] <service arguments> | ";

  /**
   * Name of the "--conf" argument. 
   */
  public static final String ARG_CONF = "--conf";

  private volatile Service service;
  private final List<IrqHandler> interruptHandlers =
    new ArrayList<IrqHandler>(1);
  private Configuration configuration;
  private String serviceClassName;

  /**
   * Create an instance of the launcher
   * @param serviceClassName classname of the service
   */
  ServiceLauncher(String serviceClassName) {
    this.serviceClassName = serviceClassName;
  }

  /**
   * Launch the service. All exceptions that occur are propagated upwards.
   *
   *
   * @param conf configuration
   * @param stdout output stream
   * @param stderr error stream
   * @throws ClassNotFoundException classname not on the classpath
   * @param rawArgs raw arguments from the main method
   * @param processedArgs arguments after the configuration parameters
   * have been stripped out.
   * @throws IllegalAccessException not allowed at the class
   * @throws InstantiationException not allowed to instantiate it
   * @throws InterruptedException thread interrupted
   * @throws IOException any IO exception
   */
  int launchService(Configuration conf,
                    PrintStream stdout,
                    PrintStream stderr,
                    String[] rawArgs,
                    String[] processedArgs)
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
    registerInterruptHandler();
    //and the shutdown hook
    ServiceOperations.ServiceShutdownHook shutdownHook =
      new ServiceOperations.ServiceShutdownHook(service);
    ShutdownHookManager.get().addShutdownHook(
      shutdownHook, PRIORITY);
    RunService runService = null;
    
    if (service instanceof RunService) {
      //if its a runService, pass in the arguments (hopefully before init)
      runService = (RunService) service;
      runService.setArgs(rawArgs, processedArgs);
    }
    
    //some class constructors init; here this is picked up on.
    if (!service.isInState(Service.STATE.INITED)) {
      service.init(configuration);
    }
    service.start();
    if (runService != null) {
      //assume that runnable services are meant to run from here
      return runService.runService(stdout, stderr);
    } else {
      //run the service until it stops or an interrupt happens on a different thread.
      service.waitForServiceToStop(0);
    }
    //exit
    return EXIT_SUCCESS;
  }

  /**
   * Register this class as the handler for the control-C interrupt.
   * Can be overridden for testing.
   * @throws IOException on a failure to add the handler
   */
  protected void registerInterruptHandler() throws IOException {
    interruptHandlers.add(new IrqHandler(IrqHandler.CONTROL_C, this));
  }

  /**
   * The service has been interrupted. 
   * Trigger something resembling an elegant shutdown;
   * Give the service time to do this before the exit operation is called 
   * @param interruptData the interrupted data.
   */
  @Override
  public void interrupted(IrqHandler.InterruptData interruptData) {
    boolean controlC = IrqHandler.CONTROL_C.equals(interruptData.name);
    int shutdownTimeMillis = SHUTDOWN_TIME_ON_INTERRUPT;
    //start an async shutdown thread with a timeout
    ServiceForcedShutdown forcedShutdown =
      new ServiceForcedShutdown(shutdownTimeMillis);
    Thread thread = new Thread(forcedShutdown);
    thread.setDaemon(true);
    thread.start();
    //wait for that thread to finish
    try {
      thread.join(shutdownTimeMillis);
    } catch (InterruptedException ignored) {
      //ignored
    }
    if (!forcedShutdown.isServiceStopped()) {
      LOG.warn("Service did not shut down in time");
    }
    exit(controlC ? EXIT_SUCCESS : EXIT_INTERRUPTED);
  }

  /**
   * Exit the code.
   * This is method can be overridden for testing, throwing an 
   * exception instead. Any subclassed method MUST raise an 
   * {@link ExitUtil.ExitException} instance.
   * The service launcher code assumes that after this method is invoked,
   * no other code in the same method is called.
   * @param exitCode code to exit
   */
  protected void exit(int exitCode) {
    ExitUtil.terminate(exitCode);
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
   * Parse the command line, building a configuration from it, then
   * launch the service and wait for it to finish. finally, exit
   * passing the status code to the {@link #exit(int)} method.
   * @param stdout output stream
   * @param stderr error stream
   * @param args arguments to the service. arg[0] is 
 * assumed to be the service classname and is automatically
   */
  public void launchServiceAndExit(PrintStream stdout,
                                   PrintStream stderr,
                                   String[] args) {

    //Currently the config just the default
    Configuration conf = new Configuration();
    String[] processedArgs = extractConfigurationArgs(conf, args);


    int exitCode = launchServiceRobustly(conf, stderr, stderr, args, processedArgs
                                        );
    exit(exitCode);
  }

  /**
   * Extract the configuration arguments and apply them to the configuration,
   * building an array of processed arguments to hand down to the service.
   * @param conf configuration to update
   * @param args main arguments. args[0] is assumed to be the service
   * classname and is skipped
   * @return the processed list.
   */
  protected String[] extractConfigurationArgs(Configuration conf,
                                              String[] args) {
    //convert args to a list
    List<String> argsList = new ArrayList<String>(args.length - 1);
    for (int index = 1; index < args.length; index++) {
      String arg = args[index];
      if (arg.equals(ARG_CONF)) {
        //the argument is a --conf file tuple: extract the path and load
        //it in as a configuration resource.

        //increment the loop counter
        index++;
        if (index == args.length) {
          //overshot the end of the file
          exitWithMessage(EXIT_COMMAND_ARGUMENT_ERROR,
                          ARG_CONF + ": missing configuration file after ");
        }
        File file = new File(args[index]);
        if (!file.exists()) {
          exitWithMessage(EXIT_COMMAND_ARGUMENT_ERROR,
                          ARG_CONF + ": configuration file not found: "
                          + file);
        }
        try {
          conf.addResource(file.toURI().toURL());
        } catch (MalformedURLException e) {
          exitWithMessage(EXIT_COMMAND_ARGUMENT_ERROR,
                          ARG_CONF + ": configuration file path invalid: "
                          + file);
        }
      } else {
        argsList.add(arg);
      }
    }
    String[] processedArgs = new String[argsList.size()];
    argsList.toArray(processedArgs);
    return processedArgs;
  }

  /**
   * Launch a service catching all excpetions and downgrading them to exit codes
   *
   *
   *
   *
   * @param conf configuration to use
   * @param stdout output stream
   * @param stderr error stream
   * @param rawArgs command line arguments
   * @param processedArgs command line after the launcher-specific arguments have
   * been stripped out
   * @return an exit code.
   */
  protected int launchServiceRobustly(Configuration conf,
                                      PrintStream stdout,
                                      PrintStream stderr,
                                      String[] rawArgs,
                                      String[] processedArgs) {
    try {
      launchService(conf, stdout, stderr, rawArgs, processedArgs);
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
      stdout.println("While running " + getServiceName()
                  + ": " + thrown);
      return EXIT_EXCEPTION_THROWN;
    }
  }

  /**
   * Build a log message for starting up and shutting down. 
   * This was grabbed from the ToolRunner code.
   * @param classname the class of the server
   * @param args arguments
   */
  public static String startupShutdownMessage(String classname,
                                            String[] args) {
    final String hostname = NetUtils.getHostname();
    
    return toStartupShutdownString("STARTUP_MSG: ", new String[]{
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
      });
  }

  /**
   * Exit with a printed message
   * @param status status code
   * @param message message
   */
  private static void exitWithMessage(int status, String message) {
    System.err.println(message);
    ExitUtil.terminate(status);
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

  /**
   * This is the main entry point for the service launcher.
   * @param args command line arguments.
   */
  public static void main(String[] args) {
    if (args.length < 1) {
      exitWithMessage(EXIT_USAGE, USAGE_MESSAGE);
    } else {
      String serviceClassName = args[0];
  
      if (LOG.isDebugEnabled()) {
        LOG.debug(startupShutdownMessage(serviceClassName, args));
      }
      Thread.setDefaultUncaughtExceptionHandler(
        new YarnUncaughtExceptionHandler());

      ServiceLauncher serviceLauncher = new ServiceLauncher(serviceClassName);
      serviceLauncher.launchServiceAndExit(System.out, System.err, args);
    }
  }

  /**
   * An interface which services can implement to have their
   * execution managed by the ServiceLauncher.
   * The command line options will be passed down before the 
   * {@link Service#init(Configuration)} operation is invoked via an
   * invocation of {@link RunService#setArgs(String[], String[])}
   * After the service has been successfully started via {@link Service#start()}
   * the {@link RunService#runService(PrintStream, PrintStream)} method is called to execute the 
   * service. When this method returns, the service launcher will exit, using
   * the return code from the method as its exit option.
   */
  public interface RunService {

    /**
     * Propagate the command line arguments
     * @param rawArgs the raw list of arguments
     * @param processedArgs the arguments after the preprocessing to
     * extract configuration arguments and strip the command line
     * 
     */
    void setArgs(String[] rawArgs, String[] processedArgs) throws IOException;
    
    /**
     * Run a service
     * @return the exit code
     * @throws Throwable any exception to report
     * @param stdout output stream for normal output
     * @param stderr output stream for errors
     */
    int runService(PrintStream stdout, PrintStream stderr) throws Throwable ;
  }

  /**
   * forced shutdown runnable.
   */
  protected class ServiceForcedShutdown implements Runnable {

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

}
