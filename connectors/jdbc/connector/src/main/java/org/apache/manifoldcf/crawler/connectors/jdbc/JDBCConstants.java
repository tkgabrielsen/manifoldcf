/* $Id: JDBCConstants.java 988245 2010-08-23 18:39:35Z kwright $ */

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
package org.apache.manifoldcf.crawler.connectors.jdbc;

/** These are the constant strings needed by the Oracle connector.
*/
public class JDBCConstants
{
  public static final String _rcsid = "@(#)$Id: JDBCConstants.java 988245 2010-08-23 18:39:35Z kwright $";

  /** The jdbc provider parameter */
  public static String providerParameter = "JDBC Provider";
  /** The column interrogation method name parameter */
  public static String methodParameter = "JDBC column access method";
  /** The host machine config parameter */
  public static String hostParameter = "Host";
  /** The database name config parameter */
  public static String databaseNameParameter = "Database name";
  /** The user name config parameter */
  public static String databaseUserName = "User name";
  /** The password config parameter */
  public static String databasePassword = "Password";

  /** The node containing the identifier query */
  public static String idQueryNode = "idquery";
  /** The node containing the version query */
  public static String versionQueryNode = "versionquery";
  /** The node containing the process query */
  public static String dataQueryNode = "dataquery";

  /** The name of the id return column */
  public static String idReturnColumnName = "lcf__id";
  /** The name of the version return column */
  public static String versionReturnColumnName = "lcf__version";
  /** The name of the url return column */
  public static String urlReturnColumnName = "lcf__url";
  /** The name of the data return column */
  public static String dataReturnColumnName = "lcf__data";

  /** The name of the id return variable */
  public static String idReturnVariable = "IDCOLUMN";
  /** The name of the version return variable */
  public static String versionReturnVariable = "VERSIONCOLUMN";
  /** The name of the url return variable */
  public static String urlReturnVariable = "URLCOLUMN";
  /** The name of the data return variable */
  public static String dataReturnVariable = "DATACOLUMN";
  /** The name of the start time variable */
  public static String startTimeVariable = "STARTTIME";
  /** The name of the end time variable */
  public static String endTimeVariable = "ENDTIME";
  /** The name of the id list */
  public static String idListVariable = "IDLIST";

}


