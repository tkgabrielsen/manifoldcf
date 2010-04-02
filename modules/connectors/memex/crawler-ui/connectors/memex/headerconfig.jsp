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
	// This file is included in the head section by every place that the configuration information for the memex connector
	// needs to be edited.  When it is called, the parameter Map object is placed in the thread context
	// under the name "Parameters".

	// The coder can presume that this jsp is executed within a head section.  The purpose would be to provide javascript
	// functions needed by the editconfig.jsp for this connector.
	//
	// The method checkConfigOnSave() is called prior to the form being submitted for save.  It should return false if the
	// form should not be submitted.

	ConfigParams parameters = (ConfigParams)threadContext.get("Parameters");
	ArrayList tabsArray = (ArrayList)threadContext.get("Tabs");
	String tabName = (String)threadContext.get("TabName");
%>

<%
	if (parameters == null)
		out.println("No parameters!!!");
	if (tabsArray == null)
		out.println("No tabs array!");
	if (tabName == null)
		out.println("No tab name!");

	tabsArray.add("Memex Server");
	tabsArray.add("Web Server");
%>

<script type="text/javascript">
<!--

	function checkConfig()
	{
		if (editconnection.memexserverport.value != "" && !isInteger(editconnection.memexserverport.value))
		{
			alert("A valid number is required");
			editconnection.memexserverport.focus();
			return false;
		}
		if (editconnection.webserverport.value != "" && !isInteger(editconnection.webserverport.value))
		{
			alert("A valid number, or blank, is required");
			editconnection.webserverport.focus();
			return false;
		}
		return true;
	}

	function checkConfigForSave()
	{
		if (editconnection.crawluser.value == "")
		{
			alert("Please supply the name of a crawl user");
			SelectTab("Memex Server");
			editconnection.crawluser.focus();
			return false;
		}
		if (editconnection.memexservername.value == "")
		{
			alert("Please supply the name of a Memex server");
			SelectTab("Memex Server");
			editconnection.memexservername.focus();
			return false;
		}
		if (editconnection.memexserverport.value == "")
		{
			alert("A Memex server port is required");
			SelectTab("Memex Server");
			editconnection.memexserverport.focus();
			return false;
		}
		return true;
	}

//-->
</script>
