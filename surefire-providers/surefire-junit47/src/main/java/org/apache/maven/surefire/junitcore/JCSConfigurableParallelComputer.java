package org.apache.maven.surefire.junitcore;

import java.util.logging.Level;

import org.junit.experimental.cloud.JCSParallelRunner;
import org.junit.experimental.cloud.policies.SamplePolicy;
import org.junit.experimental.cloud.scheduling.JCSParallelScheduler;
import org.junit.runner.Computer;
import org.junit.runner.Runner;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.RunnerScheduler;

import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfiguration;
import at.ac.tuwien.infosys.jcloudscale.configuration.JCloudScaleConfigurationBuilder;
import at.ac.tuwien.infosys.jcloudscale.vm.JCloudScaleClient;
import at.ac.tuwien.infosys.jcloudscale.vm.docker.DockerCloudPlatformConfiguration;

/*
 * TODO FIXME Controlla perche' i test non vengono riportati, sembra quasi che faccia lo schedule e poi finito quello chi si e' visto si e' visto
 */
public class JCSConfigurableParallelComputer extends Computer {

	private int concurrentTestCasesLimit = Integer.parseInt(System.getProperty("concurrent.test.cases", "-1"));

	private int concurrentTestPerTestClassLimit = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.test.class", "-1"));

	private int concurrentTestsPerHostLimit = Integer.parseInt(System.getProperty("concurrent.tests.per.host", "-1"));

	private int concurrentTestsFromSameTestClassPerHost = Integer
			.parseInt(System.getProperty("concurrent.test.methods.per.host", "-1"));

	private int threadLimit = Integer.parseInt(System.getProperty("max.threads", "-1"));

	private int sizeLimit = Integer.parseInt(System.getProperty("max.host", "-1"));

	public JCSConfigurableParallelComputer() {
		configureJCloudScale();
	}

	// TODO THIS IS NOT THE BEST WE CAN DO !
	public void configureJCloudScale() {

		SamplePolicy policy = new SamplePolicy(sizeLimit, concurrentTestsPerHostLimit,
				concurrentTestsFromSameTestClassPerHost);

		JCloudScaleConfiguration config = new JCloudScaleConfigurationBuilder(new DockerCloudPlatformConfiguration(
				"http://192.168.56.101:2375", "", "alessio/jcs:0.4.6-SNAPSHOT-SHADED", "", "")).with(policy)
						.withCommunicationServerPublisher(false).withMQServer("192.168.56.101", 61616)
						.withLoggingClient(Level.OFF).withLoggingServer(Level.OFF).build();

		JCloudScaleClient.setConfiguration(config);

		// System.out.println("ConfigurableParallelComputer.configureJCloudScale()\n"
		// + "==== ==== ==== ==== ==== ==== \n"
		// + "JCSParallelRunner SETTING CONF () \n " + "==== ==== ==== ==== ====
		// ==== ");
	}

	private Runner parallelize(Runner runner, RunnerScheduler runnerInterceptor) {
		if (runner instanceof ParentRunner<?>) {
			((ParentRunner<?>) runner).setScheduler(runnerInterceptor);
		}
		return runner;
	}

	@Override
	protected Runner getRunner(RunnerBuilder builder, Class<?> testClass) throws Throwable {
		Runner runner = super.getRunner(builder, testClass);
		//
		if (runner instanceof JCSParallelRunner) {
			// System.out.println("JCSConfigurableParallelComputer.getRunner()
			// Overwrite the Scheduler with "
			// + concurrentTestsLimit + " " + threadLimit);
			((JCSParallelRunner) runner)
					.setScheduler(new JCSParallelScheduler(testClass, concurrentTestPerTestClassLimit, threadLimit));

		}
		return runner;
	}

	@Override
	public Runner getSuite(RunnerBuilder builder, java.lang.Class<?>[] classes) throws InitializationError {
		Runner suite = super.getSuite(builder, classes);
		// Use the scheduler to parallelize the execution ?
		return parallelize(suite, new JCSParallelScheduler(this.getClass(), concurrentTestCasesLimit, threadLimit));
	}

}
