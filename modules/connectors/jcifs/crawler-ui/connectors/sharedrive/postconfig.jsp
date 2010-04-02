<%@ include file="../../adminDefaults.jsp" %>

<%

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
%>

<%
	// This file is included by every place that edited specification information for the file system connector
	// is posted upon submit.  When it is called, the Map parameters object is placed in the thread context
	// under the name "Parameters".  This map should be edited by this code.

	// The coder cannot presume that this jsp is executed within a body section.  Errors should thus be
	// forwarded to "error.jsp" using <jsp:forward>.
	// Arguments from the original request object for the post page will remain available for access.

	ConfigParams parameters = (ConfigParams)threadContext.get("Parameters");

	if (parameters == null)
		System.out.println("No parameter map!!!");
		
	String server = variableContext.getParameter("server");
	if (server != null)
		parameters.setParameter(org.apache.lcf.crawler.connectors.sharedrive.SharedDriveParameters.server,server);
	
	String domain = variableContext.getParameter("domain");
	if (domain != null)
		parameters.setParameter(org.apache.lcf.crawler.connectors.sharedrive.SharedDriveParameters.domain,domain);
	
	String username = variableContext.getParameter("username");
	if (username != null)
		parameters.setParameter(org.apache.lcf.crawler.connectors.sharedrive.SharedDriveParameters.username,username);
		
	String password = variableContext.getParameter("password");
	if (password != null)
		parameters.setObfuscatedParameter(org.apache.lcf.crawler.connectors.sharedrive.SharedDriveParameters.password,password);

%>