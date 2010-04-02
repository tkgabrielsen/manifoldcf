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
package org.apache.lcf.crawler.connectors.sharedrive;

import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;

import jcifs.smb.ACE;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileFilter;

import org.apache.lcf.agents.interfaces.RepositoryDocument;
import org.apache.lcf.agents.interfaces.ServiceInterruption;
import org.apache.lcf.core.interfaces.ConfigParams;
import org.apache.lcf.core.interfaces.LCFException;
import org.apache.lcf.crawler.interfaces.DocumentSpecification;
import org.apache.lcf.crawler.interfaces.IDocumentIdentifierStream;
import org.apache.lcf.crawler.interfaces.IProcessActivity;
import org.apache.lcf.crawler.interfaces.IFingerprintActivity;
import org.apache.lcf.core.interfaces.SpecificationNode;
import org.apache.lcf.crawler.interfaces.IVersionActivity;
import org.apache.lcf.crawler.system.Logging;

/** This is the "repository connector" for a smb/cifs shared drive file system.  It's a relative of the share crawler, and should have
* comparable basic functionality.
*/
public class SharedDriveConnector extends org.apache.lcf.crawler.connectors.BaseRepositoryConnector
{
  public static final String _rcsid = "@(#)$Id$";

  // Activities we log
  public final static String ACTIVITY_ACCESS = "access";

  // These are the share connector nodes and attributes in the document specification
  public static final String NODE_STARTPOINT = "startpoint";
  public static final String NODE_INCLUDE = "include";
  public static final String NODE_EXCLUDE = "exclude";
  public static final String NODE_PATHNAMEATTRIBUTE = "pathnameattribute";
  public static final String NODE_PATHMAP = "pathmap";
  public static final String NODE_FILEMAP = "filemap";
  public static final String NODE_URIMAP = "urimap";
  public static final String NODE_SHAREACCESS = "shareaccess";
  public static final String NODE_SHARESECURITY = "sharesecurity";
  public static final String NODE_MAXLENGTH = "maxlength";
  public static final String NODE_ACCESS = "access";
  public static final String NODE_SECURITY = "security";
  public static final String ATTRIBUTE_PATH = "path";
  public static final String ATTRIBUTE_TYPE = "type";
  public static final String ATTRIBUTE_INDEXABLE = "indexable";
  public static final String ATTRIBUTE_FILESPEC = "filespec";
  public static final String ATTRIBUTE_VALUE = "value";
  public static final String ATTRIBUTE_TOKEN = "token";
  public static final String ATTRIBUTE_MATCH = "match";
  public static final String ATTRIBUTE_REPLACE = "replace";
  public static final String VALUE_DIRECTORY = "directory";
  public static final String VALUE_FILE = "file";

  private String smbconnectionPath = null;
  private String server = null;
  private String domain = null;
  private String username = null;
  private String password = null;

  private NtlmPasswordAuthentication pa;

  /** Deny access token for default authority */
  private final static String defaultAuthorityDenyToken = "McAdAuthority_MC_DEAD_AUTHORITY";

  /** Constructor.
  */
  public SharedDriveConnector()
  {
  }

  /** Establish a "session".  In the case of the jcifs connector, this just builds the appropriate smbconnectionPath string, and does the necessary checks. */
  protected void getSession()
    throws LCFException
  {
    if (smbconnectionPath == null)
    {
      // Get the server
      if (server == null || server.length() == 0)
        throw new LCFException("Missing parameter '"+SharedDriveParameters.server+"'");

      // make the smb connection to the server
      String authenticationString;
      if (domain == null || domain.length() == 0)
        authenticationString = username + ":" + password;
      else
        authenticationString = domain + ";" + username + ":" + password;

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("Connecting to: " + "smb://" + authenticationString.substring(0,authenticationString.indexOf(":")+1) + "<password>@" + server + "/");

      try
      {
        // use NtlmPasswordAuthentication so that we can reuse credential for DFS support
        pa = new NtlmPasswordAuthentication(authenticationString);
        SmbFile smbconnection = new SmbFile("smb://" + server + "/",pa);
        smbconnectionPath = getFileCanonicalPath(smbconnection);
      }
      catch (MalformedURLException e)
      {
        Logging.connectors.error("Unable to access SMB/CIFS share: "+"smb://" + authenticationString.substring(0,authenticationString.indexOf(":")+1) + "<password>@" + server + "/\n" + e);
        throw new LCFException("Unable to access SMB/CIFS share: "+server, e, LCFException.REPOSITORY_CONNECTION_ERROR);
      }
    }
  }

  /** Return the list of activities that this connector supports (i.e. writes into the log).
  *@return the list.
  */
  public String[] getActivitiesList()
  {
    return new String[]{ACTIVITY_ACCESS};
  }

  /** Return the path for the UI interface JSP elements.
  * These JSP's must be provided to allow the connector to be configured, and to
  * permit it to present document filtering specification information in the UI.
  * This method should return the name of the folder, under the <webapp>/connectors/
  * area, where the appropriate JSP's can be found.  The name should NOT have a slash in it.
  *@return the folder part
  */
  public String getJSPFolder()
  {
    return "sharedrive";
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  public void disconnect()
    throws LCFException
  {
    server = null;
    domain = null;
    username = null;
    password = null;
    pa = null;
    smbconnectionPath = null;
    super.disconnect();
  }

  /** Connect.
  *@param configParameters is the set of configuration parameters, which
  * in this case describe the root directory.
  */
  public void connect(ConfigParams configParameters)
  {
    super.connect(configParameters);

    // Get the server
    server = configParameters.getParameter(SharedDriveParameters.server);
    domain   = configParameters.getParameter(SharedDriveParameters.domain);
    username = configParameters.getParameter(SharedDriveParameters.username);
    if (username == null)
      username = "";
    password = configParameters.getObfuscatedParameter(SharedDriveParameters.password);
    if (password == null)
      password = "";

    // Rejigger the username/domain to be sure we PASS in a domain and we do not include the domain attached to the user!
    // (This became essential at jcifs 1.3.0)
    int index = username.indexOf("@");
    if (index != -1)
    {
      // Strip off the domain from the user
      String userDomain = username.substring(index+1);
      if (domain == null || domain.length() == 0)
        domain = userDomain;
      username = username.substring(0,index);
    }
    index = username.indexOf("\\");
    if (index != -1)
    {
      String userDomain = username.substring(0,index);
      if (domain == null || domain.length() == 0)
        domain = userDomain;
      username = username.substring(index+1);
    }
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
  public String[] getBinNames(String documentIdentifier)
  {
    return new String[]{server};
  }

  /**
  * Convert a document identifier to a URI. The URI is the URI that will be
  * the unique key from the search index, and will be presented to the user
  * as part of the search results.
  *
  * @param documentIdentifier
  *            is the document identifier.
  * @return the document uri.
  */
  protected static String convertToURI(String documentIdentifier, MatchMap fileMap, MatchMap uriMap)
    throws LCFException
  {
    //
    // Note well: This MUST be a legal URI!!
    // e.g.
    // smb://10.33.65.1/Test Folder/PPT Docs/Dearman_University of Texas 20030220.ppt
    // file:////10.33.65.1/Test Folder/PPT Docs/Dearman_University of Texas 20030220.ppt

    String serverPath = documentIdentifier.substring("smb://".length());

    // The first mapping converts one server path to another.
    // If not present, we leave the original path alone.
    serverPath = fileMap.translate(serverPath);

    // The second mapping, if present, creates a URI, using certain rules.  If not present, the old standard IRI conversion is done.
    if (uriMap.getMatchCount() != 0)
    {
      // URI translation.
      // First step is to perform utf-8 translation and %-encoding.
      try
      {
        byte[] byteArray = serverPath.getBytes("utf-8");
        StringBuffer output = new StringBuffer();
        int i = 0;
        while (i < byteArray.length)
        {
          int x = ((int)byteArray[i++]) & 0xff;
          if (x >= 0x80 || (x >= 0 && x <= ' ') || x == ':' || x == '?' || x == '^' || x == '{' || x == '}' ||
            x == '%' || x == '#' || x == '`' || x == ';' || x == '@' || x == '&' || x == '=' || x == '+' ||
            x == '$' || x == ',')
          {
            output.append('%');
            String hexValue = Integer.toHexString((int)x).toUpperCase();
            if (hexValue.length() == 1)
              output.append('0');
            output.append(hexValue);
          }
          else
            output.append((char)x);
        }

        // Second step is to perform the mapping.  This strips off the server name and glues on the protocol and web server name, most likely.
        return uriMap.translate(output.toString());
      }
      catch (java.io.UnsupportedEncodingException e)
      {
        // Should not happen...
        throw new LCFException(e.getMessage(),e);
      }
    }
    else
    {
      // Convert to a URI that begins with file://///.  This used to be done according to the following IE7 specification:
      //   http://blogs.msdn.com/ie/archive/2006/12/06/file-uris-in-windows.aspx
      // However, two factors required change.  First, IE8 decided to no longer adhere to the same specification as IE7.
      // Second, the ingestion API does not (and will never) accept anything other than a well-formed URI.  Thus, file
      // specifications are ingested in a canonical form (which happens to be pretty much what this connector used prior to
      // 3.9.0), and the various clients are responsible for converting that form into something the browser will accept.
      try
      {
        StringBuffer output = new StringBuffer();

        int i = 0;
        while (i < serverPath.length())
        {
          int pos = serverPath.indexOf("/",i);
          if (pos == -1)
            pos = serverPath.length();
          String piece = serverPath.substring(i,pos);
          // Note well.  This does *not* %-encode some characters such as '#', which are legal in URI's but have special meanings!
          String replacePiece = java.net.URLEncoder.encode(piece,"utf-8");
          // Convert the +'s back to %20's
          int j = 0;
          while (j < replacePiece.length())
          {
            int plusPos = replacePiece.indexOf("+",j);
            if (plusPos == -1)
              plusPos = replacePiece.length();
            output.append(replacePiece.substring(j,plusPos));
            if (plusPos < replacePiece.length())
            {
              output.append("%20");
              plusPos++;
            }
            j = plusPos;
          }

          if (pos < serverPath.length())
          {
            output.append("/");
            pos++;
          }
          i = pos;
        }
        return "file://///"+output.toString();
      }
      catch (java.io.UnsupportedEncodingException e)
      {
        // Should not happen...
        throw new LCFException(e.getMessage(),e);
      }
    }
  }


  /** Given a document specification, get either a list of starting document identifiers (seeds),
  * or a list of changes (deltas), depending on whether this is a "crawled" connector or not.
  * These document identifiers will be loaded into the job's queue at the beginning of the
  * job's execution.
  * This method can return changes only (because it is provided a time range).  For full
  * recrawls, the start time is always zero.
  * Note that it is always ok to return MORE documents rather than less with this method.
  *@param spec is a document specification (that comes from the job).
  *@param startTime is the beginning of the time range to consider, inclusive.
  *@param endTime is the end of the time range to consider, exclusive.
  *@return the stream of local document identifiers that should be added to the queue.
  */
  public IDocumentIdentifierStream getDocumentIdentifiers(DocumentSpecification spec, long startTime, long endTime)
    throws LCFException, ServiceInterruption
  {
    getSession();
    return new IdentifierStream(spec);
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
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  *@param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
  *@return the corresponding version strings, with null in the places where the document no longer exists.
  * Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
  * will always be processed.
  */
  public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
    DocumentSpecification spec, int jobMode, boolean usesDefaultAuthority)
    throws LCFException, ServiceInterruption
  {
    getSession();
    // Read the forced acls.  A null return indicates that security is disabled!!!
    // A zero-length return indicates that the native acls should be used.
    // All of this is germane to how we ingest the document, so we need to note it in
    // the version string completely.
    String[] acls = getForcedAcls(spec);
    String[] shareAcls = getForcedShareAcls(spec);

    String pathAttributeName = null;
    MatchMap matchMap = new MatchMap();
    MatchMap fileMap = new MatchMap();
    MatchMap uriMap = new MatchMap();

    int i = 0;
    while (i < spec.getChildCount())
    {
      SpecificationNode n = spec.getChild(i++);
      if (n.getType().equals(NODE_PATHNAMEATTRIBUTE))
        pathAttributeName = n.getAttributeValue(ATTRIBUTE_VALUE);
      else if (n.getType().equals(NODE_PATHMAP))
      {
        // Path mapping info also needs to be looked at, because it affects what is
        // ingested.
        String pathMatch = n.getAttributeValue(ATTRIBUTE_MATCH);
        String pathReplace = n.getAttributeValue(ATTRIBUTE_REPLACE);
        matchMap.appendMatchPair(pathMatch,pathReplace);
      }
      else if (n.getType().equals(NODE_FILEMAP))
      {
        String pathMatch = n.getAttributeValue(ATTRIBUTE_MATCH);
        String pathReplace = n.getAttributeValue(ATTRIBUTE_REPLACE);
        fileMap.appendMatchPair(pathMatch,pathReplace);
      }
      else if (n.getType().equals(NODE_URIMAP))
      {
        String pathMatch = n.getAttributeValue(ATTRIBUTE_MATCH);
        String pathReplace = n.getAttributeValue(ATTRIBUTE_REPLACE);
        uriMap.appendMatchPair(pathMatch,pathReplace);
      }
    }

    String[] rval = new String[documentIdentifiers.length];
    String documentIdentifier = null;
    i = 0;
    while (i < rval.length)
    {
      documentIdentifier = documentIdentifiers[i];
      try
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("JCIFS: getVersions(): documentIdentifiers[" + i + "] is: " + documentIdentifier);
        SmbFile file = new SmbFile(documentIdentifier,pa);

        // File has to exist AND have a non-null canonical path to be readable.  If the canonical path is
        // null, it means that the windows permissions are not right and directory/file is not readable!!!
        String newPath = getFileCanonicalPath(file);
        // We MUST check the specification here, otherwise a recrawl may not delete what it's supposed to!
        if (fileExists(file) && newPath != null && checkInclude(file,newPath,spec))
        {
          if (fileIsDirectory(file))
          {
            // It's a directory. The version ID will be the
            // last modified date.
            long lastModified = fileLastModified(file);
            rval[i] = new Long(lastModified).toString();

          }
          else
          {
            // It's a file of acceptable length.
            // The ability to get ACLs, list files, and an inputstream under DFS all work now.

            // The format of this string changed on 11/8/2006 to be comformant with the standard way
            // acls and metadata descriptions are being stuffed into the version string across connectors.

            // The format of this string changed again on 7/3/2009 to permit the ingestion uri/iri to be included.
            // This was to support filename/uri mapping functionality.

            StringBuffer sb = new StringBuffer();

            // Parseable stuff goes first.  There's no metadata for jcifs, so this will just be the acls
            describeDocumentSecurity(sb,file,acls,shareAcls);

            // Include the path attribute name and value in the parseable area.
            if (pathAttributeName != null)
            {
              sb.append('+');
              pack(sb,pathAttributeName,'+');
              // Calculate path string; we'll include that wholesale in the version
              String pathAttributeValue = documentIdentifier;
              // 3/13/2008
              // In looking at what comes into the path metadata attribute by default, and cogitating a bit, I've concluded that
              // the smb:// and the server/domain name at the start of the path are just plain old noise, and should be stripped.
              // This changes a behavior that has been around for a while, so there is a risk, but a quick back-and-forth with the
              // SE's leads me to believe that this is safe.

              if (pathAttributeValue.startsWith("smb://"))
              {
                int index = pathAttributeValue.indexOf("/","smb://".length());
                if (index == -1)
                  index = pathAttributeValue.length();
                pathAttributeValue = pathAttributeValue.substring(index);
              }
              // Now, translate
              pathAttributeValue = matchMap.translate(pathAttributeValue);
              pack(sb,pathAttributeValue,'+');
            }
            else
              sb.append('-');

            // Calculate the ingestion IRI/URI, and include that in the parseable area.
            String ingestionURI = convertToURI(documentIdentifier,fileMap,uriMap);
            pack(sb,ingestionURI,'+');

            // The stuff from here on down is non-parseable.
            // Get the file's modified date.
            long lastModified = fileLastModified(file);
            sb.append(new Long(lastModified).toString()).append(":")
              .append(new Long(fileLength(file)).toString());
            // Also include the specification-based answer for the question of whether fingerprinting is
            // going to be done.  Although we may not consider this to truly be "version" information, the
            // specification does affect whether anything is ingested or not, so it really is.  The alternative
            // is to fingerprint right here, in the version part of the world, but that's got a performance
            // downside, because it means that we'd have to suck over pretty much everything just to determine
            // what we wanted to ingest.
            boolean ifIndexable = wouldFileBeIncluded(newPath,spec,true);
            boolean ifNotIndexable = wouldFileBeIncluded(newPath,spec,false);
            if (ifIndexable == ifNotIndexable)
              sb.append("I");
            else
              sb.append(ifIndexable?"Y":"N");
            rval[i] = sb.toString();
          }
        }
        else
          rval[i] = null;
      }
      catch (jcifs.smb.SmbAuthException e)
      {
        Logging.connectors.warn("JCIFS: Authorization exception reading version information for "+documentIdentifier+" - skipping");
        rval[i] = null;
      }
      catch (MalformedURLException mue)
      {
        Logging.connectors.error("JCIFS: MalformedURLException thrown: "+mue.getMessage(),mue);
        throw new LCFException("MalformedURLException thrown: "+mue.getMessage(),mue);
      }
      catch (SmbException se)
      {
        processSMBException(se,documentIdentifier,"getting document version","fetching share security");
        rval[i] = null;
      }
      catch (java.net.SocketTimeoutException e)
      {
        long currentTime = System.currentTimeMillis();
        Logging.connectors.warn("JCIFS: Socket timeout reading version information for document "+documentIdentifier+": "+e.getMessage(),e);
        throw new ServiceInterruption("Timeout or other service interruption: "+e.getMessage(),e,currentTime + 300000L,
          currentTime + 3 * 60 * 60000L,-1,false);
      }
      catch (InterruptedIOException e)
      {
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (IOException e)
      {
        long currentTime = System.currentTimeMillis();
        Logging.connectors.warn("JCIFS: I/O error reading version information for document "+documentIdentifier+": "+e.getMessage(),e);
        throw new ServiceInterruption("Timeout or other service interruption: "+e.getMessage(),e,currentTime + 300000L,
          currentTime + 3 * 60 * 60000L,-1,false);
      }
      i++;
    }
    return rval;
  }


  /**
  * Process a set of documents. This is the method that should cause each
  * document to be fetched, processed, and the results either added to the
  * queue of documents for the current job, and/or entered into the
  * incremental ingestion manager. The document specification allows this
  * class to filter what is done based on the job.
  *
  * @param documentIdentifiers
  *            is the set of document identifiers to process.
  * @param activities
  *            is the interface this method should use to queue up new
  *            document references and ingest documents.
  * @param spec
  *            is the document specification.
  * @param scanOnly
  *            is an array corresponding to the document identifiers. It is
  *            set to true to indicate when the processing should only find
  *            other references, and should not actually call the ingestion
  *            methods.
  */
  public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
    DocumentSpecification spec, boolean[] scanOnly) throws LCFException, ServiceInterruption
  {
    getSession();

    byte[] transferBuffer = null;

    int i = 0;
    while (i < documentIdentifiers.length)
    {
      String documentIdentifier = documentIdentifiers[i];
      String version = versions[i];

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Processing '"+documentIdentifier+"'");
      try
      {

        SmbFile file = new SmbFile(documentIdentifier,pa);

        if (fileExists(file))
        {
          if (fileIsDirectory(file))
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("JCIFS: '"+documentIdentifier+"' is a directory");

            // Queue up stuff for directory
            // DFS special support no longer needed, because JCifs now does the right thing.

            // This is the string we replace in the child canonical paths.
            // String matchPrefix = "";
            // This is what we replace it with, to get back to a DFS path.
            // String matchReplace = "";

            // DFS resolved.

            // Use a filter to actually do the work here.  This prevents large arrays from being
            // created when there are big directories.
            ProcessDocumentsFilter filter = new ProcessDocumentsFilter(activities,spec);
            fileListFiles(file,filter);
            filter.checkAndThrow();
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("JCIFS: '"+documentIdentifier+"' is a file");

            if (!scanOnly[i])
            {
              // We've already avoided queuing documents that we
              // don't want, based on file specifications.
              // We still need to check based on file data.

              // DFS support is now implicit in JCifs.

              long startFetchTime = System.currentTimeMillis();
              String fileName = getFileCanonicalPath(file);
              if (fileName != null)
              {
                // manipulate path to include the DFS alias, not the literal path
                // String newPath = matchPrefix + fileName.substring(matchReplace.length());
                String newPath = fileName;
                if (checkNeedFileData(newPath, spec))
                {
                  if (Logging.connectors.isDebugEnabled())
                    Logging.connectors.debug("JCIFS: Local file data needed for '"+documentIdentifier+"'");

                  // Create a temporary file, and use that for the check and then the ingest
                  File tempFile = File.createTempFile("_sdc_",null);
                  try
                  {
                    FileOutputStream os = new FileOutputStream(tempFile);
                    try
                    {

                      // Now, make a local copy so we can fingerprint
                      InputStream inputStream = getFileInputStream(file);
                      try
                      {
                        // Copy!
                        if (transferBuffer == null)
                          transferBuffer = new byte[65536];
                        while (true)
                        {
                          int amt = inputStream.read(transferBuffer,0,transferBuffer.length);
                          if (amt == -1)
                            break;
                          os.write(transferBuffer,0,amt);
                        }
                      }
                      finally
                      {
                        inputStream.close();
                      }
                    }
                    finally
                    {
                      os.close();
                    }


                    if (checkIngest(tempFile, newPath, spec, activities))
                    {
                      if (Logging.connectors.isDebugEnabled())
                        Logging.connectors.debug("JCIFS: Decided to ingest '"+documentIdentifier+"'");
                      // OK, do ingestion itself!
                      InputStream inputStream = new FileInputStream(tempFile);
                      try
                      {
                        RepositoryDocument rd = new RepositoryDocument();
                        rd.setBinary(inputStream, tempFile.length());
                        int index = 0;
                        index = setDocumentSecurity(rd,version,index);
                        index = setPathMetadata(rd,version,index);
                        StringBuffer ingestURI = new StringBuffer();
                        index = unpack(ingestURI,version,index,'+');
                        activities.ingestDocument(documentIdentifier, versions[i], ingestURI.toString(), rd);
                      }
                      finally
                      {
                        inputStream.close();
                      }

                      // I put this record here deliberately for two reasons:
                      // (1) the other path includes ingestion time, and
                      // (2) if anything fails up to and during ingestion, I want THAT failure record to be written, not this one.
                      // So, really, ACTIVITY_ACCESS is a bit more than just fetch for JCIFS...
                      activities.recordActivity(new Long(startFetchTime),ACTIVITY_ACCESS,
                        new Long(tempFile.length()),documentIdentifier,"Success",null,null);

                    }
                    else
                    {
                      // We must actively remove the document here, because the getDocumentVersions()
                      // method has no way of signalling this, since it does not do the fingerprinting.
                      if (Logging.connectors.isDebugEnabled())
                        Logging.connectors.debug("JCIFS: Decided to remove '"+documentIdentifier+"'");
                      activities.deleteDocument(documentIdentifier);
                      // We should record the access here as well, since this is a non-exception way through the code path.
                      // (I noticed that this was not being recorded in the history while fixing 25477.)
                      activities.recordActivity(new Long(startFetchTime),ACTIVITY_ACCESS,
                        new Long(tempFile.length()),documentIdentifier,"Success",null,null);
                    }
                  }
                  finally
                  {
                    tempFile.delete();
                  }
                }
                else
                {
                  if (Logging.connectors.isDebugEnabled())
                    Logging.connectors.debug("JCIFS: Local file data not needed for '"+documentIdentifier+"'");

                  // Presume that since the file was queued that it fulfilled the needed criteria.
                  // Go off and ingest the fast way.

                  // Ingest the document.
                  InputStream inputStream = getFileInputStream(file);
                  try
                  {
                    RepositoryDocument rd = new RepositoryDocument();
                    rd.setBinary(inputStream, fileLength(file));
                    int index = 0;
                    index = setDocumentSecurity(rd,version,index);
                    index = setPathMetadata(rd,version,index);
                    StringBuffer ingestURI = new StringBuffer();
                    index = unpack(ingestURI,version,index,'+');
                    activities.ingestDocument(documentIdentifier, versions[i], ingestURI.toString(), rd);
                  }
                  finally
                  {
                    inputStream.close();
                  }
                  activities.recordActivity(new Long(startFetchTime),ACTIVITY_ACCESS,
                    new Long(fileLength(file)),documentIdentifier,"Success",null,null);
                }
              }
              else
              {
                Logging.connectors.debug("JCIFS: Skipping file because canonical path is null");
                activities.recordActivity(null,ACTIVITY_ACCESS,
                  null,documentIdentifier,"Skip","Null canonical path",null);
              }
            }
          }
        }
      }
      catch (MalformedURLException mue)
      {
        Logging.connectors.error("MalformedURLException tossed",mue);
        activities.recordActivity(null,ACTIVITY_ACCESS,
          null,documentIdentifier,"Error","Malformed URL: "+mue.getMessage(),null);
        throw new LCFException("MalformedURLException tossed: "+mue.getMessage(),mue);
      }
      catch (jcifs.smb.SmbAuthException e)
      {
        Logging.connectors.warn("JCIFS: Authorization exception reading document/directory "+documentIdentifier+" - skipping");
        activities.recordActivity(null,ACTIVITY_ACCESS,
          null,documentIdentifier,"Skip","Authorization: "+e.getMessage(),null);
        // We call the delete even if it's a directory; this is harmless and it cleans up the jobqueue row.
        activities.deleteDocument(documentIdentifier);
      }
      catch (SmbException se)
      {
        // At least some of these are transport errors, and should be treated as service
        // interruptions.
        long currentTime = System.currentTimeMillis();
        Throwable cause = se.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw new LCFException(te.getRootCause().getMessage(),te.getRootCause(),LCFException.INTERRUPTED);

          Logging.connectors.warn("JCIFS: Timeout processing document/directory "+documentIdentifier+": retrying...",se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Retry","Transport: "+cause.getMessage(),null);
          throw new ServiceInterruption("Timeout or other service interruption: "+cause.getMessage(),cause,currentTime + 300000L,
            currentTime + 12 * 60 * 60000L,-1,false);
        }
        if (se.getMessage().indexOf("busy") != -1)
        {
          Logging.connectors.warn("JCIFS: 'Busy' response when processing document/directory for "+documentIdentifier+": retrying...",se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Retry","Busy: "+se.getMessage(),null);
          throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
            currentTime + 3 * 60 * 60000L,-1,false);
        }
        else if (se.getMessage().indexOf("handle is invalid") != -1)
        {
          Logging.connectors.warn("JCIFS: 'Handle is invalid' response when processing document/directory for "+documentIdentifier+": retrying...",se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Retry","Expiration: "+se.getMessage(),null);
          throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
            currentTime + 3 * 60 * 60000L,-1,false);
        }
        else if (se.getMessage().indexOf("parameter is incorrect") != -1)
        {
          Logging.connectors.warn("JCIFS: 'Parameter is incorrect' response when processing document/directory for "+documentIdentifier+": retrying...",se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Retry","Expiration: "+se.getMessage(),null);
          throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
            currentTime + 3 * 60 * 60000L,-1,false);
        }
        else if (se.getMessage().indexOf("no longer available") != -1)
        {
          Logging.connectors.warn("JCIFS: 'No longer available' response when processing document/directory for "+documentIdentifier+": retrying...",se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Retry","Expiration: "+se.getMessage(),null);
          throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
            currentTime + 3 * 60 * 60000L,-1,false);
        }
        else if (se.getMessage().indexOf("cannot find") != -1 || se.getMessage().indexOf("cannot be found") != -1)
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Skipping document/directory "+documentIdentifier+" because it cannot be found");
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Not found",null,null);
          activities.deleteDocument(documentIdentifier);
        }
        else if (se.getMessage().indexOf("is denied") != -1)
        {
          Logging.connectors.warn("JCIFS: Access exception reading document/directory "+documentIdentifier+" - skipping");
          // We call the delete even if it's a directory; this is harmless and it cleans up the jobqueue row.
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Skip","Authorization: "+se.getMessage(),null);
          activities.deleteDocument(documentIdentifier);
        }
        else
        {
          Logging.connectors.error("JCIFS: SmbException tossed processing "+documentIdentifier,se);
          activities.recordActivity(null,ACTIVITY_ACCESS,
            null,documentIdentifier,"Error","Unknown: "+se.getMessage(),null);
          throw new LCFException("SmbException tossed: "+se.getMessage(),se);
        }
      }
      catch (java.net.SocketTimeoutException e)
      {
        long currentTime = System.currentTimeMillis();
        Logging.connectors.warn("JCIFS: Socket timeout processing "+documentIdentifier+": "+e.getMessage(),e);
        activities.recordActivity(null,ACTIVITY_ACCESS,
          null,documentIdentifier,"Retry","Socket timeout: "+e.getMessage(),null);
        throw new ServiceInterruption("Timeout or other service interruption: "+e.getMessage(),e,currentTime + 300000L,
          currentTime + 3 * 60 * 60000L,-1,false);
      }
      catch (InterruptedIOException e)
      {
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (IOException e)
      {
        long currentTime = System.currentTimeMillis();
        Logging.connectors.warn("JCIFS: IO error processing "+documentIdentifier+": "+e.getMessage(),e);
        activities.recordActivity(null,ACTIVITY_ACCESS,
          null,documentIdentifier,"Retry","IO Error: "+e.getMessage(),null);
        throw new ServiceInterruption("Timeout or other service interruption: "+e.getMessage(),e,currentTime + 300000L,
          currentTime + 3 * 60 * 60000L,-1,false);
      }

      i++;
    }

  }



  /** This method calculates an ACL string based on whether there are forced acls and also based on
  * the acls in place for a file.
  */
  protected void describeDocumentSecurity(StringBuffer description, SmbFile file, String[] forcedacls,
    String[] forcedShareAcls)
    throws LCFException, IOException
  {
    String[] shareAllowAcls;
    String[] shareDenyAcls;
    String[] allowAcls;
    String[] denyAcls;

    int j;
    int allowCount;
    int denyCount;
    ACE[] aces;

    if (forcedShareAcls!=null)
    {
      description.append("+");

      if (forcedShareAcls.length==0)
      {
        // Do the share acls first.  Note that the smbfile passed in has been dereferenced,
        // so if this is a DFS path, we will be looking up the permissions on the share
        // that is actually used to contain the file.  However, there's no guarantee that the
        // url generated from the original share will work to get there; the permissions on
        // the original share may prohibit users that the could nevertheless see the document
        // if they went in the direct way.


        // Grab the share permissions.
        aces = getFileShareSecurity(file);

        if (aces == null)
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Share has no ACL for '"+getFileCanonicalPath(file)+"'");

          // "Public" share: S-1-1-0
          shareAllowAcls = new String[]{"S-1-1-0"};
          shareDenyAcls = new String[]{defaultAuthorityDenyToken};
        }
        else
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Found "+Integer.toString(aces.length)+" share access tokens for '"+getFileCanonicalPath(file)+"'");

          // We are interested in the read permission, and take
          // a keen interest in allow/deny
          allowCount = 0;
          denyCount = 0;
          j = 0;
          while (j < aces.length)
          {
            ACE ace = aces[j++];
            if ((ace.getAccessMask() & ACE.FILE_READ_DATA) != 0)
            {
              if (ace.isAllow())
                allowCount++;
              else
                denyCount++;
            }
          }

          shareAllowAcls = new String[allowCount];
          shareDenyAcls = new String[denyCount+1];
          j = 0;
          allowCount = 0;
          denyCount = 0;
          shareDenyAcls[denyCount++] = defaultAuthorityDenyToken;
          while (j < aces.length)
          {
            ACE ace = aces[j++];
            if ((ace.getAccessMask() & ACE.FILE_READ_DATA) != 0)
            {
              if (ace.isAllow())
                shareAllowAcls[allowCount++] = ace.getSID().toString();
              else
                shareDenyAcls[denyCount++] = ace.getSID().toString();
            }
          }
        }
      }
      else
      {
        shareAllowAcls = forcedShareAcls;
        if (forcedShareAcls.length == 0)
          shareDenyAcls = new String[0];
        else
          shareDenyAcls = new String[]{defaultAuthorityDenyToken};
      }
      java.util.Arrays.sort(shareAllowAcls);
      java.util.Arrays.sort(shareDenyAcls);
      // Stuff the acls into the description string.
      packList(description,shareAllowAcls,'+');
      packList(description,shareDenyAcls,'+');
    }
    else
      description.append('-');

    if (forcedacls!=null)
    {
      description.append("+");

      if (forcedacls.length==0)
      {
        aces = getFileSecurity(file);
        if (aces == null)
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Document has no ACL for '"+getFileCanonicalPath(file)+"'");

          // Document is "public", meaning we want S-1-1-0 and the deny token
          allowAcls = new String[]{"S-1-1-0"};
          denyAcls = new String[]{defaultAuthorityDenyToken};
        }
        else
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Found "+Integer.toString(aces.length)+" document access tokens for '"+getFileCanonicalPath(file)+"'");

          // We are interested in the read permission, and take
          // a keen interest in allow/deny
          allowCount = 0;
          denyCount = 0;
          j = 0;
          while (j < aces.length)
          {
            ACE ace = aces[j++];
            if ((ace.getAccessMask() & ACE.FILE_READ_DATA) != 0)
            {
              if (ace.isAllow())
                allowCount++;
              else
                denyCount++;
            }
          }

          allowAcls = new String[allowCount];
          denyAcls = new String[denyCount+1];
          j = 0;
          allowCount = 0;
          denyCount = 0;
          denyAcls[denyCount++] = defaultAuthorityDenyToken;
          while (j < aces.length)
          {
            ACE ace = aces[j++];
            if ((ace.getAccessMask() & ACE.FILE_READ_DATA) != 0)
            {
              if (ace.isAllow())
                allowAcls[allowCount++] = ace.getSID().toString();
              else
                denyAcls[denyCount++] = ace.getSID().toString();
            }
          }
        }
      }
      else
      {
        allowAcls = forcedacls;
        if (forcedacls.length == 0)
          denyAcls = new String[0];
        else
          denyAcls = new String[]{defaultAuthorityDenyToken};
      }
      java.util.Arrays.sort(allowAcls);
      java.util.Arrays.sort(denyAcls);
      packList(description,allowAcls,'+');
      packList(description,denyAcls,'+');
    }
    else
      description.append('-');

  }


  protected static void processSMBException(SmbException se, String documentIdentifier, String activity, String operation)
    throws LCFException, ServiceInterruption
  {
    // At least some of these are transport errors, and should be treated as service
    // interruptions.
    long currentTime = System.currentTimeMillis();
    Throwable cause = se.getRootCause();
    if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
    {
      // See if it's an interruption
      jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
      if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
        throw new LCFException(te.getRootCause().getMessage(),te.getRootCause(),LCFException.INTERRUPTED);
      Logging.connectors.warn("JCIFS: Timeout "+activity+" for "+documentIdentifier+": retrying...",se);
      // Transport exceptions no longer abort when they give up, so we can't get notified that there is a problem.

      throw new ServiceInterruption("Timeout or other service interruption: "+cause.getMessage(),cause,currentTime + 300000L,
        currentTime + 12 * 60 * 60000L,-1,false);
    }
    if (se.getMessage().indexOf("busy") != -1)
    {
      Logging.connectors.warn("JCIFS: 'Busy' response when "+activity+" for "+documentIdentifier+": retrying...",se);
      // Busy exceptions just skip the document and keep going
      throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
        currentTime + 3 * 60 * 60000L,-1,false);
    }
    else if (se.getMessage().indexOf("handle is invalid") != -1)
    {
      Logging.connectors.warn("JCIFS: 'Handle is invalid' response when "+activity+" for "+documentIdentifier+": retrying...",se);
      // Invalid handle errors treated like "busy"
      throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
        currentTime + 3 * 60 * 60000L,-1,false);
    }
    else if (se.getMessage().indexOf("parameter is incorrect") != -1)
    {
      Logging.connectors.warn("JCIFS: 'Parameter is incorrect' response when "+activity+" for "+documentIdentifier+": retrying...",se);
      // Invalid handle errors treated like "busy"
      throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
        currentTime + 3 * 60 * 60000L,-1,false);
    }
    else if (se.getMessage().indexOf("no longer available") != -1)
    {
      Logging.connectors.warn("JCIFS: 'No longer available' response when "+activity+" for "+documentIdentifier+": retrying...",se);
      // No longer available == busy
      throw new ServiceInterruption("Timeout or other service interruption: "+se.getMessage(),se,currentTime + 300000L,
        currentTime + 3 * 60 * 60000L,-1,false);
    }
    else if (se.getMessage().indexOf("cannot find") != -1 || se.getMessage().indexOf("cannot be found") != -1)
    {
      return;
    }
    else if (se.getMessage().indexOf("is denied") != -1)
    {
      Logging.connectors.warn("JCIFS: Access exception when "+activity+" for "+documentIdentifier+" - skipping");
      return;
    }
    else if (se.getMessage().indexOf("Incorrect function") != -1)
    {
      Logging.connectors.error("JCIFS: Server does not support a required operation ("+operation+"?) for "+documentIdentifier);
      throw new LCFException("Server does not support a required operation ("+operation+", possibly?) accessing document "+documentIdentifier,se);
    }
    else
    {
      Logging.connectors.error("SmbException thrown "+activity+" for "+documentIdentifier,se);
      throw new LCFException("SmbException thrown: "+se.getMessage(),se);
    }
  }

  protected static int setDocumentSecurity(RepositoryDocument rd, String version, int startPosition)
  {
    if (startPosition < version.length() && version.charAt(startPosition++) == '+')
    {
      // Unpack share allow and share deny
      ArrayList shareAllowAcls = new ArrayList();
      startPosition = unpackList(shareAllowAcls,version,startPosition,'+');
      ArrayList shareDenyAcls = new ArrayList();
      startPosition = unpackList(shareDenyAcls,version,startPosition,'+');
      String[] shareAllow = new String[shareAllowAcls.size()];
      String[] shareDeny = new String[shareDenyAcls.size()];
      int i = 0;
      while (i < shareAllow.length)
      {
        shareAllow[i] = (String)shareAllowAcls.get(i);
        i++;
      }
      i = 0;
      while (i < shareDeny.length)
      {
        shareDeny[i] = (String)shareDenyAcls.get(i);
        i++;
      }

      // set share acls
      rd.setShareACL(shareAllow);
      rd.setShareDenyACL(shareDeny);
    }
    if (startPosition < version.length() && version.charAt(startPosition++) == '+')
    {
      // Unpack allow and deny acls
      ArrayList allowAcls = new ArrayList();
      startPosition = unpackList(allowAcls,version,startPosition,'+');
      ArrayList denyAcls = new ArrayList();
      startPosition = unpackList(denyAcls,version,startPosition,'+');
      String[] allow = new String[allowAcls.size()];
      String[] deny = new String[denyAcls.size()];
      int i = 0;
      while (i < allow.length)
      {
        allow[i] = (String)allowAcls.get(i);
        i++;
      }
      i = 0;
      while (i < deny.length)
      {
        deny[i] = (String)denyAcls.get(i);
        i++;
      }

      // set native file acls
      rd.setACL(allow);
      rd.setDenyACL(deny);
    }
    return startPosition;
  }

  protected static int setPathMetadata(RepositoryDocument rd, String version, int index)
    throws LCFException
  {
    if (version.length() > index && version.charAt(index++) == '+')
    {
      StringBuffer pathAttributeNameBuffer = new StringBuffer();
      StringBuffer pathAttributeValueBuffer = new StringBuffer();
      index = unpack(pathAttributeNameBuffer,version,index,'+');
      index = unpack(pathAttributeValueBuffer,version,index,'+');
      String pathAttributeName = pathAttributeNameBuffer.toString();
      String pathAttributeValue = pathAttributeValueBuffer.toString();
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Path attribute name is '"+pathAttributeName+"'");
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Path attribute value is '"+pathAttributeValue+"'");
      rd.addField(pathAttributeName,pathAttributeValue);
    }
    else
      Logging.connectors.debug("JCIFS: Path attribute name is null");
    return index;
  }

  /** Check status of connection.
  */
  public String check()
    throws LCFException
  {
    getSession();
    String serverURI = smbconnectionPath;
    SmbFile server = null;
    try
    {
      server = new SmbFile(serverURI,pa);
    }
    catch (MalformedURLException e1)
    {
      return "Malformed URL: '"+serverURI+"': "+e1.getMessage();
    }
    try
    {
      // check to make sure it's a server or a folder
      int type = getFileType(server);
      if (type==SmbFile.TYPE_SERVER || type==SmbFile.TYPE_SHARE
        || type==SmbFile.TYPE_FILESYSTEM)
      {
        try
        {
          server.connect();
          if (!server.exists())
            return "Server or path does not exist";
        }
        catch (java.net.SocketTimeoutException e)
        {
          return "Timeout connecting to server: "+e.getMessage();
        }
        catch (InterruptedIOException e)
        {
          throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
        }
        catch (IOException e)
        {
          return "Couldn't connect to server: "+e.getMessage();
        }
        return super.check();
      }
      else
        return "URI is not a server URI: '"+serverURI+"'";
    }
    catch (SmbException e)
    {
      return "Could not connect: "+e.getMessage();
    }
  }

  // Protected methods

  /** Check if a file or directory should be included, given a document specification.
  *@param file is the file object.
  *@param fileName is the canonical file name.
  *@param documentSpecification is the specification.
  *@return true if it should be included.
  */
  protected boolean checkInclude(SmbFile file, String fileName, DocumentSpecification documentSpecification)
    throws LCFException, ServiceInterruption
  {
    if (Logging.connectors.isDebugEnabled())
      Logging.connectors.debug("JCIFS: In checkInclude for '"+fileName+"'");

    // This method does not attempt to do any fingerprinting.  Instead, it will opt to include any
    // file that may depend on fingerprinting, and exclude everything else.  The actual setup for
    // the fingerprinting test is in checkNeedFileData(), while the actual code that determines in vs.
    // out using the file data is in checkIngest().
    try
    {
      String pathPart;
      String filePart;
      boolean isDirectory = fileIsDirectory(file);
      if (isDirectory)
      {

        pathPart = fileName;
        filePart = null;
      }
      else
      {
        int lastSlash = fileName.lastIndexOf("/");
        if (lastSlash == -1)
        {
          pathPart = "";
          filePart = fileName;
        }
        else
        {
          // Pathpart has to include the slash
          pathPart = fileName.substring(0,lastSlash+1);
          filePart = fileName.substring(lastSlash+1);
        }
      }

      // If it's a file, make sure the maximum length is not exceeded
      int i;
      if (!isDirectory)
      {
        long maxFileLength = Long.MAX_VALUE;
        i = 0;
        while (i < documentSpecification.getChildCount())
        {
          SpecificationNode sn = documentSpecification.getChild(i++);
          if (sn.getType().equals(NODE_MAXLENGTH))
          {
            try
            {
              String value = sn.getAttributeValue(ATTRIBUTE_VALUE);
              if (value != null && value.length() > 0)
                maxFileLength = new Long(value).longValue();
            }
            catch (NumberFormatException e)
            {
              throw new LCFException("Bad number",e);
            }
          }
        }
        if (fileLength(file) > maxFileLength)
          return false;
      }

      // Scan until we match a startpoint
      i = 0;
      while (i < documentSpecification.getChildCount())
      {
        SpecificationNode sn = documentSpecification.getChild(i++);
        if (sn.getType().equals(NODE_STARTPOINT))
        {
          // Prepend the server URL to the path, since that's what pathpart will have.
          String path = mapToIdentifier(sn.getAttributeValue(ATTRIBUTE_PATH));

          // Compare with filename
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("JCIFS: Matching startpoint '"+path+"' against actual '"+pathPart+"'");
          int matchEnd = matchSubPath(path,pathPart);
          if (matchEnd == -1)
          {
            Logging.connectors.debug("JCIFS: No match");
            continue;
          }

          Logging.connectors.debug("JCIFS: Startpoint found!");

          // If this is the root, it's always included.
          if (matchEnd == fileName.length())
          {
            Logging.connectors.debug("JCIFS: Startpoint: always included");
            return true;
          }

          // matchEnd is the start of the rest of the path (after the match) in fileName.
          // We need to walk through the rules and see whether it's in or out.
          int j = 0;
          while (j < sn.getChildCount())
          {
            SpecificationNode node = sn.getChild(j++);
            String flavor = node.getType();
            if (flavor.equals(NODE_INCLUDE) || flavor.equals(NODE_EXCLUDE))
            {
              String type = node.getAttributeValue(ATTRIBUTE_TYPE);
              if (type == null)
                type = "";
              String indexable = node.getAttributeValue(ATTRIBUTE_INDEXABLE);
              if (indexable == null)
                indexable = "";
              String match = node.getAttributeValue(ATTRIBUTE_FILESPEC);

              // Check if there's a match against the filespec
              if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("JCIFS: Checking '"+match+"' against '"+fileName.substring(matchEnd-1)+"'");
              boolean isMatch = checkMatch(fileName,matchEnd-1,match);
              boolean isKnown = true;

              // Check the directory/file criteria
              if (isMatch)
              {
                Logging.connectors.debug("JCIFS: Match found.");
                isMatch = type.length() == 0 ||
                  (type.equals(VALUE_DIRECTORY) && isDirectory) ||
                  (type.equals(VALUE_FILE) && !isDirectory);
              }
              else
                Logging.connectors.debug("JCIFS: No match!");

              // Check the indexable criteria
              if (isMatch)
              {
                if (indexable.length() != 0)
                {
                  // Directories are never considered indexable.
                  // But if this is not a directory, things become ambiguous.
                  boolean isIndexable;
                  if (isDirectory)
                  {
                    isIndexable = false;
                    isMatch = (indexable.equals("yes") && isIndexable) ||
                      (indexable.equals("no") && !isIndexable);
                  }
                  else
                    isKnown = false;

                }
              }

              if (isKnown)
              {
                if (isMatch)
                {
                  if (flavor.equals(NODE_INCLUDE))
                    return true;
                  else
                    return false;
                }
              }
              else
              {
                // Not known
                // What we do depends on whether this is an include rule or an exclude one.
                // We want to err on the side of inclusion, which means for include rules
                // we return true, and for exclude rules we simply continue.
                if (flavor.equals(NODE_INCLUDE))
                  return true;
                // Continue
              }
            }
          }

        }
      }
      return false;
    }
    catch (jcifs.smb.SmbAuthException e)
    {
      Logging.connectors.warn("JCIFS: Authorization exception checking inclusion for "+fileName+" - skipping");
      return false;
    }
    catch (SmbException se)
    {
      processSMBException(se, fileName, "checking inclusion", "canonical path mapping");
      return false;
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    finally
    {
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Leaving checkInclude for '"+fileName+"'");
    }

  }

  /** Pretend that a file is either indexable or not, and return whether or not it would be ingested.
  * This is only ever called for files.
  *@param fileName is the canonical file name.
  *@param documentSpecification is the specification.
  *@param pretendIndexable should be set to true if the document's contents would be fingerprinted as "indexable",
  *       or false otherwise.
  *@return true if the file would be ingested given the parameters.
  */
  protected boolean wouldFileBeIncluded(String fileName, DocumentSpecification documentSpecification,
    boolean pretendIndexable)
    throws LCFException
  {
    if (Logging.connectors.isDebugEnabled())
      Logging.connectors.debug("JCIFS: In wouldFileBeIncluded for '"+fileName+"', pretendIndexable="+(pretendIndexable?"true":"false"));

    // This file was flagged as needing file data.  However, that doesn't tell us *for what* we need it.
    // So we need to redo the decision tree, but this time do everything completely.

    try
    {
      String pathPart;
      String filePart;
      boolean isDirectory = false;

      int lastSlash = fileName.lastIndexOf("/");
      if (lastSlash == -1)
      {
        pathPart = "";
        filePart = fileName;
      }
      else
      {
        pathPart = fileName.substring(0,lastSlash+1);
        filePart = fileName.substring(lastSlash+1);
      }

      // Scan until we match a startpoint
      int i = 0;
      while (i < documentSpecification.getChildCount())
      {
        SpecificationNode sn = documentSpecification.getChild(i++);
        if (sn.getType().equals(NODE_STARTPOINT))
        {
          // Prepend the server URL to the path, since that's what pathpart will have.
          String path = mapToIdentifier(sn.getAttributeValue(ATTRIBUTE_PATH));

          // Compare with filename
          int matchEnd = matchSubPath(path,pathPart);
          if (matchEnd == -1)
          {
            continue;
          }

          // matchEnd is the start of the rest of the path (after the match) in fileName.
          // We need to walk through the rules and see whether it's in or out.
          int j = 0;
          while (j < sn.getChildCount())
          {
            SpecificationNode node = sn.getChild(j++);
            String flavor = node.getType();
            if (flavor.equals(NODE_INCLUDE) || flavor.equals(NODE_EXCLUDE))
            {
              String type = node.getAttributeValue(ATTRIBUTE_TYPE);
              if (type == null)
                type = "";
              String indexable = node.getAttributeValue(ATTRIBUTE_INDEXABLE);
              if (indexable == null)
                indexable = "";
              String match = node.getAttributeValue(ATTRIBUTE_FILESPEC);

              // Check if there's a match against the filespec
              boolean isMatch = checkMatch(fileName,matchEnd-1,match);

              // Check the directory/file criteria
              if (isMatch)
              {
                isMatch = type.length() == 0 ||
                  (type.equals(VALUE_DIRECTORY) && isDirectory) ||
                  (type.equals(VALUE_FILE) && !isDirectory);
              }

              // Check the indexable criteria
              if (isMatch)
              {
                if (indexable.length() != 0)
                {
                  // Directories are never considered indexable.
                  // But if this is not a directory, things become ambiguous.
                  boolean isIndexable;
                  if (isDirectory)
                    isIndexable = false;
                  else
                  {
                    isIndexable = pretendIndexable;
                  }

                  isMatch = (indexable.equals("yes") && isIndexable) ||
                    (indexable.equals("no") && !isIndexable);


                }
              }

              if (isMatch)
              {
                if (flavor.equals(NODE_INCLUDE))
                  return true;
                else
                  return false;
              }
            }
          }

        }
      }
      return false;
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    finally
    {
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Leaving wouldFileBeIncluded for '"+fileName+"'");
    }
  }

  /** Check to see whether we need the contents of the file for anything.  We do this by assuming that
  * the file is indexable, and assuming that it's not, and seeing if the same thing would happen.
  *@param fileName is the name of the file.
  *@param documentSpecification is the document specification.
  *@return true if the file needs to be fingerprinted.
  */
  protected boolean checkNeedFileData(String fileName, DocumentSpecification documentSpecification)
    throws LCFException
  {
    return wouldFileBeIncluded(fileName,documentSpecification,true) != wouldFileBeIncluded(fileName,documentSpecification,false);
  }

  /** Check if a file should be ingested, given a document specification and a local copy of the
  * file.  It is presumed that only files that passed checkInclude() and were also flagged as needing
  * file data by checkNeedFileData() will be checked by this method.
  *@param localFile is the file.
  *@param fileName is the JCIFS file name.
  *@param documentSpecification is the specification.
  *@param activities are the activities available to determine indexability.
  *@return true if the file should be ingested.
  */
  protected boolean checkIngest(File localFile, String fileName, DocumentSpecification documentSpecification, IFingerprintActivity activities)
    throws LCFException, ServiceInterruption
  {
    if (Logging.connectors.isDebugEnabled())
      Logging.connectors.debug("JCIFS: In checkIngest for '"+fileName+"'");

    // This file was flagged as needing file data.  However, that doesn't tell us *for what* we need it.
    // So we need to redo the decision tree, but this time do everything completely.

    try
    {
      String pathPart;
      String filePart;
      boolean isDirectory = false;

      int lastSlash = fileName.lastIndexOf("/");
      if (lastSlash == -1)
      {
        pathPart = "";
        filePart = fileName;
      }
      else
      {
        pathPart = fileName.substring(0,lastSlash+1);
        filePart = fileName.substring(lastSlash+1);
      }

      // Scan until we match a startpoint
      int i = 0;
      while (i < documentSpecification.getChildCount())
      {
        SpecificationNode sn = documentSpecification.getChild(i++);
        if (sn.getType().equals(NODE_STARTPOINT))
        {
          // Prepend the server URL to the path, since that's what pathpart will have.
          String path = mapToIdentifier(sn.getAttributeValue(ATTRIBUTE_PATH));

          // Compare with filename
          int matchEnd = matchSubPath(path,pathPart);
          if (matchEnd == -1)
          {
            continue;
          }

          // matchEnd is the start of the rest of the path (after the match) in fileName.
          // We need to walk through the rules and see whether it's in or out.
          int j = 0;
          while (j < sn.getChildCount())
          {
            SpecificationNode node = sn.getChild(j++);
            String flavor = node.getType();
            if (flavor.equals(NODE_INCLUDE) || flavor.equals(NODE_EXCLUDE))
            {
              String type = node.getAttributeValue(ATTRIBUTE_TYPE);
              if (type == null)
                type = "";
              String indexable = node.getAttributeValue(ATTRIBUTE_INDEXABLE);
              if (indexable == null)
                indexable = "";
              String match = node.getAttributeValue(ATTRIBUTE_FILESPEC);

              // Check if there's a match against the filespec
              boolean isMatch = checkMatch(fileName,matchEnd-1,match);

              // Check the directory/file criteria
              if (isMatch)
              {
                isMatch = type.length() == 0 ||
                  (type.equals(VALUE_DIRECTORY) && isDirectory) ||
                  (type.equals(VALUE_FILE) && !isDirectory);
              }

              // Check the indexable criteria
              if (isMatch)
              {
                if (indexable.length() != 0)
                {
                  // Directories are never considered indexable.
                  // But if this is not a directory, things become ambiguous.
                  boolean isIndexable;
                  if (isDirectory)
                    isIndexable = false;
                  else
                  {
                    isIndexable = activities.checkDocumentIndexable(localFile);
                  }

                  isMatch = (indexable.equals("yes") && isIndexable) ||
                    (indexable.equals("no") && !isIndexable);


                }
              }

              if (isMatch)
              {
                if (flavor.equals(NODE_INCLUDE))
                  return true;
                else
                  return false;
              }
            }
          }

        }
      }
      return false;
    }
    catch (jcifs.smb.SmbAuthException e)
    {
      Logging.connectors.warn("JCIFS: Authorization exception checking ingestion for "+fileName+" - skipping");
      return false;
    }
    catch (SmbException se)
    {
      processSMBException(se, fileName, "checking ingestion", "reading document");
      return false;
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
    }
    finally
    {
      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("JCIFS: Leaving checkIngest for '"+fileName+"'");
    }

  }

  /** Match a sub-path.  The sub-path must match the complete starting part of the full path, in a path
  * sense.  The returned value should point into the file name beyond the end of the matched path, or
  * be -1 if there is no match.
  *@param subPath is the sub path.
  *@param fullPath is the full path.
  *@return the index of the start of the remaining part of the full path, or -1.
  */
  protected static int matchSubPath(String subPath, String fullPath)
  {
    if (subPath.length() > fullPath.length())
      return -1;
    if (fullPath.startsWith(subPath) == false)
      return -1;
    int rval = subPath.length();
    if (fullPath.length() == rval)
      return rval;
    char x = fullPath.charAt(rval);
    if (x == File.separatorChar)
      rval++;
    return rval;
  }

  /** Check a match between two strings with wildcards.
  *@param sourceMatch is the expanded string (no wildcards)
  *@param sourceIndex is the starting point in the expanded string.
  *@param match is the wildcard-based string.
  *@return true if there is a match.
  */
  protected static boolean checkMatch(String sourceMatch, int sourceIndex, String match)
  {
    // Note: The java regex stuff looks pretty heavyweight for this purpose.
    // I've opted to try and do a simple recursive version myself, which is not compiled.
    // Basically, the match proceeds by recursive descent through the string, so that all *'s cause
    // recursion.
    boolean caseSensitive = false;

    return processCheck(caseSensitive, sourceMatch, sourceIndex, match, 0);
  }

  /** Recursive worker method for checkMatch.  Returns 'true' if there is a path that consumes both
  * strings in their entirety in a matched way.
  *@param caseSensitive is true if file names are case sensitive.
  *@param sourceMatch is the source string (w/o wildcards)
  *@param sourceIndex is the current point in the source string.
  *@param match is the match string (w/wildcards)
  *@param matchIndex is the current point in the match string.
  *@return true if there is a match.
  */
  protected static boolean processCheck(boolean caseSensitive, String sourceMatch, int sourceIndex,
    String match, int matchIndex)
  {
    // Logging.connectors.debug("Matching '"+sourceMatch+"' position "+Integer.toString(sourceIndex)+
    //      " against '"+match+"' position "+Integer.toString(matchIndex));

    // Match up through the next * we encounter
    while (true)
    {
      // If we've reached the end, it's a match.
      if (sourceMatch.length() == sourceIndex && match.length() == matchIndex)
        return true;
      // If one has reached the end but the other hasn't, no match
      if (match.length() == matchIndex)
        return false;
      if (sourceMatch.length() == sourceIndex)
      {
        if (match.charAt(matchIndex) != '*')
          return false;
        matchIndex++;
        continue;
      }
      char x = sourceMatch.charAt(sourceIndex);
      char y = match.charAt(matchIndex);
      if (!caseSensitive)
      {
        if (x >= 'A' && x <= 'Z')
          x -= 'A'-'a';
        if (y >= 'A' && y <= 'Z')
          y -= 'A'-'a';
      }
      if (y == '*')
      {
        // Wildcard!
        // We will recurse at this point.
        // Basically, we want to combine the results for leaving the "*" in the match string
        // at this point and advancing the source index, with skipping the "*" and leaving the source
        // string alone.
        return processCheck(caseSensitive,sourceMatch,sourceIndex+1,match,matchIndex) ||
          processCheck(caseSensitive,sourceMatch,sourceIndex,match,matchIndex+1);
      }
      if (y == '?' || x == y)
      {
        sourceIndex++;
        matchIndex++;
      }
      else
        return false;
    }
  }

  /** Grab forced acl out of document specification.
  *@param spec is the document specification.
  *@return the acls.
  */
  protected static String[] getForcedAcls(DocumentSpecification spec)
  {
    HashMap map = new HashMap();
    int i = 0;
    boolean securityOn = true;
    while (i < spec.getChildCount())
    {
      SpecificationNode sn = spec.getChild(i++);
      if (sn.getType().equals(NODE_ACCESS))
      {
        String token = sn.getAttributeValue(ATTRIBUTE_TOKEN);
        map.put(token,token);
      }
      else if (sn.getType().equals(NODE_SECURITY))
      {
        String value = sn.getAttributeValue(ATTRIBUTE_VALUE);
        if (value.equals("on"))
          securityOn = true;
        else if (value.equals("off"))
          securityOn = false;
      }
    }
    if (!securityOn)
      return null;

    String[] rval = new String[map.size()];
    Iterator iter = map.keySet().iterator();
    i = 0;
    while (iter.hasNext())
    {
      rval[i++] = (String)iter.next();
    }
    return rval;
  }

  /** Grab forced share acls out of document specification.
  *@param spec is the document specification.
  *@return the acls.
  */
  protected static String[] getForcedShareAcls(DocumentSpecification spec)
  {
    HashMap map = new HashMap();
    int i = 0;
    boolean securityOn = true;
    while (i < spec.getChildCount())
    {
      SpecificationNode sn = spec.getChild(i++);
      if (sn.getType().equals(NODE_SHAREACCESS))
      {
        String token = sn.getAttributeValue(ATTRIBUTE_TOKEN);
        map.put(token,token);
      }
      else if (sn.getType().equals(NODE_SHARESECURITY))
      {
        String value = sn.getAttributeValue(ATTRIBUTE_VALUE);
        if (value.equals("on"))
          securityOn = true;
        else if (value.equals("off"))
          securityOn = false;
      }
    }
    if (!securityOn)
      return null;
    String[] rval = new String[map.size()];
    Iterator iter = map.keySet().iterator();
    i = 0;
    while (iter.hasNext())
    {
      rval[i++] = (String)iter.next();
    }
    return rval;
  }

  /** Map a "path" specification to a full identifier.
  */
  protected String mapToIdentifier(String path)
    throws IOException
  {
    String smburi = smbconnectionPath;
    String uri = smburi + path + "/";
    return getFileCanonicalPath(new SmbFile(uri,pa));
  }

  /** Stuffer for packing a single string with an end delimiter */
  protected static void pack(StringBuffer output, String value, char delimiter)
  {
    int i = 0;
    while (i < value.length())
    {
      char x = value.charAt(i++);
      if (x == '\\' || x == delimiter)
        output.append('\\');
      output.append(x);
    }
    output.append(delimiter);
  }

  /** Unstuffer for the above. */
  protected static int unpack(StringBuffer sb, String value, int startPosition, char delimiter)
  {
    while (startPosition < value.length())
    {
      char x = value.charAt(startPosition++);
      if (x == '\\')
      {
        if (startPosition < value.length())
          x = value.charAt(startPosition++);
      }
      else if (x == delimiter)
        break;
      sb.append(x);
    }
    return startPosition;
  }

  /** Stuffer for packing lists of fixed length */
  protected static void packFixedList(StringBuffer output, String[] values, char delimiter)
  {
    int i = 0;
    while (i < values.length)
    {
      pack(output,values[i++],delimiter);
    }
  }

  /** Unstuffer for unpacking lists of fixed length */
  protected static int unpackFixedList(String[] output, String value, int startPosition, char delimiter)
  {
    StringBuffer sb = new StringBuffer();
    int i = 0;
    while (i < output.length)
    {
      sb.setLength(0);
      startPosition = unpack(sb,value,startPosition,delimiter);
      output[i++] = sb.toString();
    }
    return startPosition;
  }

  /** Stuffer for packing lists of variable length */
  protected static void packList(StringBuffer output, ArrayList values, char delimiter)
  {
    pack(output,Integer.toString(values.size()),delimiter);
    int i = 0;
    while (i < values.size())
    {
      pack(output,values.get(i++).toString(),delimiter);
    }
  }

  /** Another stuffer for packing lists of variable length */
  protected static void packList(StringBuffer output, String[] values, char delimiter)
  {
    pack(output,Integer.toString(values.length),delimiter);
    int i = 0;
    while (i < values.length)
    {
      pack(output,values[i++],delimiter);
    }
  }

  /** Unstuffer for unpacking lists of variable length.
  *@param output is the array to write the unpacked results into.
  *@param value is the value to unpack.
  *@param startPosition is the place to start the unpack.
  *@param delimiter is the character to use between values.
  *@return the next position beyond the end of the list.
  */
  protected static int unpackList(ArrayList output, String value, int startPosition, char delimiter)
  {
    StringBuffer sb = new StringBuffer();
    startPosition = unpack(sb,value,startPosition,delimiter);
    try
    {
      int count = Integer.parseInt(sb.toString());
      int i = 0;
      while (i < count)
      {
        sb.setLength(0);
        startPosition = unpack(sb,value,startPosition,delimiter);
        output.add(sb.toString());
        i++;
      }
    }
    catch (NumberFormatException e)
    {
    }
    return startPosition;
  }

  // These methods allow me to experiment with cluster-mandated error handling on an entirely local level.  They correspond to individual SMBFile methods.

  /** Get canonical path */
  protected static String getFileCanonicalPath(SmbFile file)
  {
    return file.getCanonicalPath();
  }

  /** Check for file/directory existence */
  protected static boolean fileExists(SmbFile file)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.exists();
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while checking if file exists: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Check if file is a directory */
  protected static boolean fileIsDirectory(SmbFile file)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.isDirectory();
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while seeing if file is a directory: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get last modified date for file */
  protected static long fileLastModified(SmbFile file)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.lastModified();
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting file last-modified date: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get file length */
  protected static long fileLength(SmbFile file)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.length();
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting file length: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** List files */
  protected static SmbFile[] fileListFiles(SmbFile file, SmbFileFilter filter)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.listFiles(filter);
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while listing files: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get input stream for file */
  protected static InputStream getFileInputStream(SmbFile file)
    throws IOException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    IOException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.getInputStream();
      }
      catch (java.net.SocketTimeoutException e)
      {
        throw e;
      }
      catch (InterruptedIOException e)
      {
        throw e;
      }
      catch (IOException e)
      {
        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting file input stream: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentIOExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get file security */
  protected static ACE[] getFileSecurity(SmbFile file)
    throws IOException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    IOException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.getSecurity(false);
      }
      catch (java.net.SocketTimeoutException e)
      {
        throw e;
      }
      catch (InterruptedIOException e)
      {
        throw e;
      }
      catch (IOException e)
      {
        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting file security: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentIOExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get share security */
  protected static ACE[] getFileShareSecurity(SmbFile file)
    throws IOException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    IOException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.getShareSecurity(false);
      }
      catch (java.net.SocketTimeoutException e)
      {
        throw e;
      }
      catch (InterruptedIOException e)
      {
        throw e;
      }
      catch (IOException e)
      {
        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting share security: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentIOExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Get file type */
  protected static int getFileType(SmbFile file)
    throws SmbException
  {
    int totalTries = 0;
    int retriesRemaining = 3;
    SmbException currentException = null;
    while (retriesRemaining > 0 && totalTries < 5)
    {
      retriesRemaining--;
      totalTries++;
      try
      {
        return file.getType();
      }
      catch (SmbException e)
      {
        // If it's an interruption, throw it right away.
        Throwable cause = e.getRootCause();
        if (cause != null && (cause instanceof jcifs.util.transport.TransportException))
        {
          // See if it's an interruption
          jcifs.util.transport.TransportException te = (jcifs.util.transport.TransportException)cause;
          if (te.getRootCause() != null && te.getRootCause() instanceof java.lang.InterruptedException)
            throw e;
        }

        Logging.connectors.warn("JCIFS: Possibly transient exception detected on attempt "+Integer.toString(totalTries)+" while getting file type: "+e.getMessage(),e);
        if (currentException != null)
        {
          // Compare exceptions.  If they differ, reset the retry count.
          if (!equivalentSmbExceptions(currentException,e))
            retriesRemaining = 3;
        }
        currentException = e;
      }
    }
    throw currentException;
  }

  /** Check if two SmbExceptions are equivalent */
  protected static boolean equivalentSmbExceptions(SmbException e1, SmbException e2)
  {
    // The thing we want to compare is the message.  This is a little risky in that if there are (for example) object addresses in the message, the comparison will always fail.
    // However, I don't think we expect any such thing in this case.
    String e1m = e1.getMessage();
    String e2m = e2.getMessage();
    if (e1m == null)
      e1m = "";
    if (e2m == null)
      e2m = "";
    return e1m.equals(e2m);
  }

  /** Check if two IOExceptions are equivalent */
  protected static boolean equivalentIOExceptions(IOException e1, IOException e2)
  {
    // The thing we want to compare is the message.  This is a little risky in that if there are (for example) object addresses in the message, the comparison will always fail.
    // However, I don't think we expect any such thing in this case.
    String e1m = e1.getMessage();
    String e2m = e2.getMessage();
    if (e1m == null)
      e1m = "";
    if (e2m == null)
      e2m = "";
    return e1m.equals(e2m);
  }

  /** Document identifier stream.
  */
  protected class IdentifierStream implements IDocumentIdentifierStream
  {
    protected String[] ids = null;
    protected int currentIndex = 0;

    public IdentifierStream(DocumentSpecification spec)
      throws LCFException
    {
      try
      {
        // Walk the specification for the "startpoint" types.  Amalgamate these into a list of strings.
        // Presume that all roots are startpoint nodes
        int i = 0;
        int j = 0;
        while (i < spec.getChildCount())
        {
          SpecificationNode n = spec.getChild(i);
          if (n.getType().equals(NODE_STARTPOINT))
            j++;
          i++;
        }
        ids = new String[j];
        i = 0;
        j = 0;
        while (i < ids.length)
        {
          SpecificationNode n = spec.getChild(i);
          if (n.getType().equals(NODE_STARTPOINT))
          {
            // The id returned MUST be in canonical form!!!
            ids[j] = mapToIdentifier(n.getAttributeValue(ATTRIBUTE_PATH));

            if (Logging.connectors.isDebugEnabled())
            {
              Logging.connectors.debug("Seed = '"+ids[j]+"'");
            }
            j++;
          }
          i++;
        }
      }
      catch (java.net.SocketTimeoutException e)
      {
        throw new LCFException("Couldn't map to canonical path: "+e.getMessage(),e);
      }
      catch (InterruptedIOException e)
      {
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (IOException e)
      {
        throw new LCFException("Could not get a canonical path: "+e.getMessage(),e);
      }
    }

    /** Get the next identifier.
    *@return the next document identifier, or null if there are no more.
    */
    public String getNextIdentifier()
      throws LCFException, ServiceInterruption
    {
      if (currentIndex == ids.length)
        return null;
      return ids[currentIndex++];
    }

    /** Close the stream.
    */
    public void close()
      throws LCFException
    {
      ids = null;
    }

  }

  /* The following are additional methods used by the UI */

  /**
  * given a server uri, return all shares
  *
  * @param serverURI -
  * @return an array of SmbFile
  */
  public SmbFile[] getShareNames(String serverURI)
    throws LCFException
  {
    getSession();
    SmbFile server = null;
    try
    {
      server = new SmbFile(serverURI,pa);
    }
    catch (MalformedURLException e1)
    {
      throw new LCFException("MalformedURLException tossed",e1);
    }
    SmbFile[] shares = null;
    try
    {
      // check to make sure it's a server
      if (getFileType(server)==SmbFile.TYPE_SERVER)
      {
        shares = fileListFiles(server,new ShareFilter());
      }
    }
    catch (SmbException e)
    {
      throw new LCFException("SmbException tossed: "+e.getMessage(),e);
    }
    return shares;
  }

  /**
  * Given a folder path, determine if the folder is in fact legal and accessible (and is a folder).
  * @param folder is the relative folder from the network root
  * @return the canonical folder name if valid, or null if not.
  * @throws LCFException
  */
  public String validateFolderName(String folder) throws LCFException
  {
    getSession();
    //create new connection by appending to the old connection
    String smburi = smbconnectionPath;
    String uri = smburi;
    if (folder.length() > 0) {
      uri = smburi + folder + "/";
    }

    SmbFile currentDirectory = null;
    try
    {
      currentDirectory = new SmbFile(uri,pa);
    }
    catch (MalformedURLException e1)
    {
      throw new LCFException("validateFolderName: Can't get parent file: " + uri,e1);
    }

    try
    {
      currentDirectory.connect();
      if (fileIsDirectory(currentDirectory) == false)
        return null;
      String newCanonicalPath = currentDirectory.getCanonicalPath();
      String rval = newCanonicalPath.substring(smburi.length());
      if (rval.endsWith("/"))
        rval = rval.substring(0,rval.length()-1);
      return rval;
    }
    catch (SmbException se)
    {
      try
      {
        processSMBException(se, folder, "checking folder", "getting canonical path");
        return null;
      }
      catch (ServiceInterruption si)
      {
        throw new LCFException("Service interruption: "+si.getMessage(),si);
      }
    }
    catch (MalformedURLException e)
    {
      throw new LCFException("MalformedURLException tossed: "+e.getMessage(),e);
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new LCFException("IOException tossed: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new LCFException("IOException tossed: "+e.getMessage(),e);
    }

  }

  /**
  * given a smb uri, return all children directories
  *
  * @param folder is the relative folder from the network root
  * @return array of child folder names
  * @throws LCFException
  */
  public String[] getChildFolderNames(String folder) throws LCFException
  {
    getSession();
    //create new connection by appending to the old connection
    String smburi = smbconnectionPath;
    String uri = smburi;
    if (folder.length() > 0) {
      uri = smburi + folder + "/";
    }

    SmbFile currentDirectory = null;
    try
    {
      currentDirectory = new SmbFile(uri,pa);
    }
    catch (MalformedURLException e1)
    {
      throw new LCFException("getChildFolderNames: Can't get parent file: " + uri,e1);
    }

    // add DFS support
    SmbFile[] children = null;
    try
    {
      currentDirectory.connect();
      children = currentDirectory.listFiles(new DirectoryFilter());
    }
    catch (SmbException se)
    {
      try
      {
        processSMBException(se, folder, "getting child folder names", "listing files");
        children = new SmbFile[0];
      }
      catch (ServiceInterruption si)
      {
        throw new LCFException("Service interruption: "+si.getMessage(),si);
      }
    }
    catch (MalformedURLException e)
    {
      throw new LCFException("MalformedURLException tossed: "+e.getMessage(),e);
    }
    catch (java.net.SocketTimeoutException e)
    {
      throw new LCFException("IOException tossed: "+e.getMessage(),e);
    }
    catch (InterruptedIOException e)
    {
      throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      throw new LCFException("IOException tossed: "+e.getMessage(),e);
    }

    // populate a String array
    String[] directories = new String[children.length];
    for (int i=0;i < children.length;i++){
      String directoryName = children[i].getName();
      // strip the trailing slash
      directoryName = directoryName.replaceAll("/","");
      directories[i] = directoryName;
    }

    java.util.Arrays.sort(directories);
    return directories;
  }

  /**
  * inner class which returns only shares. used by listfiles(SmbFileFilter)
  *
  * @author James Maupin
  */

  class ShareFilter implements SmbFileFilter
  {
    /* (non-Javadoc)
    * @see jcifs.smb.SmbFileFilter#accept(jcifs.smb.SmbFile)
    */
    public boolean accept(SmbFile arg0) throws SmbException
    {
      if (getFileType(arg0)==SmbFile.TYPE_SHARE){
        return true;
      } else {
        return false;
      }
    }
  }

  /**
  * inner class which returns only directories. used by listfiles(SmbFileFilter)
  *
  * @author James Maupin
  */

  class DirectoryFilter implements SmbFileFilter
  {
    /* (non-Javadoc)
    * @see jcifs.smb.SmbFileFilter#accept(jcifs.smb.SmbFile)
    */
    public boolean accept(SmbFile arg0) throws SmbException {
      int type = getFileType(arg0);
      if (type==SmbFile.TYPE_SHARE || (type==SmbFile.TYPE_FILESYSTEM && fileIsDirectory(arg0))){
        return true;
      } else {
        return false;
      }
    }
  }

  /** This is the filter class that actually receives the files in batches.  We do it this way
  * so that the client won't run out of memory loading a huge directory.
  */
  protected class ProcessDocumentsFilter implements SmbFileFilter
  {

    /** This is the activities object, where matching references will be logged */
    protected IProcessActivity activities;
    /** Document specification */
    protected DocumentSpecification spec;
    /** Exceptions that we saw.  These are saved here so that they can be rethrown when done */
    protected LCFException lcfException = null;
    protected ServiceInterruption serviceInterruption = null;

    /** Constructor */
    public ProcessDocumentsFilter(IProcessActivity activities, DocumentSpecification spec)
    {
      this.activities = activities;
      this.spec = spec;
    }

    /** Decide if we accept the file.  This is where we will actually do the work. */
    public boolean accept(SmbFile f) throws SmbException
    {
      if (lcfException != null || serviceInterruption != null)
        return false;

      try
      {
        int type = f.getType();
        if (type != SmbFile.TYPE_SERVER && type != SmbFile.TYPE_FILESYSTEM && type != SmbFile.TYPE_SHARE)
          return false;
        String canonicalPath = getFileCanonicalPath(f);
        if (canonicalPath != null)
        {
          // manipulate path to include the DFS alias, not the literal path
          // String newPath = matchPrefix + canonicalPath.substring(matchReplace.length());
          String newPath = canonicalPath;

          // Check against the current specification.  This is a nicety to avoid queuing
          // documents that we will immediately turn around and remove.  However, if this
          // check was not here, everything should still function, provided the getDocumentVersions()
          // method does the right thing.
          if (checkInclude(f, newPath, spec))
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("JCIFS: Recorded path is '" + newPath + "' and is included.");
            activities.addDocumentReference(newPath);
          }
          else
          {
            if (Logging.connectors.isDebugEnabled())
              Logging.connectors.debug("JCIFS: Recorded path '"+newPath+"' is excluded!");
          }
        }
        else
          Logging.connectors.debug("JCIFS: Excluding a child file because canonical path is null");


        return false;
      }
      catch (LCFException e)
      {
        if (lcfException == null)
          lcfException = e;
        return false;
      }
      catch (ServiceInterruption e)
      {
        if (serviceInterruption == null)
          serviceInterruption = e;
        return false;
      }
    }

    /** Check for exception, and throw if there is one */
    public void checkAndThrow()
      throws ServiceInterruption, LCFException
    {
      if (lcfException != null)
        throw lcfException;
      if (serviceInterruption != null)
        throw serviceInterruption;
    }
  }

}