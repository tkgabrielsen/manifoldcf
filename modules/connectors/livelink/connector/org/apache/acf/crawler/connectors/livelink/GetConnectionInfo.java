/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.acf.crawler.connectors.livelink;

import org.apache.acf.core.interfaces.*;
import org.apache.acf.agents.interfaces.*;
import org.apache.acf.crawler.interfaces.*;
import org.apache.acf.crawler.system.Logging;
import org.apache.acf.crawler.system.ACF;


/** Get a livelink connection's information in printed form.
*/
public class GetConnectionInfo
{
  public static final String _rcsid = "@(#)$Id$";

  private GetConnectionInfo()
  {
  }

  public static void main(String[] args)
  {
    if (args.length != 1)
    {
      System.err.println("Usage: GetConnectionInfo <connection_name>");
      System.err.println("");
      System.err.println("The result will be printed to standard out, in UTF-8 encoding, and will contain the following columns:");
      System.err.println("    livelink_server");
      System.exit(1);
    }

    String connectionName = args[0];

    try
    {
      ACF.initializeEnvironment();
      IThreadContext tc = ThreadContextFactory.make();
      IRepositoryConnectionManager connectionManager = RepositoryConnectionManagerFactory.make(tc);
      IRepositoryConnection connection = connectionManager.load(connectionName);
      if (connection == null)
        throw new ACFException("Connection "+connectionName+" does not exist");

      if (connection.getClassName() == null || !connection.getClassName().equals("org.apache.acf.crawler.connectors.livelink.LivelinkConnector"))
        throw new ACFException("Command can only be used on working Livelink connections.");

      ConfigParams cfg = connection.getConfigParams();

      UTF8Stdout.println(commaEscape(cfg.getParameter(LiveLinkParameters.serverName)));

      System.err.println("Connection info done");
    }
    catch (Exception e)
    {
      e.printStackTrace();
      System.exit(2);
    }

  }

  protected static String commaEscape(String input)
  {
    StringBuffer output = new StringBuffer();
    int i = 0;
    while (i < input.length())
    {
      char x = input.charAt(i++);
      if (x == '\\' || x == ',')
        output.append("\\");
      output.append(x);
    }
    return output.toString();
  }

}

