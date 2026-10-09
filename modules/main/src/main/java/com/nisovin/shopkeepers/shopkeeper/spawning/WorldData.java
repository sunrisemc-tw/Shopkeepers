package com.nisovin.shopkeepers.shopkeeper.spawning;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.shopkeeper.spawning.WorldSaveDespawner.RespawnShopkeepersAfterWorldSaveTask;
import com.nisovin.shopkeepers.util.java.Validate;

final class WorldData {

	private final String worldName;
	private volatile @Nullable RespawnShopkeepersAfterWorldSaveTask worldSaveRespawnTask = null;

	WorldData(String worldName) {
		Validate.notNull(worldName, "worldName is null");
		this.worldName = worldName;
	}

	public String getWorldName() {
		return worldName;
	}

	boolean isWorldSaveRespawnPending() {
		return (worldSaveRespawnTask != null);
	}

	void setWorldSaveRespawnTask(
			@Nullable RespawnShopkeepersAfterWorldSaveTask worldSaveRespawnTask
	) {
		this.worldSaveRespawnTask = worldSaveRespawnTask;
	}

	synchronized boolean replaceWorldSaveRespawnTask(
			@Nullable RespawnShopkeepersAfterWorldSaveTask expected,
			@Nullable RespawnShopkeepersAfterWorldSaveTask replacement
	) {
		if (worldSaveRespawnTask != expected) return false;
		worldSaveRespawnTask = replacement;
		return true;
	}

	boolean isWorldSaveRespawnTask(RespawnShopkeepersAfterWorldSaveTask task) {
		return worldSaveRespawnTask == task;
	}

	void cancelWorldSaveRespawnTask() {
		@Nullable RespawnShopkeepersAfterWorldSaveTask task = worldSaveRespawnTask;
		if (task != null) task.cancel();
	}

	void cleanUp() {
		this.cancelWorldSaveRespawnTask();
	}
}
