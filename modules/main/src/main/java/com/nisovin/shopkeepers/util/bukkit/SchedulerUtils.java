package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitWorker;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.util.java.Validate;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Scheduler related utilities.
 * <p>
 * This is a thin facade over {@link FoliaLib}: On Folia it uses the region, global, and async
 * schedulers, whereas on Spigot and Paper it transparently falls back to the {@link Bukkit}
 * scheduler. Consequently, the behavior on Spigot and Paper remains unchanged.
 */
public final class SchedulerUtils {

	/**
	 * Creates a {@link WrappedExecutor} that executes tasks on the thread that owns the region of a
	 * given {@link Location}, using {@link #runOnMainThreadOrOmit(Location, Runnable)}.
	 * <p>
	 * If the current thread already owns the location's region, the task is run immediately.
	 * Otherwise, it is scheduled. If the plugin is not enabled at the time of task registration, the
	 * task is omitted.
	 *
	 * @return the executor
	 */
	public static WrappedExecutor createSyncExecutor() {
		return (location, runnable) -> runOnMainThreadOrOmit(location, runnable);
	}

	/**
	 * Creates an {@link Executor} that executes tasks using {@link #runAsyncTaskOrOmit(Runnable)}.
	 *
	 * @return the executor
	 */
	public static Executor createAsyncExecutor() {
		return (runnable) -> runAsyncTaskOrOmit(runnable);
	}

	public static int getActiveAsyncTasks(Plugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		FoliaLib foliaLib = getFoliaLib();
		if (!foliaLib.isFolia()) {
			// Preserve the original behavior on non-Folia servers: Count the plugin's active Bukkit
			// async workers.
			int workers = 0;
			for (BukkitWorker worker : Bukkit.getScheduler().getActiveWorkers()) {
				if (worker.getOwner().equals(plugin)) {
					workers++;
				}
			}
			return workers;
		}

		// On Folia, count the not-yet-cancelled tasks tracked by FoliaLib:
		int tasks = 0;
		for (WrappedTask task : foliaLib.getScheduler().getAllTasks()) {
			if (!task.isCancelled()) {
				tasks++;
			}
		}
		return tasks;
	}

	private static void validateTask(Runnable task) {
		Validate.notNull(task, "task is null");
	}

	/**
	 * Checks whether the current thread owns the region of the given location.
	 * <p>
	 * On non-Folia servers this checks whether the current thread is the server's main thread.
	 *
	 * @param location
	 *            the location, not <code>null</code>
	 * @return <code>true</code> if the current thread owns the location's region
	 */
	public static boolean isMainThread(Location location) {
		Validate.notNull(location, "location is null");
		return getFoliaLib().getScheduler().isOwnedByCurrentRegion(location);
	}

	/**
	 * Checks whether the current thread is the server's global tick thread.
	 * <p>
	 * On non-Folia servers this checks whether the current thread is the server's main thread.
	 *
	 * @return <code>true</code> if the current thread is the global tick thread
	 */
	public static boolean isGlobalThread() {
		return getFoliaLib().getScheduler().isGlobalTickThread();
	}

	/**
	 * Schedules the given task to be run on the thread that owns the given location's region, if
	 * required.
	 * <p>
	 * If the current thread already owns the location's region, the task is run immediately.
	 * Otherwise, it attempts to schedule the task. However, if the plugin is disabled, the task is
	 * not scheduled.
	 *
	 * @param location
	 *            the location whose region shall run the task, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was run or successfully scheduled to be run,
	 *         <code>false</code> otherwise
	 */
	public static boolean runOnMainThreadOrOmit(Location location, Runnable task) {
		validateTask(task);
		if (isMainThread(location)) {
			task.run();
			return true;
		} else {
			return (runTaskOrOmit(location, task) != null);
		}
	}

	public static @Nullable WrappedTask runTaskOrOmit(Entity entity, Runnable task) {
		return runTaskLaterOrOmit(entity, task, 0L);
	}

	// A null location means that the task has no region context and is run immediately.
	public static @Nullable WrappedTask runTaskOrOmit(@Nullable Location location, Runnable task) {
		validateTask(task);
		if (location == null) {
			task.run();
			return null;
		}
		return runTaskLaterOrOmit(location, task, 0L);
	}

	public static @Nullable WrappedTask runTaskLaterOrOmit(Entity entity, Runnable task, long delay) {
		Validate.notNull(entity, "entity is null");
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		if (!foliaLib.isFolia()) {
			return runTaskLaterOrOmit(entity.getLocation(), task, delay);
		}

		if (foliaLib.getPlugin().isEnabled()) {
			try {
				// Retirement omits the work, without accessing the retired entity.
				return foliaLib.getScheduler().runAtEntityLater(entity, task, () -> {}, delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			} catch (NullPointerException e) {
				// FoliaLib 0.5.2 wraps a null native task when the entity scheduler rejects it.
				if (!"nativeTask".equals(e.getMessage())) throw e;
			}
		}
		return null;
	}

	public static @Nullable WrappedTask runTaskLaterOrOmit(
			Location location,
			Runnable task,
			long delay
	) {
		Validate.notNull(location, "location is null");
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				return foliaLib.getScheduler().runAtLocationLater(location.clone(), task, delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static void runTaskTimerOrOmit(
			Entity entity,
			Consumer<WrappedTask> task,
			long delay,
			long period
	) {
		Validate.notNull(entity, "entity is null");
		Validate.notNull(task, "task is null");
		FoliaLib foliaLib = getFoliaLib();
		if (!foliaLib.isFolia()) {
			runTaskTimerOrOmit(entity.getLocation(), task, delay, period);
			return;
		}

		if (foliaLib.getPlugin().isEnabled()) {
			try {
				// The consumer overload silently omits registrations for retired entities.
				foliaLib.getScheduler().runAtEntityTimer(entity,
						wrappedTask -> task.accept(Unsafe.assertNonNull(wrappedTask)),
						() -> {}, delay, period);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
	}

	public static void runTaskTimerOrOmit(
			Location location,
			Consumer<WrappedTask> task,
			long delay,
			long period
	) {
		Validate.notNull(location, "location is null");
		Validate.notNull(task, "task is null");
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				foliaLib.getScheduler().runAtLocationTimer(location.clone(),
						wrappedTask -> task.accept(Unsafe.assertNonNull(wrappedTask)),
						delay, period);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
	}

	public static @Nullable WrappedTask runAsyncTaskOrOmit(Runnable task) {
		return runAsyncTaskLaterOrOmit(task, 0L);
	}

	public static @Nullable WrappedTask runAsyncTaskLaterOrOmit(Runnable task, long delay) {
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				return foliaLib.getScheduler().runLaterAsync(task, delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable WrappedTask runAsyncTaskTimerOrOmit(Runnable task, long delay, long period) {
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				return foliaLib.getScheduler().runTimerAsync(task, delay, period);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	// Runs a repeating task on the server's global tick thread. On non-Folia servers this falls back
	// to a regular synchronous Bukkit timer task, preserving the previous behavior. This is used for
	// plugin-wide driver tasks (such as the shopkeeper AI and ticking tasks) that iterate across
	// multiple regions and dispatch their per-region work onto the respective region schedulers.
	public static @Nullable WrappedTask runTaskTimerGloballyOrOmit(Runnable task, long delay, long period) {
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				return foliaLib.getScheduler().runTimer(task, delay, period);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	// Note: We intentionally use FoliaLib's Runnable-based global scheduler methods (which return a
	// WrappedTask) rather than the Consumer-based ones (which return a CompletableFuture): The
	// CompletableFuture returned by FoliaLib is not linked to the underlying task, so cancelling it
	// does not actually prevent the task from running. Returning a WrappedTask allows callers to
	// reliably cancel the task, matching the previous Bukkit behavior.
	public static @Nullable WrappedTask runTaskGloballyOrOmit(Runnable task) {
		return runTaskLaterGloballyOrOmit(task, 0L);
	}

	public static @Nullable WrappedTask runTaskLaterGloballyOrOmit(Runnable task, long delay) {
		validateTask(task);
		FoliaLib foliaLib = getFoliaLib();
		// Tasks can only be registered while enabled:
		if (foliaLib.getPlugin().isEnabled()) {
			try {
				return foliaLib.getScheduler().runLater(task, delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	/**
	 * Awaits the completion of async tasks of the specified plugin.
	 * <p>
	 * If a logger is specified, it will be used to print informational messages suited to the
	 * context of this method being called during disabling of the plugin.
	 *
	 * @param plugin
	 *            the plugin
	 * @param asyncTasksTimeoutSeconds
	 *            the duration to wait for async tasks to finish in seconds (can be <code>0</code>)
	 * @param logger
	 *            the logger used for printing informational messages, can be <code>null</code>
	 * @return the number of remaining async tasks that are still running after waiting for the
	 *         specified duration
	 */
	public static int awaitAsyncTasksCompletion(
			Plugin plugin,
			int asyncTasksTimeoutSeconds,
			@Nullable Logger logger
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.isTrue(asyncTasksTimeoutSeconds >= 0, "asyncTasksTimeoutSeconds cannot be negative");

		int activeAsyncTasks = getActiveAsyncTasks(plugin);
		if (activeAsyncTasks > 0 && asyncTasksTimeoutSeconds > 0) {
			if (logger != null) {
				logger.info("Waiting up to " + asyncTasksTimeoutSeconds + " seconds for "
						+ activeAsyncTasks + " remaining async tasks to finish ...");
			}

			final long asyncTasksTimeoutMillis = TimeUnit.SECONDS.toMillis(asyncTasksTimeoutSeconds);
			final long waitStartNanos = System.nanoTime();
			long waitDurationMillis = 0L;
			do {
				// Periodically check again:
				try {
					Thread.sleep(25L);
				} catch (InterruptedException e) {
					// Ignore, but reset interrupt flag:
					Thread.currentThread().interrupt();
				}
				// Update the number of active async task before breaking from loop:
				activeAsyncTasks = getActiveAsyncTasks(plugin);

				// Update waiting duration and compare to timeout:
				waitDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartNanos);
				if (waitDurationMillis > asyncTasksTimeoutMillis) {
					// Timeout reached, abort waiting..
					break;
				}
			} while (activeAsyncTasks > 0);

			if (waitDurationMillis > 1 && logger != null) {
				logger.info("Waited " + waitDurationMillis + " ms for async tasks to finish.");
			}
		}

		if (activeAsyncTasks > 0 && logger != null) {
			// Severe, since this can potentially result in data loss, depending on what the tasks
			// are doing:
			logger.severe("There are still " + activeAsyncTasks
					+ " remaining async tasks active! Disabling anyway now.");
		}
		return activeAsyncTasks;
	}

	private static FoliaLib getFoliaLib() {
		return SKShopkeepersPlugin.getInstance().getFoliaLib();
	}

	private SchedulerUtils() {
	}
}
