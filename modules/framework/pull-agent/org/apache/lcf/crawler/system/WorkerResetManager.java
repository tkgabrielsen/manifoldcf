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
package org.apache.lcf.crawler.system;

import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.crawler.interfaces.*;
import java.io.*;
import java.util.*;

/** Class which handles reset for worker thread pool.  The reset action here
* is to mark all active documents as being ready for queuing.
*/
public class WorkerResetManager extends ResetManager
{
  public static final String _rcsid = "@(#)$Id$";

  /** The document queue */
  protected DocumentQueue dq;

  /** Constructor. */
  public WorkerResetManager(DocumentQueue dq)
  {
    super();
    this.dq = dq;
  }

  /** Reset */
  protected void performResetLogic(IThreadContext tc)
    throws LCFException
  {
    IJobManager jobManager = JobManagerFactory.make(tc);
    jobManager.resetDocumentWorkerStatus();
    dq.clear();
  }
}
