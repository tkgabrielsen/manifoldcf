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

/** This class is a mutable reference to a variable.
* It exists as a separate class so that the reference to the underlying
* variable can be easily modified.  The reference can, of course, be null.
*/
public class VariableReference
{
  protected Variable reference;
  
  public VariableReference()
  {
    reference = null;
  }
  
  public VariableReference(Variable object)
    throws ScriptException
  {
    reference = object;
  }
  
  public void setReference(Variable object)
    throws ScriptException
  {
    reference = object;
  }
  
  public Variable resolve()
  {
    return reference;
  }
}