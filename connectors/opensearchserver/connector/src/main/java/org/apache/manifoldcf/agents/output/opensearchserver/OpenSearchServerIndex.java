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

package org.apache.manifoldcf.agents.output.opensearchserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.manifoldcf.core.common.Base64;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;

public class OpenSearchServerIndex extends OpenSearchServerConnection {

  private class IndexRequestEntity implements RequestEntity {

    private String documentURI;

    private InputStream inputStream;

    private String fileName;

    public IndexRequestEntity(String documentURI, InputStream inputStream) {
      this.documentURI = documentURI;
      this.inputStream = inputStream;
      this.fileName = FilenameUtils.getName(documentURI);
    }

    public long getContentLength() {
      return -1;
    }

    public String getContentType() {
      return "text/xml; charset=utf-8";
    }

    public boolean isRepeatable() {
      return false;
    }

    public void writeRequest(OutputStream out) throws IOException {
      PrintWriter pw = new PrintWriter(out);
      try {
        pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
        pw.println("<index>);");
        pw.print("<document><field name=\"uri\"><value>");
        pw.print(documentURI);
        pw.println("</value></field>");
        pw.print("<binary fileName=\"");
        pw.print(fileName);
        pw.println("\">");
        Base64 base64 = new Base64();
        base64.encodeStream(inputStream, pw);
        pw.println("</binary></document>");
        pw.println("</index>");
      } catch (ManifoldCFException e) {
        throw new IOException(e.getMessage());
      } finally {
        IOUtils.closeQuietly(pw);
      }
    }
  }

  public OpenSearchServerIndex(String documentURI, InputStream inputStream,
      OpenSearchServerConfig config) throws ManifoldCFException {
    super(config);
    StringBuffer url = getApiUrl("update");
    PutMethod put = new PutMethod(url.toString());
    RequestEntity entity = new IndexRequestEntity(documentURI, inputStream);
    put.setRequestEntity(entity);
    call(put);
    if ("OK".equals(checkXPath(xPathStatus)))
      return;
    String error = checkXPath(xPathException);
    setResult(Result.ERROR, error);
    System.err.println(getResponse());
  }

}
