/* $Id: Messages.java 1001023 2011-12-12 18:41:28Z hozawa $ */

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
package org.apache.manifoldcf.core.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.HashSet;

import java.io.InputStream;

import org.apache.manifoldcf.core.system.Logging;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;

public class Messages
{
  // Keep track of messages and bundles we've already complained about.
  
  protected static Set<BundleKey> bundleSet = new HashSet<BundleKey>();
  protected static Set<MessageKey> messageSet = new HashSet<MessageKey>();
  protected static Set<ResourceKey> resourceSet = new HashSet<ResourceKey>();
  
  /** Constructor - do no instantiate
  */
  protected Messages()
  {
  }
  
  /** Read a resource as an input stream, given a classloader, path, locale, and resource key.
  */
  public static InputStream getResourceAsStream(Class classInstance, String pathName,
    Locale locale, String resourceKey)
    throws ManifoldCFException
  {
    InputStream is = classInstance.getResourceAsStream(localizeResourceName(pathName,resourceKey,locale));
    if (is == null)
    {
      complainMissingResource("No resource in path '"+pathName+"' named '"+resourceKey+"' found for locale '"+locale.toString()+"'",
        new Exception("Resource not found"),pathName,locale,resourceKey);
      locale = new Locale(locale.getLanguage());
      is = classInstance.getResourceAsStream(localizeResourceName(pathName,resourceKey,locale));
      if (is == null)
      {
        complainMissingResource("No resource in path '"+pathName+"' named '"+resourceKey+"' found for locale '"+locale.toString()+"'",
          new Exception("Resource not found"),pathName,locale,resourceKey);
        locale = Locale.US;
        is = classInstance.getResourceAsStream(localizeResourceName(pathName,resourceKey,locale));
        if (is == null)
        {
          complainMissingResource("No resource in path '"+pathName+"' named '"+resourceKey+"' found for locale '"+locale.toString()+"'",
            new Exception("Resource not found"),pathName,locale,resourceKey);
          locale = new Locale(locale.getLanguage());
          is = classInstance.getResourceAsStream(localizeResourceName(pathName,resourceKey,locale));
          if (is == null)
            throw new ManifoldCFException("No language resource in path '"+pathName+"' named '"+resourceKey+"' found for locale '"+locale.toString()+"'");
        }
      }
    }
    return is;
  }
  
  private static String localizeResourceName(String pathName, String resourceName, Locale locale)
  {
    // Path names temporarily disabled, since they don't work.
    // MHL
    int dotIndex = resourceName.lastIndexOf(".");
    if (dotIndex == -1)
      return /*pathName + "." + */resourceName + "_" + locale.toString();
    return /*pathName + "." + */resourceName.substring(0,dotIndex) + "_" + locale.toString() + resourceName.substring(dotIndex);
  }
  
  /** Obtain a string given a classloader, bundle, locale, message key, and arguments.
  */
  public static String getString(ClassLoader classLoader, String bundleName, Locale locale,
    String messageKey, Object[] args)
  {
    ResourceBundle resources;
    try
    {
      resources = ResourceBundle.getBundle(bundleName, locale, classLoader);
    }
    catch (MissingResourceException e)
    {
      complainMissingBundle("Missing resource bundle '" + bundleName + "' for locale '"+locale.toString()+"': "+e.getMessage()+"; trying "+locale.getLanguage(),
        e,bundleName,locale);
      // Try plain language next
      locale = new Locale(locale.getLanguage());
      try
      {
        resources = ResourceBundle.getBundle(bundleName, locale, classLoader);
      }
      catch (MissingResourceException e2)
      {
        // Use English if we don't have a bundle for the current locale
        complainMissingBundle("Missing resource bundle '" + bundleName + "' for locale '"+locale.toString()+"': "+e2.getMessage()+"; trying en_US",
          e2,bundleName,locale);
        locale = Locale.US;
        try
        {
          resources = ResourceBundle.getBundle(bundleName, locale, classLoader);
        }
        catch (MissingResourceException e3)
        {
          complainMissingBundle("No backup en_US bundle found! "+e3.getMessage(),e3,bundleName,locale);
          locale = new Locale(locale.getLanguage());
          try
          {
            resources = ResourceBundle.getBundle(bundleName, locale, classLoader);
          }
          catch (MissingResourceException e4)
          {
            complainMissingBundle("No backup en bundle found! "+e4.getMessage(),e4,bundleName,locale);
            return messageKey;
          }
        }
      }
    }
    
    String message;
    try
    {
      message = resources.getString(messageKey);
    }
    catch (MissingResourceException e)
    {
      complainMissingMessage("Missing resource '" + messageKey + "' in bundle '" + bundleName + "' for locale '"+locale.toString()+"'",
        e,bundleName,locale,messageKey);
      message = messageKey;
    }

    // Format the message
    String formatMessage;
    if (args != null)
    {
      formatMessage = MessageFormat.format(message, args);
    }
    else
    {
      formatMessage = message;
    }
    return formatMessage;
  }

  protected static void complainMissingBundle(String errorMessage, Throwable exception, String bundleName, Locale locale)
  {
    String localeName = locale.toString();
    BundleKey bk = new BundleKey(bundleName,localeName);
    synchronized (bundleSet)
    {
      if (bundleSet.contains(bk))
        return;
      bundleSet.add(bk);
    }
    logError(errorMessage,exception);
  }
  
  protected static void complainMissingMessage(String errorMessage, Throwable exception, String bundleName, Locale locale, String messageKey)
  {
    String localeName = locale.toString();
    MessageKey bk = new MessageKey(bundleName,localeName,messageKey);
    synchronized (messageSet)
    {
      if (messageSet.contains(bk))
        return;
      messageSet.add(bk);
    }
    logError(errorMessage,exception);
  }

  protected static void complainMissingResource(String errorMessage, Throwable exception, String pathName, Locale locale, String resourceKey)
  {
    String localeName = locale.toString();
    ResourceKey bk = new ResourceKey(pathName,localeName,resourceKey);
    synchronized (resourceSet)
    {
      if (resourceSet.contains(bk))
        return;
      resourceSet.add(bk);
    }
    logError(errorMessage,exception);
  }

  protected static void logError(String errorMessage, Throwable exception)
  {
    if (Logging.misc == null)
    {
      System.err.println(errorMessage);
      exception.printStackTrace(System.err);
    }
    else
      Logging.misc.error(errorMessage,exception);
  }

  /** Class to help keep track of the missing resource bundles we've already complained about,
  * so we don't fill up the standard out log with repetitive stuff. */
  protected static class BundleKey
  {
    protected String bundleName;
    protected String localeName;
    
    public BundleKey(String bundleName, String localeName)
    {
      this.bundleName = bundleName;
      this.localeName = localeName;
    }
    
    public int hashCode()
    {
      return bundleName.hashCode() + localeName.hashCode();
    }
    
    public boolean equals(Object o)
    {
      if (!(o instanceof BundleKey))
        return false;
      BundleKey b = (BundleKey)o;
      return b.bundleName.equals(bundleName) && b.localeName.equals(localeName);
    }
  }

  /** Class to help keep track of the missing messages we've already complained about,
  * so we don't fill up the standard out log with repetitive stuff. */
  protected static class MessageKey
  {
    protected String bundleName;
    protected String localeName;
    protected String messageKey;
    
    public MessageKey(String bundleName, String localeName, String messageKey)
    {
      this.bundleName = bundleName;
      this.localeName = localeName;
      this.messageKey = messageKey;
    }
    
    public int hashCode()
    {
      return bundleName.hashCode() + localeName.hashCode() + messageKey.hashCode();
    }
    
    public boolean equals(Object o)
    {
      if (!(o instanceof MessageKey))
        return false;
      MessageKey b = (MessageKey)o;
      return b.bundleName.equals(bundleName) && b.localeName.equals(localeName) && b.messageKey.equals(messageKey);
    }
  }

  /** Class to help keep track of the missing resources we've already complained about,
  * so we don't fill up the standard out log with repetitive stuff. */
  protected static class ResourceKey
  {
    protected String pathName;
    protected String localeName;
    protected String resourceKey;
    
    public ResourceKey(String pathName, String localeName, String resourceKey)
    {
      this.pathName = pathName;
      this.localeName = localeName;
      this.resourceKey = resourceKey;
    }
    
    public int hashCode()
    {
      return pathName.hashCode() + localeName.hashCode() + resourceKey.hashCode();
    }
    
    public boolean equals(Object o)
    {
      if (!(o instanceof ResourceKey))
        return false;
      ResourceKey b = (ResourceKey)o;
      return b.pathName.equals(pathName) && b.localeName.equals(localeName) && b.resourceKey.equals(resourceKey);
    }
  }

}
