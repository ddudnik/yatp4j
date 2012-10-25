package by.grodnosoft.yatp4j;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import by.grodnosoft.yatp4j.SimpleThreadPoolExecutor.Priority;

public class SimpleThreadPoolExecutorTest {

	private static final int testRunnablesCount = 40;
	private static Map<Runnable, Priority> testRunnables;

	@BeforeClass
	public static void prepareTestTasks() throws Exception {
		testRunnables = new HashMap<Runnable, Priority>();
		for (int i = 0; i < testRunnablesCount; i++) {
			final int count = i + 1;
			final Priority priority = getRandomPriority();
			testRunnables.put(new Runnable() {
				@Override
				public void run() {
					System.out.println("Task No. " + count
							+ priority.stringValue());
				}
			}, priority);
		}
	}

	private static Priority getRandomPriority() {
		Priority[] priorities = Priority.values();
		return priorities[(int) (Math.random() * 3)];
	}

	@Test
	public void testExecution() throws Exception {
		SimpleThreadPoolExecutor executor = new SimpleThreadPoolExecutor();
		for (Runnable runnable : testRunnables.keySet()) {
			executor.execute(runnable, testRunnables.get(runnable));
		}
		while (executor.hasActiveThreads()) {
			Thread.sleep(100);
		}
		Assert.assertEquals(testRunnablesCount, executor.getExecutedTotal());
	}

	@Test(expected = RuntimeException.class)
	public void testRuntimeExceptionAfterShutdown() throws Exception {
		SimpleThreadPoolExecutor executor = new SimpleThreadPoolExecutor(
				testRunnables.keySet());
		executor.shutdown();
		while (executor.hasActiveThreads()) {
			Thread.sleep(100);
		}
		executor.execute(new Runnable() {
			@Override
			public void run() {
				// do nothing
			}
		});
	}

}
