/* $Id: ElasticSearchAction.java 1299512 2012-03-12 00:58:38Z piergiorgio $ */

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

package org.apache.manifoldcf.agents.output.elasticsearch;

import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.HttpClient;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;

public class ElasticSearchAction extends ElasticSearchConnection
{

  public enum CommandEnum
  {
    _optimize, _refresh, _status;
  }

  public ElasticSearchAction(HttpClient client, CommandEnum cmd, ElasticSearchConfig config, boolean checkConnection)
      throws ManifoldCFException
  {
    super(config, client);
    StringBuffer url = getApiUrl(cmd.toString(), checkConnection);
    GetMethod method = new GetMethod(url.toString());
    call(method);
    if ("true".equals(checkJson(jsonStatus)))
      return;
    setResult(Result.ERROR, checkJson(jsonException));
  }
}
