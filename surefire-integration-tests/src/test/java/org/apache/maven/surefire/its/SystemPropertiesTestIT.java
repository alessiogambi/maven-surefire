package org.apache.maven.surefire.its;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.it.Verifier;
import org.apache.maven.it.util.ResourceExtractor;

import java.io.File;
import java.util.ArrayList;

/**
 * Test system properties
 *
 * @author <a href="mailto:dfabulich@apache.org">Dan Fabulich</a>
 */
public class SystemPropertiesTestIT
    extends AbstractSurefireIntegrationTestClass
{
    public void testSystemProperties()
        throws Exception
    {
        File testDir = ResourceExtractor.simpleExtractResources( getClass(), "/system-properties" );

        Verifier verifier = new Verifier( testDir.getAbsolutePath() );
        ArrayList goals = getInitialGoals();
        goals.add( "test" );
        // SUREFIRE-121... someday we should re-enable this
        // goals.add( "-DsetOnMavenCommandLine=baz" );

        goals.add( "-DsetOnArgLineWorkAround=baz" );
        verifier.executeGoals( goals );
        verifier.verifyErrorFreeLog();
        verifier.resetStreams();

        HelperAssertions.assertTestSuiteResults( 6, 0, 0, 0, testDir );
    }

    public void testSystemPropertiesNoFork()
        throws Exception
    {
        File testDir = ResourceExtractor.simpleExtractResources( getClass(), "/system-properties" );

        Verifier verifier = new Verifier( testDir.getAbsolutePath() );
        ArrayList goals = getInitialGoals();
        goals.add( "test" );
        goals.add( "-DforkMode=never" );
        goals.add( "-DsetOnArgLineWorkAround=baz" );
        // SUREFIRE-121... someday we should re-enable this
        // goals.add( "-DsetOnMavenCommandLine=baz" );
        // DGF fake the argLine, since we're not forking
        goals.add( "-DsetOnArgLine=bar" );
        verifier.executeGoals( goals );
        verifier.verifyErrorFreeLog();
        verifier.resetStreams();

        HelperAssertions.assertTestSuiteResults( 6, 0, 0, 0, testDir );
    }
}