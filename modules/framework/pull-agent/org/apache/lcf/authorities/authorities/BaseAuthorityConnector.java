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
package org.apache.lcf.authorities.authorities;

import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.authorities.interfaces.*;

/** An authority connector supplies an ACL of some kind for a given user.  This is necessary so that the search UI
* can find the documents that can be legally seen.
*
* An instance of this interface provides this functionality.  Authority connector instances are pooled, so that session
* setup does not need to be done repeatedly.  The pool is segregated by specific sets of configuration parameters.
*/
public abstract class BaseAuthorityConnector implements IAuthorityConnector
{
  public static final String _rcsid = "@(#)$Id$";

  // Config params
  protected ConfigParams params = null;

  // Current thread context
  protected IThreadContext currentContext = null;

  /** Install the connector.
  * This method is called to initialize persistent storage for the connector, such as database tables etc.
  * It is called when the connector is registered.
  *@param threadContext is the current thread context.
  */
  public void install(IThreadContext threadContext)
    throws LCFException
  {
    // Base version does nothing.
  }


  /** Uninstall the connector.
  * This method is called to remove persistent storage for the connector, such as database tables etc.
  * It is called when the connector is deregistered.
  *@param threadContext is the current thread context.
  */
  public void deinstall(IThreadContext threadContext)
    throws LCFException
  {
    // Base version does nothing.
  }

  /** Connect.  The configuration parameters are included.
  *@param configParams are the configuration parameters for this connection.
  */
  public void connect(ConfigParams configParams)
  {
    params = configParams;
  }


  // All methods below this line will ONLY be called if a connect() call succeeded
  // on this instance!

  /** Test the connection.  Returns a string describing the connection integrity.
  *@return the connection's status as a displayable string.
  */
  public String check()
    throws LCFException
  {
    return "Connection working";
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  public void poll()
    throws LCFException
  {
    // Base version does nothing.
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  public void disconnect()
    throws LCFException
  {
    params = null;
  }

  /** Get configuration information.
  *@return the configuration information for this class.
  */
  public ConfigParams getConfiguration()
  {
    return params;
  }

  /** Clear out any state information specific to a given thread.
  * This method is called when this object is returned to the connection pool.
  */
  public void clearThreadContext()
  {
    currentContext = null;
  }

  /** Attach to a new thread.
  *@param threadContext is the new thread context.
  */
  public void setThreadContext(IThreadContext threadContext)
  {
    currentContext = threadContext;
  }

  /** Obtain the access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  public AuthorizationResponse getAuthorizationResponse(String userName)
    throws LCFException
  {
    // Implementation for old-style behavior.  Override this method for new-style behavior.
    try
    {
      String[] accessTokens = getAccessTokens(userName);
      if (accessTokens == null)
        return new AuthorizationResponse(new String[0],AuthorizationResponse.RESPONSE_USERNOTFOUND);
      return new AuthorizationResponse(accessTokens,AuthorizationResponse.RESPONSE_OK);
    }
    catch (LCFException e)
    {
      // There's an authorization failure of some kind.
      String[] defaultAccessTokens = getDefaultAccessTokens(userName);
      if (defaultAccessTokens == null)
      {
        // Treat it as an authorization failure
        return new AuthorizationResponse(new String[0],AuthorizationResponse.RESPONSE_USERUNAUTHORIZED);
      }
      return new AuthorizationResponse(defaultAccessTokens,AuthorizationResponse.RESPONSE_UNREACHABLE);
    }
  }

  /** Obtain the default access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the default response tokens, presuming that the connect method fails.
  */
  public AuthorizationResponse getDefaultAuthorizationResponse(String userName)
  {
    String[] acls = getDefaultAccessTokens(userName);
    if (acls == null)
      return new AuthorizationResponse(new String[0],AuthorizationResponse.RESPONSE_USERUNAUTHORIZED);
    else
      return new AuthorizationResponse(acls,AuthorizationResponse.RESPONSE_UNREACHABLE);
  }

  /** Obtain the access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the tokens (according to the current authority), or null if the user does not exist.
  * (Throw an exception if access is denied, usually because the authority is down).
  */
  public String[] getAccessTokens(String userName)
    throws LCFException
  {
    return null;
  }

  /** Return the default access tokens in the case where the getAccessTokens() method could not
  * connect with the server.
  *@param userName is the username that the access tokens are for.  Typically this is not used.
  *@return the default tokens, or null if there are no default takens, and the error should be
  * treated as a hard one.
  */
  public String[] getDefaultAccessTokens(String userName)
  {
    return null;
  }
}