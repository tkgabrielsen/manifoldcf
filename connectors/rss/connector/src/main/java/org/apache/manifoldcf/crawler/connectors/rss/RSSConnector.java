/* $Id: RSSConnector.java 994959 2010-09-08 10:04:42Z kwright $ */

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
package org.apache.manifoldcf.crawler.connectors.rss;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.crawler.interfaces.*;
import org.apache.manifoldcf.crawler.system.Logging;
import org.apache.manifoldcf.crawler.system.ManifoldCF;

import org.xml.sax.Attributes;

import org.apache.manifoldcf.core.common.XMLDoc;
import org.apache.manifoldcf.agents.common.XMLStream;
import org.apache.manifoldcf.agents.common.XMLContext;
import org.apache.manifoldcf.agents.common.XMLStringContext;
import org.apache.manifoldcf.agents.common.XMLFileContext;

import java.io.*;
import java.util.*;
import java.net.*;
import java.util.regex.*;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.params.*;

/** This is the RSS implementation of the IRepositoryConnector interface.
* This connector basically looks at an RSS document in order to seed the
* document queue.  The document is always fetched from the same URL (it's
* specified in the configuration parameters).  The documents subsequently
* crawled are not scraped for additional links; only the primary document is
* ingested.  On the other hand, redirections ARE honored, so that various
* sites that use this trick can be supported (e.g. the BBC)
*
*/
public class RSSConnector extends org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector
{
  public static final String _rcsid = "@(#)$Id: RSSConnector.java 994959 2010-09-08 10:04:42Z kwright $";


  /** Robots usage parameter */
  public static final String robotsUsageParameter = "Robots usage";
  /** Email parameter */
  public static final String emailParameter = "Email address";
  /** Max kilobytes per second per server */
  public static final String bandwidthParameter = "KB per second";
  /** Max simultaneous open connections per server */
  public static final String maxOpenParameter = "Max server connections";
  /** Max fetches per minute per server */
  public static final String maxFetchesParameter = "Max fetches per minute";
  /** The throttle group name */
  public static final String throttleGroupParameter = "Throttle group";
  /** Proxy host name */
  public static final String proxyHostParameter = "Proxy host";
  /** Proxy port */
  public static final String proxyPortParameter = "Proxy port";
  /** Proxy auth domain */
  public static final String proxyAuthDomainParameter = "Proxy authentication domain";
  /** Proxy auth username */
  public static final String proxyAuthUsernameParameter = "Proxy authentication user name";
  /** Proxy auth password */
  public static final String proxyAuthPasswordParameter = "Proxy authentication password";

  // Usage flag values
  protected static final int ROBOTS_NONE = 0;
  protected static final int ROBOTS_DATA = 1;
  protected static final int ROBOTS_ALL = 2;

  /** Dechromed content mode - none */
  public static final int DECHROMED_NONE = 0;
  /** Dechromed content mode - description field */
  public static final int DECHROMED_DESCRIPTION = 1;
  /** Dechromed content mode - content field */
  public static final int DECHROMED_CONTENT = 2;

  /** Chromed suppression mode - use chromed content */
  public static final int CHROMED_USE = 0;
  /** Chromed suppression mode - skip all chromed content */
  public static final int CHROMED_SKIP = 1;

  /** Robots usage flag */
  protected int robotsUsage = ROBOTS_ALL;

  /** The user-agent for this connector instance */
  protected String userAgent = null;
  /** The email address for this connector instance */
  protected String from = null;
  /** The minimum milliseconds between fetches */
  protected long minimumMillisecondsPerFetchPerServer = -1L;
  /** The maximum open connections */
  protected int maxOpenConnectionsPerServer = 0;
  /** The minimum milliseconds between bytes */
  protected double minimumMillisecondsPerBytePerServer = 0.0;
  /** The throttle group name */
  protected String throttleGroupName = null;
  /** The proxy host */
  protected String proxyHost = null;
  /** The proxy port */
  protected int proxyPort = -1;
  /** Proxy auth domain */
  protected String proxyAuthDomain = null;
  /** Proxy auth username */
  protected String proxyAuthUsername = null;
  /** Proxy auth password */
  protected String proxyAuthPassword = null;

  /** The throttled fetcher used by this instance */
  protected ThrottledFetcher fetcher = null;
  /** The robots object used by this instance */
  protected Robots robots = null;

  /** Storage for fetcher objects */
  protected static Map fetcherMap = new HashMap();
  /** Storage for robots objects */
  protected static Map robotsMap = new HashMap();

  /** Flag indicating whether session data is initialized */
  protected boolean isInitialized = false;

  // A couple of very important points.
  // The canonical document identifier is simply a URL.
  // Versions of the document are calculated using a checksum technique

  protected static DataCache cache = new DataCache();


  protected static final Map understoodProtocols = new HashMap();
  static
  {
    understoodProtocols.put("http","http");
    understoodProtocols.put("https","https");
  }

  // Activity types
  public final static String ACTIVITY_FETCH = "fetch";
  public final static String ACTIVITY_ROBOTSPARSE = "robots parse";

  /** Deny access token for default authority */
  private final static String defaultAuthorityDenyToken = "DEAD_AUTHORITY";

  /** Constructor.
  */
  public RSSConnector()
  {
  }

  /** Establish a session */
  protected void getSession()
    throws ManifoldCFException
  {
    if (!isInitialized)
    {
      String x;

      String emailAddress = params.getParameter(emailParameter);
      if (emailAddress == null)
        throw new ManifoldCFException("Missing email address");
      userAgent = "Mozilla/5.0 (ApacheManifoldCFRSSFeedReader; "+((emailAddress==null)?"":emailAddress)+")";
      from = emailAddress;

      String robotsUsageString = params.getParameter(robotsUsageParameter);
      robotsUsage = ROBOTS_ALL;
      if (robotsUsageString == null || robotsUsageString.length() == 0 || robotsUsageString.equals("all"))
        robotsUsage = ROBOTS_ALL;
      else if (robotsUsageString.equals("none"))
        robotsUsage = ROBOTS_NONE;
      else if (robotsUsageString.equals("data"))
        robotsUsage = ROBOTS_DATA;

      proxyHost = params.getParameter(proxyHostParameter);
      String proxyPortString = params.getParameter(proxyPortParameter);
      proxyAuthDomain = params.getParameter(proxyAuthDomainParameter);
      proxyAuthUsername = params.getParameter(proxyAuthUsernameParameter);
      proxyAuthPassword = params.getObfuscatedParameter(proxyAuthPasswordParameter);

      proxyPort = -1;
      if (proxyPortString != null && proxyPortString.length() > 0)
      {
        try
        {
          proxyPort = Integer.parseInt(proxyPortString);
        }
        catch (NumberFormatException e)
        {
          throw new ManifoldCFException(e.getMessage(),e);
        }
      }

      // Read throttling configuration parameters
      minimumMillisecondsPerBytePerServer = 0.0;
      maxOpenConnectionsPerServer = 10;
      minimumMillisecondsPerFetchPerServer = 0L;

      x = params.getParameter(bandwidthParameter);
      if (x != null && x.length() > 0)
      {
        try
        {
          int maxKBytesPerSecondPerServer = Integer.parseInt(x);
          if (maxKBytesPerSecondPerServer > 0)
            minimumMillisecondsPerBytePerServer = ((double)1.0)/(double)maxKBytesPerSecondPerServer;
        }
        catch (NumberFormatException e)
        {
          throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
        }
      }

      x = params.getParameter(maxOpenParameter);
      if (x != null && x.length() > 0)
      {
        try
        {
          maxOpenConnectionsPerServer = Integer.parseInt(x);
        }
        catch (NumberFormatException e)
        {
          throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
        }
      }

      x = params.getParameter(maxFetchesParameter);
      if (x != null && x.length() > 0)
      {
        try
        {
          int maxFetches = Integer.parseInt(x);
          if (maxFetches == 0)
            maxFetches = 1;
          minimumMillisecondsPerFetchPerServer = 60000L/((long)maxFetches);
        }
        catch (NumberFormatException e)
        {
          throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
        }

      }

      isInitialized = true;
    }
  }

  /** Return the list of activities that this connector supports (i.e. writes into the log).
  *@return the list.
  */
  @Override
  public String[] getActivitiesList()
  {
    return new String[]{ACTIVITY_FETCH, ACTIVITY_ROBOTSPARSE};
  }

  /** Tell the world what model this connector uses for getDocumentIdentifiers().
  * This must return a model value as specified above.
  *@return the model type value.
  */
  @Override
  public int getConnectorModel()
  {
    // This connector is currently structured that the RSS feeds are the seeds.
    return MODEL_ALL;
  }

  // All methods below this line will ONLY be called if a connect() call succeeded
  // on this instance!

  /** Connect.  The configuration parameters are included.
  *@param configParams are the configuration parameters for this connection.
  * Note well: There are no exceptions allowed from this call, since it is expected to mainly establish connection parameters.
  */
  @Override
  public void connect(ConfigParams configParams)
  {
    super.connect(configParams);

    // Do the necessary bookkeeping around connection counting
    throttleGroupName = params.getParameter(throttleGroupParameter);
    if (throttleGroupName == null)
      throttleGroupName = "";

    fetcher = getFetcher();
    robots = getRobots(fetcher);

    // Let the system know we have a connection.
    fetcher.noteConnectionEstablished();
    robots.noteConnectionEstablished();
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  @Override
  public void poll()
    throws ManifoldCFException
  {
    fetcher.poll();
    robots.poll();
  }

  /** Check status of connection.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    getSession();
    return super.check();
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    isInitialized = false;

    // Let the system know we are freeing the connection
    robots.noteConnectionReleased();
    fetcher.noteConnectionReleased();

    userAgent = null;
    from = null;
    minimumMillisecondsPerFetchPerServer = -1L;
    maxOpenConnectionsPerServer = 0;
    minimumMillisecondsPerBytePerServer = 0.0;
    throttleGroupName = null;
    proxyHost = null;
    proxyPort = -1;
    proxyAuthDomain = null;
    proxyAuthUsername = null;
    proxyAuthPassword = null;

    super.disconnect();
  }


  /** Get the bin name string for a document identifier.  The bin name describes the queue to which the
  * document will be assigned for throttling purposes.  Throttling controls the rate at which items in a
  * given queue are fetched; it does not say anything about the overall fetch rate, which may operate on
  * multiple queues or bins.
  * For example, if you implement a web crawler, a good choice of bin name would be the server name, since
  * that is likely to correspond to a real resource that will need real throttle protection.
  *@param documentIdentifier is the document identifier.
  *@return the bin name.
  */
  @Override
  public String[] getBinNames(String documentIdentifier)
  {
    try
    {
      WebURL uri = new WebURL(documentIdentifier);
      return new String[]{uri.getHost()};
    }
    catch (URISyntaxException e)
    {
      return new String[]{""};
    }

  }

  /** Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
  * are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
  *
  * This method can choose to find repository changes that happen only during the specified time interval.
  * The seeds recorded by this method will be viewed by the framework based on what the
  * getConnectorModel() method returns.
  *
  * It is not a big problem if the connector chooses to create more seeds than are
  * strictly necessary; it is merely a question of overall work required.
  *
  * The times passed to this method may be interpreted for greatest efficiency.  The time ranges
  * any given job uses with this connector will not overlap, but will proceed starting at 0 and going
  * to the "current time", each time the job is run.  For continuous crawling jobs, this method will
  * be called once, when the job starts, and at various periodic intervals as the job executes.
  *
  * When a job's specification is changed, the framework automatically resets the seeding start time to 0.  The
  * seeding start time may also be set to 0 on each job run, depending on the connector model returned by
  * getConnectorModel().
  *
  * Note that it is always ok to send MORE documents rather than less to this method.
  *@param activities is the interface this method should use to perform whatever framework actions are desired.
  *@param spec is a document specification (that comes from the job).
  *@param startTime is the beginning of the time range to consider, inclusive.
  *@param endTime is the end of the time range to consider, exclusive.
  */
  @Override
  public void addSeedDocuments(ISeedingActivity activities, DocumentSpecification spec,
    long startTime, long endTime)
    throws ManifoldCFException, ServiceInterruption
  {
    getSession();

    Filter f = new Filter(spec,true);

    // Go through all the seeds.
    Iterator iter = f.getSeeds();
    while (iter.hasNext())
    {
      String canonicalURL = (String)iter.next();
      activities.addSeedDocument(canonicalURL);
    }
  }

  /** Convert an absolute or relative URL to a document identifier.  This may involve several steps at some point,
  * but right now it does NOT involve converting the host name to a canonical host name.
  * (Doing so would destroy the ability of virtually hosted sites to do the right thing,
  * since the original host name would be lost.)  Thus, we do the conversion to IP address
  * right before we actually fetch the document.
  *@param policies are the canonicalization policies in effect.
  *@param parentIdentifier the identifier of the document in which the raw url was found, or null if none.
  *@param rawURL is the raw, un-normalized and un-canonicalized url.
  *@return the canonical URL (the document identifier), or null if the url was illegal.
  */
  protected static String makeDocumentIdentifier(CanonicalizationPolicies policies, String parentIdentifier, String rawURL)
    throws ManifoldCFException
  {
    try
    {
      // First, find the matching canonicalization policy, if any
      CanonicalizationPolicy p = policies.findMatch(rawURL);

      // Filter out control characters
      StringBuilder sb = new StringBuilder();
      int i = 0;
      while (i < rawURL.length())
      {
        char x = rawURL.charAt(i++);
        // Only 7-bit ascii is allowed in URLs - and that has limits too (no control characters)
        if (x >= ' ' && x < 128)
          sb.append(x);
      }
      rawURL = sb.toString();

      WebURL url;
      if (parentIdentifier != null)
      {
        WebURL parentURL = new WebURL(parentIdentifier);
        url = parentURL.resolve(rawURL);
      }
      else
        url = new WebURL(rawURL);

      String protocol = url.getScheme();
      String host = url.getHost();

      // The new URL better darn well have a host and a protocol, and we only know how to deal with
      // http and https.
      if (protocol == null || host == null)
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it has no protocol or host");
        return null;
      }
      if (understoodProtocols.get(protocol) == null)
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it has an unsupported protocol '"+protocol+"'");
        return null;
      }

      // Canonicalization procedure.
      // The query part of the URL may contain bad parameters (session id's, for instance), or may be ordered in such a
      // way as to prevent an effectively identical URL from being matched.  The anchor part of the URL should also be stripped.
      // This code performs both of these activities in a simple way; rewrites of various pieces may get more involved if we add
      // the ability to perform mappings using criteria specified in the UI.  Right now we don't.
      String id = doCanonicalization(p,url);
      if (id == null)
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it could not be canonicalized");
        return null;
      }

      // As a last basic legality check, go through looking for illegal characters.
      i = 0;
      while (i < id.length())
      {
        char x = id.charAt(i++);
        // Only 7-bit ascii is allowed in URLs - and that has limits too (no control characters)
        if (x < ' ' || x > 127)
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it has illegal characters in it");
          return null;
        }
      }

      return id;
    }
    catch (java.net.URISyntaxException e)
    {
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it is badly formed: "+e.getMessage());
      return null;
    }
    catch (java.lang.IllegalArgumentException e)
    {
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because there was an argument error: "+e.getMessage(),e);
      return null;
    }
    catch (java.lang.NullPointerException e)
    {
      // This gets tossed by url.toAsciiString() for reasons I don't understand, but which have to do with a malformed URL.
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: Can't use url '"+rawURL+"' because it is missing fields: "+e.getMessage(),e);
      return null;
    }
  }

  /** Code to canonicalize a URL.  If URL cannot be canonicalized (and is illegal) return null.
  */
  protected static String doCanonicalization(CanonicalizationPolicy p, WebURL url)
    throws ManifoldCFException, java.net.URISyntaxException
  {
    // Note well: The java.net.URI class mistreats the query part of the URI, near as I can tell, in the following ways:
    // (1) It decodes the whole thing without regards to the argument interpretation, so the escaped ampersands etc in the arguments are converted
    //     to non-escaped ones (ugh).  This is why I changed the code below to parse the RAW query string and decode it myself.
    // (2) On reassembly of the query string, the class does not properly escape ":", "/", or a bunch of other characters the class description *says*
    //     it will escape.  This means it creates URI's that are illegal according to RFC 2396 - although it is true that RFC 2396 also contains
    //     apparent errors.
    //
    // I've therefore opted to deal with this problem by doing much of the query string processing myself - including its final reassembly into the
    // URI at the end of the processing.
    //

    // To make the url be canonical, we need to strip off everything after the #.  We also need to order the arguments in a canonical
    // way, and remove session identifiers of the types we know about.
    String queryString = url.getRawQuery();
    if (queryString != null)
    {
      // Rewrite the query string.  To do this, we first parse it (by looking for ampersands and equal signs), and then
      // we ditch any keys that we really don't want (session identifiers particularly).  Finally, we go through the
      // keys in sorted order and reassemble the query, making sure that any arguments that have the same name
      // appear in the same order.

      // I don't use the 'split' operation because I think it's a lot more oomph (and performance loss) than is needed
      // for this simple parsing task.

      // When reordering a url, the following is done:
      // (1) The individual order of all arguments with the same name is preserved
      // (2) The arguments themselves appear in sorted order, minus any arguments that should be removed because they
      //      are interpreted to be session arguments.
      //
      // When a url is NOT reordered, the following is done:
      // (1) Each argument is examined IN TURN.
      // (2) If the argument is a session argument and should be excluded, it is simply skipped.

      // Canonicalization note: Broadvision
      //
      // The format of Broadvision's urls is as follows:
      // http://blah/path/path?arg|arg|arg|BVSession@@@@=xxxx&more stuff
      // The session identifier is the BVSession@@@@.  In theory I could strip this away, but I've found that
      // most Broadvision sites require session even for basic navigation!

      if (p == null || p.canReorder())
      {
        // Reorder the arguments.
        HashMap argumentMap = new HashMap();
        int index = 0;
        while (index < queryString.length())
        {
          int newIndex = queryString.indexOf("&",index);
          if (newIndex == -1)
            newIndex = queryString.length();
          String argument = queryString.substring(index,newIndex);
          int valueIndex = argument.indexOf("=");
          String key;
          if (valueIndex == -1)
            key = argument;
          else
            key = argument.substring(0,valueIndex);

          // If this is a disallowed argument, simply don't include it in the final map.
          boolean includeArgument = true;
          if ((p == null || p.canRemovePhpSession()) && key.equals("PHPSESSID"))
            includeArgument = false;
          if ((p == null || p.canRemoveBvSession()) && key.indexOf("BVSession@@@@") != -1)
            includeArgument = false;

          if (includeArgument)
          {
            ArrayList list = (ArrayList)argumentMap.get(key);
            if (list == null)
            {
              list = new ArrayList();
              argumentMap.put(key,list);
            }
            list.add(argument);
          }

          if (newIndex < queryString.length())
            index = newIndex + 1;
          else
            index = newIndex;
        }

        // Reassemble query string in sorted order
        String[] sortArray = new String[argumentMap.size()];
        int i = 0;
        Iterator iter = argumentMap.keySet().iterator();
        while (iter.hasNext())
        {
          sortArray[i++] = (String)iter.next();
        }
        java.util.Arrays.sort(sortArray);

        StringBuilder newString = new StringBuilder();
        boolean isFirst = true;
        i = 0;
        while (i < sortArray.length)
        {
          String key = sortArray[i++];
          ArrayList list = (ArrayList)argumentMap.get(key);
          int j = 0;
          while (j < list.size())
          {
            if (isFirst == false)
            {
              newString.append("&");
            }
            else
              isFirst = false;
            newString.append((String)list.get(j++));
          }
        }
        queryString = newString.toString();
      }
      else
      {
        // Do not reorder!
        StringBuilder newString = new StringBuilder();
        int index = 0;
        boolean isFirst = true;
        while (index < queryString.length())
        {
          int newIndex = queryString.indexOf("&",index);
          if (newIndex == -1)
            newIndex = queryString.length();
          String argument = queryString.substring(index,newIndex);
          int valueIndex = argument.indexOf("=");
          String key;
          if (valueIndex == -1)
            key = argument;
          else
            key = argument.substring(0,valueIndex);

          // If this is a disallowed argument, simply don't include it in the final query.
          boolean includeArgument = true;
          if ((p == null || p.canRemovePhpSession()) && key.equals("PHPSESSID"))
            includeArgument = false;
          if ((p == null || p.canRemoveBvSession()) && key.indexOf("BVSession@@@@") != -1)
            includeArgument = false;

          if (includeArgument)
          {
            if (!isFirst)
              newString.append("&");
            else
              isFirst = false;
            newString.append(argument);
          }

          if (newIndex < queryString.length())
            index = newIndex + 1;
          else
            index = newIndex;
        }
        queryString = newString.toString();
      }
    }

    // Now, rewrite path to get rid of jsessionid etc.
    String pathString = url.getPath();
    if (pathString != null)
    {
      int index = pathString.indexOf(";jsessionid=");
      if ((p == null || p.canRemoveJavaSession()) && index != -1)
      {
        // There's a ";jsessionid="
        // Strip the java session id
        pathString = pathString.substring(0,index);
      }
      if ((p == null || p.canRemoveAspSession()) && pathString.startsWith("/s("))
      {
        // It's asp.net
        index = pathString.indexOf(")");
        if (index != -1)
          pathString = pathString.substring(index+1);
      }

    }

    // Put it back into the URL without the ref, and with the modified query and path parts.
    url = new WebURL(url.getScheme(),url.getHost(),url.getPort(),pathString,queryString);
    String rval = url.toASCIIString();
    return rval;
  }



  /** Get document versions given an array of document identifiers.
  * This method is called for EVERY document that is considered. It is
  * therefore important to perform as little work as possible here.
  *@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
  *@param oldVersions is the corresponding array of version strings that have been saved for the document identifiers.
  *   A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
  *   had an empty version string.
  *@param activities is the interface this method should use to perform whatever framework actions are desired.
  *@param spec is the current document specification for the current job.  If there is a dependency on this
  * specification, then the version string should include the pertinent data, so that reingestion will occur
  * when the specification changes.  This is primarily useful for metadata.
  *@param jobType is an integer describing how the job is being run, whether continuous or once-only.
  *@param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
  *@return the corresponding version strings, with null in the places where the document no longer exists.
  * Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
  * will always be processed.
  */
  @Override
  public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
    DocumentSpecification spec, int jobType, boolean usesDefaultAuthority)
    throws ManifoldCFException, ServiceInterruption
  {
    getSession();

    // The connection limit is designed to permit this connector to coexist with potentially other connectors, such as the web connector.
    // There is currently no good way to enforce connection limits across all installed connectors - this will require considerably more
    // thought to set up properly.
    int connectionLimit = 200;

    if (Logging.connectors.isDebugEnabled())
      Logging.connectors.debug("RSS: In getDocumentVersions for "+Integer.toString(documentIdentifiers.length)+" documents");

    Filter f = new Filter(spec,false);

    String[] acls = f.getAcls();
    // Sort it,
    java.util.Arrays.sort(acls);

    // Build a map of the metadata names and values from the spec
    ArrayList namesAndValues = f.getMetadata();
    // Create an array of name/value fixedlists
    String[] metadata = new String[namesAndValues.size()];
    int k = 0;
    String[] fixedListStrings = new String[2];
    while (k < metadata.length)
    {
      NameValue nv = (NameValue)namesAndValues.get(k);
      String name = nv.getName();
      String value = nv.getValue();
      fixedListStrings[0] = name;
      fixedListStrings[1] = value;
      StringBuilder newsb = new StringBuilder();
      packFixedList(newsb,fixedListStrings,'=');
      metadata[k++] = newsb.toString();
    }
    java.util.Arrays.sort(metadata);

    Logging.connectors.debug("RSS: Done setting up metadata version strings");

    // NOTE: There are two kinds of documents in here; documents that are RSS feeds (that presumably have a content-type
    // of text/xml), and documents that need to be indexed.
    //
    // For the latter, the metadata etc is part of the version string.  For the former, the only thing that is part of the version string is the
    // document's checksum.
    //
    // The need to exclude documents from fetch based on whether they match an expression causes some difficulties, because we really
    // DON'T want this to apply to the feeds themselves.  Since the distinguishing characteristic of a feed is that it is in the seed list,
    // and that its content-type is text/xml, we could use either of these characteristics to treat feeds differently from
    // fetchable urls.  But the latter approach requires a fetch, which is forbidden.  So - the spec will be used to characterize the url.
    // However, the spec might change, and the url might be dropped from the list - and then what??
    //
    // The final solution is to simply not queue what cannot be mapped.

    int feedTimeout = f.getFeedTimeoutValue();

    // A preliminary fetch must be done in either case, because otherwise we cannot detect a deletion.
    // But, since we don't want to fetch twice, write these urls to temporary files.  The
    // files will be cleaned up as soon as possible.

    String[] rval = new String[documentIdentifiers.length];

    // HttpClient client = new HttpClient(httpConMan.getManager());

    // The document specification has already been used to trim out documents that are not
    // allowed from appearing in the queue.  So, even that has already been done.
    int i = 0;
    while (i < documentIdentifiers.length)
    {
      // If it is in this list, we presume that it has been vetted against the map etc., so we don't do that again.  We just fetch it.
      // And, if the content type is xml, we calculate the version as if it is a feed rather than a document.

      // Get the url
      String urlValue = documentIdentifiers[i];

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: Getting version string for '"+urlValue+"'");

      // If there's a carrydown "data" value for this url, we use that value rather than actually fetching the document.  This also means we don't need to
      // do a robots check, because we aren't actually crawling anything.  So, ALWAYS do this first...
      CharacterInput[] dechromedData = activities.retrieveParentDataAsFiles(urlValue,"data");
      try
      {
        if (dechromedData.length > 0)
        {
          // Data already available.  The fetch cycle can be entirely avoided, as can the robots check.
          String ingestURL = f.mapDocumentURL(urlValue);
          if (ingestURL != null)
          {
            // Open up an input stream corresponding to the carrydown data.  The stream will be encoded as utf-8.
            try
            {
              InputStream is = dechromedData[0].getUtf8Stream();
              try
              {
                StringBuilder sb = new StringBuilder();
                long checkSum = cache.addData(activities,urlValue,"text/html",is);
                // Grab what we need from the passed-down data for the document.  These will all become part
                // of the version string.
                String[] pubDates = activities.retrieveParentData(urlValue,"pubdate");
                String[] sources = activities.retrieveParentData(urlValue,"source");
                String[] titles = activities.retrieveParentData(urlValue,"title");
                String[] categories = activities.retrieveParentData(urlValue,"category");
                String[] descriptions = activities.retrieveParentData(urlValue,"description");
                java.util.Arrays.sort(pubDates);
                java.util.Arrays.sort(sources);
                java.util.Arrays.sort(titles);
                java.util.Arrays.sort(categories);
                java.util.Arrays.sort(descriptions);

                if (sources.length == 0)
                {
                  if (Logging.connectors.isDebugEnabled())
                    Logging.connectors.debug("RSS: Warning; URL '"+ingestURL+"' doesn't seem to have any RSS feed source!");
                }

                sb.append('+');
                packList(sb,acls,'+');
                if (acls.length > 0)
                {
                  sb.append('+');
                  pack(sb,defaultAuthorityDenyToken,'+');
                }
                else
                  sb.append('-');
                // Now, do the metadata
                packList(sb,metadata,'+');
                // The ingestion URL
                pack(sb,ingestURL,'+');
                // The pub dates
                packList(sb,pubDates,'+');
                // The titles
                packList(sb,titles,'+');
                // The sources
                packList(sb,sources,'+');
                // The categories
                packList(sb,categories,'+');
                // The descriptions
                packList(sb,descriptions,'+');

                // Do the checksum part, which does not need to be parseable.
                sb.append(new Long(checkSum).toString());

                rval[i] = sb.toString();
              }
              finally
              {
                is.close();
              }
            }
            catch (UnsupportedEncodingException e)
            {
              throw new ManifoldCFException("Unsupported encoding: "+e.getMessage(),e);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception reading data from string: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception reading data from string: "+e.getMessage(),e);
            }
          }
          else
          {
            // Document a seed or unmappable; just skip
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: Skipping carry-down document '"+urlValue+"' because it is unmappable or is a seed.");
          }
        }
        else
        {
          // Get the old version string
          String oldVersionString = oldVersions[i];

          // Unpack the old version as much as possible.
          // We are interested in what the ETag and Last-Modified headers were last time.
          String lastETagValue = null;
          String lastModifiedValue = null;
          // Note well: Non-continuous jobs cannot use etag because the rss document MUST be fetched each time for such jobs,
          // or the documents it points at would get deleted.
          //
          // NOTE: I disabled this code because we really need the feed's TTL value in order to reschedule properly.  I can't get the
          // TTL value without refetching the document - therefore ETag and Last-Modified cannot be used :-(
          if (false && jobType == JOBMODE_CONTINUOUS && oldVersionString != null && oldVersionString.startsWith("-"))
          {
            // It's a feed, so the last etag and last-modified fields should be encoded in this version string.
            StringBuilder lastETagBuffer = new StringBuilder();
            int unpackPos = unpack(lastETagBuffer,oldVersionString,1,'+');
            StringBuilder lastModifiedBuffer = new StringBuilder();
            unpackPos = unpack(lastModifiedBuffer,oldVersionString,unpackPos,'+');
            if (lastETagBuffer.length() > 0)
              lastETagValue = lastETagBuffer.toString();
            if (lastModifiedBuffer.length() > 0)
              lastModifiedValue = lastModifiedBuffer.toString();
          }

          if (Logging.connectors.isDebugEnabled() && (lastETagValue != null || lastModifiedValue != null))
            Logging.connectors.debug("RSS: Document '"+urlValue+"' was found to have a previous ETag value of '"+((lastETagValue==null)?"null":lastETagValue)+
            "' and a previous Last-Modified value of '"+((lastModifiedValue==null)?"null":lastModifiedValue)+"'");

          // Robots check.  First, we need to separate the url into its components
          try
          {
            URL url = new URL(urlValue);
            String protocol = url.getProtocol();
            int port = url.getPort();
            String hostName = url.getHost();
            String pathPart = url.getFile();

            // Check with robots to see if it's allowed
            if (robotsUsage >= ROBOTS_DATA && !robots.isFetchAllowed(protocol,port,hostName,url.getPath(),
              userAgent,from,
              minimumMillisecondsPerBytePerServer,
              maxOpenConnectionsPerServer,
              minimumMillisecondsPerFetchPerServer,
              proxyHost, proxyPort, proxyAuthDomain, proxyAuthUsername, proxyAuthPassword,
              activities, connectionLimit))
            {
              activities.recordActivity(null,ACTIVITY_FETCH,
                null,urlValue,Integer.toString(-2),"Robots exclusion",null);

              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: Skipping url '"+urlValue+"' because robots.txt says to");
              rval[i] = null;
            }
            else
            {

              // Now, use the fetcher, and get the file.
              IThrottledConnection connection = fetcher.createConnection(hostName,
                minimumMillisecondsPerBytePerServer,
                maxOpenConnectionsPerServer,
                minimumMillisecondsPerFetchPerServer,
                connectionLimit,
                feedTimeout);
              try
              {
                // Begin the fetch
                connection.beginFetch("Data");
                try
                {
                  // Execute the request.
                  // Use the connect timeout from the document specification!
                  int status = connection.executeFetch(protocol,port,pathPart,userAgent,from,proxyHost,proxyPort,
                    proxyAuthDomain,proxyAuthUsername,proxyAuthPassword,
                    lastETagValue,lastModifiedValue);
                  switch (status)
                  {
                  case IThrottledConnection.STATUS_NOCHANGE:
                    rval[i] = oldVersionString;
                    break;
                  case IThrottledConnection.STATUS_OK:
                    try
                    {
                      if (Logging.connectors.isDebugEnabled())
                        Logging.connectors.debug("RSS: Successfully fetched "+urlValue);
                      // Document successfully fetched!
                      // If its content is xml, presume it's a feed...
                      String contentType = connection.getResponseHeader("Content-Type");
                      // Some sites have multiple content types.  We just look at the LAST one in that case.
                      if (contentType != null)
                      {
                        String[] contentTypes = contentType.split(",");
                        if (contentTypes.length > 0)
                          contentType = contentTypes[contentTypes.length-1].trim();
                        else
                          contentType = null;
                      }
                      boolean isXML = (contentType != null &&
                        (contentType.startsWith("text/xml") ||
                        contentType.startsWith("application/rss+xml") ||
                        contentType.startsWith("application/xml") ||
                        contentType.startsWith("application/atom+xml") ||
                        contentType.startsWith("application/xhtml+xml") ||
                        contentType.startsWith("text/XML") ||
                        contentType.startsWith("application/rdf+xml") ||
                        contentType.startsWith("text/application") ||
                        contentType.startsWith("XML") ));
                      String ingestURL = null;
                      if (!isXML)
                      {
                        // If the chromed content mode is set to "skip", and we got here, it means
                        // we should not include the content.
                        if (f.getChromedContentMode() == CHROMED_SKIP)
                        {
                          if (Logging.connectors.isDebugEnabled())
                            Logging.connectors.debug("RSS: Removing url '"+urlValue+"' because it no longer has dechromed content available");
                          rval[i] = null;
                          break;
                        }

                        // Decide whether to exclude this document based on what we see here.
                        // Basically, we want to get rid of everything that we don't know what
                        // to do with in the ingestion system.
                        if (!isContentInteresting(activities,contentType))
                        {
                          if (Logging.connectors.isDebugEnabled())
                            Logging.connectors.debug("RSS: Removing url '"+urlValue+"' because it had the wrong content type: "+((contentType==null)?"null":"'"+contentType+"'"));
                          rval[i] = null;
                          break;
                        }

                        ingestURL = f.mapDocumentURL(urlValue);
                      }
                      else
                      {
                        if (Logging.connectors.isDebugEnabled())
                          Logging.connectors.debug("RSS: The url '"+urlValue+"' is a feed");

                        if (!f.isSeed(urlValue))
                        {
                          // Remove the feed from consideration, since it has left the list of seeds
                          if (Logging.connectors.isDebugEnabled())
                            Logging.connectors.debug("RSS: Removing feed url '"+urlValue+"' because it is not a seed.");
                          rval[i] = null;
                          break;
                        }
                      }

                      InputStream is = connection.getResponseBodyStream();
                      try
                      {
                        long checkSum = cache.addData(activities,urlValue,contentType,is);
                        StringBuilder sb = new StringBuilder();
                        if (ingestURL != null)
                        {
                          // We think it is ingestable.  The version string accordingly starts with a "+".

                          // Grab what we need from the passed-down data for the document.  These will all become part
                          // of the version string.
                          String[] pubDates = activities.retrieveParentData(urlValue,"pubdate");
                          String[] sources = activities.retrieveParentData(urlValue,"source");
                          String[] titles = activities.retrieveParentData(urlValue,"title");
                          String[] categories = activities.retrieveParentData(urlValue,"category");
                          String[] descriptions = activities.retrieveParentData(urlValue,"description");
                          java.util.Arrays.sort(pubDates);
                          java.util.Arrays.sort(sources);
                          java.util.Arrays.sort(titles);
                          java.util.Arrays.sort(categories);
                          java.util.Arrays.sort(descriptions);

                          if (sources.length == 0)
                          {
                            if (Logging.connectors.isDebugEnabled())
                              Logging.connectors.debug("RSS: Warning; URL '"+ingestURL+"' doesn't seem to have any RSS feed source!");
                          }

                          sb.append('+');
                          packList(sb,acls,'+');
                          if (acls.length > 0)
                          {
                            sb.append('+');
                            pack(sb,defaultAuthorityDenyToken,'+');
                          }
                          else
                            sb.append('-');
                          // Now, do the metadata
                          packList(sb,metadata,'+');
                          // The ingestion URL
                          pack(sb,ingestURL,'+');
                          // The pub dates
                          packList(sb,pubDates,'+');
                          // The titles
                          packList(sb,titles,'+');
                          // The sources
                          packList(sb,sources,'+');
                          // The categories
                          packList(sb,categories,'+');
                          // The descriptions
                          packList(sb,descriptions,'+');

                        }
                        else
                        {
                          sb.append('-');
                          String etag = connection.getResponseHeader("ETag");
                          if (etag == null)
                            pack(sb,"",'+');
                          else
                            pack(sb,etag,'+');
                          String lastModified = connection.getResponseHeader("Last-Modified");
                          if (lastModified == null)
                            pack(sb,"",'+');
                          else
                            pack(sb,lastModified,'+');

                        }

                        // Do the checksum part, which does not need to be parseable.
                        sb.append(new Long(checkSum).toString());

                        rval[i] = sb.toString();
                      }
                      finally
                      {
                        is.close();
                      }
                    }
                    catch (java.net.SocketTimeoutException e)
                    {
                      Logging.connectors.warn("RSS: Socket timeout exception fetching document contents '"+urlValue+"' - skipping: "+e.getMessage(), e);
                      rval[i] = null;
                    }
                    catch (org.apache.commons.httpclient.ConnectTimeoutException e)
                    {
                      Logging.connectors.warn("RSS: Connecto timeout exception fetching document contents '"+urlValue+"' - skipping: "+e.getMessage(), e);
                      rval[i] = null;
                    }
                    catch (InterruptedIOException e)
                    {
                      throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
                    }
                    catch (IOException e)
                    {
                      Logging.connectors.warn("RSS: IO exception fetching document contents '"+urlValue+"' - skipping: "+e.getMessage(), e);
                      rval[i] = null;
                    }

                    break;

                  case IThrottledConnection.STATUS_SITEERROR:
                  case IThrottledConnection.STATUS_PAGEERROR:
                  default:
                    // Record an *empty* version.
                    // This signals the processDocuments() method that we really don't want to ingest this document, but we also don't
                    // want to blow the document out of the queue, since then we'd wind up perhaps fetching it multiple times.
                    rval[i] = "";
                    break;
                  }
                }
                finally
                {
                  connection.doneFetch(activities);
                }
              }
              finally
              {
                connection.close();
              }
            }
          }
          catch (MalformedURLException e)
          {
            Logging.connectors.debug("RSS: URL '"+urlValue+"' is malformed; skipping",e);
            rval[i] = null;
          }
        }
      }
      finally
      {
        int j = 0;
        while (j < dechromedData.length)
        {
          CharacterInput ci = (CharacterInput)dechromedData[j++];
          if (ci != null)
            ci.discard();
        }

      }

      i++;
    }

    return rval;
  }

  /** Process a set of documents.
  * This is the method that should cause each document to be fetched, processed, and the results either added
  * to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
  * The document specification allows this class to filter what is done based on the job.
  *@param documentIdentifiers is the set of document identifiers to process.
  *@param activities is the interface this method should use to queue up new document references
  * and ingest documents.
  *@param spec is the document specification.
  *@param scanOnly is an array corresponding to the document identifiers.  It is set to true to indicate when the processing
  * should only find other references, and should not actually call the ingestion methods.
  */
  @Override
  public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
    DocumentSpecification spec, boolean[] scanOnly, int jobType)
    throws ManifoldCFException, ServiceInterruption
  {
    getSession();

    // The version strings contain the ingest url as well as everything we need to determine if this url is a feed or just a document.
    // So, there is no need to reparse the specification.
    Filter f = null;

    String[] fixedList = new String[2];

    int i = 0;
    while (i < documentIdentifiers.length)
    {
      String urlValue = documentIdentifiers[i];
      String version = versions[i];

      if (version.length() == 0)
      {
        // This document had an http response incompatible with its existence on the web.
        i++;
        // Leave document in jobqueue, but do NOT get rid of it, or we will wind up seeing it queued again by
        // somebody else.  We *do* have to signal the document to be removed from the index, however, or it will
        // stick around until the job is deleted.
        activities.deleteDocument(urlValue,version);
        continue;
      }

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: Processing '"+urlValue+"'");

      // The only links we extract come from documents that we think are RSS feeds.
      // When we think that's the case, we attempt to parse it as RSS XML.
      if (version.startsWith("-"))
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Interpreting document '"+urlValue+"' as a feed");

        // We think it is a feed.
        // The version string does not have anything useful in it.
        if (f == null)
          f = new Filter(spec,false);

        // If this is a continuous job, AND scanonly is true, it means that the document was either identical to the
        // previous fetch, or was not fetched at all.  In that case, it may not even be there, and we *certainly* don't
        // want to attempt to process it in any case.
        //

        // NOTE: I re-enabled the scan permanently because we need the TTL value to be set whatever the cost.  If the
        // TTL value is not set, we default to the specified job's feed-rescan time, which is not going to be current enough for some feeds.
        if (true || scanOnly[i] == false || jobType != JOBMODE_CONTINUOUS)
        {
          handleRSSFeedSAX(urlValue,activities,f);
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: Extraction of feed '"+urlValue+"' complete");

          // Record the feed's version string, so we won't refetch unless needed.
          // This functionality is required for the last ETag and Last-Modified fields to be sent to the rss server, and to
          // keep track of the adaptive parameters.
          activities.recordDocument(urlValue,version);
        }
        else
        {
          // The problem here is that we really do need to set the rescan time to something reasonable.
          // But we might not even have read the feed!  So what to do??
          // One answer is to build a connector-specific table that carries the last value of every feed around.
          // Another answer is to change the version code to always read the feed (and the heck with ETag and Last-Modified).
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: Feed '"+urlValue+"' does not appear to differ from previous fetch for a continuous job; not extracting!");

          long currentTime = System.currentTimeMillis();

          Long defaultRescanTime = f.getDefaultRescanTime(currentTime);

          if (defaultRescanTime != null)
          {
            Long minimumTime = f.getMinimumRescanTime(currentTime);
            if (minimumTime != null)
            {
              if (defaultRescanTime.longValue() < minimumTime.longValue())
                defaultRescanTime = minimumTime;
            }
          }

          activities.setDocumentScheduleBounds(urlValue,defaultRescanTime,defaultRescanTime,null,null);

        }
      }
      else if (scanOnly[i] == false && version.startsWith("+"))
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Interpreting '"+urlValue+"' as a document");



        if (isDataIngestable(activities,urlValue))
        {
          // Treat it as an ingestable document.
          // Version *should* start with a "+".
          ArrayList acls = new ArrayList();
          StringBuilder denyAclBuffer = new StringBuilder();
          int startPos = unpackList(acls,version,1,'+');
          if (startPos < version.length() && version.charAt(startPos++) == '+')
          {
            startPos = unpack(denyAclBuffer,version,startPos,'+');
          }
          ArrayList metadata = new ArrayList();
          startPos = unpackList(metadata,version,startPos,'+');
          StringBuilder ingestUrlBuffer = new StringBuilder();
          startPos = unpack(ingestUrlBuffer,version,startPos,'+');
          String ingestURL = ingestUrlBuffer.toString();
          ArrayList pubDates = new ArrayList();
          startPos = unpackList(pubDates,version,startPos,'+');
          ArrayList titles = new ArrayList();
          startPos = unpackList(titles,version,startPos,'+');
          ArrayList sources = new ArrayList();
          startPos = unpackList(sources,version,startPos,'+');
          ArrayList categories = new ArrayList();
          startPos = unpackList(categories,version,startPos,'+');
          ArrayList descriptions = new ArrayList();
          startPos = unpackList(descriptions,version,startPos,'+');

          if (ingestURL.length() > 0)
          {
            long dataSize = cache.getDataLength(urlValue);
            RepositoryDocument rd = new RepositoryDocument();

            // Turn into acls and add into description
            String[] aclArray = new String[acls.size()];
            int j = 0;
            while (j < aclArray.length)
            {
              aclArray[j] = (String)acls.get(j);
              j++;
            }
            rd.setACL(aclArray);

            // Deny acl too
            if (denyAclBuffer.length() > 0)
            {
              String[] denyAclArray = new String[]{denyAclBuffer.toString()};
              rd.setDenyACL(denyAclArray);
            }

            // Grab metadata
            HashMap metaHash = new HashMap();
            int k = 0;
            while (k < metadata.size())
            {
              String metadataItem = (String)metadata.get(k++);
              unpackFixedList(fixedList,metadataItem,0,'=');
              HashMap hashValue = (HashMap)metaHash.get(fixedList[0]);
              if (hashValue == null)
              {
                hashValue = new HashMap();
                metaHash.put(fixedList[0],hashValue);
              }
              hashValue.put(fixedList[1],fixedList[1]);
            }
            Iterator metaIter = metaHash.keySet().iterator();
            while (metaIter.hasNext())
            {
              String key = (String)metaIter.next();
              HashMap metaList = (HashMap)metaHash.get(key);
              String[] values = new String[metaList.size()];
              Iterator iter = metaList.keySet().iterator();
              k = 0;
              while (iter.hasNext())
              {
                values[k] = (String)iter.next();
                k++;
              }
              rd.addField(key,values);
            }

            // Loop through the titles to add those to the metadata
            String[] titleValues = new String[titles.size()];
            k = 0;
            while (k < titleValues.length)
            {
              titleValues[k] = (String)titles.get(k);
              k++;
            }
            if (k > 0)
              rd.addField("title",titleValues);
            
            // Loop through the descriptions to add those to the metadata
            String[] descriptionValues = new String[descriptions.size()];
            k = 0;
            while (k < descriptionValues.length)
            {
              descriptionValues[k] = (String)descriptions.get(k);
              k++;
            }
            if (k > 0)
              rd.addField("summary",descriptionValues);

            // Loop through the sources to add those to the metadata
            String[] sourceValues = new String[sources.size()];
            k = 0;
            while (k < sourceValues.length)
            {
              sourceValues[k] = (String)sources.get(k);
              k++;
            }
            if (k > 0)
              rd.addField("source",sourceValues);

            // Add the categories now
            String[] categoryValues = new String[categories.size()];
            k = 0;
            while (k < categoryValues.length)
            {
              categoryValues[k] = (String)categories.get(k);
              k++;
            }
            if (k > 0)
              rd.addField("category",categoryValues);

            // The pubdates are a ms since epoch value; we want the minimum one for the origination time.
            Long minimumOrigTime = null;
            String[] pubDateValues = new String[pubDates.size()];
            k = 0;
            while (k < pubDates.size())
            {
              String pubDate = (String)pubDates.get(k);
              pubDateValues[k++] = pubDate;
              try
              {
                Long pubDateLong = new Long(pubDate);
                if (minimumOrigTime == null || pubDateLong.longValue() < minimumOrigTime.longValue())
                  minimumOrigTime = pubDateLong;
              }
              catch (NumberFormatException e)
              {
                // Do nothing; the version string seems to not mean anything
              }
            }
            if (k > 0)
              rd.addField("pubdate",pubDateValues);

            if (minimumOrigTime != null)
              activities.setDocumentOriginationTime(urlValue,minimumOrigTime);

            InputStream is = cache.getData(urlValue);
            if (is != null)
            {
              try
              {
                rd.setBinary(is,dataSize);
                activities.ingestDocument(urlValue,version,ingestURL,rd);
              }
              finally
              {
                try
                {
                  is.close();
                }
                catch (java.net.SocketTimeoutException e)
                {
                  throw new ManifoldCFException("IO error closing stream: "+e.getMessage(),e);
                }
                catch (InterruptedIOException e)
                {
                  throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
                }
                catch (IOException e)
                {
                  throw new ManifoldCFException("IO error closing stream: "+e.getMessage(),e);
                }
              }
            }
          }
        }
        else
        {
          activities.deleteDocument(urlValue,version);

          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: Skipping document '"+urlValue+"' because it cannot be indexed");
        }
      }
      else
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Skipping document '"+urlValue+"' because it cannot have interesting links");
      }
      i++;
    }

  }

  /** Free a set of documents.  This method is called for all documents whose versions have been fetched using
  * the getDocumentVersions() method, including those that returned null versions.  It may be used to free resources
  * committed during the getDocumentVersions() method.  It is guaranteed to be called AFTER any calls to
  * processDocuments() for the documents in question.
  *@param documentIdentifiers is the set of document identifiers.
  *@param versions is the corresponding set of version identifiers (individual identifiers may be null).
  */
  @Override
  public void releaseDocumentVersions(String[] documentIdentifiers, String[] versions)
    throws ManifoldCFException
  {
    int i = 0;
    while (i < documentIdentifiers.length)
    {
      String version = versions[i];
      if (version != null)

      {
        String urlValue = documentIdentifiers[i];
        cache.deleteData(urlValue);
      }
      i++;
    }
  }

  // UI support methods.
  //
  // These support methods come in two varieties.  The first bunch is involved in setting up connection configuration information.  The second bunch
  // is involved in presenting and editing document specification information for a job.  The two kinds of methods are accordingly treated differently,
  // in that the first bunch cannot assume that the current connector object is connected, while the second bunch can.  That is why the first bunch
  // receives a thread context argument for all UI methods, while the second bunch does not need one (since it has already been applied via the connect()
  // method, above).
    
  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add(Messages.getString(locale,"RSSConnector.Email"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Robots"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Bandwidth"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Proxy"));
    out.print(
"<script type=\"text/javascript\">\n"+
"<!--\n"+
"function checkConfig()\n"+
"{\n"+
"  if (editconnection.email.value != \"\" && editconnection.email.value.indexOf(\"@\") == -1)\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.NeedAValidEmailAddress")+"\");\n"+
"    editconnection.email.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.bandwidth.value != \"\" && !isInteger(editconnection.bandwidth.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.EnterAValidNumberOrBlankForNoLimit")+"\");\n"+
"    editconnection.bandwidth.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.connections.value == \"\" || !isInteger(editconnection.connections.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.EnterAValidNumberForTheMaxNumberOfOpenConnectionsPerServer")+"\");\n"+
"    editconnection.connections.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.fetches.value != \"\" && !isInteger(editconnection.fetches.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.EnterAValidNumberOrBlankForNoLimit")+"\");\n"+
"    editconnection.fetches.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"function checkConfigForSave()\n"+
"{\n"+
"  if (editconnection.email.value == \"\")\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.EmailAddressRequiredToBeIncludedInAllRequestHeaders")+"\");\n"+
"    SelectTab(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.Email")+"\");\n"+
"    editconnection.email.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"//-->\n"+
"</script>\n"
    );
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    String email = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.emailParameter);
    if (email == null)
      email = "";
    String robotsUsage = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.robotsUsageParameter);
    if (robotsUsage == null)
      robotsUsage = "all";
    String bandwidth = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.bandwidthParameter);
    if (bandwidth == null)
      bandwidth = "64";
    String connections = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.maxOpenParameter);
    if (connections == null)
      connections = "2";
    String fetches = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.maxFetchesParameter);
    if (fetches == null)
      fetches = "12";
    String throttleGroup = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.throttleGroupParameter);
    if (throttleGroup == null)
      throttleGroup = "";
    String proxyHost = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyHostParameter);
    if (proxyHost == null)
      proxyHost = "";
    String proxyPort = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyPortParameter);
    if (proxyPort == null)
      proxyPort = "";
    String proxyAuthDomain = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthDomainParameter);
    if (proxyAuthDomain == null)
      proxyAuthDomain = "";
    String proxyAuthUsername = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthUsernameParameter);
    if (proxyAuthUsername == null)
      proxyAuthUsername = "";
    String proxyAuthPassword = parameters.getObfuscatedParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthPasswordParameter);
    if (proxyAuthPassword == null)
      proxyAuthPassword = "";
      
    // Email tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Email")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.EmailAddressToContactColon") + "</nobr></td><td class=\"value\"><input type=\"text\" size=\"32\" name=\"email\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(email)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"email\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(email)+"\"/>\n"
      );
    }

    // Robots tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Robots")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.RobotsTxtUsageColon") + "</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <select name=\"robotsusage\" size=\"3\">\n"+
"        <option value=\"none\" "+(robotsUsage.equals("none")?"selected=\"selected\"":"")+">" + Messages.getBodyString(locale,"RSSConnector.DontLookAtRobotsTxt") + "</option>\n"+
"        <option value=\"data\" "+(robotsUsage.equals("data")?"selected=\"selected\"":"")+">" + Messages.getBodyString(locale,"RSSConnector.ObeyRobotsTxtForDataFetchesOnly") + "</option>\n"+
"        <option value=\"all\" "+(robotsUsage.equals("all")?"selected=\"selected\"":"")+">" + Messages.getBodyString(locale,"RSSConnector.ObeyRobotsTxtForAllFetches") + "</option>\n"+
"      </select>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"robotsusage\" value=\""+robotsUsage+"\"/>\n"
      );
    }

    // Bandwidth tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Bandwidth")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.MaxKBytesPerSecondPerServerColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"6\" name=\"bandwidth\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(bandwidth)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.MaxConnectionsPerServerColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"4\" name=\"connections\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(connections)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.MaxFetchesPerMinutePerServerColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"4\" name=\"fetches\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(fetches)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ThrottleGroupNameColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"throttlegroup\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(throttleGroup)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"bandwidth\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(bandwidth)+"\"/>\n"+
"<input type=\"hidden\" name=\"connections\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(connections)+"\"/>\n"+
"<input type=\"hidden\" name=\"fetches\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(fetches)+"\"/>\n"+
"<input type=\"hidden\" name=\"throttlegroup\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(throttleGroup)+"\"/>\n"
      );
    }
    
    // Proxy tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Proxy")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ProxyHostColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"40\" name=\"proxyhost\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyHost)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ProxyPortColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"proxyport\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyPort)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ProxyAuthenticationDomainColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"proxyauthdomain\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthDomain)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ProxyAuthenticationUserNameColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"proxyauthusername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthUsername)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ProxyAuthenticationPasswordColon") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"password\" size=\"16\" name=\"proxyauthpassword\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthPassword)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"proxyhost\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyHost)+"\"/>\n"+
"<input type=\"hidden\" name=\"proxyport\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyPort)+"\"/>\n"+
"<input type=\"hidden\" name=\"proxyauthusername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthUsername)+"\"/>\n"+
"<input type=\"hidden\" name=\"proxyauthdomain\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthDomain)+"\"/>\n"+
"<input type=\"hidden\" name=\"proxyauthpassword\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(proxyAuthPassword)+"\"/>\n"
      );
    }
  }
  
  /** Process a configuration post.
  * This method is called at the start of the connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException
  {
    String email = variableContext.getParameter("email");
    if (email != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.emailParameter,email);
    String robotsUsage = variableContext.getParameter("robotsusage");
    if (robotsUsage != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.robotsUsageParameter,robotsUsage);
    String bandwidth = variableContext.getParameter("bandwidth");
    if (bandwidth != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.bandwidthParameter,bandwidth);
    String connections = variableContext.getParameter("connections");
    if (connections != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.maxOpenParameter,connections);
    String fetches = variableContext.getParameter("fetches");
    if (fetches != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.maxFetchesParameter,fetches);
    String throttleGroup = variableContext.getParameter("throttlegroup");
    if (throttleGroup != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.throttleGroupParameter,throttleGroup);
    String proxyHost = variableContext.getParameter("proxyhost");
    if (proxyHost != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyHostParameter,proxyHost);
    String proxyPort = variableContext.getParameter("proxyport");
    if (proxyPort != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyPortParameter,proxyPort);
    String proxyAuthDomain = variableContext.getParameter("proxyauthdomain");
    if (proxyAuthDomain != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthDomainParameter,proxyAuthDomain);
    String proxyAuthUsername = variableContext.getParameter("proxyauthusername");
    if (proxyAuthUsername != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthUsernameParameter,proxyAuthUsername);
    String proxyAuthPassword = variableContext.getParameter("proxyauthpassword");
    if (proxyAuthPassword != null)
      parameters.setObfuscatedParameter(org.apache.manifoldcf.crawler.connectors.rss.RSSConnector.proxyAuthPasswordParameter,proxyAuthPassword);

    return null;
  }
  
  /** View configuration.
  * This method is called in the body section of the connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    out.print(
"<table class=\"displaytable\">\n"+
"  <tr>\n"+
"    <td class=\"description\" colspan=\"1\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.ParametersColon") + "</nobr></td>\n"+
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
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"=********</nobr><br/>\n"
        );
      }
      else if (param.length() >="keystore".length() && param.substring(param.length()-"keystore".length()).equalsIgnoreCase("keystore"))
      {
        IKeystoreManager kmanager = KeystoreManagerFactory.make("",value);
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"=<"+Integer.toString(kmanager.getContents().length)+Messages.getBodyString(locale,"RSSConnector.certificates")+"></nobr><br/>\n"
        );
      }
      else
      {
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"="+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(value)+"</nobr><br/>\n"
        );
      }
    }
    out.print(
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
    );
  }
  
  /** Output the specification header section.
  * This method is called in the head section of a job page which has selected a repository connection of the current type.  Its purpose is to add the required tabs
  * to the list, and to output any javascript methods that might be needed by the job editing HTML.
  *@param out is the output to which any HTML should be sent.
  *@param ds is the current document specification for this job.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale, DocumentSpecification ds, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add(Messages.getString(locale,"RSSConnector.URLs"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Canonicalization"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.URLMappings"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.TimeValues"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Security"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.Metadata"));
    tabsArray.add(Messages.getString(locale,"RSSConnector.DechromedContent"));
    out.print(
"<script type=\"text/javascript\">\n"+
"<!--\n"+
"function SpecOp(n, opValue, anchorvalue)\n"+
"{\n"+
"  eval(\"editjob.\"+n+\".value = \\\"\"+opValue+\"\\\"\");\n"+
"  postFormSetAnchor(anchorvalue);\n"+
"}\n"+
"\n"+
"function AddRegexp(anchorvalue)\n"+
"{\n"+
"  if (editjob.rssmatch.value == \"\")\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.MatchMustHaveARegexpValue")+"\");\n"+
"    editjob.rssmatch.focus();\n"+
"    return;\n"+
"  }\n"+
"\n"+
"  SpecOp(\"rssop\",\"Add\",anchorvalue);\n"+
"}\n"+
"\n"+
"function RemoveRegexp(index, anchorvalue)\n"+
"{\n"+
"  editjob.rssindex.value = index;\n"+
"  SpecOp(\"rssop\",\"Delete\",anchorvalue);\n"+
"}\n"+
"\n"+
"function SpecAddToken(anchorvalue)\n"+
"{\n"+
"  if (editjob.spectoken.value == \"\")\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.TypeInAnAccessToken")+"\");\n"+
"    editjob.spectoken.focus();\n"+
"    return;\n"+
"  }\n"+
"  SpecOp(\"accessop\",\"Add\",anchorvalue);\n"+
"}\n"+
"\n"+
"function SpecAddMetadata(anchorvalue)\n"+
"{\n"+
"  if (editjob.specmetaname.value == \"\")\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.TypeInMetadataName")+"\");\n"+
"    editjob.specmetaname.focus();\n"+
"    return;\n"+
"  }\n"+
"  if (editjob.specmetavalue.value == \"\")\n"+
"  {\n"+
"    alert(\""+Messages.getString(locale,"RSSConnector.TypeInMetadataValue")+"\");\n"+
"    editjob.specmetavalue.focus();\n"+
"    return;\n"+
"  }\n"+
"  SpecOp(\"metadataop\",\"Add\",anchorvalue);\n"+
"}\n"+
"\n"+
"function URLRegexpDelete(index, anchorvalue)\n"+
"{\n"+
"  editjob.urlregexpnumber.value = index;\n"+
"  SpecOp(\"urlregexpop\",\"Delete\",anchorvalue);\n"+
"}\n"+
"\n"+
"function URLRegexpAdd(anchorvalue)\n"+
"{\n"+
"  SpecOp(\"urlregexpop\",\"Add\",anchorvalue);\n"+
"}\n"+
"\n"+
"function checkSpecification()\n"+
"{\n"+
"  if (editjob.feedtimeout.value == \"\" || !isInteger(editjob.feedtimeout.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.ATimeoutValueInSecondsIsRequired")+"\");\n"+
"    editjob.feedtimeout.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editjob.feedrefetch.value == \"\" || !isInteger(editjob.feedrefetch.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.ARefetchIntervalInMinutesIsRequired")+"\");\n"+
"    editjob.feedrefetch.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editjob.minfeedrefetch.value == \"\" || !isInteger(editjob.minfeedrefetch.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.AMinimumRefetchIntervalInMinutesIsRequire")+"\");\n"+
"    editjob.minfeedrefetch.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editjob.badfeedrefetch.value != \"\" && !isInteger(editjob.badfeedrefetch.value))\n"+
"  {\n"+
"    alert(\""+Messages.getBodyJavascriptString(locale,"RSSConnector.ABadFeedRefetchIntervalInMinutesIsRequired")+"\");\n"+
"    editjob.badfeedrefetch.focus();\n"+
"    return false;\n"+
"  }\n"+
"\n"+
"  return true;\n"+
"}\n"+
"\n"+
"//-->\n"+
"</script>\n"
    );
  }
  
  /** Output the specification body section.
  * This method is called in the body section of a job page which has selected a repository connection of the current type.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editjob".
  *@param out is the output to which any HTML should be sent.
  *@param ds is the current document specification for this job.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale, DocumentSpecification ds, String tabName)
    throws ManifoldCFException, IOException
  {
    int i;
    int k;


    // Build the url seed string, and the url regexp match and map
    StringBuilder sb = new StringBuilder();
    ArrayList regexp = new ArrayList();
    ArrayList matchStrings = new ArrayList();
    int feedTimeoutValue = 60;
    int feedRefetchValue = 60;
    int minFeedRefetchValue = 15;
    Integer badFeedRefetchValue = null;
    
    // Now, loop through paths
    i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("feed"))
      {
        String rssURL = sn.getAttributeValue("url");
        if (rssURL != null)
        {
          sb.append(rssURL).append("\n");
        }
      }
      else if (sn.getType().equals("map"))
      {
        String match = sn.getAttributeValue("match");
        String map = sn.getAttributeValue("map");
        if (match != null)
        {
          regexp.add(match);
          if (map == null)
            map = "";
          matchStrings.add(map);
        }
      }
      else if (sn.getType().equals("feedtimeout"))
      {
        String value = sn.getAttributeValue("value");
        feedTimeoutValue = Integer.parseInt(value);
      }
      else if (sn.getType().equals("feedrescan"))
      {
        String value = sn.getAttributeValue("value");
        feedRefetchValue = Integer.parseInt(value);
      }
      else if (sn.getType().equals("minfeedrescan"))
      {
        String value = sn.getAttributeValue("value");
        minFeedRefetchValue = Integer.parseInt(value);
      }
      else if (sn.getType().equals("badfeedrescan"))
      {
        String value = sn.getAttributeValue("value");
        badFeedRefetchValue = new Integer(value);
      }
    }

    // URLs tab

    if (tabName.equals(Messages.getString(locale,"RSSConnector.URLs")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"value\" colspan=\"2\">\n"+
"      <textarea rows=\"25\" cols=\"80\" name=\"rssurls\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(sb.toString())+"</textarea>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"rssurls\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(sb.toString())+"\"/>\n"
      );
    }

    // Canonicalization tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Canonicalization")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"boxcell\" colspan=\"2\">\n"+
"      <input type=\"hidden\" name=\"urlregexpop\" value=\"Continue\"/>\n"+
"      <input type=\"hidden\" name=\"urlregexpnumber\" value=\"\"/>\n"+
"      <table class=\"formtable\">\n"+
"        <tr class=\"formheaderrow\">\n"+
"          <td class=\"formcolumnheader\"></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.URLRegularExpression")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.Description")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.Reorder")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveJSPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveASPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemovePHPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveBVSessions")+"</nobr></td>\n"+
"        </tr>\n"
      );
      int q = 0;
      int l = 0;
      while (q < ds.getChildCount())
      {
        SpecificationNode specNode = ds.getChild(q++);
        if (specNode.getType().equals("urlspec"))
        {
          // Ok, this node matters to us
          String regexpString = specNode.getAttributeValue("regexp");
          String description = specNode.getAttributeValue("description");
          if (description == null)
            description = "";
          String allowReorder = specNode.getAttributeValue("reorder");
          if (allowReorder == null || allowReorder.length() == 0)
            allowReorder = "no";
          String allowJavaSessionRemoval = specNode.getAttributeValue("javasessionremoval");
          if (allowJavaSessionRemoval == null || allowJavaSessionRemoval.length() == 0)
            allowJavaSessionRemoval = "no";
          String allowASPSessionRemoval = specNode.getAttributeValue("aspsessionremoval");
          if (allowASPSessionRemoval == null || allowASPSessionRemoval.length() == 0)
            allowASPSessionRemoval = "no";
          String allowPHPSessionRemoval = specNode.getAttributeValue("phpsessionremoval");
          if (allowPHPSessionRemoval == null || allowPHPSessionRemoval.length() == 0)
            allowPHPSessionRemoval = "no";
          String allowBVSessionRemoval = specNode.getAttributeValue("bvsessionremoval");
          if (allowBVSessionRemoval == null || allowBVSessionRemoval.length() == 0)
            allowBVSessionRemoval = "no";
          out.print(
"        <tr class=\""+(((l % 2)==0)?"evenformrow":"oddformrow")+"\">\n"+
"          <td class=\"formcolumncell\">\n"+
"            <a name=\""+"urlregexp_"+Integer.toString(l)+"\">\n"+
"              <input type=\"button\" value=\"Delete\" alt=\""+Messages.getAttributeString(locale,"RSSConnector.DeleteUrlRegexp")+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(regexpString)+"\" onclick='javascript:URLRegexpDelete("+Integer.toString(l)+",\"urlregexp_"+Integer.toString(l)+"\");'/>\n"+
"            </a>\n"+
"          </td>\n"+
"          <td class=\"formcolumncell\">\n"+
"            <input type=\"hidden\" name=\""+"urlregexp_"+Integer.toString(l)+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(regexpString)+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpdesc_"+Integer.toString(l)+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(description)+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpreorder_"+Integer.toString(l)+"\" value=\""+allowReorder+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpjava_"+Integer.toString(l)+"\" value=\""+allowJavaSessionRemoval+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpasp_"+Integer.toString(l)+"\" value=\""+allowASPSessionRemoval+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpphp_"+Integer.toString(l)+"\" value=\""+allowPHPSessionRemoval+"\"/>\n"+
"            <input type=\"hidden\" name=\""+"urlregexpbv_"+Integer.toString(l)+"\" value=\""+allowBVSessionRemoval+"\"/>\n"+
"            <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(regexpString)+"</nobr>\n"+
"          </td>\n"+
"          <td class=\"formcolumncell\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(description)+"</td>\n"+
"          <td class=\"formcolumncell\">"+allowReorder+"</td>\n"+
"          <td class=\"formcolumncell\">"+allowJavaSessionRemoval+"</td>\n"+
"          <td class=\"formcolumncell\">"+allowASPSessionRemoval+"</td>\n"+
"          <td class=\"formcolumncell\">"+allowPHPSessionRemoval+"</td>\n"+
"          <td class=\"formcolumncell\">"+allowBVSessionRemoval+"</td>\n"+
"        </tr>\n"
          );

          l++;
        }
      }
      if (l == 0)
      {
        out.print(
"        <tr class=\"formrow\"><td colspan=\"8\" class=\"formcolumnmessage\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.NoCanonicalizationSpecified")+"</nobr></td></tr>\n"
        );
      }
      out.print(
"        <tr class=\"formrow\"><td colspan=\"8\" class=\"formseparator\"><hr/></td></tr>\n"+
"        <tr class=\"formrow\">\n"+
"          <td class=\"formcolumncell\">\n"+
"            <a name=\""+"urlregexp_"+Integer.toString(l)+"\">\n"+
"              <input type=\"button\" value=\"Add\" alt=\""+Messages.getAttributeString(locale,"RSSConnector.AddUlRegexp")+"\" onclick='javascript:URLRegexpAdd(\"urlregexp_"+Integer.toString(l+1)+"\");'/>\n"+
"              <input type=\"hidden\" name=\"urlregexpcount\" value=\""+Integer.toString(l)+"\"/>\n"+
"            </a>\n"+
"          </td>\n"+
"          <td class=\"formcolumncell\"><input type=\"text\" name=\"urlregexp\" size=\"30\" value=\"\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"text\" name=\"urlregexpdesc\" size=\"30\" value=\"\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"checkbox\" name=\"urlregexpreorder\" value=\"yes\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"checkbox\" name=\"urlregexpjava\" value=\"yes\" checked=\"true\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"checkbox\" name=\"urlregexpasp\" value=\"yes\" checked=\"true\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"checkbox\" name=\"urlregexpphp\" value=\"yes\" checked=\"true\"/></td>\n"+
"          <td class=\"formcolumncell\"><input type=\"checkbox\" name=\"urlregexpbv\" value=\"yes\" checked=\"true\"/></td>\n"+
"        </tr>\n"+
"      </table>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Post the canonicalization specification
      int q = 0;
      int l = 0;
      while (q < ds.getChildCount())
      {
        SpecificationNode specNode = ds.getChild(q++);
        if (specNode.getType().equals("urlspec"))
        {
          // Ok, this node matters to us
          String regexpString = specNode.getAttributeValue("regexp");
          String description = specNode.getAttributeValue("description");
          if (description == null)
            description = "";
          String allowReorder = specNode.getAttributeValue("reorder");
          if (allowReorder == null || allowReorder.length() == 0)
            allowReorder = "no";
          String allowJavaSessionRemoval = specNode.getAttributeValue("javasessionremoval");
          if (allowJavaSessionRemoval == null || allowJavaSessionRemoval.length() == 0)
            allowJavaSessionRemoval = "no";
          String allowASPSessionRemoval = specNode.getAttributeValue("aspsessionremoval");
          if (allowASPSessionRemoval == null || allowASPSessionRemoval.length() == 0)
            allowASPSessionRemoval = "no";
          String allowPHPSessionRemoval = specNode.getAttributeValue("phpsessionremoval");
          if (allowPHPSessionRemoval == null || allowPHPSessionRemoval.length() == 0)
            allowPHPSessionRemoval = "no";
          String allowBVSessionRemoval = specNode.getAttributeValue("bvsessionremoval");
          if (allowBVSessionRemoval == null || allowBVSessionRemoval.length() == 0)
            allowBVSessionRemoval = "no";
          out.print(
"<input type=\"hidden\" name=\""+"urlregexp_"+Integer.toString(l)+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(regexpString)+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpdesc_"+Integer.toString(l)+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(description)+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpreorder_"+Integer.toString(l)+"\" value=\""+allowReorder+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpjava_"+Integer.toString(l)+"\" value=\""+allowJavaSessionRemoval+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpasp_"+Integer.toString(l)+"\" value=\""+allowASPSessionRemoval+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpphp_"+Integer.toString(l)+"\" value=\""+allowPHPSessionRemoval+"\"/>\n"+
"<input type=\"hidden\" name=\""+"urlregexpbv_"+Integer.toString(l)+"\" value=\""+allowBVSessionRemoval+"\"/>\n"
          );
          l++;
        }
      }
      out.print(
"<input type=\"hidden\" name=\"urlregexpcount\" value=\""+Integer.toString(l)+"\"/>\n"
      );
    }
  
    // Mappings tab

    if (tabName.equals(Messages.getString(locale,"RSSConnector.URLMappings")))
    {
      out.print(
"<input type=\"hidden\" name=\"rssop\" value=\"\"/>\n"+
"<input type=\"hidden\" name=\"rssindex\" value=\"\"/>\n"+
"<input type=\"hidden\" name=\"rssmapcount\" value=\""+Integer.toString(regexp.size())+"\"/>\n"+
"\n"+
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"4\"><hr/></td></tr>\n"
      );

      i = 0;
      while (i < regexp.size())
      {
        String prefix = "rssregexp_"+Integer.toString(i)+"_";
        out.print(
"  <tr>\n"+
"    <td class=\"value\">\n"+
"      <a name=\""+"regexp_"+Integer.toString(i)+"\">\n"+
"        <input type=\"button\" value=\"Remove\" onclick='javascript:RemoveRegexp("+Integer.toString(i)+",\"regexp_"+Integer.toString(i)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.RemoveRegexp")+Integer.toString(i)+"\"/>\n"+
"      </a>\n"+
"    </td>\n"+
"    <td class=\"value\"><input type=\"hidden\" name=\""+prefix+"match"+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape((String)regexp.get(i))+"\"/>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape((String)regexp.get(i))+"</td>\n"+
"    <td class=\"value\">==></td>\n"+
"    <td class=\"value\">\n"
        );
        String match = (String)matchStrings.get(i);
        out.print(
"      <input type=\"hidden\" name=\""+prefix+"map"+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(match)+"\"/>\n"
        );
        if (match.length() == 0)
        {
          out.print(
"      &lt;as is&gt;\n"
          );
        }
        else
        {
          out.print(
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(match)+"\n"
          );
        }
        out.print(
"    </td>\n"+
"  </tr>\n"
        );
        i++;
      }
      out.print(
"  <tr>\n"+
"    <td class=\"value\"><a name=\""+"regexp_"+Integer.toString(i)+"\"><input type=\"button\" value=\"Add\" onclick='javascript:AddRegexp(\"regexp_"+Integer.toString(i+1)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.AddRegexp")+"\"/></a></td>\n"+
"    <td class=\"value\"><input type=\"text\" name=\"rssmatch\" size=\"16\" value=\"\"/></td>\n"+
"    <td class=\"value\">==></td>\n"+
"    <td class=\"value\"><input type=\"text\" name=\"rssmap\" size=\"16\" value=\"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"rssmapcount\" value=\""+Integer.toString(regexp.size())+"\"/>\n"
      );
      i = 0;
      while (i < regexp.size())
      {
        String prefix = "rssregexp_"+Integer.toString(i)+"_";
        String match = (String)matchStrings.get(i);
        out.print(
"<input type=\"hidden\" name=\""+prefix+"match"+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape((String)regexp.get(i))+"\"/>\n"+
"<input type=\"hidden\" name=\""+prefix+"map"+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(match)+"\"/>\n"
        );
        i++;
      }
    }

    // Timeout Value tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.TimeValues")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.FeedConnectTimeout")+"</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"feedtimeout\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(feedTimeoutValue))+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.DefaultFeedRefetchTime")+"</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"feedrefetch\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(feedRefetchValue))+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.MinimumFeedRefetchTime")+"</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"minfeedrefetch\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(minFeedRefetchValue))+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.BadFeedRefetchTime")+"</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <input type=\"hidden\" name=\"badfeedrefetch_present\" value=\"true\"/>\n"+
"      <input type=\"text\" size=\"5\" name=\"badfeedrefetch\" value=\""+((badFeedRefetchValue==null)?"":org.apache.manifoldcf.ui.util.Encoder.attributeEscape(badFeedRefetchValue.toString()))+"\"/>\n"+
"    </td>\n"+
"  </tr>\n"+
"\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"feedtimeout\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(feedTimeoutValue))+"\"/>\n"+
"<input type=\"hidden\" name=\"feedrefetch\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(feedRefetchValue))+"\"/>\n"+
"<input type=\"hidden\" name=\"minfeedrefetch\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(Integer.toString(minFeedRefetchValue))+"\"/>\n"+
"<input type=\"hidden\" name=\"badfeedrefetch_present\" value=\"true\"/>\n"+
"<input type=\"hidden\" name=\"badfeedrefetch\" value=\""+((badFeedRefetchValue==null)?"":org.apache.manifoldcf.ui.util.Encoder.attributeEscape(badFeedRefetchValue.toString()))+"\"/>\n"
      );
    }

    // Dechromed content tab
    String dechromedMode = "none";
    String chromedMode = "use";
    i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("dechromedmode"))
        dechromedMode = sn.getAttributeValue("mode");
      else if (sn.getType().equals("chromedmode"))
        chromedMode = sn.getAttributeValue("mode");
    }
    if (tabName.equals(Messages.getString(locale,"RSSConnector.DechromedContent")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"1\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"value\"><nobr><input type=\"radio\" name=\"dechromedmode\" value=\"none\" "+(dechromedMode.equals("none")?"checked=\"true\"":"")+"/>"+Messages.getBodyString(locale,"RSSConnector.NoDechromedContent")+"</nobr></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"value\"><nobr><input type=\"radio\" name=\"dechromedmode\" value=\"description\" "+(dechromedMode.equals("description")?"checked=\"true\"":"")+"/>"+Messages.getBodyString(locale,"RSSConnector.DechromedContentIfPresentInDescriptionField")+"</nobr></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"value\"><nobr><input type=\"radio\" name=\"dechromedmode\" value=\"content\" "+(dechromedMode.equals("content")?"checked=\"true\"":"")+"/>"+Messages.getBodyString(locale,"RSSConnector.ContentIfPresentInContentField")+"</nobr></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"separator\"><hr/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"value\"><nobr><input type=\"radio\" name=\"chromedmode\" value=\"use\" "+(chromedMode.equals("use")?"checked=\"true\"":"")+"/>"+Messages.getBodyString(locale,"RSSConnector.UseChromedContentIfNoDechromedContentFound")+"</nobr></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"value\"><nobr><input type=\"radio\" name=\"chromedmode\" value=\"skip\" "+(chromedMode.equals("skip")?"checked=\"true\"":"")+"/>"+Messages.getBodyString(locale,"RSSConnector.NeverUseChromedContent")+"</nobr></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      out.print(
"<input type=\"hidden\" name=\"dechromedmode\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(dechromedMode)+"\"/>\n"+
"<input type=\"hidden\" name=\"chromedmode\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(chromedMode)+"\"/>\n"
      );
    }
  
    // Security tab
    // There is no native security, so all we care about are the tokens.
    i = 0;

    if (tabName.equals(Messages.getString(locale,"RSSConnector.Security")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
      );
      // Go through forced ACL
      i = 0;
      k = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i++);
        if (sn.getType().equals("access"))
        {
          String accessDescription = "_"+Integer.toString(k);
          String accessOpName = "accessop"+accessDescription;
          String token = sn.getAttributeValue("token");
          out.print(
"  <tr>\n"+
"    <td class=\"description\">\n"+
"      <input type=\"hidden\" name=\""+accessOpName+"\" value=\"\"/>\n"+
"      <input type=\"hidden\" name=\""+"spectoken"+accessDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(token)+"\"/>\n"+
"      <a name=\""+"token_"+Integer.toString(k)+"\">\n"+
"        <input type=\"button\" value=\"Delete\" onClick='Javascript:SpecOp(\""+accessOpName+"\",\"Delete\",\"token_"+Integer.toString(k)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.DeleteToken")+Integer.toString(k)+"\"/>\n"+
"      </a>&nbsp;\n"+
"    </td>\n"+
"    <td class=\"value\">\n"+
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(token)+"\n"+
"    </td>\n"+
"  </tr>\n"
          );
          k++;
        }
      }
      if (k == 0)
      {
        out.print(
"  <tr>\n"+
"    <td class=\"message\" colspan=\"2\">" + Messages.getBodyString(locale,"RSSConnector.NoAccessTokensPresent") + "</td>\n"+
"  </tr>\n"
        );
      }
      out.print(
"  <tr><td class=\"lightseparator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\">\n"+
"      <input type=\"hidden\" name=\"tokencount\" value=\""+Integer.toString(k)+"\"/>\n"+
"      <input type=\"hidden\" name=\"accessop\" value=\"\"/>\n"+
"      <a name=\""+"token_"+Integer.toString(k)+"\">\n"+
"        <input type=\"button\" value=\"Add\" onClick='Javascript:SpecAddToken(\"token_"+Integer.toString(k+1)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.AddAccessToken")+"\"/>\n"+
"      </a>&nbsp;\n"+
"    </td>\n"+
"    <td class=\"value\">\n"+
"      <input type=\"text\" size=\"30\" name=\"spectoken\" value=\"\"/>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Finally, go through forced ACL
      i = 0;
      k = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i++);
        if (sn.getType().equals("access"))
        {
          String accessDescription = "_"+Integer.toString(k);
          String token = sn.getAttributeValue("token");
          out.print(
"<input type=\"hidden\" name=\""+"spectoken"+accessDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(token)+"\"/>\n"
          );
          k++;
        }
      }
      out.print(
"<input type=\"hidden\" name=\"tokencount\" value=\""+Integer.toString(k)+"\"/>\n"
      );
    }

    // "Metadata" tab
    if (tabName.equals(Messages.getString(locale,"RSSConnector.Metadata")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"4\"><hr/></td></tr>\n"
      );
      // Go through metadata
      i = 0;
      k = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i++);
        if (sn.getType().equals("metadata"))
        {
          String metadataDescription = "_"+Integer.toString(k);
          String metadataOpName = "metadataop"+metadataDescription;
          String name = sn.getAttributeValue("name");
          String value = sn.getAttributeValue("value");
          out.print(
"  <tr>\n"+
"    <td class=\"description\">\n"+
"      <input type=\"hidden\" name=\""+metadataOpName+"\" value=\"\"/>\n"+
"      <input type=\"hidden\" name=\""+"specmetaname"+metadataDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(name)+"\"/>\n"+
"      <input type=\"hidden\" name=\""+"specmetavalue"+metadataDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(value)+"\"/>\n"+
"      <a name=\""+"metadata_"+Integer.toString(k)+"\">\n"+
"        <input type=\"button\" value=\"Delete\" onClick='Javascript:SpecOp(\""+metadataOpName+"\",\"Delete\",\"metadata_"+Integer.toString(k)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.DeleteMetadata")+Integer.toString(k)+"\"/>\n"+
"      </a>&nbsp;\n"+
"    </td>\n"+
"    <td class=\"value\">\n"+
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(name)+"\n"+
"    </td>\n"+
"    <td class=\"value\">=</td>\n"+
"    <td class=\"value\">\n"+
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(value)+"\n"+
"    </td>\n"+
"  </tr>\n"
          );
          k++;
        }

      }
      if (k == 0)
      {
        out.print(
"  <tr>\n"+
"    <td class=\"message\" colspan=\"4\">"+Messages.getBodyString(locale,"RSSConnector.NoMetadataPresent")+"</td>\n"+
"  </tr>\n"
        );
      }
      out.print(
"  <tr><td class=\"lightseparator\" colspan=\"4\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\">\n"+
"      <input type=\"hidden\" name=\"metadatacount\" value=\""+Integer.toString(k)+"\"/>\n"+
"      <input type=\"hidden\" name=\"metadataop\" value=\"\"/>\n"+
"      <a name=\""+"metadata_"+Integer.toString(k)+"\">\n"+
"        <input type=\"button\" value=\"Add\" onClick='Javascript:SpecAddMetadata(\"metadata_"+Integer.toString(k+1)+"\")' alt=\""+Messages.getAttributeString(locale,"RSSConnector.AddMetadata")+"\"/>\n"+
"      </a>&nbsp;\n"+
"    </td>\n"+
"    <td class=\"value\">\n"+
"      <input type=\"text\" size=\"30\" name=\"specmetaname\" value=\"\"/>\n"+
"    </td>\n"+
"    <td class=\"value\">=</td>\n"+
"    <td class=\"value\">\n"+
"      <input type=\"text\" size=\"80\" name=\"specmetavalue\" value=\"\"/>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );

    }
    else
    {
      // Finally, go through metadata
      i = 0;
      k = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i++);
        if (sn.getType().equals("metadata"))
        {
          String metadataDescription = "_"+Integer.toString(k);
          String name = sn.getAttributeValue("name");
          String value = sn.getAttributeValue("value");
          out.print(
"<input type=\"hidden\" name=\""+"specmetaname"+metadataDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(name)+"\"/>\n"+
"<input type=\"hidden\" name=\""+"specmetavalue"+metadataDescription+"\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(value)+"\"/>\n"
          );
          k++;
        }
      }
      out.print(
"<input type=\"hidden\" name=\"metadatacount\" value=\""+Integer.toString(k)+"\"/>\n"
      );
    
    }
  }
  
  /** Process a specification post.
  * This method is called at the start of job's edit or view page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the document specification accordingly.
  * The name of the posted form is "editjob".
  *@param variableContext contains the post data, including binary file-upload information.
  *@param ds is the current document specification for this job.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the job (and cause a redirection to an error page).
  */
  @Override
  public String processSpecificationPost(IPostParameters variableContext, Locale locale, DocumentSpecification ds)
    throws ManifoldCFException
  {
    // Get the map
    String value = variableContext.getParameter("rssmapcount");
    if (value != null)
    {
      int mapsize = Integer.parseInt(value);

      // Clear it first
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("map"))
          ds.removeChild(j);
        else
          j++;
      }

      // Grab the map values
      j = 0;
      while (j < mapsize)
      {
        String prefix = "rssregexp_"+Integer.toString(j)+"_";
        String match = variableContext.getParameter(prefix+"match");
        String map = variableContext.getParameter(prefix+"map");
        if (map == null)
          map = "";
        // Add to the documentum specification
        SpecificationNode node = new SpecificationNode("map");
        node.setAttribute("match",match);
        node.setAttribute("map",map);
        ds.addChild(ds.getChildCount(),node);

        j++;
      }
    }

    // Get the cgiPath
    String rssURLSequence = variableContext.getParameter("rssurls");
    if (rssURLSequence != null)
    {
      // Delete all url specs first
      int i = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i);
        if (sn.getType().equals("feed"))
          ds.removeChild(i);
        else
          i++;
      }

      try
      {
        java.io.Reader str = new java.io.StringReader(rssURLSequence);
        try
        {
          java.io.BufferedReader is = new java.io.BufferedReader(str);
          try
          {
            while (true)
            {
              String nextString = is.readLine();
              if (nextString == null)
                break;
              if (nextString.length() == 0)
                continue;
              SpecificationNode node = new SpecificationNode("feed");
              node.setAttribute("url",nextString);
              ds.addChild(ds.getChildCount(),node);
            }
          }
          finally
          {
            is.close();
          }
        }
        finally
        {
          str.close();
        }
      }
      catch (java.io.IOException e)
      {
        throw new ManifoldCFException("IO error",e);
      }
    }

    // Read the url specs
    String urlRegexpCount = variableContext.getParameter("urlregexpcount");
    if (urlRegexpCount != null && urlRegexpCount.length() > 0)
    {
      int regexpCount = Integer.parseInt(urlRegexpCount);
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("urlspec"))
          ds.removeChild(j);
        else
          j++;
      }
      
      // Grab the operation and the index (if any)
      String operation = variableContext.getParameter("urlregexpop");
      if (operation == null)
        operation = "Continue";
      int opIndex = -1;
      if (operation.equals("Delete"))
        opIndex = Integer.parseInt(variableContext.getParameter("urlregexpnumber"));
      
      // Reconstruct urlspec nodes
      j = 0;
      while (j < regexpCount)
      {
        // For each index, first look for a delete operation
        if (!operation.equals("Delete") || j != opIndex)
        {
          // Add the jth node
          String regexp = variableContext.getParameter("urlregexp_"+Integer.toString(j));
          String regexpDescription = variableContext.getParameter("urlregexpdesc_"+Integer.toString(j));
          String reorder = variableContext.getParameter("urlregexpreorder_"+Integer.toString(j));
          String javaSession = variableContext.getParameter("urlregexpjava_"+Integer.toString(j));
          String aspSession = variableContext.getParameter("urlregexpasp_"+Integer.toString(j));
          String phpSession = variableContext.getParameter("urlregexpphp_"+Integer.toString(j));
          String bvSession = variableContext.getParameter("urlregexpbv_"+Integer.toString(j));
          SpecificationNode newSn = new SpecificationNode("urlspec");
          newSn.setAttribute("regexp",regexp);
          if (regexpDescription != null && regexpDescription.length() > 0)
            newSn.setAttribute("description",regexpDescription);
          if (reorder != null && reorder.length() > 0)
            newSn.setAttribute("reorder",reorder);
          if (javaSession != null && javaSession.length() > 0)
            newSn.setAttribute("javasessionremoval",javaSession);
          if (aspSession != null && aspSession.length() > 0)
            newSn.setAttribute("aspsessionremoval",aspSession);
          if (phpSession != null && phpSession.length() > 0)
            newSn.setAttribute("phpsessionremoval",phpSession);
          if (bvSession != null && bvSession.length() > 0)
            newSn.setAttribute("bvsessionremoval",bvSession);
          ds.addChild(ds.getChildCount(),newSn);
        }
        j++;
      }
      if (operation.equals("Add"))
      {
        String regexp = variableContext.getParameter("urlregexp");
        String regexpDescription = variableContext.getParameter("urlregexpdesc");
        String reorder = variableContext.getParameter("urlregexpreorder");
        String javaSession = variableContext.getParameter("urlregexpjava");
        String aspSession = variableContext.getParameter("urlregexpasp");
        String phpSession = variableContext.getParameter("urlregexpphp");
        String bvSession = variableContext.getParameter("urlregexpbv");

        // Add a new node at the end
        SpecificationNode newSn = new SpecificationNode("urlspec");
        newSn.setAttribute("regexp",regexp);
        if (regexpDescription != null && regexpDescription.length() > 0)
          newSn.setAttribute("description",regexpDescription);
        if (reorder != null && reorder.length() > 0)
          newSn.setAttribute("reorder",reorder);
        if (javaSession != null && javaSession.length() > 0)
          newSn.setAttribute("javasessionremoval",javaSession);
        if (aspSession != null && aspSession.length() > 0)
          newSn.setAttribute("aspsessionremoval",aspSession);
        if (phpSession != null && phpSession.length() > 0)
          newSn.setAttribute("phpsessionremoval",phpSession);
        if (bvSession != null && bvSession.length() > 0)
          newSn.setAttribute("bvsessionremoval",bvSession);
        ds.addChild(ds.getChildCount(),newSn);
      }
    }
    
    // Read the feed timeout, if present
    String feedTimeoutValue = variableContext.getParameter("feedtimeout");
    if (feedTimeoutValue != null && feedTimeoutValue.length() > 0)
    {
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("feedtimeout"))
          ds.removeChild(j);
        else
          j++;
      }
      SpecificationNode node = new SpecificationNode("feedtimeout");
      node.setAttribute("value",feedTimeoutValue);
      ds.addChild(ds.getChildCount(),node);
    }

    // Read the feed refetch interval, if present
    String feedRefetchValue = variableContext.getParameter("feedrefetch");
    if (feedRefetchValue != null && feedRefetchValue.length() > 0)
    {
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("feedrescan"))
          ds.removeChild(j);
        else
          j++;
      }
      SpecificationNode node = new SpecificationNode("feedrescan");
      node.setAttribute("value",feedRefetchValue);
      ds.addChild(ds.getChildCount(),node);
    }

    // Read the minimum feed refetch interval, if present
    String minFeedRefetchValue = variableContext.getParameter("minfeedrefetch");
    if (minFeedRefetchValue != null && minFeedRefetchValue.length() > 0)
    {
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("minfeedrescan"))
          ds.removeChild(j);
        else
          j++;
      }
      SpecificationNode node = new SpecificationNode("minfeedrescan");
      node.setAttribute("value",minFeedRefetchValue);
      ds.addChild(ds.getChildCount(),node);
    }
    
    // Read the bad feed refetch interval (which is allowed to be null)
    String badFeedRefetchValuePresent = variableContext.getParameter("badfeedrefetch_present");
    if (badFeedRefetchValuePresent != null && badFeedRefetchValuePresent.length() > 0)
    {
      String badFeedRefetchValue = variableContext.getParameter("badfeedrefetch");
      int k = 0;
      while (k < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(k);
        if (sn.getType().equals("badfeedrescan"))
          ds.removeChild(k);
        else
          k++;
      }
      if (badFeedRefetchValue != null && badFeedRefetchValue.length() > 0)
      {
        SpecificationNode node = new SpecificationNode("badfeedrescan");
        node.setAttribute("value",badFeedRefetchValue);
        ds.addChild(ds.getChildCount(),node);
      }
    }
    
    // Read the dechromed mode
    String dechromedMode = variableContext.getParameter("dechromedmode");
    if (dechromedMode != null && dechromedMode.length() > 0)
    {
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("dechromedmode"))
          ds.removeChild(j);
        else
          j++;
      }
      SpecificationNode node = new SpecificationNode("dechromedmode");
      node.setAttribute("mode",dechromedMode);
      ds.addChild(ds.getChildCount(),node);
    }
    
    // Read the chromed mode
    String chromedMode = variableContext.getParameter("chromedmode");
    if (chromedMode != null && chromedMode.length() > 0)
    {
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("chromedmode"))
          ds.removeChild(j);
        else
          j++;
      }
      SpecificationNode node = new SpecificationNode("chromedmode");
      node.setAttribute("mode",chromedMode);
      ds.addChild(ds.getChildCount(),node);
    }
    
    // Now, do whatever action we were told to do.
    String rssop = variableContext.getParameter("rssop");
    if (rssop != null && rssop.equals("Add"))
    {
      // Add a match to the end
      String match = variableContext.getParameter("rssmatch");
      String map = variableContext.getParameter("rssmap");
      SpecificationNode node = new SpecificationNode("map");
      node.setAttribute("match",match);
      node.setAttribute("map",map);
      ds.addChild(ds.getChildCount(),node);
    }
    else if (rssop != null && rssop.equals("Delete"))
    {
      int index = Integer.parseInt(variableContext.getParameter("rssindex"));
      int j = 0;
      while (j < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(j);
        if (sn.getType().equals("map"))
        {
          if (index == 0)
          {
            ds.removeChild(j);
            break;
          }
          index--;
        }
        j++;
      }
    }

    String xc = variableContext.getParameter("tokencount");
    if (xc != null)
    {
      // Delete all tokens first
      int i = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i);
        if (sn.getType().equals("access"))
          ds.removeChild(i);
        else
          i++;
      }

      int accessCount = Integer.parseInt(xc);
      i = 0;
      while (i < accessCount)
      {
        String accessDescription = "_"+Integer.toString(i);
        String accessOpName = "accessop"+accessDescription;
        xc = variableContext.getParameter(accessOpName);
        if (xc != null && xc.equals("Delete"))
        {
          // Next row
          i++;
          continue;
        }
        // Get the stuff we need
        String accessSpec = variableContext.getParameter("spectoken"+accessDescription);
        SpecificationNode node = new SpecificationNode("access");
        node.setAttribute("token",accessSpec);
        ds.addChild(ds.getChildCount(),node);
        i++;
      }

      String op = variableContext.getParameter("accessop");
      if (op != null && op.equals("Add"))
      {
        String accessspec = variableContext.getParameter("spectoken");
        SpecificationNode node = new SpecificationNode("access");
        node.setAttribute("token",accessspec);
        ds.addChild(ds.getChildCount(),node);
      }
    }

    xc = variableContext.getParameter("metadatacount");
    if (xc != null)
    {
      // Delete all tokens first
      int i = 0;
      while (i < ds.getChildCount())
      {
        SpecificationNode sn = ds.getChild(i);
        if (sn.getType().equals("metadata"))
          ds.removeChild(i);
        else
          i++;
      }

      int metadataCount = Integer.parseInt(xc);
      i = 0;
      while (i < metadataCount)
      {
        String metadataDescription = "_"+Integer.toString(i);
        String metadataOpName = "metadataop"+metadataDescription;
        xc = variableContext.getParameter(metadataOpName);
        if (xc != null && xc.equals("Delete"))
        {
          // Next row
          i++;
          continue;
        }
        // Get the stuff we need
        String metaNameSpec = variableContext.getParameter("specmetaname"+metadataDescription);
        String metaValueSpec = variableContext.getParameter("specmetavalue"+metadataDescription);
        SpecificationNode node = new SpecificationNode("metadata");
        node.setAttribute("name",metaNameSpec);
        node.setAttribute("value",metaValueSpec);
        ds.addChild(ds.getChildCount(),node);
        i++;
      }

      String op = variableContext.getParameter("metadataop");
      if (op != null && op.equals("Add"))
      {
        String metaNameSpec = variableContext.getParameter("specmetaname");
        String metaValueSpec = variableContext.getParameter("specmetavalue");
        
        SpecificationNode node = new SpecificationNode("metadata");
        node.setAttribute("name",metaNameSpec);
        node.setAttribute("value",metaValueSpec);
        
        ds.addChild(ds.getChildCount(),node);
      }
    }
    return null;
  }
  
  /** View specification.
  * This method is called in the body section of a job's view page.  Its purpose is to present the document specification information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param out is the output to which any HTML should be sent.
  *@param ds is the current document specification for this job.
  */
  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, DocumentSpecification ds)
    throws ManifoldCFException, IOException
  {
    out.print(
"<table class=\"displaytable\">\n"
    );
    int i = 0;
    boolean seenAny = false;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("feed"))
      {
        if (seenAny == false)
        {
          out.print(
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RSSUrls")+"</nobr></td>\n"+
"    <td class=\"value\">\n"
          );
          seenAny = true;
        }
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(sn.getAttributeValue("url"))+"</nobr><br/>\n"
        );
      }
    }

    if (seenAny)
    {
      out.print(
"    </td>\n"+
"  </tr>\n"
      );
    }
    else
    {
      out.print(
"  <tr><td class=\"message\" colspan=\"2\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.NoRSSUrlsSpecified")+"</nobr></td></tr>\n"
      );
    }
    out.print(
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
    );
    i = 0;
    int l = 0;
    seenAny = false;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("urlspec"))
      {
        if (l == 0)
        {
          out.print(
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.URLCanonicalization")+"</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <table class=\"formtable\">\n"+
"        <tr class=\"formheaderrow\">\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.URLRegexp")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.Description")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.Reorder")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveJSPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveASPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemovePHPSessions")+"</nobr></td>\n"+
"          <td class=\"formcolumnheader\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.RemoveBVSessions")+"</nobr></td>\n"+
"        </tr>\n"
          );
        }
        String regexpString = sn.getAttributeValue("regexp");
        String description = sn.getAttributeValue("description");
        if (description == null)
          description = "";
        String allowReorder = sn.getAttributeValue("reorder");
        if (allowReorder == null || allowReorder.length() == 0)
          allowReorder = "no";
        String allowJavaSessionRemoval = sn.getAttributeValue("javasessionremoval");
        if (allowJavaSessionRemoval == null || allowJavaSessionRemoval.length() == 0)
          allowJavaSessionRemoval = "no";
        String allowASPSessionRemoval = sn.getAttributeValue("aspsessionremoval");
        if (allowASPSessionRemoval == null || allowASPSessionRemoval.length() == 0)
          allowASPSessionRemoval = "no";
        String allowPHPSessionRemoval = sn.getAttributeValue("phpsessionremoval");
        if (allowPHPSessionRemoval == null || allowPHPSessionRemoval.length() == 0)
          allowPHPSessionRemoval = "no";
        String allowBVSessionRemoval = sn.getAttributeValue("bvsessionremoval");
        if (allowBVSessionRemoval == null || allowBVSessionRemoval.length() == 0)
          allowBVSessionRemoval = "no";
        out.print(
"        <tr class=\""+(((l % 2)==0)?"evenformrow":"oddformrow")+"\">\n"+
"          <td class=\"formcolumncell\"><nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(regexpString)+"</nobr></td>\n"+
"          <td class=\"formcolumncell\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(description)+"</td>\n"+
"          <td class=\"formcolumncell\"><nobr>"+allowReorder+"</nobr></td>\n"+
"          <td class=\"formcolumncell\"><nobr>"+allowJavaSessionRemoval+"</nobr></td>\n"+
"          <td class=\"formcolumncell\"><nobr>"+allowASPSessionRemoval+"</nobr></td>\n"+
"          <td class=\"formcolumncell\"><nobr>"+allowPHPSessionRemoval+"</nobr></td>\n"+
"          <td class=\"formcolumncell\"><nobr>"+allowBVSessionRemoval+"</nobr></td>\n"+
"        </tr>\n"
        );
        l++;
      }
    }
    if (l > 0)
    {
      out.print(
"      </table>\n"+
"    </td>\n"+
"  </tr>\n"
      );
    }
    else
    {
      out.print(
"  <tr><td class=\"message\" colspan=\"2\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.NoCanonicalizationSpecified")+"</nobr></td></tr>\n"
      );
    }

    out.print(
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
    );
    i = 0;
    seenAny = false;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("map"))
      {
        if (seenAny == false)
        {
          out.print(
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.URLMappingsColon")+"</nobr></td>\n"+
"    <td class=\"value\">\n"
          );
          seenAny = true;
        }
        String match = sn.getAttributeValue("match");
        String map = sn.getAttributeValue("map");
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(match)+"</nobr>\n"
        );
        if (map != null && map.length() > 0)
        {
          out.print(
"      &nbsp;--&gt;&nbsp;<nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(map)+"</nobr>\n"
          );
        }
        out.print(
"      <br/>\n"
        );
      }
    }

    if (seenAny)
    {
      out.print(
"    </td>\n"+
"  </tr>\n"
      );
    }
    else
    {
      out.print(
"  <tr><td class=\"message\" colspan=\"2\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.NoMappingsSpecifiedWillAcceptAllUrls")+"</nobr></td></tr>\n"
      );
    }
    out.print(
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
    );
    String feedTimeoutValue = "60";
    String feedRefetchValue = "60";
    String minFeedRefetchValue = "15";
    String badFeedRefetchValue = null;
    String dechromedMode = "none";
    String chromedMode = "use";
    i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("feedtimeout"))
      {
        feedTimeoutValue = sn.getAttributeValue("value");
      }
      else if (sn.getType().equals("feedrescan"))
      {
        feedRefetchValue = sn.getAttributeValue("value");
      }
      else if (sn.getType().equals("minfeedrescan"))
      {
        minFeedRefetchValue = sn.getAttributeValue("value");
      }
      else if (sn.getType().equals("badfeedrescan"))
      {
        badFeedRefetchValue = sn.getAttributeValue("value");
      }
      else if (sn.getType().equals("dechromedmode"))
      {
        dechromedMode = sn.getAttributeValue("mode");
      }
      else if (sn.getType().equals("chromedmode"))
      {
        chromedMode = sn.getAttributeValue("mode");
      }
    }
    out.print(
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.FeedConnectionTimeout")+"</nobr></td>\n"+
"    <td class=\"value\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(feedTimeoutValue)+"</td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.DefaultFeedRescanInterval")+"</nobr></td>\n"+
"    <td class=\"value\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(feedRefetchValue)+"</td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.MinimumFeedRescanInterval")+"</nobr></td>\n"+
"    <td class=\"value\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(minFeedRefetchValue)+"</td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.BadFeedRescanInterval")+"</nobr></td>\n"+
"    <td class=\"value\">"+((badFeedRefetchValue==null)?"(Default feed rescan value)":org.apache.manifoldcf.ui.util.Encoder.bodyEscape(badFeedRefetchValue))+"</td>\n"+

"  </tr>\n"+
"      \n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.DechromedContentSource")+"</nobr></td>\n"+
"    <td class=\"value\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(dechromedMode)+"</td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.ChromedContent")+"</nobr></td>\n"+
"    <td class=\"value\">"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(chromedMode)+"</td>\n"+
"  </tr>\n"+
"\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
    );
    // Go through looking for access tokens
    seenAny = false;
    i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("access"))
      {
        if (seenAny == false)
        {
          out.print(
"  <tr><td class=\"description\"><nobr>"+Messages.getBodyString(locale,"RSSConnector.AccessTokens")+"</nobr></td>\n"+
"    <td class=\"value\">\n"
          );
          seenAny = true;
        }
        String token = sn.getAttributeValue("token");
        out.print(
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(token)+"<br/>\n"
        );
      }
    }

    if (seenAny)
    {
      out.print(
"    </td>\n"+
"  </tr>\n"
      );
    }
    else
    {
      out.print(
"  <tr><td class=\"message\" colspan=\"2\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.NoAccessTokensSpecified") + "</nobr></td></tr>\n"
      );
    }
    out.print(
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"
    );
    // Go through looking for metadata
    seenAny = false;
    i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals("metadata"))
      {
        if (seenAny == false)
        {
          out.print(
"  <tr><td class=\"description\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.MetadataColon") + "</nobr></td>\n"+
"    <td class=\"value\">\n"
          );
          seenAny = true;
        }
        String name = sn.getAttributeValue("name");
        String value = sn.getAttributeValue("value");
        out.print(
"      "+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(name)+"&nbsp;=&nbsp;"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(value)+"<br/>\n"
        );
      }
    }

    if (seenAny)
    {
      out.print(
"    </td>\n"+
"  </tr>\n"
      );
    }
    else
    {
      out.print(
"  <tr><td class=\"message\" colspan=\"2\"><nobr>" + Messages.getBodyString(locale,"RSSConnector.NoMetadataSpecified") + "</nobr></td></tr>\n"
      );
    }
    out.print(
"</table>\n"
    );
  }

  /** Handle an RSS feed document, using SAX to limit the memory impact */
  protected void handleRSSFeedSAX(String documentIdentifier, IProcessActivity activities, Filter filter)
    throws ManifoldCFException, ServiceInterruption
  {
    // The SAX model uses parsing events to control parsing, which allows me to manage memory usage much better.
    // This is essential for when a feed contains dechromed content as well as links.

    // First, catch all flavors of IO exception, and handle them properly
    try
    {
      // Open the input stream, and set up the parse
      InputStream is = cache.getData(documentIdentifier);
      if (is == null)
      {
        Logging.connectors.error("RSS: Document '"+documentIdentifier+"' should be in cache but isn't");
        return;
      }
      try
      {
        // Parse the document.  This will cause various things to occur, within the instantiated XMLContext class.
        XMLStream x = new XMLStream();
        OuterContextClass c = new OuterContextClass(x,documentIdentifier,activities,filter);
        x.setContext(c);
        try
        {
          try
          {
            x.parse(is);
          }
          catch (ManifoldCFException e)
          {
            // Ignore XML parsing errors.
            if (e.getMessage().indexOf("pars") >= 0)
            {
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: XML document '"+documentIdentifier+"' was unparseable ("+e.getMessage()+"), skipping");

              c.setDefaultRescanTimeIfNeeded();

              return;
            }
            throw e;

          }
          c.checkIfValidFeed();
          c.setDefaultRescanTimeIfNeeded();
        }
        finally
        {
          x.cleanup();
        }
      }
      finally
      {
        is.close();
      }
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new ManifoldCFException("Socket timeout error: "+e.getMessage(),e);
    }
    catch (org.apache.commons.httpclient.ConnectTimeoutException e)
    {
      throw new ManifoldCFException("Socket connect timeout error: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new ManifoldCFException("IO error: "+e.getMessage(),e);
    }

  }

  /** This class handles the outermost XML context for the feed document. */
  protected class OuterContextClass extends XMLContext
  {
    /** Keep track of the number of valid feed signals we saw */
    protected int outerTagCount = 0;
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;
    /** Flag indicating the the rescan time was set for this feed */
    protected boolean rescanTimeSet = false;

    public OuterContextClass(XMLStream theStream, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    /** Check if feed was valid */
    public void checkIfValidFeed()
    {
      if (outerTagCount == 0)
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: RSS document '"+documentIdentifier+"' does not have rss, feed, or rdf:RDF tag - not valid feed");
      }
    }

    /** Check if the rescan flag was set or not, and if not, make sure it gets set properly */
    public void setDefaultRescanTimeIfNeeded()
      throws ManifoldCFException
    {
      if (rescanTimeSet == false)
      {
        // Set it!
        // Need to set the requeue parameters appropriately, since otherwise the feed reverts to default document
        // rescan or expire behavior.
        long currentTime = System.currentTimeMillis();
        Long rescanTime = filter.getBadFeedRescanTime(currentTime);
        if (rescanTime == null)
          rescanTime = filter.getDefaultRescanTime(currentTime);

        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"' setting default rescan time to "+((rescanTime==null)?"null":rescanTime.toString()));

        activities.setDocumentScheduleBounds(documentIdentifier,rescanTime,rescanTime,null,null);
        rescanTimeSet = true;
      }
    }

    /** Handle the tag beginning to set the correct second-level parsing context */
    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      if (qName.equals("rss"))
      {
        // RSS feed detected
        outerTagCount++;
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("RSS: Parsed bottom-level XML for RSS document '"+documentIdentifier+"'");
        return new RSSContextClass(theStream,namespaceURI,localName,qName,atts,documentIdentifier,activities,filter);
      }
      else if (qName.equals("rdf:RDF"))
      {
        // RDF/Atom feed detected
        outerTagCount++;
        return new RDFContextClass(theStream,namespaceURI,localName,qName,atts,documentIdentifier,activities,filter);
      }
      else if (qName.equals("feed"))
      {
        // Basic feed detected
        outerTagCount++;
        return new FeedContextClass(theStream,namespaceURI,localName,qName,atts,documentIdentifier,activities,filter);
      }
      else if (qName.equals("urlset") || qName.equals("sitemapindex"))
      {
        // Sitemap detected
        outerTagCount++;
        return new UrlsetContextClass(theStream,namespaceURI,localName,qName,atts,documentIdentifier,activities,filter);
      }
      
      // The default action is to establish a new default context.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    /** Handle the tag ending */
    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext context = theStream.getContext();
      String tagName = context.getQname();
      if (tagName.equals("rss"))
      {
        rescanTimeSet = ((RSSContextClass)context).process();
      }
      else if (tagName.equals("rdf:RDF"))
      {
        rescanTimeSet = ((RDFContextClass)context).process();
      }
      else if (tagName.equals("feed"))
      {
        rescanTimeSet = ((FeedContextClass)context).process();
      }
      else if (tagName.equals("urlset") || tagName.equals("sitemapindex"))
      {
        rescanTimeSet = ((UrlsetContextClass)context).process();
      }
      else
        super.endTag();
    }

  }

  protected class RSSContextClass extends XMLContext
  {
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;
    /** Rescan time set flag */
    protected boolean rescanTimeSet = false;

    public RSSContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // Handle each channel
      if (qName.equals("channel"))
      {
        // Channel detected
        return new RSSChannelContextClass(theStream,namespaceURI,localName,qName,atts,documentIdentifier,activities,filter);
      }

      // Skip everything else.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      // If it's our channel tag, process global channel information
      XMLContext context = theStream.getContext();
      String tagName = context.getQname();
      if (tagName.equals("channel"))
      {
        rescanTimeSet = ((RSSChannelContextClass)context).process();
      }
      else
        super.endTag();
    }

    /** Process this data */
    protected boolean process()
      throws ManifoldCFException
    {
      return rescanTimeSet;
    }

  }

  protected class RSSChannelContextClass extends XMLContext
  {
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;

    /** TTL value is set on a per-channel basis */
    protected String ttlValue = null;

    public RSSChannelContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("ttl"))
      {
        // TTL value seen.  Prepare to record it, as a string.
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("item"))
      {
        // Item seen.  We don't need any of the attributes etc., but we need to start a new context.
        return new RSSItemContextClass(theStream,namespaceURI,localName,qName,atts,filter.getDechromedContentMode());
      }
      // Skip everything else.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("ttl"))
        // If the current context must be the TTL one, record its data value.
        ttlValue = ((XMLStringContext)theContext).getValue();
      else if (theTag.equals("item"))
      {
        // It's an item.
        RSSItemContextClass itemContext = (RSSItemContextClass)theContext;
        // Presumably, since we are done parsing, we've recorded all the information we need in the context, object including:
        // (1) File name (if any), containing dechromed content
        // (2) Link name(s)
        // (3) Pubdate
        // (4) Title
        // The job now is to pull this info out and call the activities interface appropriately.

        // NOTE: After this endTag() method is called, tagCleanup() will be called for the item context.  This should clean up
        // all dangling files etc. that need to be removed.
        // If an exception or error is thrown during the parse, this endTag() method will NOT be called, but the tagCleanup()
        // method will be called regardless.
        itemContext.process(documentIdentifier,activities,filter);
      }
      else
        super.endTag();
    }

    /** Process this data, return true if rescan time was set */
    protected boolean process()
      throws ManifoldCFException
    {
      // Deal with the ttlvalue, if it was found
      // Use the ttl value as a signal for when we ought to look at this feed again.  If not present, use the default.
      long currentTime = System.currentTimeMillis();
      Long rescanTime = filter.getDefaultRescanTime(currentTime);
      if (ttlValue != null)
      {
        try
        {
          int minutes = Integer.parseInt(ttlValue);
          long nextTime = currentTime + minutes * 60000L;
          rescanTime = new Long(nextTime);
          // Set the upper bound time; we want to scan the feeds aggressively.
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"', found a ttl value of "+ttlValue+"; setting refetch time accordingly");
        }
        catch (NumberFormatException e)
        {
          Logging.connectors.warn("RSS: RSS document '"+documentIdentifier+"' has illegal ttl value '"+ttlValue+"'");
        }
      }

      if (rescanTime != null)
      {
        Long minimumTime = filter.getMinimumRescanTime(currentTime);
        if (minimumTime != null)
        {
          if (rescanTime.longValue() < minimumTime.longValue())
            rescanTime = minimumTime;
        }
      }

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"' setting rescan time to "+((rescanTime==null)?"null":rescanTime.toString()));

      activities.setDocumentScheduleBounds(documentIdentifier,rescanTime,rescanTime,null,null);
      return true;
    }
  }

  protected class RSSItemContextClass extends XMLContext
  {
    protected int dechromedContentMode;
    protected String guidField = null;
    protected String linkField = null;
    protected String pubDateField = null;
    protected String titleField = null;
    protected String descriptionField = null;
    protected ArrayList categoryField = new ArrayList();
    protected File contentsFile = null;

    public RSSItemContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, int dechromedContentMode)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.dechromedContentMode = dechromedContentMode;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("link"))
      {
        // "link" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("guid"))
      {
        // "guid" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("pubDate"))
      {
        // "pubDate" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("title"))
      {
        // "title" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("category"))
      {
        // "category" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else
      {
        // Handle potentially longer fields.  Both "description" and "content" fields can potentially be large; they are thus
        // processed as temporary files.  But the dance is complicated because (a) we only want one PRIMARY content source,
        // and (b) we want access to the description field, if it is not used as primary content.
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (qName.equals("description"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (qName.equals("description"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          break;
        case DECHROMED_CONTENT:
          if (qName.equals("content"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          else if (qName.equals("description"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        default:
          break;
        }
        // Skip everything else.
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
    }

    /** Convert the individual sub-fields of the item context into their final forms */
    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("link"))
      {
        linkField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("guid"))
      {
        guidField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("pubDate"))
      {
        pubDateField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("title"))
      {
        titleField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("category"))
      {
        categoryField.add(((XMLStringContext)theContext).getValue());
      }
      else
      {
        // What we want is: (a) if dechromed mode is NONE, just put the description file in the description field; (b)
        // if dechromed mode is "description", put the description field in the primary content field; (c)
        // if dechromed mode is "content", put the content field in the primary content field, and the description field in the description field.
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (theTag.equals("description"))
          {
            descriptionField = ((XMLStringContext)theContext).getValue();
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (theTag.equals("description"))
          {
            // Content file has been written; retrieve it (being sure not to leak any files already hanging around!)
            tagCleanup();
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          break;
        case DECHROMED_CONTENT:
          if (theTag.equals("content"))
          {
            tagCleanup();
            // Retrieve content file
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          else if (theTag.equals("description"))
          {
            descriptionField = ((XMLStringContext)theContext).getValue();
          }
          break;
        default:
          break;
        }

        super.endTag();
      }
    }

    protected void tagCleanup()
      throws ManifoldCFException
    {
      // Delete the contents file if it is there.
      if (contentsFile != null)
      {
        contentsFile.delete();
        contentsFile = null;
      }
    }

    /** Process the data accumulated for this item */
    public void process(String documentIdentifier, IProcessActivity activities, Filter filter)
      throws ManifoldCFException
    {
      if (linkField == null || linkField.length() == 0)
        linkField = guidField;

      if (linkField != null && linkField.length() > 0)
      {
        Long origDate = null;
        if (pubDateField != null && pubDateField.length() > 0)
        {
          origDate = parseRSSDate(pubDateField);
          // Special for China Daily News
          if (origDate == null)
            origDate = parseChinaDate(pubDateField);
        }

        String[] links = linkField.split(", ");
        int l = 0;
        while (l < links.length)
        {
          String rawURL = links[l++].trim();
          // Process the link
          String newIdentifier = makeDocumentIdentifier(filter.getCanonicalizationPolicies(),documentIdentifier,rawURL);
          if (newIdentifier != null)
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"', found a link to '"+newIdentifier+"', which has origination date "+
              ((origDate==null)?"null":origDate.toString()));
            if (filter.isLegalURL(newIdentifier))
            {
              if (contentsFile == null)
              {
                // It's a reference!  Add it.
                String[] dataNames = new String[]{"pubdate","title","source","category","description"};
                String[][] dataValues = new String[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                dataValues[3] = new String[categoryField.size()];
                int q = 0;
                while (q < categoryField.size())
                {
                  (dataValues[3])[q] = (String)categoryField.get(q);
                  q++;
                }
                if (descriptionField != null)
                  dataValues[4] = new String[]{descriptionField};
                // Add document reference, not including the data to pass down, but including a description
                activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
              }
              else
              {
                // The issue here is that if a document is ingested without a jobqueue entry, the document will not
                // be cleaned up if the job is deleted; nor is there any expiration possibility.  So, we really do need to make
                // sure a jobqueue entry gets created somehow.  Therefore I can't just ingest the document
                // right here.

                // Since the dechromed data is available from the feed, the possibility remains of passing the document

                // Now, set up the carrydown info
                String[] dataNames = new String[]{"pubdate","title","source","category","data","description"};
                Object[][] dataValues = new Object[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                dataValues[3] = new String[categoryField.size()];
                int q = 0;
                while (q < categoryField.size())
                {
                  (dataValues[3])[q] = (String)categoryField.get(q);
                  q++;
                }

                if (descriptionField != null)
                  dataValues[5] = new String[]{descriptionField};

                CharacterInput ci = new TempFileCharacterInput(contentsFile);
                try
                {
                  contentsFile = null;
                  dataValues[4] = new Object[]{ci};

                  // Add document reference, including the data to pass down, and the dechromed content too
                  activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
                }
                finally
                {
                  ci.discard();
                }
              }
            }
            else
            {
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: Identifier '"+newIdentifier+"' is excluded");
            }
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"', found an unincluded URL '"+rawURL+"'");
          }
        }
      }
    }
  }

  protected class RDFContextClass extends XMLContext
  {
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;

    /** ttl value */
    protected String ttlValue = null;

    public RDFContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("ttl"))
      {
        // TTL value seen.  Prepare to record it, as a string.
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("item"))
      {
        // Item seen.  We don't need any of the attributes etc., but we need to start a new context.
        return new RDFItemContextClass(theStream,namespaceURI,localName,qName,atts,filter.getDechromedContentMode());
      }
      // Skip everything else.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("ttl"))
        // If the current context must be the TTL one, record its data value.
        ttlValue = ((XMLStringContext)theContext).getValue();
      else if (theTag.equals("item"))
      {
        // It's an item.
        RDFItemContextClass itemContext = (RDFItemContextClass)theContext;
        // Presumably, since we are done parsing, we've recorded all the information we need in the context, object including:
        // (1) File name (if any), containing dechromed content
        // (2) Link name(s)
        // (3) Pubdate
        // (4) Title
        // The job now is to pull this info out and call the activities interface appropriately.

        // NOTE: After this endTag() method is called, tagCleanup() will be called for the item context.  This should clean up
        // all dangling files etc. that need to be removed.
        // If an exception or error is thrown during the parse, this endTag() method will NOT be called, but the tagCleanup()
        // method will be called regardless.
        itemContext.process(documentIdentifier,activities,filter);
      }
      else
        super.endTag();
    }

    /** Process this data */
    protected boolean process()
      throws ManifoldCFException
    {
      // Deal with the ttlvalue, if it was found
      // Use the ttl value as a signal for when we ought to look at this feed again.  If not present, use the default.
      long currentTime = System.currentTimeMillis();
      Long rescanTime = filter.getDefaultRescanTime(currentTime);
      if (ttlValue != null)
      {
        try
        {
          int minutes = Integer.parseInt(ttlValue);
          long nextTime = currentTime + minutes * 60000L;
          rescanTime = new Long(nextTime);
          // Set the upper bound time; we want to scan the feeds aggressively.
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: In RDF document '"+documentIdentifier+"', found a ttl value of "+ttlValue+"; setting refetch time accordingly");
        }
        catch (NumberFormatException e)
        {
          Logging.connectors.warn("RSS: RDF document '"+documentIdentifier+"' has illegal ttl value '"+ttlValue+"'");
        }
      }

      if (rescanTime != null)
      {
        Long minimumTime = filter.getMinimumRescanTime(currentTime);
        if (minimumTime != null)
        {
          if (rescanTime.longValue() < minimumTime.longValue())
            rescanTime = minimumTime;
        }
      }

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: In RDF document '"+documentIdentifier+"' setting rescan time to "+((rescanTime==null)?"null":rescanTime.toString()));

      activities.setDocumentScheduleBounds(documentIdentifier,rescanTime,rescanTime,null,null);
      return true;
    }
  }

  protected class RDFItemContextClass extends XMLContext
  {
    protected int dechromedContentMode;
    protected String linkField = null;
    protected String pubDateField = null;
    protected String titleField = null;
    protected String descriptionField = null;
    protected File contentsFile = null;

    public RDFItemContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, int dechromedContentMode)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.dechromedContentMode = dechromedContentMode;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("link"))
      {
        // "link" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("dc:date"))
      {
        // "dc:date" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("title"))
      {
        // "title" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else
      {
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (qName.equals("description"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (qName.equals("description"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          break;
        case DECHROMED_CONTENT:
          if (qName.equals("dc:content"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          else if (qName.equals("description"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        default:
          break;
        }
        // Skip everything else.
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
    }

    /** Convert the individual sub-fields of the item context into their final forms */
    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("link"))
      {
        linkField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("dc:date"))
      {
        pubDateField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("title"))
      {
        titleField = ((XMLStringContext)theContext).getValue();
      }
      else
      {
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (theTag.equals("description"))
          {
            descriptionField = ((XMLStringContext)theContext).getValue();
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (theTag.equals("description"))
          {
            // Content file has been written; retrieve it (being sure not to leak any files already hanging around!)
            tagCleanup();
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          break;
        case DECHROMED_CONTENT:
          if (theTag.equals("dc:content"))
          {
            // Retrieve content file
            tagCleanup();
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          else if (theTag.equals("description"))
          {
            descriptionField = ((XMLStringContext)theContext).getValue();
          }
          break;
        default:
          break;
        }

        super.endTag();
      }
    }

    protected void tagCleanup()
      throws ManifoldCFException
    {
      // Delete the contents file if it is there.
      if (contentsFile != null)
      {
        contentsFile.delete();
        contentsFile = null;
      }
    }

    /** Process the data accumulated for this item */
    public void process(String documentIdentifier, IProcessActivity activities, Filter filter)
      throws ManifoldCFException
    {
      if (linkField != null && linkField.length() > 0)
      {
        Long origDate = null;
        if (pubDateField != null && pubDateField.length() > 0)
          origDate = parseZuluDate(pubDateField);

        String[] links = linkField.split(", ");
        int l = 0;
        while (l < links.length)
        {
          String rawURL = links[l++].trim();
          // Process the link
          String newIdentifier = makeDocumentIdentifier(filter.getCanonicalizationPolicies(),documentIdentifier,rawURL);
          if (newIdentifier != null)
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In RDF document '"+documentIdentifier+"', found a link to '"+newIdentifier+"', which has origination date "+
              ((origDate==null)?"null":origDate.toString()));
            if (filter.isLegalURL(newIdentifier))
            {
              if (contentsFile == null)
              {
                // It's a reference!  Add it.
                String[] dataNames = new String[]{"pubdate","title","source","description"};
                String[][] dataValues = new String[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                if (descriptionField != null)
                  dataValues[3] = new String[]{descriptionField};

                // Add document reference, including the data to pass down
                activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
              }
              else
              {
                // The issue here is that if a document is ingested without a jobqueue entry, the document will not
                // be cleaned up if the job is deleted; nor is there any expiration possibility.  So, we really do need to make
                // sure a jobqueue entry gets created somehow.  Therefore I can't just ingest the document
                // right here.

                // Now, set up the carrydown info
                String[] dataNames = new String[]{"pubdate","title","source","data","description"};
                Object[][] dataValues = new Object[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                if (descriptionField != null)
                  dataValues[4] = new String[]{descriptionField};
                CharacterInput ci = new TempFileCharacterInput(contentsFile);
                try
                {
                  contentsFile = null;
                  dataValues[3] = new Object[]{ci};

                  // Add document reference, including the data to pass down, and the dechromed content too
                  activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
                }
                finally
                {
                  ci.discard();
                }
              }
            }
            else
            {
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: Identifier '"+newIdentifier+"' is excluded");
            }
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In RSS document '"+documentIdentifier+"', found an unincluded URL '"+rawURL+"'");
          }
        }
      }
    }
  }

  protected class FeedContextClass extends XMLContext
  {
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;

    /** ttl value */
    protected String ttlValue = null;

    public FeedContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("ttl"))
      {
        // TTL value seen.  Prepare to record it, as a string.
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("entry"))
      {
        // Item seen.  We don't need any of the attributes etc., but we need to start a new context.
        return new FeedItemContextClass(theStream,namespaceURI,localName,qName,atts,filter.getDechromedContentMode());
      }
      // Skip everything else.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("ttl"))
        // If the current context must be the TTL one, record its data value.
        ttlValue = ((XMLStringContext)theContext).getValue();
      else if (theTag.equals("entry"))
      {
        // It's an item.
        FeedItemContextClass itemContext = (FeedItemContextClass)theContext;
        // Presumably, since we are done parsing, we've recorded all the information we need in the context, object including:
        // (1) File name (if any), containing dechromed content
        // (2) Link name(s)
        // (3) Pubdate
        // (4) Title
        // The job now is to pull this info out and call the activities interface appropriately.

        // NOTE: After this endTag() method is called, tagCleanup() will be called for the item context.  This should clean up
        // all dangling files etc. that need to be removed.
        // If an exception or error is thrown during the parse, this endTag() method will NOT be called, but the tagCleanup()
        // method will be called regardless.
        itemContext.process(documentIdentifier,activities,filter);
      }
      else
        super.endTag();
    }

    /** Process this data */
    protected boolean process()
      throws ManifoldCFException
    {
      // Deal with the ttlvalue, if it was found
      // Use the ttl value as a signal for when we ought to look at this feed again.  If not present, use the default.
      long currentTime = System.currentTimeMillis();
      Long rescanTime = filter.getDefaultRescanTime(currentTime);
      if (ttlValue != null)
      {
        try
        {
          int minutes = Integer.parseInt(ttlValue);
          long nextTime = currentTime + minutes * 60000L;
          rescanTime = new Long(nextTime);
          // Set the upper bound time; we want to scan the feeds aggressively.
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: In Atom document '"+documentIdentifier+"', found a ttl value of "+ttlValue+"; setting refetch time accordingly");
        }
        catch (NumberFormatException e)
        {
          Logging.connectors.warn("RSS: Atom document '"+documentIdentifier+"' has illegal ttl value '"+ttlValue+"'");
        }
      }

      if (rescanTime != null)
      {
        Long minimumTime = filter.getMinimumRescanTime(currentTime);
        if (minimumTime != null)
        {
          if (rescanTime.longValue() < minimumTime.longValue())
            rescanTime = minimumTime;
        }
      }

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: In Atom document '"+documentIdentifier+"' setting rescan time to "+((rescanTime==null)?"null":rescanTime.toString()));

      activities.setDocumentScheduleBounds(documentIdentifier,rescanTime,rescanTime,null,null);
      return true;
    }
  }

  protected class FeedItemContextClass extends XMLContext
  {
    protected int dechromedContentMode;
    protected String linkField = null;
    protected String pubDateField = null;
    protected String titleField = null;
    protected ArrayList categoryField = new ArrayList();
    protected File contentsFile = null;
    protected String descriptionField = null;

    public FeedItemContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, int dechromedContentMode)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.dechromedContentMode = dechromedContentMode;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "ttl" and "item", nothing else.
      if (qName.equals("link"))
      {
        // "link" tag
        linkField = atts.getValue("href");
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("published") || qName.equals("updated"))
      {
        // "published" pr "updated" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("title"))
      {
        // "title" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("category"))
      {
        String category = atts.getValue("term");
        if (category != null && category.length() > 0)
          categoryField.add(category);
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
      else
      {
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (qName.equals("subtitle"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (qName.equals("subtitle"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          break;
        case DECHROMED_CONTENT:
          if (qName.equals("content"))
          {
            try
            {
              File tempFile = File.createTempFile("_rssdata_","tmp");
              return new XMLFileContext(theStream,namespaceURI,localName,qName,atts,tempFile);
            }
            catch (java.net.SocketTimeoutException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
            catch (InterruptedIOException e)
            {
              throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
            }
            catch (IOException e)
            {
              throw new ManifoldCFException("IO exception creating temp file: "+e.getMessage(),e);
            }
          }
          else if (qName.equals("subtitle"))
          {
            return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
          }
          break;
        default:
          break;
        }
        // Skip everything else.
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
    }

    /** Convert the individual sub-fields of the item context into their final forms */
    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("published") || theTag.equals("updated"))
      {
        pubDateField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("title"))
      {
        titleField = ((XMLStringContext)theContext).getValue();
      }
      else
      {
        switch (dechromedContentMode)
        {
        case DECHROMED_NONE:
          if (theTag.equals("subtitle"))
          {
            titleField = ((XMLStringContext)theContext).getValue();
          }
          break;
        case DECHROMED_DESCRIPTION:
          if (theTag.equals("subtitle"))
          {
            // Content file has been written; retrieve it (being sure not to leak any files already hanging around!)
            tagCleanup();
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          break;
        case DECHROMED_CONTENT:
          if (theTag.equals("content"))
          {
            // Retrieve content file
            tagCleanup();
            contentsFile = ((XMLFileContext)theContext).getCompletedFile();
            return;
          }
          else if (theTag.equals("subtitle"))
          {
            titleField = ((XMLStringContext)theContext).getValue();
          }
          break;
        default:
          break;
        }

        super.endTag();
      }
    }

    protected void tagCleanup()
      throws ManifoldCFException
    {
      // Delete the contents file if it is there.
      if (contentsFile != null)
      {
        contentsFile.delete();
        contentsFile = null;
      }
    }

    /** Process the data accumulated for this item */
    public void process(String documentIdentifier, IProcessActivity activities, Filter filter)
      throws ManifoldCFException
    {
      if (linkField != null && linkField.length() > 0)
      {
        Long origDate = null;
        if (pubDateField != null && pubDateField.length() > 0)
          origDate = parseZuluDate(pubDateField);

        String[] links = linkField.split(", ");
        int l = 0;
        while (l < links.length)
        {
          String rawURL = links[l++].trim();
          // Process the link
          String newIdentifier = makeDocumentIdentifier(filter.getCanonicalizationPolicies(),documentIdentifier,rawURL);
          if (newIdentifier != null)
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In Atom document '"+documentIdentifier+"', found a link to '"+newIdentifier+"', which has origination date "+
              ((origDate==null)?"null":origDate.toString()));
            if (filter.isLegalURL(newIdentifier))
            {
              if (contentsFile == null)
              {
                // It's a reference!  Add it.
                String[] dataNames = new String[]{"pubdate","title","source","category","description"};
                String[][] dataValues = new String[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                dataValues[3] = new String[categoryField.size()];
                int q = 0;
                while (q < categoryField.size())
                {
                  (dataValues[3])[q] = (String)categoryField.get(q);
                  q++;
                }
                if (descriptionField != null)
                  dataValues[4] = new String[]{descriptionField};
                  
                // Add document reference, including the data to pass down
                activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
              }
              else
              {
                // The issue here is that if a document is ingested without a jobqueue entry, the document will not
                // be cleaned up if the job is deleted; nor is there any expiration possibility.  So, we really do need to make
                // sure a jobqueue entry gets created somehow.  Therefore I can't just ingest the document
                // right here.

                // Now, set up the carrydown info
                String[] dataNames = new String[]{"pubdate","title","source","category","data","description"};
                Object[][] dataValues = new Object[dataNames.length][];
                if (origDate != null)
                  dataValues[0] = new String[]{origDate.toString()};
                if (titleField != null)
                  dataValues[1] = new String[]{titleField};
                dataValues[2] = new String[]{documentIdentifier};
                dataValues[3] = new String[categoryField.size()];
                int q = 0;
                while (q < categoryField.size())
                {
                  (dataValues[3])[q] = (String)categoryField.get(q);
                  q++;
                }
                if (descriptionField != null)
                  dataValues[5] = new String[]{descriptionField};
                  
                CharacterInput ci = new TempFileCharacterInput(contentsFile);
                try
                {
                  contentsFile = null;

                  dataValues[4] = new Object[]{ci};

                  // Add document reference, including the data to pass down, and the dechromed content too
                  activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
                }
                finally
                {
                  ci.discard();
                }
              }
            }
            else
            {
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: Identifier '"+newIdentifier+"' is excluded");
            }
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In Atom document '"+documentIdentifier+"', found an unincluded URL '"+rawURL+"'");
          }
        }
      }
    }
  }
  
  protected class UrlsetContextClass extends XMLContext
  {
    /** The document identifier */
    protected String documentIdentifier;
    /** Activities interface */
    protected IProcessActivity activities;
    /** Filter */
    protected Filter filter;

    /** ttl value */
    protected String ttlValue = null;

    public UrlsetContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts, String documentIdentifier, IProcessActivity activities, Filter filter)
    {
      super(theStream,namespaceURI,localName,qName,atts);
      this.documentIdentifier = documentIdentifier;
      this.activities = activities;
      this.filter = filter;
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "url", nothing else.
      if (qName.equals("url") || qName.equals("sitemap"))
      {
        // Item seen.  We don't need any of the attributes etc., but we need to start a new context.
        return new UrlsetItemContextClass(theStream,namespaceURI,localName,qName,atts);
      }
      // Skip everything else.
      return super.beginTag(namespaceURI,localName,qName,atts);
    }

    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("url") || theTag.equals("sitemap"))
      {
        // It's an item.
        UrlsetItemContextClass itemContext = (UrlsetItemContextClass)theContext;
        // Presumably, since we are done parsing, we've recorded all the information we need in the context, object including:
        // (1) File name (if any), containing dechromed content
        // (2) Link name(s)
        // (3) Pubdate
        // (4) Title
        // The job now is to pull this info out and call the activities interface appropriately.

        // NOTE: After this endTag() method is called, tagCleanup() will be called for the item context.  This should clean up
        // all dangling files etc. that need to be removed.
        // If an exception or error is thrown during the parse, this endTag() method will NOT be called, but the tagCleanup()
        // method will be called regardless.
        itemContext.process(documentIdentifier,activities,filter);
      }
      else
        super.endTag();
    }

    /** Process this data */
    protected boolean process()
      throws ManifoldCFException
    {
      // Deal with the ttlvalue, if it was found
      // Use the ttl value as a signal for when we ought to look at this feed again.  If not present, use the default.
      long currentTime = System.currentTimeMillis();
      Long rescanTime = filter.getDefaultRescanTime(currentTime);
      if (ttlValue != null)
      {
        try
        {
          int minutes = Integer.parseInt(ttlValue);
          long nextTime = currentTime + minutes * 60000L;
          rescanTime = new Long(nextTime);
          // Set the upper bound time; we want to scan the feeds aggressively.
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("RSS: In SiteMap document '"+documentIdentifier+"', found a ttl value of "+ttlValue+"; setting refetch time accordingly");
        }
        catch (NumberFormatException e)
        {
          Logging.connectors.warn("RSS: SiteMap document '"+documentIdentifier+"' has illegal ttl value '"+ttlValue+"'");
        }
      }

      if (rescanTime != null)
      {
        Long minimumTime = filter.getMinimumRescanTime(currentTime);
        if (minimumTime != null)
        {
          if (rescanTime.longValue() < minimumTime.longValue())
            rescanTime = minimumTime;
        }
      }

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("RSS: In SiteMap document '"+documentIdentifier+"' setting rescan time to "+((rescanTime==null)?"null":rescanTime.toString()));

      activities.setDocumentScheduleBounds(documentIdentifier,rescanTime,rescanTime,null,null);
      return true;
    }
  }

  protected class UrlsetItemContextClass extends XMLContext
  {
    protected String linkField = null;
    protected String pubDateField = null;

    public UrlsetItemContextClass(XMLStream theStream, String namespaceURI, String localName, String qName, Attributes atts)
    {
      super(theStream,namespaceURI,localName,qName,atts);
    }

    protected XMLContext beginTag(String namespaceURI, String localName, String qName, Attributes atts)
      throws ManifoldCFException, ServiceInterruption
    {
      // The tags we care about are "loc" and "lastmod", nothing else.
      if (qName.equals("loc"))
      {
        // "loc" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else if (qName.equals("lastmod"))
      {
        // "lastmod" tag
        return new XMLStringContext(theStream,namespaceURI,localName,qName,atts);
      }
      else
      {
        // Skip everything else.
        return super.beginTag(namespaceURI,localName,qName,atts);
      }
    }

    /** Convert the individual sub-fields of the item context into their final forms */
    protected void endTag()
      throws ManifoldCFException, ServiceInterruption
    {
      XMLContext theContext = theStream.getContext();
      String theTag = theContext.getQname();
      if (theTag.equals("loc"))
      {
        linkField = ((XMLStringContext)theContext).getValue();
      }
      else if (theTag.equals("lastmod"))
      {
        pubDateField = ((XMLStringContext)theContext).getValue();
      }
      else
      {
        super.endTag();
      }
    }

    protected void tagCleanup()
      throws ManifoldCFException
    {
    }

    /** Process the data accumulated for this item */
    public void process(String documentIdentifier, IProcessActivity activities, Filter filter)
      throws ManifoldCFException
    {
      if (linkField != null && linkField.length() > 0)
      {
        Long origDate = null;
        if (pubDateField != null && pubDateField.length() > 0)
          origDate = parseZuluDate(pubDateField);

        String[] links = linkField.split(", ");
        int l = 0;
        while (l < links.length)
        {
          String rawURL = links[l++].trim();
          // Process the link
          String newIdentifier = makeDocumentIdentifier(filter.getCanonicalizationPolicies(),documentIdentifier,rawURL);
          if (newIdentifier != null)
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In SiteMap document '"+documentIdentifier+"', found a link to '"+newIdentifier+"', which has origination date "+
              ((origDate==null)?"null":origDate.toString()));
            if (filter.isLegalURL(newIdentifier))
            {
              // It's a reference!  Add it.
              String[] dataNames = new String[]{"pubdate","source"};
              String[][] dataValues = new String[dataNames.length][];
              if (origDate != null)
                dataValues[0] = new String[]{origDate.toString()};
              dataValues[1] = new String[]{documentIdentifier};
                  
              // Add document reference, including the data to pass down
              activities.addDocumentReference(newIdentifier,documentIdentifier,null,dataNames,dataValues,origDate);
            }
            else
            {
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("RSS: Identifier '"+newIdentifier+"' is excluded");
            }
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("RSS: In SiteMap document '"+documentIdentifier+"', found an unincluded URL '"+rawURL+"'");
          }
        }
      }
    }
  }

  // Month map
  protected static HashMap monthMap = new HashMap();
  static
  {
    monthMap.put("jan",new Integer(1));
    monthMap.put("feb",new Integer(2));
    monthMap.put("mar",new Integer(3));
    monthMap.put("apr",new Integer(4));
    monthMap.put("may",new Integer(5));
    monthMap.put("jun",new Integer(6));
    monthMap.put("jul",new Integer(7));
    monthMap.put("aug",new Integer(8));
    monthMap.put("sep",new Integer(9));
    monthMap.put("oct",new Integer(10));
    monthMap.put("nov",new Integer(11));
    monthMap.put("dec",new Integer(12));
  }

  /** Parse an RDF date */
  protected static Long parseZuluDate(String dateValue)
  {
    dateValue = dateValue.trim();
    // Format: YYYY-MM-DDTHH:MM:SSZ
    // 2007-11-11T05:00:00Z
    int index = dateValue.indexOf("-");
    if (index == -1)
      return null;
    String year = dateValue.substring(0,index);
    dateValue = dateValue.substring(index+1);
    index = dateValue.indexOf("-");
    if (index == -1)
      return null;
    String month = dateValue.substring(0,index);
    dateValue = dateValue.substring(index+1);
    index = dateValue.indexOf("T");
    String day;
    String hour = "0";
    String minute = "0";
    String second = "0";
    String timezone = "GMT";
    if (index != -1)
    {
      day = dateValue.substring(0,index);
      dateValue = dateValue.substring(index+1);
      index = dateValue.indexOf(":");
      if (index == -1)
        return null;
      hour = dateValue.substring(0,index);
      dateValue = dateValue.substring(index+1);
      index = dateValue.indexOf(":");
      if (index != -1)
      {
        minute = dateValue.substring(0,index);
        dateValue = dateValue.substring(index+1);
        if (dateValue.endsWith("Z"))
        {
          index = dateValue.indexOf("Z");
          if (index == -1)
            return null;
        }
        else
        {
          index = dateValue.indexOf("+");
          if (index == -1)
            index = dateValue.indexOf("-");
          if (index == -1)
            return null;
          timezone = "GMT"+dateValue.substring(index);
        }
        second = dateValue.substring(0,index);
      }
      else
      {
        minute = dateValue;
      }
    }
    else
    {
      day = dateValue;
    }

    // Now construct a calendar object from this
    TimeZone tz = TimeZone.getTimeZone(timezone);

    Calendar c = new GregorianCalendar(tz);
    try
    {
      int value = Integer.parseInt(year);
      c.set(Calendar.YEAR,value);

      value = Integer.parseInt(month);
      c.set(Calendar.MONTH,value-1);

      value = Integer.parseInt(day);
      c.set(Calendar.DAY_OF_MONTH,value);

      value = Integer.parseInt(hour);
      c.set(Calendar.HOUR_OF_DAY,value);

      value = Integer.parseInt(minute);
      c.set(Calendar.MINUTE,value);

      int index2 = second.indexOf(".");
      if (index2 != -1)
        second = second.substring(0,index2);

      value = Integer.parseInt(second);
      c.set(Calendar.SECOND,value);

      c.set(Calendar.MILLISECOND,0);
      return new Long(c.getTimeInMillis());
    }
    catch (NumberFormatException e)
    {
      return null;
    }


  }

  /** Parse a China Daily News date */
  protected static Long parseChinaDate(String dateValue)
  {
    dateValue = dateValue.trim();
    // Format: 2007/12/30 11:01
    int index;
    index = dateValue.indexOf("/");
    if (index == -1)
      return null;
    String year = dateValue.substring(0,index);
    dateValue = dateValue.substring(index+1);
    index = dateValue.indexOf("/");
    if (index == -1)
      return null;
    String month = dateValue.substring(0,index);
    dateValue = dateValue.substring(index+1);
    index = dateValue.indexOf(" ");
    String day;
    String hour = null;
    String minute = null;
    String second = null;
    if (index == -1)
      day = dateValue;
    else
    {
      day = dateValue.substring(0,index);
      dateValue = dateValue.substring(index+1);
      index = dateValue.indexOf(":");
      if (index == -1)
        return null;
      hour = dateValue.substring(0,index);
      dateValue = dateValue.substring(index+1);
      index = dateValue.indexOf(":");
      if (index != -1)
      {
        minute = dateValue.substring(0,index);
        dateValue = dateValue.substring(index+1);
        second = dateValue;
      }
      else
        minute = dateValue;
    }
    TimeZone tz = TimeZone.getTimeZone("GMT");
    Calendar c = new GregorianCalendar(tz);
    try
    {
      int value = Integer.parseInt(year);
      if (value < 1900)
        value += 1900;
      c.set(Calendar.YEAR,value);

      value = Integer.parseInt(month);
      c.set(Calendar.MONTH,value-1);

      value = Integer.parseInt(day);
      c.set(Calendar.DAY_OF_MONTH,value);

      if (hour != null)
        value = Integer.parseInt(hour);
      else
        value = 0;
      c.set(Calendar.HOUR_OF_DAY,value);

      if (minute != null)
        value = Integer.parseInt(minute);
      else
        value = 0;
      c.set(Calendar.MINUTE,value);

      if (second != null)
        value = Integer.parseInt(second);
      else
        value = 0;
      c.set(Calendar.SECOND,value);

      c.set(Calendar.MILLISECOND,0);
      return new Long(c.getTimeInMillis());
    }
    catch (NumberFormatException e)
    {
      return null;
    }

  }

  /** Timezone mapping from RFC822 timezones to ones understood by Java */
  protected static final HashMap milTzMap;
  static
  {
    milTzMap = new HashMap();
    milTzMap.put("Z","GMT");
    milTzMap.put("UT","GMT");
    milTzMap.put("A","GMT-01:00");
    milTzMap.put("M","GMT-12:00");
    milTzMap.put("N","GMT+01:00");
    milTzMap.put("Y","GMT+12:00");
  }

  /** Parse an RSS date */
  protected static Long parseRSSDate(String dateValue)
  {
    dateValue = dateValue.trim();
    // See http://www.faqs.org/rfcs/rfc822.html for legal formats
    // Format: [day of week,] day mo year hh24:mm:ss tz
    int commaIndex = dateValue.indexOf(",");
    String usable;
    if (commaIndex == -1)
      usable = dateValue;
    else
      usable = dateValue.substring(commaIndex+1).trim();
    int index;

    index = usable.indexOf(" ");
    if (index == -1)
      return null;
    String day = usable.substring(0,index);
    usable = usable.substring(index+1).trim();

    index = usable.indexOf(" ");
    if (index == -1)
      return null;
    String month = usable.substring(0,index).toLowerCase();
    usable = usable.substring(index+1).trim();

    String year;
    String hour = null;
    String minute = null;
    String second = null;
    String timezone = null;

    index = usable.indexOf(" ");
    if (index != -1)
    {
      year = usable.substring(0,index);
      usable = usable.substring(index+1).trim();

      index = usable.indexOf(":");
      if (index == -1)
        return null;
      hour = usable.substring(0,index);
      usable = usable.substring(index+1).trim();

      index = usable.indexOf(":");
      if (index != -1)
      {
        minute = usable.substring(0,index);
        usable = usable.substring(index+1).trim();

        index = usable.indexOf(" ");
        if (index == -1)
          second = usable;
        else
        {
          second = usable.substring(0,index);
          timezone = usable.substring(index+1).trim();
        }
      }
      else
      {
        index = usable.indexOf(" ");
        if (index == -1)
          minute = usable;
        else
        {
          minute = usable.substring(0,index);
          timezone = usable.substring(index+1).trim();
        }
      }
    }
    else
      year = usable;

    // Now construct a calendar object from this
    TimeZone tz;
    if (timezone != null && timezone.length() > 0)
    {
      if (timezone.startsWith("+") || timezone.startsWith("-"))
      {
        if (timezone.indexOf(":") == -1 && timezone.length() > 3)
          timezone = timezone.substring(0,timezone.length()-2) + ":" + timezone.substring(timezone.length()-2);
        timezone = "GMT"+timezone;
      }
      else
      {
        // Map special timezones to java timezones
        if (milTzMap.get(timezone) != null)
          timezone = (String)milTzMap.get(timezone);
      }

    }
    else
      timezone = "GMT";


    tz = TimeZone.getTimeZone(timezone);

    Calendar c = new GregorianCalendar(tz);
    try
    {
      int value = Integer.parseInt(year);
      if (value < 1900)
        value += 1900;
      c.set(Calendar.YEAR,value);

      Integer x = (Integer)monthMap.get(month);
      if (x == null)
        return null;
      c.set(Calendar.MONTH,x.intValue()-1);

      value = Integer.parseInt(day);
      c.set(Calendar.DAY_OF_MONTH,value);

      if (hour != null)
        value = Integer.parseInt(hour);
      else
        value = 0;
      c.set(Calendar.HOUR_OF_DAY,value);

      if (minute != null)
        value = Integer.parseInt(minute);
      else
        value = 0;
      c.set(Calendar.MINUTE,value);

      if (second != null)
        value = Integer.parseInt(second);
      else
        value = 0;
      c.set(Calendar.SECOND,value);

      c.set(Calendar.MILLISECOND,0);
      return new Long(c.getTimeInMillis());
    }
    catch (NumberFormatException e)
    {
      return null;
    }

  }

  /** Get the maximum number of documents to amalgamate together into one batch, for this connector.
  *@return the maximum number. 0 indicates "unlimited".
  */
  public int getMaxDocumentRequest()
  {
    // RSS and the web in general do not batch well.  Multiple chunks have no advantage over one-at-a-time requests.
    return 1;
  }

  // Protected methods and classes

  /** Code to check if data is interesting, based on response code and content type.
  */
  protected boolean isContentInteresting(IFingerprintActivity activities, String contentType)
    throws ServiceInterruption, ManifoldCFException
  {
    // Look at the content type and decide if it's a kind we want.  This is defined
    // as something we think we can either ingest, or extract links from.

    // For now, we're only going to attempt to extract links from html.  This will change eventually.
    // But the check here is just what the content type is.
    if (contentType == null)
      return false;

    int pos = contentType.indexOf(";");
    if (pos != -1)
      contentType = contentType.substring(0,pos);
    contentType = contentType.trim();
    
    return activities.checkMimeTypeIndexable(contentType);
  }

  /** Code to check if an already-fetched document should be ingested.
  */
  protected boolean isDataIngestable(IFingerprintActivity activities, String documentIdentifier)
    throws ServiceInterruption, ManifoldCFException
  {
    if (activities.checkLengthIndexable(cache.getDataLength(documentIdentifier)) == false)
      return false;

    if (activities.checkURLIndexable(documentIdentifier) == false)
      return false;

    // Check if it's a recognized content type
    String contentType = cache.getContentType(documentIdentifier);

    // Some sites have multiple content types.  We just look at the LAST one in that case.
    if (contentType != null)
    {
      String[] contentTypes = contentType.split(",");
      if (contentTypes.length > 0)
        contentType = contentTypes[contentTypes.length-1].trim();
      else
        contentType = null;
    }

    if (contentType == null)
      return false;

    int pos = contentType.indexOf(";");
    if (pos != -1)
      contentType = contentType.substring(0,pos);
    contentType = contentType.trim();

    return activities.checkMimeTypeIndexable(contentType);
  }

  /** Given the current parameters, find the correct throttled fetcher object
  * (or create one if not there).
  */
  protected ThrottledFetcher getFetcher()
  {
    synchronized (fetcherMap)
    {
      ThrottledFetcher tf = (ThrottledFetcher)fetcherMap.get(throttleGroupName);
      if (tf == null)
      {
        tf = new ThrottledFetcher();
        fetcherMap.put(throttleGroupName,tf);
      }
      return tf;
    }
  }

  /** Given the current parameters, find the correct robots object (or create
  * one if none found).
  */
  protected Robots getRobots(ThrottledFetcher fetcher)
  {
    synchronized (robotsMap)
    {
      Robots r = (Robots)robotsMap.get(throttleGroupName);
      if (r == null)
      {
        r = new Robots(fetcher);
        robotsMap.put(throttleGroupName,r);
      }
      return r;
    }
  }

  // Protected classes

  /** Name/value class */
  protected static class NameValue
  {
    protected String name;
    protected String value;

    public NameValue(String name, String value)
    {
      this.name = name;
      this.value = value;
    }

    public String getName()
    {
      return name;
    }

    public String getValue()
    {
      return value;
    }
  }

  /** Evaluator token.
  */
  protected static class EvaluatorToken
  {
    public final static int TYPE_GROUP = 0;
    public final static int TYPE_TEXT = 1;
    public final static int TYPE_COMMA = 2;

    public final static int GROUPSTYLE_NONE = 0;
    public final static int GROUPSTYLE_LOWER = 1;
    public final static int GROUPSTYLE_UPPER = 2;
    public final static int GROUPSTYLE_MIXED = 3;

    protected int type;
    protected int groupNumber = -1;
    protected int groupStyle = GROUPSTYLE_NONE;
    protected String textValue = null;

    public EvaluatorToken()
    {
      type = TYPE_COMMA;
    }

    public EvaluatorToken(int groupNumber, int groupStyle)
    {
      type = TYPE_GROUP;
      this.groupNumber = groupNumber;
      this.groupStyle = groupStyle;
    }

    public EvaluatorToken(String text)
    {
      type = TYPE_TEXT;
      this.textValue = text;
    }

    public int getType()
    {
      return type;
    }

    public int getGroupNumber()
    {
      return groupNumber;
    }

    public int getGroupStyle()
    {
      return groupStyle;
    }

    public String getTextValue()
    {
      return textValue;
    }

  }


  /** Token stream.
  */
  protected static class EvaluatorTokenStream
  {
    protected String text;
    protected int pos;
    protected EvaluatorToken token = null;

    /** Constructor.
    */
    public EvaluatorTokenStream(String text)
    {
      this.text = text;
      this.pos = 0;
    }

    /** Get current token.
    */
    public EvaluatorToken peek()
      throws ManifoldCFException
    {
      if (token == null)
      {
        token = nextToken();
      }
      return token;
    }

    /** Go on to next token.
    */
    public void advance()
    {
      token = null;
    }

    protected EvaluatorToken nextToken()
      throws ManifoldCFException
    {
      char x;
      // Fetch the next token
      while (true)
      {
        if (pos == text.length())
          return null;
        x = text.charAt(pos);
        if (x > ' ')
          break;
        pos++;
      }

      StringBuilder sb;

      if (x == '"')
      {
        // Parse text
        pos++;
        sb = new StringBuilder();
        while (true)
        {
          if (pos == text.length())
            break;
          x = text.charAt(pos);
          pos++;
          if (x == '"')
          {
            break;
          }
          if (x == '\\')
          {
            if (pos == text.length())
              break;
            x = text.charAt(pos++);
          }
          sb.append(x);
        }

        return new EvaluatorToken(sb.toString());
      }

      if (x == ',')
      {
        pos++;
        return new EvaluatorToken();
      }

      // Eat number at beginning
      sb = new StringBuilder();
      while (true)
      {
        if (pos == text.length())
          break;
        x = text.charAt(pos);
        if (x >= '0' && x <= '9')
        {
          sb.append(x);
          pos++;
          continue;
        }
        break;
      }
      String numberValue = sb.toString();
      int groupNumber = 0;
      if (numberValue.length() > 0)
        groupNumber = new Integer(numberValue).intValue();
      // Save the next char position
      int modifierPos = pos;
      // Go to the end of the word
      while (true)
      {
        if (pos == text.length())
          break;
        x = text.charAt(pos);
        if (x == ',' || x >= '0' && x <= '9' || x <= ' ' && x >= 0)
          break;
        pos++;
      }

      int style = EvaluatorToken.GROUPSTYLE_NONE;
      if (modifierPos != pos)
      {
        String modifier = text.substring(modifierPos,pos);
        if (modifier.startsWith("u"))
          style = EvaluatorToken.GROUPSTYLE_UPPER;
        else if (modifier.startsWith("l"))
          style = EvaluatorToken.GROUPSTYLE_LOWER;
        else if (modifier.startsWith("m"))
          style = EvaluatorToken.GROUPSTYLE_MIXED;
        else
          throw new ManifoldCFException("Unknown style: "+modifier);
      }
      return new EvaluatorToken(groupNumber,style);
    }
  }

  /** Class representing a URL regular expression match, for the purposes of determining canonicalization policy */
  protected static class CanonicalizationPolicy
  {
    protected Pattern matchPattern;
    protected boolean reorder;
    protected boolean removeJavaSession;
    protected boolean removeAspSession;
    protected boolean removePhpSession;
    protected boolean removeBVSession;

    public CanonicalizationPolicy(Pattern matchPattern, boolean reorder, boolean removeJavaSession, boolean removeAspSession,
      boolean removePhpSession, boolean removeBVSession)
    {
      this.matchPattern = matchPattern;
      this.reorder = reorder;
      this.removeJavaSession = removeJavaSession;
      this.removeAspSession = removeAspSession;
      this.removePhpSession = removePhpSession;
      this.removeBVSession = removeBVSession;
    }

    public boolean checkMatch(String url)
    {
      Matcher matcher = matchPattern.matcher(url);
      return matcher.find();
    }

    public boolean canReorder()
    {
      return reorder;
    }

    public boolean canRemoveJavaSession()
    {
      return removeJavaSession;
    }

    public boolean canRemoveAspSession()
    {
      return removeAspSession;
    }

    public boolean canRemovePhpSession()
    {
      return removePhpSession;
    }

    public boolean canRemoveBvSession()
    {
      return removeBVSession;
    }

  }

  /** Class representing a list of canonicalization rules */
  protected static class CanonicalizationPolicies
  {
    protected ArrayList rules = new ArrayList();

    public CanonicalizationPolicies()
    {
    }

    public void addRule(CanonicalizationPolicy rule)
    {
      rules.add(rule);
    }

    public CanonicalizationPolicy findMatch(String url)
    {
      int i = 0;
      while (i < rules.size())
      {
        CanonicalizationPolicy rule = (CanonicalizationPolicy)rules.get(i++);
        if (rule.checkMatch(url))
          return rule;
      }
      return null;
    }
  }

  /** Class representing a mapping rule */
  protected static class MappingRule
  {
    protected Pattern matchPattern;
    protected String evalExpression;

    public MappingRule(Pattern matchPattern, String evalExpression)
    {
      this.matchPattern = matchPattern;
      this.evalExpression = evalExpression;
    }

    public boolean checkMatch(String url)
    {
      Matcher matcher = matchPattern.matcher(url);
      return matcher.matches();
    }

    public String map(String url)
      throws ManifoldCFException
    {
      // Create a matcher, and attempt to do a match
      Matcher matcher = matchPattern.matcher(url);
      if (!matcher.matches())
      {
        return null;
      }

      // A match!  Now, interpret the output expression
      if (evalExpression == null || evalExpression.length() == 0)
        return url;

      StringBuilder sb = new StringBuilder();
      EvaluatorTokenStream et = new EvaluatorTokenStream(evalExpression);

      while (true)
      {
        EvaluatorToken t = et.peek();
        if (t == null)
          break;
        switch (t.getType())
        {
        case EvaluatorToken.TYPE_COMMA:
          et.advance();
          break;
        case EvaluatorToken.TYPE_GROUP:
          et.advance();
          String groupValue = matcher.group(t.getGroupNumber());
          switch (t.getGroupStyle())
          {
          case EvaluatorToken.GROUPSTYLE_NONE:
            sb.append(groupValue);
            break;
          case EvaluatorToken.GROUPSTYLE_LOWER:
            sb.append(groupValue.toLowerCase());
            break;
          case EvaluatorToken.GROUPSTYLE_UPPER:
            sb.append(groupValue.toUpperCase());
            break;
          case EvaluatorToken.GROUPSTYLE_MIXED:
            if (groupValue.length() > 0)
            {
              sb.append(groupValue.substring(0,1).toUpperCase());
              sb.append(groupValue.substring(1).toLowerCase());
            }
            break;
          default:
            throw new ManifoldCFException("Illegal group style");
          }
          break;
        case EvaluatorToken.TYPE_TEXT:
          et.advance();
          sb.append(t.getTextValue());
          break;
        default:
          throw new ManifoldCFException("Illegal token type");
        }
      }
      return sb.toString();
    }

  }

  /** Class that represents all mappings */
  protected static class MappingRules
  {
    protected ArrayList mappings = new ArrayList();

    public MappingRules()
    {
    }

    public void add(MappingRule rule)
    {
      mappings.add(rule);
    }

    public boolean isMatch(String url)
    {
      if (mappings.size() == 0)
        return true;
      int i = 0;
      while (i < mappings.size())
      {
        MappingRule p = (MappingRule)mappings.get(i);
        if (p.checkMatch(url))
          return true;
        i++;
      }
      return false;
    }

    public String map(String url)
      throws ManifoldCFException
    {
      if (mappings.size() == 0)
        return url;
      int i = 0;
      while (i < mappings.size())
      {
        MappingRule p = (MappingRule)mappings.get(i);
        String rval = p.map(url);
        if (rval != null)
          return rval;
        i++;
      }
      return null;
    }
  }

  /** Class that handles parsing and interpretation of the document specification.
  * Note that I believe it to be faster to do this once, gathering all the data, than to scan the document specification multiple times.
  * Therefore, this class contains the *entire* interpreted set of data from a document specification.
  */
  protected static class Filter
  {
    protected MappingRules mappings = new MappingRules();
    protected HashMap seeds = null;
    protected Integer defaultRescanInterval = null;
    protected Integer minimumRescanInterval = null;
    protected Integer badFeedRescanInterval = null;
    protected int dechromedContentMode = DECHROMED_NONE;
    protected int chromedContentMode = CHROMED_USE;
    protected int feedTimeoutValue = 60000;
    protected ArrayList metadata = new ArrayList();
    protected HashMap acls = new HashMap();
    protected CanonicalizationPolicies canonicalizationPolicies = new CanonicalizationPolicies();

    /** Constructor. */
    public Filter(DocumentSpecification spec, boolean warnOnBadSeed)
      throws ManifoldCFException
    {
      // To save allocation, preallocate the seeds map assuming that it will require 1.5x the number of nodes in the spec
      int initialSize = spec.getChildCount();
      if (initialSize == 0)
        initialSize = 1;
      seeds = new HashMap((initialSize * 3) >> 1);

      int i = 0;

      // First pass.  Find all of the rules (which are necessary to canonicalize the seeds, etc.)
      while (i < spec.getChildCount())
      {
        SpecificationNode n = spec.getChild(i++);
        if (n.getType().equals("map"))
        {
          String match = n.getAttributeValue("match");
          String map = n.getAttributeValue("map");
          if (match != null && match.length() > 0)
          {
            Pattern p;
            try
            {
              p = Pattern.compile(match);
            }
            catch (java.util.regex.PatternSyntaxException e)
            {
              throw new ManifoldCFException("Regular expression '"+match+"' is illegal: "+e.getMessage(),e);
            }
            if (map == null)
              map = "";
            mappings.add(new MappingRule(p,map));
          }
        }
        else if (n.getType().equals("urlspec"))
        {
          String urlRegexp = n.getAttributeValue("regexp");
          if (urlRegexp == null)
            urlRegexp = "";
          String reorder = n.getAttributeValue("reorder");
          boolean reorderValue;
          if (reorder == null)
            reorderValue = false;
          else
          {
            if (reorder.equals("yes"))
              reorderValue = true;
            else
              reorderValue = false;
          }

          String javaSession = n.getAttributeValue("javasessionremoval");
          boolean javaSessionValue;
          if (javaSession == null)
            javaSessionValue = false;
          else
          {
            if (javaSession.equals("yes"))
              javaSessionValue = true;
            else
              javaSessionValue = false;
          }

          String aspSession = n.getAttributeValue("aspsessionremoval");
          boolean aspSessionValue;
          if (aspSession == null)
            aspSessionValue = false;
          else
          {
            if (aspSession.equals("yes"))
              aspSessionValue = true;
            else
              aspSessionValue = false;
          }

          String phpSession = n.getAttributeValue("phpsessionremoval");
          boolean phpSessionValue;
          if (phpSession == null)
            phpSessionValue = false;
          else
          {
            if (phpSession.equals("yes"))
              phpSessionValue = true;
            else
              phpSessionValue = false;
          }

          String bvSession = n.getAttributeValue("bvsessionremoval");
          boolean bvSessionValue;
          if (bvSession == null)
            bvSessionValue = false;
          else
          {
            if (bvSession.equals("yes"))
              bvSessionValue = true;
            else
              bvSessionValue = false;
          }
          try
          {
            canonicalizationPolicies.addRule(new CanonicalizationPolicy(Pattern.compile(urlRegexp),reorderValue,javaSessionValue,aspSessionValue,
              phpSessionValue, bvSessionValue));
          }
          catch (java.util.regex.PatternSyntaxException e)
          {
            throw new ManifoldCFException("Canonicalization regular expression '"+urlRegexp+"' is illegal: "+e.getMessage(),e);
          }
        }
      }

      // Second pass.  Do the rest of the work,
      i = 0;
      while (i < spec.getChildCount())
      {
        SpecificationNode n = spec.getChild(i++);
        if (n.getType().equals("feed"))
        {
          String rssURL = n.getAttributeValue("url");
          if (rssURL != null && rssURL.length() > 0)
          {
            String canonicalURL = makeDocumentIdentifier(canonicalizationPolicies,null,rssURL);
            if (canonicalURL != null)
            {
              seeds.put(canonicalURL,canonicalURL);
            }
            else
            {
              if (warnOnBadSeed)
                Logging.connectors.warn("RSS: Illegal seed feed '"+rssURL+"'");
            }
          }
        }
        else if (n.getType().equals("metadata"))
        {
          String name = n.getAttributeValue("name");
          String value = n.getAttributeValue("value");
          if (name != null && name.length() > 0 && value != null && value.length() > 0)
            metadata.add(new NameValue(name,value));
        }
        else if (n.getType().equals("access"))
        {
          String token = n.getAttributeValue("token");
          acls.put(token,token);
        }
        else if (n.getType().equals("feedrescan"))
        {
          String interval = n.getAttributeValue("value");
          if (interval != null && interval.length() > 0)
          {
            try
            {
              defaultRescanInterval = new Integer(interval);
            }
            catch (NumberFormatException e)
            {
              throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
            }
          }
        }
        else if (n.getType().equals("minfeedrescan"))
        {
          String interval = n.getAttributeValue("value");
          if (interval != null && interval.length() > 0)
          {
            try
            {
              minimumRescanInterval = new Integer(interval);
            }
            catch (NumberFormatException e)
            {
              throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
            }
          }
        }
        else if (n.getType().equals("badfeedrescan"))
        {
          String interval = n.getAttributeValue("value");
          if (interval != null && interval.length() > 0)
          {
            try
            {
              badFeedRescanInterval = new Integer(interval);
            }
            catch (NumberFormatException e)
            {
              throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
            }
          }
        }
        else if (n.getType().equals("feedtimeout"))
        {
          String value = n.getAttributeValue("value");
          if (value != null && value.length() > 0)
          {
            try
            {
              feedTimeoutValue= Integer.parseInt(value) * 1000;
            }
            catch (NumberFormatException e)
            {
              throw new ManifoldCFException("Bad number: "+e.getMessage(),e);
            }
          }
        }
        else if (n.getType().equals("dechromedmode"))
        {
          String mode = n.getAttributeValue("mode");
          if (mode != null && mode.length() > 0)
          {
            if (mode.equals("none"))
              dechromedContentMode = DECHROMED_NONE;
            else if (mode.equals("description"))
              dechromedContentMode = DECHROMED_DESCRIPTION;
            else if (mode.equals("content"))
              dechromedContentMode = DECHROMED_CONTENT;
          }
        }
        else if (n.getType().equals("chromedmode"))
        {
          String mode = n.getAttributeValue("mode");
          if (mode != null && mode.length() > 0)
          {
            if (mode.equals("use"))
              chromedContentMode = CHROMED_USE;
            else if (mode.equals("skip"))
              chromedContentMode = CHROMED_SKIP;
          }
        }
      }
    }

    /** Check if document is a seed */
    public boolean isSeed(String canonicalUrl)
    {
      return seeds.get(canonicalUrl) != null;
    }

    /** Iterate over all canonicalized seeds */
    public Iterator getSeeds()
    {
      return seeds.keySet().iterator();
    }

    /** Get the specified metadata */
    public ArrayList getMetadata()
    {
      return metadata;
    }

    /** Get the acls */
    public String[] getAcls()
    {
      String[] rval = new String[acls.size()];
      Iterator iter = acls.keySet().iterator();
      int i = 0;
      while (iter.hasNext())
      {
        rval[i++] = (String)iter.next();
      }
      return rval;
    }

    /** Get the feed timeout value */
    public int getFeedTimeoutValue()
    {
      return feedTimeoutValue;
    }

    /** Get the dechromed content mode */
    public int getDechromedContentMode()
    {
      return dechromedContentMode;
    }

    /** Get the chromed content mode */
    public int getChromedContentMode()
    {
      return chromedContentMode;
    }

    /** Get the next time (by default) a feed should be scanned */
    public Long getDefaultRescanTime(long currentTime)
    {
      if (defaultRescanInterval == null)
        return null;
      return new Long(defaultRescanInterval.intValue() * 60000L + currentTime);
    }

    /** Get the minimum next time a feed should be scanned */
    public Long getMinimumRescanTime(long currentTime)
    {
      if (minimumRescanInterval == null)
        return null;
      return new Long(minimumRescanInterval.intValue() * 60000L + currentTime);
    }

    /** Get the next time a "bad feed" should be rescanned */
    public Long getBadFeedRescanTime(long currentTime)
    {
      if (badFeedRescanInterval == null)
        return null;
      return new Long(badFeedRescanInterval.intValue() * 60000L + currentTime);
    }

    /** Check for legality of a url.
    * @return true if the passed-in url is either a seed, or a legal url, according to this
    * filter.
    */
    public boolean isLegalURL(String url)
    {
      if (seeds.get(url) != null)
        return true;
      return mappings.isMatch(url);
    }

    /** Scan patterns and return the one that matches first.
    * @return null if the url doesn't match or should not be ingested, or the new string if it does.
    */
    public String mapDocumentURL(String url)
      throws ManifoldCFException
    {
      if (seeds.get(url) != null)
        return null;
      return mappings.map(url);
    }

    /** Get canonicalization policies */
    public CanonicalizationPolicies getCanonicalizationPolicies()
    {
      return canonicalizationPolicies;
    }
  }

}


