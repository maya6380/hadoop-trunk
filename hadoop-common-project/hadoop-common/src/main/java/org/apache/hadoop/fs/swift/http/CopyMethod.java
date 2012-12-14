package org.apache.hadoop.fs.swift.http;

import org.apache.commons.httpclient.methods.EntityEnclosingMethod;

/**
 * @author dmezhensky
 *         <p/>
 *         Implementation for RestClient to make copy requests
 */
class CopyMethod extends EntityEnclosingMethod {

  public CopyMethod(String uri) {
    super(uri);
  }

  /**
   * @return http method name
   */
  @Override
  public String getName() {
    return "COPY";
  }
}
