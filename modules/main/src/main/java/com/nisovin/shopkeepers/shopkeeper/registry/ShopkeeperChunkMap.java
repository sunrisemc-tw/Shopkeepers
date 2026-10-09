package com.nisovin.shopkeepers.shopkeeper.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Stores shopkeepers and provides methods to query them by world and by chunk.
 */
class ShopkeeperChunkMap {

	/**
	 * An instance of this can be registered during the construction of the
	 * {@link ShopkeeperChunkMap} and is then invoked whenever a shopkeeper is added or removed from
	 * the chunk map.
	 */
	static class ChangeListener {

		public void onShopkeeperAdded(
				AbstractShopkeeper shopkeeper,
				ChunkShopkeepers chunkShopkeepers
		) {
		}

		public void onShopkeeperRemoved(
				AbstractShopkeeper shopkeeper,
				ChunkShopkeepers chunkShopkeepers
		) {
		}

		public void onWorldAdded(WorldShopkeepers worldShopkeepers) {
		}

		public void onWorldRemoved(WorldShopkeepers worldShopkeepers) {
		}

		public void onChunkAdded(ChunkShopkeepers chunkShopkeepers) {
		}

		public void onChunkRemoved(ChunkShopkeepers chunkShopkeepers) {
		}
	}

	// By world name:
	private final Map<String, WorldShopkeepers> shopkeepersByWorld = new LinkedHashMap<>();
	private final Set<String> shopkeeperWorldsView = Collections.unmodifiableSet(shopkeepersByWorld.keySet());

	private final ChangeListener changeListener; // Not null
	private final Object lock;
	private final boolean snapshots;

	ShopkeeperChunkMap() {
		this(new ChangeListener());
	}

	ShopkeeperChunkMap(ChangeListener changeListener) {
		this(new Object(), false, changeListener);
	}

	ShopkeeperChunkMap(Object lock, boolean snapshots, ChangeListener changeListener) {
		Validate.notNull(changeListener, "changeListener is null");
		this.lock = lock;
		this.snapshots = snapshots;
		this.changeListener = changeListener;
	}

	// Returns null if there are no shopkeepers in the specified world.
	@Nullable
	WorldShopkeepers getWorldShopkeepers(String worldName) {
		synchronized (lock) {
			return shopkeepersByWorld.get(worldName);
		}
	}

	// Returns null if there are no shopkeepers in the specified chunk:
	@Nullable
	ChunkShopkeepers getChunkShopkeepers(@Nullable ChunkCoords chunkCoords) {
		synchronized (lock) {
			if (chunkCoords == null) return null;
			String worldName = chunkCoords.getWorldName();
			WorldShopkeepers worldShopkeepers = this.getWorldShopkeepers(worldName);
			if (worldShopkeepers == null) return null; // There are no shopkeepers in this world
			return worldShopkeepers.getChunkShopkeepers(chunkCoords);
		}
	}

	// Only called for non-virtual shopkeepers.
	ChunkShopkeepers addShopkeeper(AbstractShopkeeper shopkeeper) {
		List<Runnable> notifications = new ArrayList<>();
		ChunkShopkeepers result = this.addShopkeeper(shopkeeper, notifications);
		notifications.forEach(Runnable::run);
		return result;
	}

	ChunkShopkeepers addShopkeeper(AbstractShopkeeper shopkeeper, List<Runnable> notifications) {
		synchronized (lock) {
			assert shopkeeper != null && !shopkeeper.isVirtual();
			assert shopkeeper.getLastChunkCoords() == null;
			String worldName = Unsafe.assertNonNull(shopkeeper.getWorldName());
			ChunkCoords shopkeeperChunk = Unsafe.assertNonNull(shopkeeper.getChunkCoords());
			assert worldName.equals(shopkeeperChunk.getWorldName());
			WorldShopkeepers worldShopkeepers = shopkeepersByWorld.computeIfAbsent(
					worldName,
					name -> new WorldShopkeepers(name, lock, snapshots)
			);
			assert worldShopkeepers != null;
			ChunkShopkeepers chunkShopkeepers = worldShopkeepers.addShopkeeper(shopkeeper);

			// Inform change listener:
			if (worldShopkeepers.getShopkeeperCount() == 1) {
				notifications.add(() -> changeListener.onWorldAdded(worldShopkeepers));
			}
			if (chunkShopkeepers.getShopkeepers().size() == 1) {
				notifications.add(() -> {
					if (this.getChunkShopkeepers(shopkeeperChunk) == chunkShopkeepers) {
						changeListener.onChunkAdded(chunkShopkeepers);
					}
				});
			}
			notifications.add(() -> changeListener.onShopkeeperAdded(shopkeeper, chunkShopkeepers));
			return chunkShopkeepers;
		}
	}

	// Only called for non-virtual shopkeepers.
	@Nullable
	ChunkShopkeepers removeShopkeeper(AbstractShopkeeper shopkeeper) {
		List<Runnable> notifications = new ArrayList<>();
		ChunkShopkeepers result = this.removeShopkeeper(shopkeeper, false, notifications);
		notifications.forEach(Runnable::run);
		return result;
	}

	@Nullable ChunkShopkeepers removeShopkeeper(
			AbstractShopkeeper shopkeeper,
			boolean skipWorldCleanup,
			List<Runnable> notifications
	) {
		synchronized (lock) {
			assert shopkeeper != null && !shopkeeper.isVirtual();
			ChunkCoords lastChunkCoords = Unsafe.assertNonNull(shopkeeper.getLastChunkCoords());
			String worldName = lastChunkCoords.getWorldName();
			WorldShopkeepers worldShopkeepers = shopkeepersByWorld.get(worldName);
			if (worldShopkeepers == null) return null; // Could not find the shopkeeper

			ChunkShopkeepers chunkShopkeepers = worldShopkeepers.removeShopkeeper(shopkeeper);
			boolean worldRemoved = false;
			if (!skipWorldCleanup && worldShopkeepers.getShopkeeperCount() == 0) {
				worldRemoved = true;
				shopkeepersByWorld.remove(worldName);
			}

			// Inform change listener:
			notifications.add(() -> changeListener.onShopkeeperRemoved(shopkeeper, chunkShopkeepers));
			if (chunkShopkeepers.getShopkeepers().isEmpty()) {
				notifications.add(() -> {
					if (this.getChunkShopkeepers(lastChunkCoords) == null) {
						changeListener.onChunkRemoved(chunkShopkeepers);
					}
				});
			}
			if (worldRemoved) {
				notifications.add(() -> {
					if (this.getWorldShopkeepers(worldName) == null) {
						changeListener.onWorldRemoved(worldShopkeepers);
					}
				});
			}
			return chunkShopkeepers;
		}
	}

	// Updates the shopkeeper's location inside the chunk map, moving it from its previous chunk to
	// its current chunk.
	// Returns true if the shopkeeper was moved to a different chunk.
	boolean moveShopkeeper(AbstractShopkeeper shopkeeper) {
		List<Runnable> notifications = new ArrayList<>();
		synchronized (lock) {
			assert shopkeeper != null;
			ChunkCoords oldChunk = Unsafe.assertNonNull(shopkeeper.getLastChunkCoords());
			ChunkCoords newChunk = Unsafe.assertNonNull(shopkeeper.getChunkCoords());
			if (newChunk.equals(oldChunk)) {
				// The shopkeeper's chunk did not change.
				return false;
			}

			// If the shopkeeper is moved from one chunk to another within the same world, we skip any
			// world data cleanup.
			boolean skipWorldCleanup = oldChunk.getWorldName().equals(newChunk.getWorldName());
			this.removeShopkeeper(shopkeeper, skipWorldCleanup, notifications);
			this.addShopkeeper(shopkeeper, notifications);
		}

		notifications.forEach(Runnable::run);
		return true;
	}

	void ensureEmpty() {
		synchronized (lock) {
			if (!shopkeepersByWorld.isEmpty()) {
				Log.warning("Some shopkeepers were not properly removed from the chunk map!");
				shopkeepersByWorld.clear();
			}
		}
	}

	void discardOnShutdown() {
		synchronized (lock) {
			shopkeepersByWorld.clear();
		}
	}

	// QUERIES

	boolean usesSnapshots() {
		return snapshots;
	}

	public Collection<? extends String> getWorldsWithShopkeepers() {
		synchronized (lock) {
			return snapshots ? Collections.unmodifiableSet(
					new LinkedHashSet<>(shopkeepersByWorld.keySet())) : shopkeeperWorldsView;
		}
	}
}
