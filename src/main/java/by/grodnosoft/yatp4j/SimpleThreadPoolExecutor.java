package by.grodnosoft.yatp4j;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

public class SimpleThreadPoolExecutor implements Executor {

	private static final int DEFAULT_POOL_SIZE = 5;
	private static final long DEFAULT_KEEP_ALIVE_TIME_MILLIS = 1000L;
	private static final String DEFAULT_TASK_NAME = "New Task";

	public enum Priority {
		URGENT, NORMAL, LOWEST;

		public boolean lessThan(Priority that) {
			return !(this.equals(that) || this.equals(Priority.URGENT) || (this
					.equals(Priority.NORMAL) && that.equals(Priority.LOWEST)));
		}

		public String stringValue() {
			switch (this) {
			case URGENT:
				return "[URGENT]";
			case NORMAL:
				return "[NORMAL]";
			case LOWEST:
				return "[LOWEST]";
			default:
				return "[UNKNOWN]";
			}
		}
	}

	private PriorityQueue<Task> workQueue;

	private ThreadGroup workersGroup;
	private int currentPoolSize = 0;
	private int maxPoolSize;

	private Object lockObject = new Object();

	private volatile int urgentCompleted = 0;
	private volatile int normalCompleted = 0;
	private volatile int lowestCompleted = 0;

	private volatile boolean shutdownCalled = false;

	public SimpleThreadPoolExecutor() {
		this(null, DEFAULT_POOL_SIZE);
	}

	public SimpleThreadPoolExecutor(Collection<Runnable> commands) {
		this(commands, DEFAULT_POOL_SIZE);
	}

	public SimpleThreadPoolExecutor(int maxPoolSize) {
		this(null, maxPoolSize);
	}

	public SimpleThreadPoolExecutor(Collection<Runnable> commands,
			int maxPoolSize) {
		this.workQueue = new PriorityQueue<Task>();
		this.workersGroup = new ThreadGroup("SimpleThreadPoolExecutor Group");
		this.maxPoolSize = maxPoolSize;
		if (commands != null) {
			for (Iterator<Runnable> iterator = commands.iterator(); iterator
					.hasNext();) {
				execute(iterator.next());
			}
		}
	}

	@Override
	public void execute(Runnable command) {
		execute(command, Priority.NORMAL);
	}

	public void execute(Runnable command, Priority runPriority) {
		execute(command, runPriority, null);
	}

	public void execute(Runnable command, Priority runPriority, String taskName) {
		if (shutdownCalled) {
			throw new RuntimeException(
					"This ThreadPool was shutdown and can't accept any commands!");
		}
		synchronized (lockObject) {
			try {
				Task newTask = new Task(command, runPriority, taskName);
				if (currentPoolSize >= maxPoolSize) {
					workQueue.add(newTask);
				} else {
					WorkerThread worker = new WorkerThread(workersGroup,
							newTask);
					currentPoolSize++;
					worker.start();
				}
			} finally {
				lockObject.notify();
			}
		}
	}

	public List<Task> getWaitingList() {
		Task[] waitingTasks;
		synchronized (lockObject) {
			try {
				waitingTasks = new Task[workQueue.size()];
				workQueue.toArray(waitingTasks);
			} finally {
				lockObject.notify();
			}
		}
		return Collections.unmodifiableList(Arrays.asList(waitingTasks));
	}

	public int getExecutedTotal() {
		synchronized (lockObject) {
			try {
				return urgentCompleted + lowestCompleted + normalCompleted;
			} finally {
				lockObject.notify();
			}
		}
	}

	public void shutdown() {
		shutdownCalled = true;
		workersGroup.interrupt();
	}

	public boolean hasActiveThreads() {
		Thread[] threads = new Thread[maxPoolSize];
		int list = workersGroup.enumerate(threads);
		return list > 0;
	}

	public class Task implements Comparable<Task> {

		private Runnable taskCommand;
		private Priority taskPriority;
		private String taskName;

		public Task(Runnable taskCommand, Priority taskPriority, String taskName) {
			if (taskCommand == null) {
				throw new IllegalArgumentException("Runnable must be not null!");
			}
			if (taskPriority == null) {
				taskPriority = Priority.NORMAL;
			}
			if (taskName == null || taskName.isEmpty()) {
				taskName = DEFAULT_TASK_NAME;
			}
			this.taskCommand = taskCommand;
			this.taskPriority = taskPriority;
			this.taskName = taskName;
		}

		public Priority getTaskPriority() {
			return taskPriority;
		}

		public String getTaskName() {
			return taskName;
		}

		@Override
		public int compareTo(Task that) {
			if (this.taskPriority.equals(that.taskPriority)) {
				return 0;
			} else if (this.taskPriority.equals(Priority.LOWEST)
					&& this.taskPriority.lessThan(that.taskPriority)
					&& lowestCompleted > 0
					&& normalCompleted / lowestCompleted >= 3) {
				return -1;
			} else if (this.taskPriority.equals(Priority.NORMAL)
					&& this.taskPriority.lessThan(that.taskPriority)
					&& normalCompleted > 0
					&& urgentCompleted / normalCompleted >= 3) {
				return -1;
			} else {
				if (this.taskPriority.lessThan(that.taskPriority)) {
					return 1;
				} else {
					return -1;
				}
			}
		}

		@Override
		public String toString() {
			return taskName + taskPriority.stringValue();
		}
	}

	private class WorkerThread extends Thread {

		private static final long timeStepMillis = 500L;
		private long idleTime = 0;

		private Task currentTask;

		public WorkerThread(ThreadGroup group, Task firstTask) {
			super(group, "WorkerThread");
			this.currentTask = firstTask;
		}

		@Override
		public void run() {
			try {
				final Timer interruptTimer = new Timer(true);
				interruptTimer.schedule(new TimerTask() {
					@Override
					public void run() {
						// this method would cause problems in case WorkerThread
						// is performing some nio operations
						// but could illustrate the basic concept
						WorkerThread.this.interrupt();
					}
				}, timeStepMillis, timeStepMillis);
				while (true) {
					if (isAlive() && currentTask == null && !shutdownCalled) {
						synchronized (lockObject) {
							while (workQueue.isEmpty()) {
								try {
									lockObject.wait();
								} catch (InterruptedException e) {
									if (shutdownCalled
											|| idleTime >= DEFAULT_KEEP_ALIVE_TIME_MILLIS) {
										interruptTimer.cancel();
										throw new ThreadInterruptedException();
									} else {
										// clear interrupted state
										idleTime += timeStepMillis;
										interrupted();
									}
								}
							}
							idleTime = 0;
							currentTask = workQueue.remove();
						}
					}
					if (shutdownCalled || !isAlive()) {
						interruptTimer.cancel();
						throw new ThreadInterruptedException();
					}
					try {
						currentTask.taskCommand.run();
					} catch (RuntimeException e) {
						// TODO: logging should be inserted here
					} finally {
						updateCompletedTaskCounter(currentTask.taskPriority);
						currentTask = null;
					}
				}
			} catch (ThreadInterruptedException e) {
				// TODO: log a message and return
			}
		}

		private void updateCompletedTaskCounter(Priority taskPriority) {
			switch (taskPriority) {
			case URGENT:
				urgentCompleted++;
				break;
			case NORMAL:
				normalCompleted++;
				break;
			case LOWEST:
				lowestCompleted++;
				break;
			default:
				// TODO: log a warning
				break;
			}
		}
	}

	private class ThreadInterruptedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

}
