package org.apache.maven.surefire.junitcore;

import java.util.concurrent.ExecutionException;

import org.junit.experimental.cloud.JCSSuiteParallelRunner;
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
		System.out.println("\t\t JCSConfigurableParallelComputer.getRunner() " + runner);
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
		// Runner suite = super.getSuite(builder, classes);
		Runner suite = new JCSSuiteParallelRunner(new RunnerBuilder() {
			@Override
			public Runner runnerForClass(Class<?> testClass) throws Throwable {
				return getRunner(builder, testClass);
			}
		}, classes);

		System.out.println("JCSConfigurableParallelComputer.getSuite() " + suite);
		// Using only this suite will run one test case after the other, we need
		// to parallelize this.
		// return suite;

		// The problem is that we cannot wrap this into a suite a run that
		// otherwise we add an additional layer and reported numbers are
		// inaccurate. So For the moment we rely on the same classes of
		// ParallelComputer.
		// In particular to Class level, since method level is already done
		//
		// This is fine for all super level of parallelism but still fails when
		// it comes to reporting the final results
		// return parallelize(suite, new AsynchronousRunner(fService));
		return suite;
	}

}
