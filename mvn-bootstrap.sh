#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# File: mvn-bootstrap.sh
#
# Created: Wednesday, September 21 2011 by Alex Ott
#

ant make-deps

mvn install:install-file -Dfile=lib/jdbcpool.jar -DgroupId=com.bitmechanic -DartifactId=jdbcpool -Dversion=0.99 -Dpackaging=jar

mvn install:install-file -Dfile=lib/commons-httpclient.jar -DgroupId=commons-httpclient -DartifactId=commons-httpclient -Dversion=3.1-mcf-1  -Dpackaging=jar
mvn install:install-file -Dfile=lib/xercesImpl.jar -DgroupId=xerces -DartifactId=xercesImpl -Dversion=2.9.1-mcf-1  -Dpackaging=jar
mvn install:install-file -Dfile=connectors/jcifs/lib-proprietary/jcifs.jar -DgroupId=org.samba.jcifs -DartifactId=jcifs -Dversion=1.3.17  -Dpackaging=jar

mvn install:install-file -Dfile=lib/opensaml.jar -DgroupId=org.opensaml -DartifactId=opensaml -Dversion=1.0.1 -Dpackaging=jar
mvn install:install-file -Dfile=lib/xmlsec.jar -DgroupId=xml-security -DartifactId=xmlsec -Dversion=1.4.1 -Dpackaging=jar

mvn install:install-file -Dfile=lib/hsqldb.jar -DgroupId=org.hsqldb -DartifactId=hsqldb -Dversion=2.2.9 -Dpackaging=jar

echo "Dependencies installed"
