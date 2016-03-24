package org.apache.maven.surefire.junitcore;

import java.util.concurrent.ExecutionException;

import org.junit.experimental.cloud.JCSSuiteParallelRunner;
import org.junit.experimental.cloud.scheduling.JCSParallelScheduler;
import org.junit.runner.Computer;
import org.junit.runner.Runner;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.RunnerScheduler;

/*
 * TODO FIXME Controlla perche' i test non vengono riportati, sembra quasi che faccia lo schedule e poi finito quello chi si e' visto si e' visto
 */
public class JCSConfigurableParallelComputer extends Computer {

	// For the moment run as many TestClasses as you like
	// private final ExecutorService fService = Executors.newCachedThreadPool();

	private Runner parallelize(Runner runner, RunnerScheduler runnerInterceptor) {
		if (runner instanceof ParentRunner<?>) {
			((ParentRunner<?>) runner).setScheduler(runnerInterceptor);
		}
		return runner;
	}

	/**
	 * We do not need to parallelize here as the runner is already parallelized!
	 */
	@Override
	protected Runner getRunner(RunnerBuilder builder, Class<?> testClass) throws Throwable {
		Runner runner = super.getRunner(builder, testClass);
		// This one must be of type JCSRunner() !
		System.out.println("\t\t JCSConfigurableParallelComputer.getRunner() " + runner);
		parallelize(runner, new JCSParallelScheduler(testClass, 1, -1));
		return runner;
	}

	@SuppressWarnings({ "UnusedDeclaration" })
	public void close() throws ExecutionException {

		System.out.println("ConfigurableParallelComputer.close()");

		// fService.shutdown();
		// try {
		// // This is quite bad..
		// fService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
		// } catch (InterruptedException e) {
		// throw new NestedRuntimeException(e);
		// }
	}

	@Override
	public Runner getSuite(final RunnerBuilder builder, java.lang.Class<?>[] classes) throws InitializationError {
		System.out.println("JCSConfigurableParallelComputer.getSuite()");
		Runner suite = super.getSuite(builder, classes); // This call all the
															// possible
		// TODO Change with parameters
		return parallelize(suite, new JCSParallelScheduler(null, 1, -1));
	}

}
