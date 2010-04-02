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
package org.apache.lcf.crawler.connectors.jdbc;

import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.agents.interfaces.*;
import java.util.*;

public class RemoveDoc
{
        public static final String _rcsid = "@(#)$Id$";

        private RemoveDoc()
        {
        }


        public static void main(String[] args)
        {
                if (args.length != 8)
                {
                        System.err.println("Usage: RemoveDoc <provider> <host> <databasename> <username> <password> <tablename> <idcolumn> <id>");
                        System.exit(1);
                }

                try
                {
                        JDBCConnection handle = new JDBCConnection(args[0],args[1],args[2],args[3],args[4]);

                        // Build query
                        StringBuffer sb = new StringBuffer();
                        ArrayList paramList = new ArrayList();
                        sb.append("DELETE FROM ").append(args[5]).append(" WHERE ").append(args[6]).append("=?");
                        paramList.add(args[7]);
                        handle.executeOperation(sb.toString(),paramList);

                        System.err.println("Successfully removed");
                }
                catch (LCFException e)
                {
                        e.printStackTrace(System.err);
                        System.exit(2);
                }
                catch (ServiceInterruption e)
                {
                        e.printStackTrace(System.err);
                        System.exit(2);
                }
        }

}