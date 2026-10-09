package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.Executor;

import org.bukkit.Location;

/**
 * An {@link Executor} that schedules its tasks for the region that owns a specific
 * {@link Location}.
 * <p>
 * The inherited {@link #execute(Runnable)} method is not supported, because it does not provide a
 * {@link Location} and therefore cannot determine the target region.
 */
public interface WrappedExecutor extends Executor {

	/**
	 * Executes the given command on the thread that owns the region of the specified location.
	 *
	 * @param location
	 *            the location whose region shall execute the command, not <code>null</code>
	 * @param command
	 *            the command to execute, not <code>null</code>
	 */
	void execute(Location location, Runnable command);

	@Override
	default void execute(Runnable command) {
		throw new UnsupportedOperationException("Use execute(Location, Runnable) instead");
	}
}
