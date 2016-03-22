package org.apache.maven.surefire.junitcore;

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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

import org.apache.maven.surefire.common.junit4.JUnit4RunListener;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.TestsToRun;
import org.junit.experimental.cloud.JCSParallelRunner;
import org.junit.experimental.cloud.JCSSuiteParallelRunner;
import org.junit.experimental.cloud.listeners.JCSJunitExecutionListener;
import org.junit.experimental.cloud.policies.SamplePolicy;
import org.junit.runner.Computer;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfiguration;
import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfigurationBuilder;
import at.ac.tuwien.infosys.jcloudscale.vm.JCloudScaleClient;
import at.ac.tuwien.infosys.jcloudscale.vm.docker.DockerCloudPlatformConfiguration;

/**
 * Encapsulates access to JUnitCore
 *
 * @author Kristian Rosenvold
 */

class JCSJUnitCoreWrapper {

	private static int concurrentTestCasesLimit = Integer.parseInt(System.getProperty("concurrent.test.cases", "-1"));

	private static int concurrentTestPerTestClassLimit = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.test.class", "-1"));

	private static int concurrentTestsPerHostLimit = Integer
			.parseInt(System.getProperty("concurrent.tests.per.host", "-1"));

	private static int concurrentTestsFromSameTestClassPerHost = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.host", "-1"));

	private static int threadLimit = Integer.parseInt(System.getProperty("max.threads", "-1"));

	private static int sizeLimit = Integer.parseInt(System.getProperty("max.host", "-1"));

	private static void configureJCloudScale() {

		SamplePolicy policy = new SamplePolicy(sizeLimit, concurrentTestsPerHostLimit,
				concurrentTestsFromSameTestClassPerHost);

		JCloudScaleConfiguration config = new JCloudScaleConfigurationBuilder(new DockerCloudPlatformConfiguration(
				"http://192.168.56.101:2375", "", "alessio/jcs:0.4.6-SNAPSHOT-SHADED", "", "")).with(policy)
						.withCommunicationServerPublisher(false).withMQServer("192.168.56.101", 61616)
						.withLoggingClient(Level.INFO).withLoggingServer(Level.INFO).build();

		JCloudScaleClient.setConfiguration(config);

		System.out.println("ConfigurableParallelComputer.configureJCloudScale()\n" + "==== ==== ==== ==== ==== ==== \n"
				+ "SETTING JCS CONF () \n " //
				+ "concurrentTestCasesLimit\t" + concurrentTestCasesLimit + "\n"//
				+ "concurrentTestPerTestClassLimit\t" + concurrentTestPerTestClassLimit + "\n"//
				+ "concurrentTestsPerHostLimit\t" + concurrentTestsPerHostLimit + "\n"//
				+ "concurrentTestsFromSameTestClassPerHost\t" + concurrentTestsFromSameTestClassPerHost + "\n"//
				+ "threadLimit\t" + threadLimit + "\n"//
				+ "sizeLimit\t" + sizeLimit + "\n"//

				+ "" + "" + "==== ==== ==== ==== ==== ==== ");
	}

	private static class FilteringRequest extends Request {
		private Runner filteredRunner;

		public FilteringRequest(Request req, Filter filter) {
			try {
				Runner runner = req.getRunner();
				filter.apply(runner);
				filteredRunner = runner;
			} catch (NoTestsRemainException e) {
				filteredRunner = null;
			}
		}

		@Override
		public Runner getRunner() {
			return filteredRunner;
		}
	}

	public static void execute(TestsToRun testsToRun, JUnitCoreParameters jUnitCoreParameters,
			List<RunListener> listeners, Filter filter) throws TestSetFailedException {

		configureJCloudScale();
		// Computer computer = getJCSComputer(jUnitCoreParameters); // new
		// JCSConfigurableParallelComputer();
		// The tirck with parallel Computer is that it uses the same executor
		// service and wait the completion of it.

		// How those two are related ? Can be that computer starts before ?
		JUnitCore junitCore = createJCSJUnitCore(listeners);

		// Computer computer =
		// getConfigurableParallelComputer(jUnitCoreParameters);
		Computer computer = getJCSParallelComputer();
		System.out.println("JCSJUnitCoreWrapper.execute() USING " + computer);

		try {
			System.out.println("JCSJUnitCoreWrapper.execute() START");
			if (testsToRun.allowEagerReading()) {
				executeEager(testsToRun, filter, computer, junitCore);
			} else {
				exeuteLazy(testsToRun, filter, computer, junitCore);
			}
		} finally {
			System.out.println("JCSJUnitCoreWrapper.execute() END");
			closeIfConfigurable(computer);
		}
	}

	private static JUnitCore createJCSJUnitCore(List<RunListener> listeners) {
		JUnitCore junitCore = new JUnitCore();

		// Force our listener to be the first !
		RunListener listener = new JCSJunitExecutionListener();
		junitCore.addListener(listener);
		System.out.println("JCSJUnitCoreWrapper.createJCSJUnitCore() Adding listener " + listener);
		for (RunListener runListener : listeners) {
			System.out.println("JCSJUnitCoreWrapper.createJCSJUnitCore() Adding listener " + runListener);
			junitCore.addListener(runListener);
		}
		return junitCore;
	}

	private static void executeEager(TestsToRun testsToRun, Filter filter, Computer computer, JUnitCore junitCore)
			throws TestSetFailedException {
		Class[] tests = testsToRun.getLocatedClasses();
		createReqestAndRun(filter, computer, junitCore, tests);
	}

	private static void exeuteLazy(TestsToRun testsToRun, Filter filter, Computer computer, JUnitCore junitCore)
			throws TestSetFailedException {
		// in order to support LazyTestsToRun, the iterator must be used
		Iterator<?> classIter = testsToRun.iterator();
		while (classIter.hasNext()) {
			createReqestAndRun(filter, computer, junitCore, new Class[] { (Class<?>) classIter.next() });
		}
	}

	private static void createReqestAndRun(Filter filter, Computer computer, JUnitCore junitCore,
			Class<?>[] classesToRun) throws TestSetFailedException {
		// Request req = Request.classes(computer, classesToRun);
		// Inject and configure our JCSRunners !
		// TODO This builder is the one that define the Runner
		RunnerBuilder builder = new RunnerBuilder() {

			@Override
			public Runner runnerForClass(Class<?> testClass) throws Throwable {
				System.out.println("JCSJUnitCoreWrapper.runnerForClass() " + testClass);
				return new JCSParallelRunner(testClass, concurrentTestPerTestClassLimit, threadLimit);
			}

		};
		Runner suite;
		try {
			suite = new JCSSuiteParallelRunner(builder, classesToRun, concurrentTestCasesLimit, threadLimit);
		} catch (InitializationError e) {
			throw new RuntimeException(
					"Bug in saff's brain: Suite constructor, called as above, should always complete");
		}

		Request req = Request.runner(suite);
		if (filter != null) {
			req = new FilteringRequest(req, filter);
			if (req.getRunner() == null) {
				// nothing to run
				return;
			}
		}

		System.out.println("JCSJUnitCoreWrapper.createReqestAndRun() Start to run ");
		final Result run = junitCore.run(req);
		JUnit4RunListener.rethrowAnyTestMechanismFailures(run);

		// Create another summary just for the sake of it, or try to synch this
		// one with the "main" one that somehow has the wrong numbers since it
		// outputs before the execution is over...
		System.out.println("JCSJUnitCoreWrapper.createReqestAndRun() End of the run \n" //
				+ "- " + run.getRunCount() + " -- \n" //
				+ "- " + run.getFailureCount() + " -- \n" //
				+ "- " + run.getIgnoreCount() + " -- \n"//
				//
				+ run.getRunTime());
	}

	private static void closeIfConfigurable(Computer computer) throws TestSetFailedException {
		System.out.println("JCSJUnitCoreWrapper.closeIfConfigurable()");
		if (computer instanceof ConfigurableParallelComputer) {
			try {
				((ConfigurableParallelComputer) computer).close();
			} catch (ExecutionException e) {
				throw new TestSetFailedException(e);
			}
		}
	}

	private static Computer getJCSParallelComputer() throws TestSetFailedException {
		return new JCSConfigurableParallelComputer();
	}

}
