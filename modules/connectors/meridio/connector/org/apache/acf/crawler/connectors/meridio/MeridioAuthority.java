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
package org.apache.acf.crawler.connectors.meridio;

import org.apache.acf.core.interfaces.*;
import org.apache.acf.crawler.connectors.meridio.DMDataSet.DMDataSet;
import org.apache.acf.crawler.connectors.meridio.meridiowrapper.MeridioDataSetException;
import org.apache.acf.crawler.connectors.meridio.meridiowrapper.MeridioWrapper;
import org.apache.acf.crawler.connectors.meridio.RMDataSet.RMDataSet;
import org.tempuri.GroupResult;
import org.apache.acf.authorities.system.Logging;
import org.apache.acf.authorities.system.ACF;
import org.apache.acf.authorities.interfaces.AuthorizationResponse;

import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolFactory;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;

import java.rmi.RemoteException;
import java.util.*;
import java.io.*;
import java.net.*;



/** This is the Meridio implementation of the IAuthorityConnector interface.
*
*/
public class MeridioAuthority extends org.apache.acf.authorities.authorities.BaseAuthorityConnector
{
  public static final String _rcsid = "@(#)$Id$";

  // Properties we need
  public final static String wsddPathProperty = "org.apache.acf.meridio.wsddpath";

  private MeridioWrapper meridio_       = null;  // A handle to the Meridio Java API Wrapper

  // URLs initialized by 'connect' code
  private URL DmwsURL = null;
  private URL RmwsURL = null;
  private URL MetaCartawsURL = null;

  private ProtocolFactory myFactory = null;

  private String DMWSProxyHost = null;
  private String DMWSProxyPort = null;
  private String RMWSProxyHost = null;
  private String RMWSProxyPort = null;
  private String MetaCartaWSProxyHost = null;
  private String MetaCartaWSProxyPort = null;
  private String UserName = null;
  private String Password = null;


  final private static int MANAGE_DOCUMENT_PRIVILEGE = 17;

  /** Deny access token for Meridio.  All tokens begin with "U" or with "G", except the blanket "READ_ALL" that I create.
  * However, we currently have code in the field, so I will continue ot use "DEAD_AUTHORITY" for that reason.
  */
  private final static String denyToken = "DEAD_AUTHORITY";

  private final static AuthorizationResponse unreachableResponse = new AuthorizationResponse(new String[]{denyToken},AuthorizationResponse.RESPONSE_UNREACHABLE);
  private final static AuthorizationResponse userNotFoundResponse = new AuthorizationResponse(new String[]{denyToken},AuthorizationResponse.RESPONSE_USERNOTFOUND);


  /** Constructor.
  */
  public MeridioAuthority() {}

  /** Return the path for the UI interface JSP elements.
  * These JSP's must be provided to allow the connector to be configured, and to
  * permit it to present document filtering specification information in the UI.
  * This method should return the name of the folder, under the <webapp>/connectors/
  * area, where the appropriate JSP's can be found.  The name should NOT have a slash in it.
  *@return the folder part
  */
  public String getJSPFolder()
  {
    final String jspFolder = "meridio";
    return jspFolder;
  }



  /** Connect.  The configuration parameters are included.
  *@param configParams are the configuration parameters for this connection.
  */
  public void connect(ConfigParams configParams)
  {
    super.connect(configParams);

    /*=================================================================
    * Construct the URL strings from the parameters
    *
    *================================================================*/
    DMWSProxyHost = configParams.getParameter("DMWSProxyHost");
    DMWSProxyPort = configParams.getParameter("DMWSProxyPort");
    RMWSProxyHost = configParams.getParameter("RMWSProxyHost");
    RMWSProxyPort = configParams.getParameter("RMWSProxyPort");
    MetaCartaWSProxyHost = configParams.getParameter("MetaCartaWSProxyHost");
    MetaCartaWSProxyPort = configParams.getParameter("MetaCartaWSProxyPort");
    UserName = configParams.getParameter("UserName");
    Password = configParams.getObfuscatedParameter("Password");

  }

  /** Set up connection before attempting to use it */
  protected void attemptToConnect()
    throws ACFException
  {
    if (meridio_ == null)
    {

      // This stuff used to be in connect(); was moved here so proper exception handling could be done
      /*=================================================================
      * Construct the URL strings from the parameters
      *
      *================================================================*/

      String DMWSProtocol = params.getParameter("DMWSServerProtocol");
      if (DMWSProtocol == null)
        throw new ACFException("Missing required configuration parameter: DMWSServerProtocol");
      String DMWSPort = params.getParameter("DMWSServerPort");
      if (DMWSPort == null || DMWSPort.length() == 0)
        DMWSPort = "";
      else
        DMWSPort = ":" + DMWSPort;

      String DMWSUrlString = DMWSProtocol + "://" +
        params.getParameter("DMWSServerName") +
        DMWSPort +
        params.getParameter("DMWSLocation");

      String RMWSProtocol = params.getParameter("RMWSServerProtocol");
      if (RMWSProtocol == null)
        throw new ACFException("Missing required configuration parameter: RMWSServerProtocol");
      String RMWSPort = params.getParameter("RMWSServerPort");
      if (RMWSPort == null || RMWSPort.length() == 0)
        RMWSPort = "";
      else
        RMWSPort = ":" + RMWSPort;

      String RMWSUrlString = RMWSProtocol + "://" +
        params.getParameter("RMWSServerName") +
        RMWSPort +
        params.getParameter("RMWSLocation");

      String MetaCartaWSProtocol = params.getParameter("MetaCartaWSServerProtocol");
      if (MetaCartaWSProtocol == null)
        throw new ACFException("Missing required configuration parameter: MetaCartaWSServerProtocol");
      String MetaCartaWSPort = params.getParameter("MetaCartaWSServerPort");
      if (MetaCartaWSPort == null || MetaCartaWSPort.length() == 0)
        MetaCartaWSPort = "";
      else
        MetaCartaWSPort = ":" + MetaCartaWSPort;

      String ACFWSUrlString = MetaCartaWSProtocol + "://" +
        params.getParameter("MetaCartaWSServerName") +
        MetaCartaWSPort +
        params.getParameter("MetaCartaWSLocation");


      // Set up ssl if indicated
      String keystoreData = params.getParameter( "MeridioKeystore" );
      myFactory = new ProtocolFactory();

      if (keystoreData != null)
      {
        IKeystoreManager keystoreManager = KeystoreManagerFactory.make("",keystoreData);
        MeridioSecureSocketFactory secureSocketFactory = new MeridioSecureSocketFactory(keystoreManager.getSecureSocketFactory());
        Protocol myHttpsProtocol = new Protocol("https", (ProtocolSocketFactory)secureSocketFactory, 443);
        myFactory.registerProtocol("https",myHttpsProtocol);
      }

      try
      {
        DmwsURL = new URL(DMWSUrlString);
        RmwsURL = new URL(RMWSUrlString);
        MetaCartawsURL = new URL(ACFWSUrlString);

        if (Logging.authorityConnectors.isDebugEnabled())
        {
          Logging.authorityConnectors.debug("Meridio: Document Management Web Service (DMWS) URL is [" + DmwsURL + "]");
          Logging.authorityConnectors.debug("Meridio: Record Management Web Service (RMWS) URL is [" + RmwsURL + "]");
          Logging.authorityConnectors.debug("Meridio: MetaCarta Web Service (MCWS) URL is [" + MetaCartawsURL + "]");
        }

      }
      catch (MalformedURLException malformedURLException)
      {
        throw new ACFException("Meridio: Could not construct the URL for either " +
          "the Meridio DM, Meridio RM, or MetaCarta Meridio Web Service: "+malformedURLException, malformedURLException);
      }

      try
      {
        /*=================================================================
        * Now try and login to Meridio; the wrapper's constructor can be
        * used as it calls the Meridio login method
        *================================================================*/
        File meridioWSDDLocation = ACF.getFileProperty(wsddPathProperty);
        if (meridioWSDDLocation == null)
          throw new ACFException("Meridio wsdd location path (property "+wsddPathProperty+") must be specified!");

        meridio_ = new MeridioWrapper(Logging.authorityConnectors, DmwsURL, RmwsURL, MetaCartawsURL,
          DMWSProxyHost, DMWSProxyPort, RMWSProxyHost, RMWSProxyPort, MetaCartaWSProxyHost, MetaCartaWSProxyPort,
          UserName, Password,
          InetAddress.getLocalHost().getHostName(),
          myFactory,
          meridioWSDDLocation.toString());
      }
      catch (UnknownHostException unknownHostException)
      {
        throw new ACFException("Meridio: A Unknown Host Exception occurred while " +
          "connecting - is a network software and hardware configuration: "+unknownHostException.getMessage(), unknownHostException);
      }
      catch (org.apache.axis.AxisFault e)
      {
        long currentTime = System.currentTimeMillis();
        if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HTTP")))
        {
          org.w3c.dom.Element elem = e.lookupFaultDetail(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HttpErrorCode"));
          if (elem != null)
          {
            elem.normalize();
            String httpErrorCode = elem.getFirstChild().getNodeValue().trim();
            throw new ACFException("Unexpected http error code "+httpErrorCode+" accessing Meridio: "+e.getMessage(),e);
          }
          throw new ACFException("Unknown http error occurred while connecting: "+e.getMessage(),e);
        }
        if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server.userException")))
        {
          String exceptionName = e.getFaultString();
          if (exceptionName.equals("java.lang.InterruptedException"))
            throw new ACFException("Interrupted",ACFException.INTERRUPTED);
        }
        if (Logging.authorityConnectors.isDebugEnabled())
          Logging.authorityConnectors.debug("Meridio: Got an unknown remote exception connecting - axis fault = "+e.getFaultCode().getLocalPart()+", detail = "+e.getFaultString()+" - retrying",e);
        throw new ACFException("Remote procedure exception: "+e.getMessage(),e);
      }
      catch (RemoteException remoteException)
      {
        throw new ACFException("Meridio: An unknown remote exception occurred while " +
          "connecting: "+remoteException.getMessage(), remoteException);
      }

    }

  }

  // All methods below this line will ONLY be called if a connect() call succeeded
  // on this instance!

  /** Check connection for sanity.
  */
  public String check()
    throws ACFException
  {
    Logging.authorityConnectors.debug("Meridio: Entering 'check' method");
    attemptToConnect();
    try
    {
      /*=================================================================
      * Call a method in the Web Services API to get the Meridio system
      * name back - just something simple to test the connection
      * end-to-end
      *================================================================*/
      DMDataSet ds = meridio_.getStaticData();
      if (null == ds)
      {
        Logging.authorityConnectors.warn("Meridio: DM DataSet returned was null in 'check' method");
        return "Connection Failed - Internal Error Contact Support";
      }
      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("Meridio System Name is [" +
        ds.getSYSTEMINFO().getSystemName() + "] and the comment is [" +
        ds.getSYSTEMINFO().getComment() + "]");

      /*=================================================================
      * For completeness, we also call a method in the RM Web
      * Service API
      *================================================================*/
      RMDataSet rmws = meridio_.getConfiguration();
      if (null == rmws)
      {
        Logging.authorityConnectors.warn("Meridio: RM DataSet returned was null in 'check' method");
        return "Connection Failed - RM DataSet Error, contact Support";
      }

      /*=================================================================
      * Finally, try to get the group parents of user ID 2 (which is the admin user always).
      * This tests the MetaCarta web service setup.
      */
      meridio_.getUsersGroups(2);

      Logging.authorityConnectors.debug("Meridio: Exiting 'check' method");

      return super.check();
    }
    catch (org.apache.axis.AxisFault e)
    {
      long currentTime = System.currentTimeMillis();
      if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HTTP")))
      {
        org.w3c.dom.Element elem = e.lookupFaultDetail(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HttpErrorCode"));
        if (elem != null)
        {
          elem.normalize();
          String httpErrorCode = elem.getFirstChild().getNodeValue().trim();
          return "Unexpected http error code "+httpErrorCode+" accessing Meridio: "+e.getMessage();
        }
        return "Unknown http error occurred while connecting: "+e.getMessage();
      }
      if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server.userException")))
      {
        String exceptionName = e.getFaultString();
        if (exceptionName.equals("java.lang.InterruptedException"))
          throw new ACFException("Interrupted",ACFException.INTERRUPTED);
      }
      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("Meridio: Got an unknown remote exception checking - axis fault = "+e.getFaultCode().getLocalPart()+", detail = "+e.getFaultString()+" - retrying",e);
      return "Axis fault: "+e.getMessage();
    }
    catch (RemoteException remoteException)
    {
      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("Meridio: Unknown remote exception occurred during 'check' method: "+remoteException.getMessage(),
        remoteException);
      return "Meridio: An unknown remote exception occurred while connecting: "+remoteException.getMessage();
    }
    catch (MeridioDataSetException meridioDataSetException)
    {
      /*=================================================================
      * Log the exception because we will then discard it
      *
      * If it is a DataSet exception it means that we could not marshal
      * or unmarshall the XML returned from the Web Service call. This
      * means there is either a problem with the code, or perhaps the
      * connector is pointing at an incorrect/unsupported version of
      * Meridio
      *================================================================*/
      Logging.authorityConnectors.warn("Meridio: DataSet Exception occurred during 'check' method",
        meridioDataSetException);

      return "Connection Failed - DataSet error: "+meridioDataSetException.getMessage();
    }
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  public void disconnect()
    throws ACFException
  {
    Logging.authorityConnectors.debug("Meridio: Entering 'disconnect' method");
    try
    {
      if (meridio_ != null)
      {
        meridio_.logout();
      }
    }
    catch (org.apache.axis.AxisFault e)
    {
      long currentTime = System.currentTimeMillis();
      if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HTTP")))
      {
        org.w3c.dom.Element elem = e.lookupFaultDetail(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HttpErrorCode"));
        if (elem != null)
        {
          elem.normalize();
          String httpErrorCode = elem.getFirstChild().getNodeValue().trim();
          Logging.authorityConnectors.warn("Unexpected http error code "+httpErrorCode+" logging out: "+e.getMessage());
          return;
        }
        Logging.authorityConnectors.warn("Unknown http error occurred while logging out: "+e.getMessage());
        return;
      }
      if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server.userException")))
      {
        String exceptionName = e.getFaultString();
        if (exceptionName.equals("java.lang.InterruptedException"))
          throw new ACFException("Interrupted",ACFException.INTERRUPTED);
      }
      if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server")))
      {
        if (e.getFaultString().indexOf(" 23031#") != -1)
        {
          // This means that the session has expired, so reset it and retry
          meridio_ = null;
          return;
        }
      }

      Logging.authorityConnectors.warn("Meridio: Got an unknown remote exception logging out - axis fault = "+e.getFaultCode().getLocalPart()+", detail = "+e.getFaultString(),e);
      return;
    }
    catch (RemoteException remoteException)
    {
      Logging.authorityConnectors.warn("Meridio: A remote exception occurred while " +
        "logging out: "+remoteException.getMessage(), remoteException);
    }
    finally
    {
      super.disconnect();
      meridio_ = null;
      DmwsURL = null;
      RmwsURL = null;
      MetaCartawsURL = null;
      myFactory = null;
      DMWSProxyHost = null;
      DMWSProxyPort = null;
      RMWSProxyHost = null;
      RMWSProxyPort = null;
      MetaCartaWSProxyHost = null;
      MetaCartaWSProxyPort = null;
      UserName = null;
      Password = null;
    }
    Logging.authorityConnectors.debug("Meridio: Exiting 'disconnect' method");
  }


  /** Obtain the access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  public AuthorizationResponse getAuthorizationResponse(String userName)
    throws ACFException
  {
    if (Logging.authorityConnectors.isDebugEnabled())
      Logging.authorityConnectors.debug("Meridio: Authentication user name = '" + userName + "'");

    while (true)
    {
      attemptToConnect();

      // Strip off everything after the @-sign
      int index = userName.indexOf("@");
      if (index != -1)
        userName = userName.substring(0,index);

      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("Meridio: Meridio user name = '"+userName+"'");

      ArrayList aclList = new ArrayList();

      try
      {
        /*=================================================================
        * Search for the user in Meridio to find their internal user ID.
        * The method returns 0 if the user is not found
        *
        * We expect the user name passed in to be the user's login name
        * without the "domain" prefix or suffix (i.e. domain\\username
        * or username@domain.com)
        *
        * If the username is passed in a different format, then it should
        * be transformed first
        *================================================================*/
        //TODO: We could also possibly search on the user's directory name to
        //      avoid the transformation
        long userId = meridio_.getUserIdFromName(userName);
        if (0L == userId)
        {
          if (Logging.authorityConnectors.isDebugEnabled())
            Logging.authorityConnectors.debug("Meridio: User '" + userName + "' does not exist");
          return userNotFoundResponse;
        }
        if (Logging.authorityConnectors.isDebugEnabled())
          Logging.authorityConnectors.debug("Meridio: Found user - the User Id for '" + userName +
          "' is " + userId);

        aclList.add("U" + userId);

        /** This is the new, MetaCarta-service way of getting the groups, which relies on the custom
        * MetaCarta web service developed by Meridio.  The old way was too inefficient to be workable for
        * installations that had more than a few users and groups.
        */
        GroupResult [] userGroups = null;
        userGroups = meridio_.getUsersGroups(new Long(userId).intValue());
        if (userGroups != null)
        {
          for (int i = 0; i < userGroups.length; i++)
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("Group ID '" + userGroups[i].getGroupID() +
              "' Group Name '" + userGroups[i].getGroupName() + ">'");
            aclList.add("G" + userGroups[i].getGroupID());
          }
        }

        /*=================================================================
        * Protective markings
        *================================================================*/
        //TODO: Check if we must cater for protective markings since there
        //      is the complexity of informative markings and
        //      hierarchical markings to deal with
        //
        // Hierarchical ones are CONFIDENTIAL, RESTRICTED, SECRET, TOP SECRET
        // Non-hierarchical ones might be UK Eyes Only, US Eyes Only, etc.
        //


        /*=================================================================
        * Get the user's privileges
        *
        * The "Manage Documents" privilege will effectively grant the user
        * "manage" access to all documents/records within Meridio, so if
        * the user has been granted that privilege, or one of the groups
        * of which they are a member has that privilege then add it to
        * the token list
        *================================================================*/
        RMDataSet userPrivileges = meridio_.getUserPrivilegeList(new Long(userId).intValue());

        for (int i = 0; i < userPrivileges.getRm2Privilege().length; i++)
        {
          if (Logging.authorityConnectors.isDebugEnabled())
            Logging.authorityConnectors.debug("Meridio: Privilege ID '" + userPrivileges.getRm2Privilege()[i].getId() + "' " +
            "Name '" + userPrivileges.getRm2Privilege()[i].getName() + "'");

          if (userPrivileges.getRm2Privilege()[i].getId() == MANAGE_DOCUMENT_PRIVILEGE)
          {
            Logging.authorityConnectors.debug("Meridio: User has Manage Document privilege so adding READ_ALL to token list");
            aclList.add("READ_ALL");
          }
        }

        String[] rval = new String[aclList.size()];
        for (int i = 0; i < rval.length; i++)
        {
          rval[i] = (String)aclList.get(i);
        }

        Logging.authorityConnectors.debug("Meridio: Exiting method getAccessTokens");
        return new AuthorizationResponse(rval,AuthorizationResponse.RESPONSE_OK);
      }
      catch (org.apache.axis.AxisFault e)
      {
        long currentTime = System.currentTimeMillis();
        if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HTTP")))
        {
          org.w3c.dom.Element elem = e.lookupFaultDetail(new javax.xml.namespace.QName("http://xml.apache.org/axis/","HttpErrorCode"));
          if (elem != null)
          {
            elem.normalize();
            String httpErrorCode = elem.getFirstChild().getNodeValue().trim();
            throw new ACFException("Unexpected http error code "+httpErrorCode+" accessing Meridio: "+e.getMessage(),e);
          }
          throw new ACFException("Unknown http error occurred while getting doc versions: "+e.getMessage(),e);
        }
        if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server.userException")))
        {
          String exceptionName = e.getFaultString();
          if (exceptionName.equals("java.lang.InterruptedException"))
            throw new ACFException("Interrupted",ACFException.INTERRUPTED);
        }
        if (e.getFaultCode().equals(new javax.xml.namespace.QName("http://schemas.xmlsoap.org/soap/envelope/","Server")))
        {
          if (e.getFaultString().indexOf(" 23031#") != -1)
          {
            // This means that the session has expired, so reset it and retry
            meridio_ = null;
            continue;
          }
        }

        if (Logging.authorityConnectors.isDebugEnabled())
          Logging.authorityConnectors.debug("Meridio: Got an unknown remote exception getting user tokens - axis fault = "+e.getFaultCode().getLocalPart()+", detail = "+e.getFaultString()+" - retrying",e);
        throw new ACFException("Axis fault: "+e.getMessage(),  e);
      }
      catch (RemoteException remoteException)
      {
        throw new ACFException("Meridio: A remote exception occurred while getting user tokens: " +
          remoteException.getMessage(), remoteException);
      }
      catch (MeridioDataSetException meridioDataSetException)
      {
        Logging.authorityConnectors.error("Meridio: A provlem occurred manipulating the Web Service XML: " +
          meridioDataSetException.getMessage(), meridioDataSetException);
        throw new ACFException("Meridio: A problem occurred manipulating the Web " +
          "Service XML: "+meridioDataSetException.getMessage(), meridioDataSetException);
      }
    }
  }

  /** Obtain the default access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the default response tokens, presuming that the connect method fails.
  */
  public AuthorizationResponse getDefaultAuthorizationResponse(String userName)
  {
    return unreachableResponse;
  }
  
  // UI support methods.
  //
  // These support methods are involved in setting up authority connection configuration information. The configuration methods cannot assume that the
  // current authority object is connected.  That is why they receive a thread context argument.
    
  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters, ArrayList tabsArray)
    throws ACFException, IOException
  {
    tabsArray.add("Document Server");
    tabsArray.add("Records Server");
    tabsArray.add("User Service Server");
    tabsArray.add("Credentials");
    out.print(
"<script type=\"text/javascript\">\n"+
"<!--\n"+
"\n"+
"function checkConfig()\n"+
"{\n"+
"  if (editconnection.dmwsServerPort.value != \"\" && !isInteger(editconnection.dmwsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.dmwsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.rmwsServerPort.value != \"\" && !isInteger(editconnection.rmwsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.rmwsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.dmwsProxyPort.value != \"\" && !isInteger(editconnection.dmwsProxyPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.dmwsProxyPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.rmwsProxyPort.value != \"\" && !isInteger(editconnection.rmwsProxyPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.rmwsProxyPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.metacartawsServerPort.value != \"\" && !isInteger(editconnection.metacartawsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.metacartawsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.metacartawsProxyPort.value != \"\" && !isInteger(editconnection.metacartawsProxyPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a valid number\");\n"+
"    editconnection.metacartawsProxyPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.userName.value != \"\" && editconnection.userName.value.indexOf(\"\\\") <= 0)\n"+
"  {\n"+
"    alert(\"A valid Meridio user name has the form <domain>\\<user>\");\n"+
"    editconnection.userName.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"function checkConfigForSave()\n"+
"{\n"+
"  if (editconnection.dmwsServerName.value == \"\")\n"+
"  {\n"+
"    alert(\"Please fill in a Meridio document management server name\");\n"+
"    SelectTab(\"Document Server\");\n"+
"    editconnection.dmwsServerName.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.rmwsServerName.value == \"\")\n"+
"  {\n"+
"    alert(\"Please fill in a Meridio records management server name\");\n"+
"    SelectTab(\"Records Server\");\n"+
"    editconnection.rmwsServerName.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.metacartawsServerName.value == \"\")\n"+
"  {\n"+
"    alert(\"Please fill in a User Service server name\");\n"+
"    SelectTab(\"User Service Server\");\n"+
"    editconnection.metacartawsServerName.focus();\n"+
"    return false;\n"+
"  }\n"+
"\n"+
"  if (editconnection.dmwsServerPort.value != \"\" && !isInteger(editconnection.dmwsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a Meridio document management port number, or none for default\");\n"+
"    SelectTab(\"Document Server\");\n"+
"    editconnection.dmwsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.rmwsServerPort.value != \"\" && !isInteger(editconnection.rmwsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a Meridio document management port number, or none for default\");\n"+
"    SelectTab(\"Records Server\");\n"+
"    editconnection.rmwsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.metacartawsServerPort.value != \"\" && !isInteger(editconnection.metacartawsServerPort.value))\n"+
"  {\n"+
"    alert(\"Please supply a User Service port number, or none for default\");\n"+
"    SelectTab(\"User Service Server\");\n"+
"    editconnection.metacartawsServerPort.focus();\n"+
"    return false;\n"+
"  }\n"+
"\n"+
"  if (editconnection.userName.value == \"\" || editconnection.userName.value.indexOf(\"\\\") <= 0)\n"+
"  {\n"+
"    alert(\"The connection requires a valid Meridio user name of the form <domain>\\<user>\");\n"+
"    SelectTab(\"Credentials\");\n"+
"    editconnection.userName.focus();\n"+
"    return false;\n"+
"  }\n"+
"\n"+
"  return true;\n"+
"}\n"+
"\n"+
"function DeleteCertificate(aliasName)\n"+
"{\n"+
"  editconnection.keystorealias.value = aliasName;\n"+
"  editconnection.configop.value = \"Delete\";\n"+
"  postForm();\n"+
"}\n"+
"\n"+
"function AddCertificate()\n"+
"{\n"+
"  if (editconnection.certificate.value == \"\")\n"+
"  {\n"+
"    alert(\"Choose a certificate file\");\n"+
"    editconnection.certificate.focus();\n"+
"  }\n"+
"  else\n"+
"  {\n"+
"    editconnection.configop.value = \"Add\";\n"+
"    postForm();\n"+
"  }\n"+
"}\n"+
"\n"+
"//-->\n"+
"</script>\n"
    );
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the authority connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters, String tabName)
    throws ACFException, IOException
  {
    String dmwsServerProtocol = parameters.getParameter("DMWSServerProtocol");
    if (dmwsServerProtocol == null)
      dmwsServerProtocol = "http";
    String rmwsServerProtocol = parameters.getParameter("RMWSServerProtocol");
    if (rmwsServerProtocol == null)
      rmwsServerProtocol = "http";
    String metacartawsServerProtocol = parameters.getParameter("MetaCartaWSServerProtocol");
    if (metacartawsServerProtocol == null)
      metacartawsServerProtocol = "http";

    String dmwsServerName = parameters.getParameter("DMWSServerName");
    if (dmwsServerName == null)
      dmwsServerName = "";
    String rmwsServerName = parameters.getParameter("RMWSServerName");
    if (rmwsServerName == null)
      rmwsServerName = "";
    String metacartawsServerName = parameters.getParameter("MetaCartaWSServerName");
    if (metacartawsServerName == null)
      metacartawsServerName = "";

    String dmwsServerPort = parameters.getParameter("DMWSServerPort");
    if (dmwsServerPort == null)
      dmwsServerPort = "";
    String rmwsServerPort = parameters.getParameter("RMWSServerPort");
    if (rmwsServerPort == null)
      rmwsServerPort = "";
    String metacartawsServerPort = parameters.getParameter("MetaCartaWSServerPort");
    if (metacartawsServerPort == null)
      metacartawsServerPort = "";

    String dmwsLocation = parameters.getParameter("DMWSLocation");
    if (dmwsLocation == null)
      dmwsLocation = "/DMWS/MeridioDMWS.asmx";
    String rmwsLocation = parameters.getParameter("RMWSLocation");
    if (rmwsLocation == null)
      rmwsLocation = "/RMWS/MeridioRMWS.asmx";
    String metacartawsLocation = parameters.getParameter("MetaCartaWSLocation");
    if (metacartawsLocation == null)
      metacartawsLocation = "/MetaCartaWebService/MetaCarta.asmx";

    String dmwsProxyHost = parameters.getParameter("DMWSProxyHost");
    if (dmwsProxyHost == null)
      dmwsProxyHost = "";
    String rmwsProxyHost = parameters.getParameter("RMWSProxyHost");
    if (rmwsProxyHost == null)
      rmwsProxyHost = "";
    String metacartawsProxyHost = parameters.getParameter("MetaCartaWSProxyHost");
    if (metacartawsProxyHost == null)
      metacartawsProxyHost = "";

    String dmwsProxyPort = parameters.getParameter("DMWSProxyPort");
    if (dmwsProxyPort == null)
      dmwsProxyPort = "";
    String rmwsProxyPort = parameters.getParameter("RMWSProxyPort");
    if (rmwsProxyPort == null)
      rmwsProxyPort = "";
    String metacartawsProxyPort = parameters.getParameter("MetaCartaWSProxyPort");
    if (metacartawsProxyPort == null)
      metacartawsProxyPort = "";

    String userName = parameters.getParameter("UserName");
    if (userName == null)
      userName = "";

    String password = parameters.getObfuscatedParameter("Password");
    if (password == null)
      password = "";

    String meridioKeystore = parameters.getParameter("MeridioKeystore");
    IKeystoreManager localKeystore;
    if (meridioKeystore == null)
      localKeystore = KeystoreManagerFactory.make("");
    else
      localKeystore = KeystoreManagerFactory.make("",meridioKeystore);

    out.print(
"<input name=\"configop\" type=\"hidden\" value=\"Continue\"/>\n"
    );
    
    // "Document Server" tab
    if (tabName.equals("Document Server"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice server protocol:</nobr></td><td class=\"value\"><select name=\"dmwsServerProtocol\"><option value=\"http\" "+(dmwsServerProtocol.equals("http")?"selected=\"true\"":"")+">http</option><option value=\"https\" "+(dmwsServerProtocol.equals("https")?"selected=\"true\"":"")+">https</option></select></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice server name:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"dmwsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsServerName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice server port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"dmwsServerPort\" value=\""+dmwsServerPort+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice location:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"dmwsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsLocation)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"separator\" colspan=\"2\"><hr/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice server proxy host:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"dmwsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsProxyHost)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Document webservice server proxy port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"dmwsProxyPort\" value=\""+dmwsProxyPort+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for the Document Server tab.
      out.print(
"<input type=\"hidden\" name=\"dmwsServerProtocol\" value=\""+dmwsServerProtocol+"\"/>\n"+
"<input type=\"hidden\" name=\"dmwsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsServerName)+"\"/>\n"+
"<input type=\"hidden\" name=\"dmwsServerPort\" value=\""+dmwsServerPort+"\"/>\n"+
"<input type=\"hidden\" name=\"dmwsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsLocation)+"\"/>\n"+
"<input type=\"hidden\" name=\"dmwsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(dmwsProxyHost)+"\"/>\n"+
"<input type=\"hidden\" name=\"dmwsProxyPort\" value=\""+dmwsProxyPort+"\"/>\n"
      );
    }

    // "Records Server" tab
    if (tabName.equals("Records Server"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice server protocol:</nobr></td><td class=\"value\"><select name=\"rmwsServerProtocol\"><option value=\"http\" "+(rmwsServerProtocol.equals("http")?"selected=\"true\"":"")+">http</option><option value=\"https\" "+(rmwsServerProtocol.equals("https")?"selected=\"true\"":"")+">https</option></select></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice server name:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"rmwsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsServerName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice server port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"rmwsServerPort\" value=\""+rmwsServerPort+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice location:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"rmwsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsLocation)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"separator\" colspan=\"2\"><hr/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice server proxy host:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"rmwsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsProxyHost)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Record webservice server proxy port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"rmwsProxyPort\" value=\""+rmwsProxyPort+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for the Records Server tab.
      out.print(
"<input type=\"hidden\" name=\"rmwsServerProtocol\" value=\""+rmwsServerProtocol+"\"/>\n"+
"<input type=\"hidden\" name=\"rmwsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsServerName)+"\"/>\n"+
"<input type=\"hidden\" name=\"rmwsServerPort\" value=\""+rmwsServerPort+"\"/>\n"+
"<input type=\"hidden\" name=\"rmwsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsLocation)+"\"/>\n"+
"<input type=\"hidden\" name=\"rmwsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(rmwsProxyHost)+"\"/>\n"+
"<input type=\"hidden\" name=\"rmwsProxyPort\" value=\""+rmwsProxyPort+"\"/>\n"
      );
    }

    // The "User Service Server" tab
    if (tabName.equals("User Service Server"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice server protocol:</nobr></td><td class=\"value\"><select name=\"metacartawsServerProtocol\"><option value=\"http\" "+(metacartawsServerProtocol.equals("http")?"selected=\"true\"":"")+">http</option><option value=\"https\" "+(metacartawsServerProtocol.equals("https")?"selected=\"true\"":"")+">https</option></select></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice server name:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"metacartawsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsServerName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice server port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"metacartawsServerPort\" value=\""+metacartawsServerPort+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice location:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"metacartawsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsLocation)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"separator\" colspan=\"2\"><hr/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice server proxy host:</nobr></td><td class=\"value\"><input type=\"text\" size=\"64\" name=\"metacartawsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsProxyHost)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User webservice server proxy port:</nobr></td><td class=\"value\"><input type=\"text\" size=\"5\" name=\"metacartawsProxyPort\" value=\""+metacartawsProxyPort+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for the User Service Server tab.
      out.print(
"<input type=\"hidden\" name=\"metacartawsServerProtocol\" value=\""+metacartawsServerProtocol+"\"/>\n"+
"<input type=\"hidden\" name=\"metacartawsServerName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsServerName)+"\"/>\n"+
"<input type=\"hidden\" name=\"metacartawsServerPort\" value=\""+metacartawsServerPort+"\"/>\n"+
"<input type=\"hidden\" name=\"metacartawsLocation\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsLocation)+"\"/>\n"+
"<input type=\"hidden\" name=\"metacartawsProxyHost\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(metacartawsProxyHost)+"\"/>\n"+
"<input type=\"hidden\" name=\"metacartawsProxyPort\" value=\""+metacartawsProxyPort+"\"/>\n"
      );
    }

    // The "Credentials" tab
    // Always pass the whole keystore as a hidden.
    if (meridioKeystore != null)
    {
      out.print(
"<input type=\"hidden\" name=\"keystoredata\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(meridioKeystore)+"\"/>\n"
      );
    }

    if (tabName.equals("Credentials"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>User name:</nobr></td><td class=\"value\"><input type=\"text\" size=\"32\" name=\"userName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(userName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Password:</nobr></td><td class=\"value\"><input type=\"password\" size=\"32\" name=\"password\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(password)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>SSL certificate list:</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <input type=\"hidden\" name=\"keystorealias\" value=\"\"/>\n"+
"      <table class=\"displaytable\">\n"
      );
      // List the individual certificates in the store, with a delete button for each
      String[] contents = localKeystore.getContents();
      if (contents.length == 0)
      {
        out.print(
"        <tr><td class=\"message\" colspan=\"2\"><nobr>No certificates present</nobr></td></tr>\n"
        );
      }
      else
      {
        int i = 0;
        while (i < contents.length)
        {
          String alias = contents[i];
          String description = localKeystore.getDescription(alias);
          if (description.length() > 128)
          description = description.substring(0,125) + "...";
          out.print(
"        <tr>\n"+
"          <td class=\"value\"><input type=\"button\" onclick='Javascript:DeleteCertificate(\""+org.apache.acf.ui.util.Encoder.attributeJavascriptEscape(alias)+"\")' alt=\"Delete cert "+org.apache.acf.ui.util.Encoder.attributeEscape(alias)+"\" value=\"Delete\"/></td>\n"+
"          <td>"+org.apache.acf.ui.util.Encoder.bodyEscape(description)+"</td>\n"+
"        </tr>\n"
          );
          i++;
        }
      }
      
      out.print(
"      </table>\n"+
"      <input type=\"button\" onclick=\"Javascript:AddCertificate()\" alt=\"Add cert\" value=\"Add\"/>&nbsp;\n"+
"        Certificate:&nbsp;<input name=\"certificate\" size=\"50\" type=\"file\"/>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for the "Credentials" tab
      out.print(
"<input type=\"hidden\" name=\"userName\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(userName)+"\"/>\n"+
"<input type=\"hidden\" name=\"password\" value=\""+org.apache.acf.ui.util.Encoder.attributeEscape(password)+"\"/>\n"
      );
    }
  }
  
  /** Process a configuration post.
  * This method is called at the start of the authority connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext, ConfigParams parameters)
    throws ACFException
  {
    String dmwsServerProtocol = variableContext.getParameter("dmwsServerProtocol");
    if (dmwsServerProtocol != null)
      parameters.setParameter("DMWSServerProtocol",dmwsServerProtocol);
    String rmwsServerProtocol = variableContext.getParameter("rmwsServerProtocol");
    if (rmwsServerProtocol != null)
      parameters.setParameter("RMWSServerProtocol",rmwsServerProtocol);
    String metacartawsServerProtocol = variableContext.getParameter("metacartawsServerProtocol");
    if (metacartawsServerProtocol != null)
      parameters.setParameter("MetaCartaWSServerProtocol",metacartawsServerProtocol);

    String dmwsServerName = variableContext.getParameter("dmwsServerName");
    if (dmwsServerName != null)
      parameters.setParameter("DMWSServerName",dmwsServerName);
    String rmwsServerName = variableContext.getParameter("rmwsServerName");
    if (rmwsServerName != null)
      parameters.setParameter("RMWSServerName",rmwsServerName);
    String metacartawsServerName = variableContext.getParameter("metacartawsServerName");
    if (metacartawsServerName != null)
      parameters.setParameter("MetaCartaWSServerName",metacartawsServerName);

    String dmwsServerPort = variableContext.getParameter("dmwsServerPort");
    if (dmwsServerPort != null)
    {
      if (dmwsServerPort.length() > 0)
        parameters.setParameter("DMWSServerPort",dmwsServerPort);
      else
        parameters.setParameter("DMWSServerPort",null);
    }
    String rmwsServerPort = variableContext.getParameter("rmwsServerPort");
    if (rmwsServerPort != null)
    {
      if (rmwsServerPort.length() > 0)
        parameters.setParameter("RMWSServerPort",rmwsServerPort);
      else
        parameters.setParameter("RMWSServerPort",null);
    }
    String metacartawsServerPort = variableContext.getParameter("metacartawsServerPort");
    if (metacartawsServerPort != null)
    {
      if (metacartawsServerPort.length() > 0)
        parameters.setParameter("MetaCartaWSServerPort",metacartawsServerPort);
      else
        parameters.setParameter("MetaCartaWSServerPort",null);
    }

    String dmwsLocation = variableContext.getParameter("dmwsLocation");
    if (dmwsLocation != null)
      parameters.setParameter("DMWSLocation",dmwsLocation);
    String rmwsLocation = variableContext.getParameter("rmwsLocation");
    if (rmwsLocation != null)
      parameters.setParameter("RMWSLocation",rmwsLocation);
    String metacartawsLocation = variableContext.getParameter("metacartawsLocation");
    if (metacartawsLocation != null)
      parameters.setParameter("MetaCartaWSLocation",metacartawsLocation);

    String dmwsProxyHost = variableContext.getParameter("dmwsProxyHost");
    if (dmwsProxyHost != null)
      parameters.setParameter("DMWSProxyHost",dmwsProxyHost);
    String rmwsProxyHost = variableContext.getParameter("rmwsProxyHost");
    if (rmwsProxyHost != null)
      parameters.setParameter("RMWSProxyHost",rmwsProxyHost);
    String metacartawsProxyHost = variableContext.getParameter("metacartawsProxyHost");
    if (metacartawsProxyHost != null)
      parameters.setParameter("MetaCartaWSProxyHost",metacartawsProxyHost);
		
    String dmwsProxyPort = variableContext.getParameter("dmwsProxyPort");
    if (dmwsProxyPort != null && dmwsProxyPort.length() > 0)
      parameters.setParameter("DMWSProxyPort",dmwsProxyPort);
    String rmwsProxyPort = variableContext.getParameter("rmwsProxyPort");
    if (rmwsProxyPort != null && rmwsProxyPort.length() > 0)
      parameters.setParameter("RMWSProxyPort",rmwsProxyPort);
    String metacartawsProxyPort = variableContext.getParameter("metacartawsProxyPort");
    if (metacartawsProxyPort != null && metacartawsProxyPort.length() > 0)
      parameters.setParameter("MetaCartaWSProxyPort",metacartawsProxyPort);

    String userName = variableContext.getParameter("userName");
    if (userName != null)
      parameters.setParameter("UserName",userName);

    String password = variableContext.getParameter("password");
    if (password != null)
      parameters.setObfuscatedParameter("Password",password);

    String configOp = variableContext.getParameter("configop");
    if (configOp != null)
    {
      String keystoreValue;
      if (configOp.equals("Delete"))
      {
        String alias = variableContext.getParameter("keystorealias");
        keystoreValue = parameters.getParameter("MeridioKeystore");
        IKeystoreManager mgr;
        if (keystoreValue != null)
          mgr = KeystoreManagerFactory.make("",keystoreValue);
        else
          mgr = KeystoreManagerFactory.make("");
        mgr.remove(alias);
        parameters.setParameter("MeridioKeystore",mgr.getString());
      }
      else if (configOp.equals("Add"))
      {
        String alias = IDFactory.make(threadContext);
        byte[] certificateValue = variableContext.getBinaryBytes("certificate");
        keystoreValue = parameters.getParameter("MeridioKeystore");
        IKeystoreManager mgr;
        if (keystoreValue != null)
          mgr = KeystoreManagerFactory.make("",keystoreValue);
        else
          mgr = KeystoreManagerFactory.make("");
        java.io.InputStream is = new java.io.ByteArrayInputStream(certificateValue);
        String certError = null;
        try
        {
          mgr.importCertificate(alias,is);
        }
        catch (Throwable e)
        {
          certError = e.getMessage();
        }
        finally
        {
          try
          {
            is.close();
          }
          catch (IOException e)
          {
            throw new ACFException(e.getMessage(),e);
          }
        }

        if (certError != null)
        {
          // Redirect to error page
          return "Illegal certificate: "+certError;
        }
        parameters.setParameter("MeridioKeystore",mgr.getString());
      }
    }
    return null;
  }
  
  /** View configuration.
  * This method is called in the body section of the authority connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters)
    throws ACFException, IOException
  {
    out.print(
"<table class=\"displaytable\">\n"+
"  <tr>\n"+
"    <td class=\"description\" colspan=\"1\"><nobr>Parameters:</nobr></td>\n"+
"    <td class=\"value\" colspan=\"3\">\n"
    );
    Iterator iter = parameters.listParameters();
    while (iter.hasNext())
    {
      String param = (String)iter.next();
      String value = parameters.getParameter(param);
      if (param.length() >= "password".length() && param.substring(param.length()-"password".length()).equalsIgnoreCase("password"))
      {
        out.print(
"      <nobr>"+org.apache.acf.ui.util.Encoder.bodyEscape(param)+"=********</nobr><br/>\n"
        );
      }
      else if (param.length() >="keystore".length() && param.substring(param.length()-"keystore".length()).equalsIgnoreCase("keystore"))
      {
        IKeystoreManager kmanager = KeystoreManagerFactory.make("",value);
        out.print(
"      <nobr>"+org.apache.acf.ui.util.Encoder.bodyEscape(param)+"=<"+Integer.toString(kmanager.getContents().length)+" certificate(s)></nobr><br/>\n"
        );
      }
      else
      {
        out.print(
"      <nobr>"+org.apache.acf.ui.util.Encoder.bodyEscape(param)+"="+org.apache.acf.ui.util.Encoder.bodyEscape(value)+"</nobr><br/>\n"
        );
      }
    }
    out.print(
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
    );
  }

}