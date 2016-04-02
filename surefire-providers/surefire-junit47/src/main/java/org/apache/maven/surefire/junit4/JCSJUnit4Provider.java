package org.apache.maven.surefire.junit4;

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

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import org.apache.maven.shared.utils.io.SelectorUtils;
import org.apache.maven.surefire.common.junit4.JUnit4RunListener;
import org.apache.maven.surefire.common.junit4.JUnit4RunListenerFactory;
import org.apache.maven.surefire.common.junit4.JUnit4TestChecker;
import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ConsoleLogger;
import org.apache.maven.surefire.report.ConsoleOutputCapture;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.PojoStackTraceWriter;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.ReporterException;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.SimpleReportEntry;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.RunOrderCalculator;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.TestsToRun;
import org.apache.maven.surefire.util.internal.StringUtils;
import org.junit.experimental.cloud.JCSRunner;
import org.junit.experimental.cloud.policies.SamplePolicy;
import org.junit.experimental.cloud.scheduling.JCSParallelScheduler;
import org.junit.experimental.cloud.shared.TestToHostMapping;
import org.junit.internal.runners.JUnit38ClassRunner;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.RunnerScheduler;

import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfiguration;
import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfigurationBuilder;
import at.ac.tuwien.infosys.jcloudscale.vm.JCloudScaleClient;
import at.ac.tuwien.infosys.jcloudscale.vm.docker.DockerCloudPlatformConfiguration;

/**
 * @author Alessio Gambi
 */
public class JCSJUnit4Provider extends AbstractProvider {

	private static int concurrentTestCasesLimit = Integer.parseInt(System.getProperty("concurrent.test.cases", "-1"));

	private static int concurrentTestPerTestClassLimit = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.test.class", "-1"));

	private static int concurrentTestsPerHostLimit = Integer
			.parseInt(System.getProperty("concurrent.tests.per.host", "-1"));

	private static int concurrentTestsFromSameTestClassPerHost = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.host", "-1"));

	private static int sizeLimit = Integer.parseInt(System.getProperty("max.host", "-1"));

	private static String loggerClient = System.getProperty("logger.client", "" + Level.INFO);
	private static String loggerServer = System.getProperty("logger.server", "" + Level.INFO);

	/**
	 * HARDCODED CONFIGURATION IS BAD, BUT ONLY SOME PARAMETERS SHOULD BE
	 * EXPOSED
	 */
	private static void configureDefaultJCloudScale() {

		SamplePolicy policy = new SamplePolicy(sizeLimit, concurrentTestsPerHostLimit,
				concurrentTestsFromSameTestClassPerHost);

		// JCloudScaleConfiguration config = new
		// JCloudScaleConfigurationBuilder(new
		// LocalCloudPlatformConfiguration())
		// .with(policy).withLoggingClient(Level.OFF).withLoggingServer(Level.OFF).build();

		// TODO Reads this from files
		JCloudScaleConfiguration config = new JCloudScaleConfigurationBuilder(new DockerCloudPlatformConfiguration(
				"http://192.168.56.101:2375", "", "alessio/jcs:0.4.6-SNAPSHOT-SHADED", "", ""))//
						.with(policy)//
						.withCommunicationServerPublisher(false)// ?
						.withMQServer("192.168.56.101", 61616)//
						.withLoggingClient(Level.parse(loggerClient)).withLoggingServer(Level.parse(loggerServer))//
						.withRedirectAllOutput(false)//
						.withDistributionTraceLogger(true).withDistributionTraceLogger(Level.INFO) // TODO
																									// How
																									// this
																									// work
																									// with
																									// non
																									// redirecting
																									// stuff
																									// ?
						.withMonitoring(true) // Events
						.build();

		JCloudScaleClient.setConfiguration(config);

		// File tmp;
		// try {
		// tmp = File.createTempFile("tmp", ".xml");
		// config.save(tmp);
		// System.out.println("JCSJUnit4Provider.configureJCloudScale() Config
		// Store at " + tmp.getAbsolutePath());
		// } catch (IOException e) {
		// }

	}

	// -----------

	private final ClassLoader testClassLoader;

	private final List<org.junit.runner.notification.RunListener> customRunListeners;

	private final JUnit4TestChecker jUnit4TestChecker;

	private final String requestedTestMethod;

	private TestsToRun testsToRun;

	private final ProviderParameters providerParameters;

	private final RunOrderCalculator runOrderCalculator;

	private final ScanResult scanResult;

	// private static Logger log;

	public JCSJUnit4Provider(ProviderParameters booterParameters) {
		this.providerParameters = booterParameters;
		this.testClassLoader = booterParameters.getTestClassLoader();
		this.scanResult = booterParameters.getScanResult();
		this.runOrderCalculator = booterParameters.getRunOrderCalculator();
		customRunListeners = JUnit4RunListenerFactory
				.createCustomListeners(booterParameters.getProviderProperties().getProperty("listener"));
		jUnit4TestChecker = new JUnit4TestChecker(testClassLoader);
		requestedTestMethod = booterParameters.getTestRequest().getRequestedTestMethod();

		ConsoleLogger consoleLog = booterParameters.getConsoleLogger();
		if (consoleLog != null) {
			consoleLog.info("==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ==== \n" //
					+ "\t\t JCS JUnit4Provider Configuration \n" + ""
					+ "==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ==== \n" //
					+ "\t concurrentTestCasesLimit\t\t" + String.format("%2s", concurrentTestCasesLimit) + "\n"//
					+ "\t concurrentTestPerTestClassLimit\t" + String.format("%2s", concurrentTestPerTestClassLimit)
					+ "\n"//
					+ "\t concurrentTestsPerHostLimit\t\t" + String.format("%2s", concurrentTestsPerHostLimit) + "\n"//
					+ "\t concurrentTestsFromSameTestClassPerHost"
					+ String.format("%2s", concurrentTestsFromSameTestClassPerHost) + "\n"//
					+ "\t sizeLimit\t\t\t\t" + String.format("%2s", sizeLimit) + "\n"//
					+ "==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ==== ====\n");
		}
		configureDefaultJCloudScale();

		// Cannot set this ther right way !
		// log =
		// JCloudScaleClient.getConfiguration().getLogger(JCSJUnit4Provider.class.getName());
		// log.setLevel(Level.FINEST);

		scheduler = new JCSParallelScheduler(null, concurrentTestCasesLimit);

	}

	public RunResult invoke(Object forkTestSet) throws TestSetFailedException, ReporterException {
		if (testsToRun == null) {
			if (forkTestSet instanceof TestsToRun) {
				testsToRun = (TestsToRun) forkTestSet;
			} else if (forkTestSet instanceof Class) {
				testsToRun = TestsToRun.fromClass((Class) forkTestSet);
			} else {
				testsToRun = scanClassPath();
			}
		}

		upgradeCheck();

		final ReporterFactory reporterFactory = providerParameters.getReporterFactory();

		final RunListener reporter = reporterFactory.createReporter();

		ConsoleOutputCapture.startCapture((ConsoleOutputReceiver) reporter);

		JUnit4RunListener jUnit4TestSetReporter = new JUnit4RunListener(reporter);

		Result result = new Result();
		final RunNotifier runNotifer = getRunNotifer(jUnit4TestSetReporter, result, customRunListeners);

		runNotifer.fireTestRunStarted(null);

		if (concurrentTestCasesLimit != 1) {
			try {
				for (@SuppressWarnings("unchecked")
				Iterator<Class<?>> iter = testsToRun.iterator(); iter.hasNext();) {
					// This shall submit test for the execution.
					final Class<?> testClass = iter.next();
					// System.out.println(
					// "JCSJUnit4Provider.invoke() Submit " +
					// testClass.getSimpleName() + " for execution");

					scheduler.schedule(new Runnable() {
						public void run() {
							try {
								executeTestSet(testClass, reporter, runNotifer);
							} catch (ReporterException e) {
								e.printStackTrace();
							} catch (TestSetFailedException e) {
								e.printStackTrace();
							}
						}
					});
				}
			} finally {
				// System.out.println("All test cases submitted.");
				// Here let's wait for the result and blocks
				scheduler.finished();
			}
		} else {
			// Synchronous
			for (@SuppressWarnings("unchecked")
			Iterator<Class<?>> iter = testsToRun.iterator(); iter.hasNext();) {
				executeTestSet(iter.next(), reporter, runNotifer);
			}

		}

		runNotifer.fireTestRunFinished(result);

		JUnit4RunListener.rethrowAnyTestMechanismFailures(result);

		closeRunNotifer(jUnit4TestSetReporter, customRunListeners);

		return reporterFactory.close();
	}

	private void executeTestSet(Class<?> clazz, RunListener reporter, RunNotifier listeners)
			throws ReporterException, TestSetFailedException {

		// System.out.println("JCSJUnit4Provider.executeTestSet() Executing " +
		// clazz);

		// This one is the guy responsible to printout the summary after the
		// execution ?
		final ReportEntry report = new SimpleReportEntry(this.getClass().getName(), clazz.getName());

		reporter.testSetStarting(report);

		try {

			TestToHostMapping.get().registerTestClass(clazz);

			String[] testMethods = null;
			if (!StringUtils.isBlank(this.requestedTestMethod)) {
				String actualTestMethod = getMethod(clazz, this.requestedTestMethod);
				testMethods = StringUtils.split(actualTestMethod, "+");

			}
			// if (concurrentTestPerTestClassLimit != 1) {
			executeParallel(clazz, listeners, testMethods);
			// } else {
			// executeSequential(clazz, listeners, testMethods);
			// }

		} catch (TestSetFailedException e) {
			throw e;
		} catch (Throwable e) {
			reporter.testError(SimpleReportEntry.withException(report.getSourceName(), report.getName(),
					new PojoStackTraceWriter(report.getSourceName(), report.getName(), e)));
		} finally {
			reporter.testSetCompleted(report);
		}
	}

	private RunNotifier getRunNotifer(org.junit.runner.notification.RunListener main, Result result,
			List<org.junit.runner.notification.RunListener> others) {
		RunNotifier fNotifier = new RunNotifier();
		fNotifier.addListener(main);
		fNotifier.addListener(result.createListener());
		for (org.junit.runner.notification.RunListener listener : others) {
			fNotifier.addListener(listener);
		}
		return fNotifier;
	}

	// I am not entierly sure as to why we do this explicit freeing, it's one of
	// those
	// pieces of code that just seem to linger on in here ;)
	private void closeRunNotifer(org.junit.runner.notification.RunListener main,
			List<org.junit.runner.notification.RunListener> others) {
		RunNotifier fNotifier = new RunNotifier();
		fNotifier.removeListener(main);
		for (org.junit.runner.notification.RunListener listener : others) {
			fNotifier.removeListener(listener);
		}
	}

	public Iterator<?> getSuites() {
		testsToRun = scanClassPath();
		return testsToRun.iterator();
	}

	private TestsToRun scanClassPath() {
		final TestsToRun scannedClasses = scanResult.applyFilter(jUnit4TestChecker, testClassLoader);
		return runOrderCalculator.orderTestClasses(scannedClasses);
	}

	@SuppressWarnings("unchecked")
	private void upgradeCheck() throws TestSetFailedException {
		if (isJunit4UpgradeCheck()) {
			List<Class> classesSkippedByValidation = scanResult.getClassesSkippedByValidation(jUnit4TestChecker,
					testClassLoader);
			if (!classesSkippedByValidation.isEmpty()) {
				StringBuilder reason = new StringBuilder();
				reason.append("Updated check failed\n");
				reason.append("There are tests that would be run with junit4 / surefire 2.6 but not with [2.7,):\n");
				for (Class testClass : classesSkippedByValidation) {
					reason.append("   ");
					reason.append(testClass.getName());
					reason.append("\n");
				}
				throw new TestSetFailedException(reason.toString());
			}
		}
	}

	private boolean isJunit4UpgradeCheck() {
		final String property = System.getProperty("surefire.junit4.upgradecheck");
		return property != null;
	}

	//
	private static RunnerScheduler scheduler;

	private static void executeParallel(Class<?> testClass, final RunNotifier fNotifier, String[] testMethods)
			throws TestSetFailedException {

		// System.out.println("JCSJUnit4Provider.executeParallel() " +
		// testClass);
		if (null != testMethods) {

			Method[] methods = testClass.getMethods();
			for (Method method : methods) {
				for (String testMethod : testMethods) {
					if (SelectorUtils.match(testMethod, method.getName())) {
						final String methodName = method.getName();
						// NPE ?!
						// System.out.println(
						// "\n-----\n\tJCSJUnit4Provider.executeParallel() :
						// THIS BRANCH WAS NEVER TESTED !");

						Runner junitTestRunner = Request.method(testClass, methodName).getRunner();
						if (junitTestRunner instanceof JCSRunner) {
							((JCSRunner) junitTestRunner)
									.setScheduler(new JCSParallelScheduler(testClass, concurrentTestPerTestClassLimit));
						}
						junitTestRunner.run(fNotifier);
					}
				}
				return;
			}
		}

		Runner junitTestRunner = Request.aClass(testClass).getRunner();

		if (junitTestRunner instanceof JCSRunner) {
			// System.out.println("JUnit 3 style but Junit 4 runner. Override
			// scheduler for test " + testClass);
			((JCSRunner) junitTestRunner)
					.setScheduler(new JCSParallelScheduler(testClass, concurrentTestPerTestClassLimit));

		} else if (junitTestRunner instanceof JUnit38ClassRunner) {
			// System.out.println("JUnit 3 style and Junit 3 runner. Override
			// scheduler for test " + testClass);
			((JUnit38ClassRunner) junitTestRunner)
					.setScheduler(new JCSParallelScheduler(testClass, concurrentTestPerTestClassLimit));

		}
		junitTestRunner.run(fNotifier);
	}

	private static void executeSequential(Class<?> testClass, final RunNotifier fNotifier, String[] testMethods)
			throws TestSetFailedException {
		// System.out.println("JCSJUnit4Provider.executeSequential() " +
		// testClass);
		if (null != testMethods) {

			Method[] methods = testClass.getMethods();
			for (Method method : methods) {
				for (String testMethod : testMethods) {
					if (SelectorUtils.match(testMethod, method.getName())) {
						final String methodName = method.getName();
						Runner junitTestRunner = Request.method(testClass, methodName).getRunner();
						junitTestRunner.run(fNotifier);
					}
				}
				return;
			}
		}

		Runner junitTestRunner = Request.aClass(testClass).getRunner();
		junitTestRunner.run(fNotifier);
	}

	/**
	 * this method retrive testMethods from String like
	 * "com.xx.ImmutablePairTest#testBasic,com.xx.StopWatchTest#testLang315+testStopWatchSimpleGet"
	 * <br>
	 * and we need to think about cases that 2 or more method in 1 class. we
	 * should choose the correct method
	 *
	 * @param testClass
	 *            the testclass
	 * @param testMethodStr
	 *            the test method string
	 * @return a string ;)
	 */
	private static String getMethod(Class testClass, String testMethodStr) {
		String className = testClass.getName();

		if (!testMethodStr.contains("#") && !testMethodStr.contains(",")) {// the
																			// original
																			// way
			return testMethodStr;
		}
		testMethodStr += ",";// for the bellow split code
		int beginIndex = testMethodStr.indexOf(className);
		int endIndex = testMethodStr.indexOf(",", beginIndex);
		String classMethodStr = testMethodStr.substring(beginIndex, endIndex);// String
																				// like
																				// "StopWatchTest#testLang315"

		int index = classMethodStr.indexOf('#');
		if (index >= 0) {
			return classMethodStr.substring(index + 1, classMethodStr.length());
		}
		return null;
	}
}
