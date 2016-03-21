package org.apache.maven.surefire.junitcore;

import java.util.Arrays;

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
import org.apache.maven.surefire.common.junit4.JUnit4RunListener;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.TestsToRun;
import org.junit.experimental.cloud.JCSParallelRunner;
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
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

/**
 * Encapsulates access to JUnitCore
 *
 * @author Kristian Rosenvold
 */

class JCSJUnitCoreWrapper {

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

		// TODO We alway force

		// This is the one managing the multi-threading stuff.
		// TODO This one is the one creating the wrapping suite using getSuite
		// we need to extends or enforce our !
		Computer computer = getComputer(jUnitCoreParameters);

		System.out.println("JCSJUnitCoreWrapper.execute() Computer " + computer);

		JUnitCore junitCore = createJCSJUnitCore(listeners);

		try {
			if (testsToRun.allowEagerReading()) {
				executeEager(testsToRun, filter, computer, junitCore);
			} else {
				exeuteLazy(testsToRun, filter, computer, junitCore);
			}
		} finally {
			closeIfConfigurable(computer);
		}
	}

	// TODO Here we should add our listener
	private static JUnitCore createJCSJUnitCore(List<RunListener> listeners) {
		// System.out.println("JCSJUnitCoreWrapper.createJCSJUnitCore()");
		JUnitCore junitCore = new JUnitCore();
		for (RunListener runListener : listeners) {
			// System.out.println("JCSJUnitCoreWrapper.createJCSJUnitCore()
			// Adding " + runListener);
			junitCore.addListener(runListener);
		}
		return junitCore;
	}

	private static void executeEager(TestsToRun testsToRun, Filter filter, Computer computer, JUnitCore junitCore)
			throws TestSetFailedException {
		// System.out.println("JCSJUnitCoreWrapper.executeEager()");
		Class[] tests = testsToRun.getLocatedClasses();
		System.out.println("JCSJUnitCoreWrapper.executeEager() Tests " + Arrays.toString(tests));
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
		Runner suite;
		try {
			suite = computer.getSuite(new RunnerBuilder() {

				@Override
				public Runner runnerForClass(Class<?> testClass) throws Throwable {
					System.out.println("JCSJUnitCoreWrapper.My run builder runner for class " + testClass);
					// TODO Parameter passing ! Where parameters come from ?!
					return new JCSParallelRunner(testClass, -1, -1);
				}
			}, classesToRun);
		} catch (InitializationError e) {
			throw new RuntimeException(
					"Bug in saff's brain: Suite constructor, called as above, should always complete");
		}

		//
		Request req = Request.runner(suite);
		if (filter != null) {
			req = new FilteringRequest(req, filter);
			if (req.getRunner() == null) {
				// nothing to run
				return;
			}
		}

		final Result run = junitCore.run(req);
		JUnit4RunListener.rethrowAnyTestMechanismFailures(run);
	}

	private static void closeIfConfigurable(Computer computer) throws TestSetFailedException {
		if (computer instanceof ConfigurableParallelComputer) {
			try {
				((ConfigurableParallelComputer) computer).close();
			} catch (ExecutionException e) {
				throw new TestSetFailedException(e);
			}
		}
	}

	private static Computer getComputer(JUnitCoreParameters jUnitCoreParameters) throws TestSetFailedException {
		System.out.println("JCSJUnitCoreWrapper.getComputer() jUnitCoreParameters.isNoThreading() "
				+ jUnitCoreParameters.isNoThreading());
		if (jUnitCoreParameters.isNoThreading()) {
			System.out.println("JCSJUnitCoreWrapper.getComputer() return a plain Computer");
			return new Computer();
		}
		return getConfigurableParallelComputer(jUnitCoreParameters);
	}

	private static Computer getConfigurableParallelComputer(JUnitCoreParameters jUnitCoreParameters)
			throws TestSetFailedException {

		System.out.println("JUnitCoreWrapper.getConfigurableParallelComputer()");

		if (jUnitCoreParameters.isUseUnlimitedThreads()) {
			return new ConfigurableParallelComputer();
		} else {
			return new ConfigurableParallelComputer(
					jUnitCoreParameters.isParallelClasses() | jUnitCoreParameters.isParallelBoth(),
					jUnitCoreParameters.isParallelMethod() | jUnitCoreParameters.isParallelBoth(),
					jUnitCoreParameters.getThreadCount(), jUnitCoreParameters.isPerCoreThreadCount());
		}
	}

}
