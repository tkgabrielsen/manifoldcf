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
package org.apache.lcf.crawler;

import java.io.*;
import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.crawler.interfaces.*;
import org.apache.lcf.crawler.system.*;
import java.util.*;

/** This class provides a script hook to export the crawler configuration to a file.
*/
public class ExportConfiguration
{
  public static final String _rcsid = "@(#)$Id$";

  private ExportConfiguration()
  {
  }

  public static void main(String[] args)
  {
    if (args.length != 1)
    {
      System.err.println("Usage: ExportConfiguration <filename>");
      System.exit(1);
    }

    String exportFilename = args[0];


    try
    {
      LCF.initializeEnvironment();
      IThreadContext tc = ThreadContextFactory.make();
      LCF.exportConfiguration(tc,exportFilename);
      System.err.println("Configuration exported");
    }
    catch (Exception e)
    {
      e.printStackTrace();
      System.exit(2);
    }
  }

}