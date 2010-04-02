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
package org.apache.lcf.crawler.connectors.rss;

import java.io.*;
import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.crawler.interfaces.*;
import org.apache.lcf.crawler.system.*;
import java.util.*;

/** This class is used to set the seed list for a specified RSS job.
*/
public class GetSeedList
{
  public static final String _rcsid = "@(#)$Id$";

  private GetSeedList()
  {
  }

  public static void main(String[] args)
  {
    if (args.length != 1)
    {
      System.err.println("Usage: GetSeedList <job_id>");
      System.err.println("(Writes a set of urls to stdout)");
      System.exit(-1);
    }

    String jobString = args[0];

    try
    {
      LCF.initializeEnvironment();
      IThreadContext tc = ThreadContextFactory.make();
      IJobManager jobManager = JobManagerFactory.make(tc);
      IJobDescription desc = jobManager.load(new Long(jobString));

      // Edit the job specification
      DocumentSpecification ds = desc.getSpecification();

      // Delete all url specs first
      int i = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i);
        if (sn.getType().equals("feed"))
        {
          String url = sn.getAttributeValue("url");
          System.out.println(url);
        }
        i++;
      }

    }
    catch (Exception e)
    {
      e.printStackTrace();
      System.exit(-2);
    }
  }

}