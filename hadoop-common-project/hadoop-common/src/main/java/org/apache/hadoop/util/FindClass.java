/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;

import java.net.URL;
import java.security.CodeSource;

/**
 * This entry point exists for diagnosing classloader problems: is a class or resource present -and if so, where.
 * It returns an error code if a class/resource cannot be loaded/found -and optionally a class may be requested as being loaded.
 * The latter action will call the class's constructor (it must support an empty constructor); any side effects from the
 * constructor or static initializers will take place.
 */
public final class FindClass extends Configured implements Tool {

  public static final String A_LOAD = "load";
  public static final String A_CREATE = "create";
  public static final String A_RESOURCE = "resource";
  public static final int SUCCESS = 0;

  /**
   * generic error {@value}
   */
  protected static final int E_GENERIC = 1;
  /**
   * usage error -bad arguments or similar {@value}
   */
  protected static final int E_USAGE = 2;
  /**
   * class or resource not found {@value}
   */
  protected static final int E_NOT_FOUND = 3;

  /**
   * class load failed {@value}
   */
  protected static final int E_LOAD_FAILED = 4;

  /**
   * class creation failed {@value}
   */
  protected static final int E_CREATE_FAILED = 5;

  public FindClass() {
  }

  public FindClass(Configuration conf) {
    super(conf);
  }

  /**
   * Get a class fromt the configuration
   * @param name the class name
   * @return the class
   * @throws ClassNotFoundException if the class was not found
   * @throws Error on other classloading problems
   */
  private Class getClass(String name) throws ClassNotFoundException {
    return getConf().getClassByName(name);
  }

  /**
   * Get the resource
   * @param name resource name
   * @return URL or null for not found
   */
  private URL getResource(String name) {
    return getConf().getResource(name);
  }

  /**
   * Load a resource
   * @param name resource name
   * @return the status code
   */
  private int loadResource(String name) {
    URL url = getResource(name);
    if (url == null) {
      String s = "Resource not found:" + name;
      err(s);
      return E_NOT_FOUND;
    }
    out(name + ": " + url);
    return SUCCESS;
  }

  /**
   * print something to stderr
   * @param s string to print
   */
  private static void err(String s) {
    System.err.println(s);
  }

  /**
   * print something to stdout
   * @param s string to print
   */
  private static void out(String s) {
    System.out.println(s);
  }

  /**
   * print a stack trace with text
   * @param text text to print
   * @param e the exception to print
   */
  private static void stack(String text, Throwable e) {
    err(text);
    e.printStackTrace(System.err);
  }

  /**
   * Loads the class of the given name
   * @param name classname
   * @return outcome code
   */
  private int loadClass(String name) {
    try {
      Class clazz = getClass(name);
      loadedClass(name, clazz);
      return SUCCESS;
    } catch (ClassNotFoundException e) {
      stack("Class not found " + name, e);
      return E_NOT_FOUND;
    } catch (Exception e) {
      stack("Exception while loading class " + name, e);
      return E_LOAD_FAILED;
    } catch (Error e) {
      stack("Error while loading class " + name, e);
      return E_LOAD_FAILED;
    }
  }

  /**
   * Log that a class has been loaded, and where from.
   * @param name classname
   * @param clazz class
   */
  private void loadedClass(String name, Class clazz) {
    out("Loaded " + name + " as " + clazz);
    CodeSource source = clazz.getProtectionDomain().getCodeSource();
    URL url = source.getLocation();
    String s = name + ": " + url;
    out(s);
  }

  /**
   * Create an instance of a class
   * @param name classname
   * @return the outcome
   */
  private int createClassInstance(String name) {
    try {
      Class clazz = getClass(name);
      loadedClass(name, clazz);
      Object instance = clazz.newInstance();
      try {
        //stringify
        out("Created instance " + instance.toString());
      } catch (Exception e) {
        //catch those classes whose toString() method is brittle, but don't fail the probe
        stack("Created class instance but the toString() operator failed", e);
      }
      return SUCCESS;
    } catch (ClassNotFoundException e) {
      stack("Class not found " + name, e);
      return E_NOT_FOUND;
    } catch (Exception e) {
      stack("Exception while creating class " + name, e);
      return E_CREATE_FAILED;
    } catch (Error e) {
      stack("Exception while creating class " + name, e);
      return E_CREATE_FAILED;
    }
  }

  /**
   * Run the class/resource find or load operation
   * @param args command specific arguments.
   * @return the outcome
   * @throws Exception if something went very wrong
   */
  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      return usage(args);
    }
    String action = args[0];
    String name = args[1];
    int result;
    if (A_LOAD.equals(action)) {
      result = loadClass(name);
    } else if (A_CREATE.equals(action)) {
      //first load to separate load errors from create
      result = loadClass(name);
      if (result == SUCCESS) {
        //class loads, so instantiate it
        result = createClassInstance(name);
      }
    } else if (A_RESOURCE.equals(action)) {
      result = loadResource(name);
    } else {
      result = usage(args);
    }
    return result;
  }

  /**
   * Print a usage message
   * @param args the command line arguments
   * @return
   */
  private int usage(String[] args) {
    err("Usage : [load <classname> | create <classname> | resource <resourcename>");
    err("Arguments: " + StringUtils.arrayToString(args));
    err("The return codes are:");
    explainResult(SUCCESS,"The operation was successful");
    explainResult(E_GENERIC,"Something went wrong");
    explainResult(E_USAGE,"This usage message was printed");
    explainResult(E_NOT_FOUND,"The class or resource was not found");
    explainResult(E_LOAD_FAILED,"The class was found but could not be loaded");
    explainResult(E_CREATE_FAILED,"The class was loaded, but an instance of it could not be created");
    return E_USAGE;
  }

  private void explainResult(int value, String text) {
    err("  " + value + " -- " + text);
  }


  public static void main(String[] args) {
    try {
      int result = ToolRunner.run(new FindClass(), args);
      System.exit(result);
    } catch (Exception e) {
      stack("Running FindClass", e);
      System.exit(E_GENERIC);
    }
  }
}
