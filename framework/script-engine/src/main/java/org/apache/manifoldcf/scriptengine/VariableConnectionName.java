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

package org.apache.manifoldcf.scriptengine;


/** Variable class representing a ManifoldCF API URL connection name segment.  In conjunction
* with the URL variable, this variable will properly character-stuff the connection name to make
* a valid URL.
*/
public class VariableConnectionName extends VariableBase
{
  protected String encodedConnectionName;
  protected String connectionName;
  
  public VariableConnectionName(String connectionName)
  {
    this.connectionName = connectionName;
    this.encodedConnectionName = encode(connectionName);
  }

  public int hashCode()
  {
    return connectionName.hashCode();
  }
  
  public boolean equals(Object o)
  {
    if (!(o instanceof VariableConnectionName))
      return false;
    return ((VariableConnectionName)o).connectionName.equals(connectionName);
  }

  /** Get the variable's script value */
  public String getScriptValue()
    throws ScriptException
  {
    StringBuilder sb = new StringBuilder();
    sb.append("(new connectionname \"");
    int i = 0;
    while (i < connectionName.length())
    {
      char x = connectionName.charAt(i++);
      if (x == '\\' || x == '\"')
        sb.append('\\');
      sb.append(x);
    }
    sb.append("\")");
    return sb.toString();
  }
  
  /** Get the variable's value as a string */
  public String getStringValue()
    throws ScriptException
  {
    return encodedConnectionName;
  }

  public VariableReference doubleEquals(Variable v)
    throws ScriptException
  {
    if (v == null)
      throw new ScriptException(composeMessage("Binary '==' operand cannot be null"));
    return new VariableBoolean(encodedConnectionName.equals(v.getStringValue()));
  }

  public VariableReference exclamationEquals(Variable v)
    throws ScriptException
  {
    if (v == null)
      throw new ScriptException(composeMessage("Binary '!=' operand cannot be null"));
    return new VariableBoolean(!encodedConnectionName.equals(v.getStringValue()));
  }

  protected static String encode(String connectionName)
  {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < connectionName.length())
    {
      char x = connectionName.charAt(i++);
      if (x == '/')
        sb.append('.').append('+');
      else if (x == '.')
        sb.append('.').append('.');
      else
        sb.append(x);
    }
    return sb.toString();
  }
  
}
