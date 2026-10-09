package com.nisovin.shopkeepers.shopkeeper.activation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.debug.DebugOptions;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.registry.SKShopkeeperRegistry;
import com.nisovin.shopkeepers.shopkeeper.spawning.ShopkeeperSpawner;
import com.nisovin.shopkeepers.shopkeeper.ticking.ShopkeeperTicker;
import com.nisovin.shopkeepers.util.bukkit.MutableChunkCoords;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.TextUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;
import com.nisovin.shopkeepers.util.timer.Timer;
import com.nisovin.shopkeepers.util.timer.Timings;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Updates and keeps track of chunk activations for chunks that contain shopkeepers.
 * <p>
 * Chunk activations trigger various other aspects, such as the ticking and spawning of shopkeepers.
 */
public class ShopkeeperChunkActivator {

	/**
	 * Spawning shopkeepers is relatively costly performance-wise. In order to not spawn shopkeepers
	 * for chunks that are only loaded briefly, we defer the activation of chunks by this amount of
	 * ticks. This also accounts for players who frequently cross chunk boundaries back and forth.
	 */
	private static final long CHUNK_ACTIVATION_DELAY_TICKS = 20;
	/**
	 * The radius in chunks around the player that we immediately activate when a player freshly
	 * joins, or teleports. A radius of {@code zero} only activates the player's own chunk.
	 * <p>
	 * The actually used radius is the minimum of this setting and the server's
	 * {@link Server#getViewDistance() view distance}.
	 */
	private static final int IMMEDIATE_CHUNK_ACTIVATION_RADIUS = 2;

	private static final Predicate<AbstractShopkeeper> SHOPKEEPER_IS_ACTIVE = AbstractShopkeeper::isActive;
	private static final Predicate<AbstractShopkeeper> SHOPKEEPER_IS_INACTIVE = Unsafe.assertNonNull(SHOPKEEPER_IS_ACTIVE.negate());

	// Thread-local, because on Folia these shared buffers may be used from multiple region threads
	// concurrently (e.g. during chunk events):
	private static final ThreadLocal<Location> sharedLocation = ThreadLocal.withInitial(
			() -> new Location(null, 0, 0, 0)
	);
	private static final ThreadLocal<MutableChunkCoords> sharedChunkCoords = ThreadLocal.withInitial(
			MutableChunkCoords::new
	);

	private final SKShopkeepersPlugin plugin;
	private final SKShopkeeperRegistry shopkeeperRegistry;
	private final ShopkeeperTicker shopkeeperTicker;
	private final ShopkeeperSpawner shopkeeperSpawner;
	private final ChunkActivationListener listener = new ChunkActivationListener(Unsafe.initialized(this));

	// Concurrent: On Folia, chunk (un)load events and shopkeeper registry changes may touch this map
	// from different region threads.
	private final Map<ChunkCoords, ChunkData> chunks = new ConcurrentHashMap<>();

	private final AtomicBoolean chunkActivationInProgress = new AtomicBoolean();
	// This does not consider pending delayed chunk activation tasks, but only tracks actual
	// activation requests while another chunk activation is in progress.
	private final Queue<ChunkData> deferredChunkActivations = new ConcurrentLinkedQueue<>();

	private final Timer chunkActivationTimings = new Timer();
	private int immediateChunkActivationRadius;
	private volatile boolean stopped;
	private volatile long generation;

	public ShopkeeperChunkActivator(
			SKShopkeepersPlugin plugin,
			SKShopkeeperRegistry shopkeeperRegistry,
			ShopkeeperTicker shopkeeperTicker,
			ShopkeeperSpawner shopkeeperSpawner
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(shopkeeperRegistry, "shopkeeperRegistry is null");
		Validate.notNull(shopkeeperTicker, "shopkeeperTicker is null");
		Validate.notNull(shopkeeperSpawner, "shopkeeperSpawner is null");
		this.plugin = plugin;
		this.shopkeeperRegistry = shopkeeperRegistry;
		this.shopkeeperTicker = shopkeeperTicker;
		this.shopkeeperSpawner = shopkeeperSpawner;
	}

	public void onEnable() {
		stopped = false;
		generation++;
		// Determine the immediate chunk activation radius:
		immediateChunkActivationRadius = Math.min(
				IMMEDIATE_CHUNK_ACTIVATION_RADIUS,
				Bukkit.getViewDistance()
		);

		Bukkit.getPluginManager().registerEvents(listener, plugin);
	}

	public void onDisable() {
		stopped = true;
		generation++;
		chunks.values().forEach(ChunkData::cleanUp);
		HandlerList.unregisterAll(listener);
		chunkActivationTimings.reset();
		if (plugin.getFoliaLib().isFolia()) {
			chunks.clear();
			deferredChunkActivations.clear();
		} else {
			this.ensureEmpty();
		}
	}

	private void ensureEmpty() {
		if (!chunks.isEmpty()) {
			Log.warning("Some chunk entries were not properly removed from the chunk activator!");
			chunks.clear();
		}
		if (!deferredChunkActivations.isEmpty()) {
			Log.warning("Some deferred chunk activations were not properly removed from the chunk activator!");
			deferredChunkActivations.clear();
		}
	}

	// DATA

	private @Nullable ChunkData getChunkData(Chunk chunk) {
		assert chunk != null;
		MutableChunkCoords sharedChunkCoords = ShopkeeperChunkActivator.sharedChunkCoords.get();
		sharedChunkCoords.set(chunk);
		return this.getChunkData(sharedChunkCoords);
	}

	private @Nullable ChunkData getChunkData(String worldName, int chunkX, int chunkZ) {
		MutableChunkCoords sharedChunkCoords = ShopkeeperChunkActivator.sharedChunkCoords.get();
		sharedChunkCoords.set(worldName, chunkX, chunkZ);
		return this.getChunkData(sharedChunkCoords);
	}

	// Returns null if there is no data for the specified chunk, i.e. if there are no shopkeepers in
	// this chunk.
	private @Nullable ChunkData getChunkData(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		return chunks.get(chunkCoords);
	}

	private ChunkData getOrCreateChunkData(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		ChunkData chunkData = chunks.computeIfAbsent(chunkCoords, coords ->
				plugin.getFoliaLib().isFolia() ? new ChunkData(coords, false) : new ChunkData(coords));
		assert chunkData != null;
		return chunkData;
	}

	private @Nullable ChunkData removeChunkData(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		ChunkData chunkData = chunks.remove(chunkCoords);
		if (chunkData != null) {
			this.cancelDeferredActivation(chunkData);
			chunkData.cleanUp();
		}
		return chunkData;
	}

	// DATA SETUP

	// Called by SKShopkeeperRegistry when a shopkeeper has been added to a new (previously empty)
	// chunk.
	public void onShopkeeperChunkAdded(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		this.getOrCreateChunkData(chunkCoords);
	}

	// Called by SKShopkeeperRegistry when the last shopkeeper has been removed from a chunk.
	public void onShopkeeperChunkRemoved(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		// Note: We expect that the chunk data exists, i.e. that it has previously been set up
		// immediately when the first shopkeeper was added to the chunk.
		this.removeChunkData(chunkCoords);
	}

	// SHOPKEEPER ACTIVATION

	// Called when the shopkeeper has been added to a chunk, either because the shopkeeper has been
	// newly added to the shopkeeper registry, or because it has been moved from one chunk to
	// another.
	// This updates the shopkeeper's activation state to match its new chunk.
	public void checkShopkeeperActivation(AbstractShopkeeper shopkeeper) {
		assert shopkeeper != null;
		assert !shopkeeper.isVirtual();
		if (stopped || !shopkeeper.isValid()) return;
		if (plugin.getFoliaLib().isFolia()
				&& !shopkeeperRegistry.isOwnerThread(shopkeeper)) {
			shopkeeperRegistry.runOnOwner(shopkeeper, () -> {
				this.checkShopkeeperActivation(shopkeeper);
				return true;
			});
			return;
		}

		ChunkCoords chunkCoords = Unsafe.assertNonNull(shopkeeper.getLastChunkCoords());
		ChunkData chunkData = this.getChunkData(chunkCoords);
		if (chunkData == null) return;
		if (plugin.getFoliaLib().isFolia() && !chunkData.isActive()
				&& !chunkData.isActivationDelayed()) {
			this.activateChunk(chunkData);
		}

		if (chunkData.isActive()) {
			this.activateShopkeeper(shopkeeper);
		} else {
			this.deactivateShopkeeper(shopkeeper);
		}
	}

	private void activateShopkeeper(AbstractShopkeeper shopkeeper) {
		assert shopkeeper != null;
		assert !shopkeeper.isVirtual();
		// We expect this to be called after the chunk data has been added:
		assert shopkeeper.getLastChunkCoords() != null;
		assert this.getChunkData(Unsafe.assertNonNull(shopkeeper.getLastChunkCoords())) != null;

		if (shopkeeper.isActive()) return; // Already active

		// Mark the shopkeeper as active:
		shopkeeper.setActive(true);

		// Start ticking:
		shopkeeperTicker.startTicking(shopkeeper);

		// Abort if the shopkeeper has already been deactivated again:
		if (!shopkeeper.isActive()) return;

		// If necessary, spawn the shopkeeper:
		shopkeeperSpawner.spawnShopkeeperImmediately(shopkeeper);
	}

	// Also called by SKShopkeeperRegistry when the shopkeeper is about to be removed.
	public void deactivateShopkeeper(AbstractShopkeeper shopkeeper) {
		assert shopkeeper != null;
		assert !shopkeeper.isVirtual();
		// We expect this to be called before the chunk data is removed:
		assert shopkeeper.getLastChunkCoords() != null;
		assert this.getChunkData(Unsafe.assertNonNull(shopkeeper.getLastChunkCoords())) != null;

		if (!shopkeeper.isActive()) return; // Already inactive

		// Mark the shopkeeper as inactive:
		shopkeeper.setActive(false);

		// Stop ticking:
		shopkeeperTicker.stopTicking(shopkeeper);

		// Abort if the shopkeeper has already been activated again:
		if (shopkeeper.isActive()) return;

		// If necessary, despawn the shopkeeper:
		shopkeeperSpawner.despawnShopkeeper(shopkeeper);
	}

	// Called by SKShopkeeperRegistry when the shopkeeper has been moved from one chunk to another.
	public void onShopkeeperMoved(AbstractShopkeeper shopkeeper, ChunkCoords oldChunkCoords) {
		assert shopkeeper != null && oldChunkCoords != null;
		assert !shopkeeper.isVirtual();

		// If necessary, update the shopkeeper's activation state:
		boolean oldActivationState = shopkeeper.isActive();
		this.checkShopkeeperActivation(shopkeeper);
		boolean activationStateChanged = (shopkeeper.isActive() != oldActivationState);

		// Inform spawner:
		shopkeeperSpawner.onShopkeeperMoved(shopkeeper, oldChunkCoords, activationStateChanged);
	}

	// CHUNK ACTIVATION

	public Timings getChunkActivationTimings() {
		return chunkActivationTimings;
	}

	public boolean isChunkActive(ChunkCoords chunkCoords) {
		ChunkData chunkData = this.getChunkData(chunkCoords);
		if (chunkData == null) return false;
		return chunkData.isActive();
	}

	void onChunkLoad(Chunk chunk) {
		assert chunk != null;
		ChunkData chunkData = this.getChunkData(chunk);
		if (chunkData == null) return; // There are no shopkeepers in this chunk

		// The chunk is not expected to already be active or pending delayed activation (assuming
		// that the server orders the chunk load and unload events consistently, and that we handle
		// them correctly):
		if (chunkData.isActive()) {
			Log.debug(DebugOptions.shopkeeperActivation,
					() -> "Detected chunk load for already active chunk: "
							+ TextUtils.getChunkString(chunk)
			);
			return;
		} else if (chunkData.isActivationDelayed()) {
			Log.debug(DebugOptions.shopkeeperActivation,
					() -> "Detected chunk load for chunk with already delayed activation: "
							+ TextUtils.getChunkString(chunk)
			);
			return;
		}

		// Delay the activation to not activate shopkeepers for chunks that are only loaded briefly:
		new DelayedChunkActivationTask(chunkData).start();
	}

	private class DelayedChunkActivationTask implements Runnable {

		private final ChunkData chunkData;
		private @Nullable WrappedTask task;

		DelayedChunkActivationTask(ChunkData chunkData) {
			assert chunkData != null;
			this.chunkData = chunkData;
		}

		void start() {
			assert !chunkData.isActive() && !chunkData.isActivationDelayed();
			ChunkCoords coords = chunkData.getChunkCoords();
			World world = coords.getWorld();
			if (world == null) return;
			Location location = new Location(world, coords.getChunkX() * 16 + 8, 0,
					coords.getChunkZ() * 16 + 8);
			this.task = SchedulerUtils.runTaskLaterOrOmit(
					location, this, CHUNK_ACTIVATION_DELAY_TICKS);
			chunkData.setDelayedActivationTask(task);
		}

		@Override
		public void run() {
			@Nullable WrappedTask task = this.task;
			if (stopped || chunks.get(chunkData.getChunkCoords()) != chunkData
					|| task == null || !chunkData.isDelayedActivationTask(task)) return;
			chunkData.setDelayedActivationTask(null);
			activateChunk(chunkData);
		}
	}

	void activatePendingNearbyChunksDelayed(Player player) {
		assert player != null;
		// Runs on the player's region, because it reads the player's location:
		SchedulerUtils.runTaskOrOmit(player, new ActivatePendingNearbyChunksTask(player));
	}

	private class ActivatePendingNearbyChunksTask implements Runnable {

		private final Player player;
		private final long expectedGeneration = generation;

		ActivatePendingNearbyChunksTask(Player player) {
			assert player != null;
			this.player = player;
		}

		@Override
		public void run() {
			if (stopped || generation != expectedGeneration) return;
			if (!player.isOnline()) return; // Player is no longer online
			activatePendingNearbyChunks(player);
		}
	}

	// Activates nearby chunks if they are currently pending a delayed activation:
	private void activatePendingNearbyChunks(Player player) {
		World world = player.getWorld();
		Location sharedLocation = ShopkeeperChunkActivator.sharedLocation.get();
		Location location = Unsafe.assertNonNull(player.getLocation(sharedLocation));
		int chunkX = ChunkCoords.fromBlock(location.getBlockX());
		int chunkZ = ChunkCoords.fromBlock(location.getBlockZ());
		sharedLocation.setWorld(null); // Reset
		this.activatePendingNearbyChunks(world, chunkX, chunkZ, immediateChunkActivationRadius);
	}

	// Activates nearby chunks if they are currently pending a delayed activation:
	private void activatePendingNearbyChunks(
			World world,
			int centerChunkX,
			int centerChunkZ,
			int chunkRadius
	) {
		assert world != null && chunkRadius >= 0;
		String worldName = world.getName();
		int minChunkX = centerChunkX - chunkRadius;
		int maxChunkX = centerChunkX + chunkRadius;
		int minChunkZ = centerChunkZ - chunkRadius;
		int maxChunkZ = centerChunkZ + chunkRadius;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				ChunkData chunkData = this.getChunkData(worldName, chunkX, chunkZ);
				if (chunkData == null) continue;

				// Activate the chunk if it is currently pending a delayed activation:
				if (chunkData.isActivationDelayed()) {
					this.activateChunk(chunkData);
				}
			}
		}
	}

	private boolean isActivationDeferred(ChunkData chunkData) {
		return deferredChunkActivations.contains(chunkData);
	}

	// This also reset's the chunk's 'should-be-active' state.
	private void cancelDeferredActivation(ChunkData chunkData) {
		assert chunkData != null;
		// Minor optimization: If the chunk is not marked as 'should-be-active', we can assume that
		// it is not pending a deferred activation.
		if (chunkData.isShouldBeActive()) {
			chunkData.setShouldBeActive(false);
			deferredChunkActivations.remove(chunkData);
		}
	}

	private void activateChunk(ChunkData chunkData) {
		assert chunkData != null;
		if (stopped) return;
		if (plugin.getFoliaLib().isFolia()) {
			ChunkCoords coords = chunkData.getChunkCoords();
			World world = coords.getWorld();
			if (world == null || !plugin.isEnabled()) return;
			Location location = new Location(world, coords.getChunkX() * 16 + 8, 0,
					coords.getChunkZ() * 16 + 8);
			if (!SchedulerUtils.isMainThread(location)) {
				SchedulerUtils.runTaskOrOmit(location, () -> this.activateChunk(chunkData));
				return;
			}
			if (chunks.get(coords) != chunkData || !coords.isChunkLoaded()) return;
		}
		// Note (SPIGOT-6980): On early versions of 1.18.2, chunks may report to not be loaded
		// during ChunkLoadEvents, which breaks this and several similar assertions (not so bad),
		// but likely also actual code related to chunk/shopkeeper activation (potentially bad).
		assert chunkData.getChunkCoords().isChunkLoaded() : "Chunk not loaded";

		// Update the chunk's target activation state:
		boolean oldShouldBeActive = chunkData.isShouldBeActive();
		chunkData.setShouldBeActive(true);

		if (chunkData.isActive()) {
			// The chunk is already active.
			assert !chunkData.isActivationDelayed();
			assert !this.isActivationDeferred(chunkData);
			return;
		}

		chunkData.cancelDelayedActivation(); // Cancel any pending delayed activation

		ChunkCoords chunkCoords = chunkData.getChunkCoords();
		if (!chunkActivationInProgress.compareAndSet(false, true)) {
			if (oldShouldBeActive) {
				// The chunk is already about to be activated.
				// Note: This does not necessarily indicate that the chunk is inside the
				// deferredChunkActivations queue (e.g. when multiple chunks are activated in a
				// single batch, they are all marked as 'should-be-active', but not added to the
				// deferred chunk activations queue).
				Log.debug(DebugOptions.shopkeeperActivation,
						() -> "Ignoring activation request of chunk " + chunkCoords
								+ ": The chunk is already pending activation.");
				return;
			}
			assert !this.isActivationDeferred(chunkData);

			Log.debug(DebugOptions.shopkeeperActivation,
					() -> "Another chunk activation is already in progress. "
							+ "Deferring activation of chunk " + chunkCoords);
			deferredChunkActivations.add(chunkData);
			if (!chunkActivationInProgress.get()) this.processDeferredChunkActivations();
			return;
		}
		// Deferred chunk activations can only be observed while another chunk activation is already
		// in progress.
		assert !this.isActivationDeferred(chunkData);

		chunkActivationTimings.start();

		// Get the chunk shopkeepers:
		Collection<? extends AbstractShopkeeper> shopkeepers = shopkeeperRegistry.getShopkeepersInChunkSnapshot(chunkCoords);

		Log.debug(DebugOptions.shopkeeperActivation,
				() -> "Activating " + shopkeepers.size() + " shopkeepers in chunk "
						+ TextUtils.getChunkString(chunkCoords)
		);

		// Mark the chunk as active:
		chunkData.setActive(true);
		if (plugin.getFoliaLib().isFolia()) {
			chunkActivationInProgress.set(false);
			chunkActivationTimings.stop();
			shopkeepers.forEach(shopkeeper -> shopkeeperRegistry.runOnOwner(shopkeeper, () -> {
				if (!stopped && chunks.get(chunkCoords) == chunkData && chunkData.isActive()
						&& shopkeeper.isValid()
						&& chunkCoords.equals(shopkeeper.getLastChunkCoords())) {
					this.activateShopkeeper(shopkeeper);
				}

				return true;
			}));
			this.processDeferredChunkActivations();
			return;
		}

		// Mark the shopkeepers as active:
		shopkeepers.forEach(shopkeeper -> shopkeeper.setActive(true));

		try {
			for (AbstractShopkeeper shopkeeper : shopkeepers) {
				// Abort the chunk activation if it has been deactivated again in the meantime:
				if (!chunkData.isActive()) {
					return;
				}

				// Skip if the shopkeeper's activation state has already changed again:
				if (!shopkeeper.isActive()) {
					continue;
				}

				// Note: Even if the shopkeeper has changed its chunk in the meantime, we still need
				// to complete its activation, because no one else does (we have already set its
				// activation state earlier, so everyone else assumes that it is already active).

				// Start ticking the shopkeepers:
				shopkeeperTicker.startTicking(shopkeeper);
			}

			// Abort the chunk activation if it has been deactivated again in the meantime:
			if (!chunkData.isActive()) {
				return;
			}

			// Spawn the shopkeepers that are still marked as active:
			// In order to avoid spawning lots of shopkeepers at the same time, we don't actually
			// spawn the shopkeepers immediately, but add them to the spawn queue instead.
			shopkeeperSpawner.spawnChunkShopkeepers(
					chunkCoords,
					"activation",
					shopkeepers,
					SHOPKEEPER_IS_ACTIVE,
					false
			);
		} finally {
			chunkActivationTimings.stop();
			chunkActivationInProgress.set(false);

			// Process the deferred chunk activations:
			this.processDeferredChunkActivations();
		}
	}

	private void processDeferredChunkActivations() {
		ChunkData chunkData;
		while ((chunkData = deferredChunkActivations.poll()) != null) {
			if (chunkData.isShouldBeActive()) this.activateChunk(chunkData);
		}
	}

	// CHUNK DEACTIVATION

	void onChunkUnload(Chunk chunk) {
		assert chunk != null;
		ChunkData chunkData = this.getChunkData(chunk);
		if (chunkData == null) return; // There are no shopkeepers in this chunk

		this.deactivateChunk(chunkData);
	}

	private void deactivateChunk(ChunkData chunkData) {
		assert chunkData != null;
		if (stopped) return;
		ChunkCoords chunkCoords = chunkData.getChunkCoords();
		if (plugin.getFoliaLib().isFolia()) {
			World world = chunkCoords.getWorld();
			if (world == null || !plugin.isEnabled()) return;
			Location location = new Location(world, chunkCoords.getChunkX() * 16 + 8, 0,
					chunkCoords.getChunkZ() * 16 + 8);
			if (!SchedulerUtils.isMainThread(location)) {
				SchedulerUtils.runTaskOrOmit(location, () -> {
					if (chunks.get(chunkCoords) == chunkData) this.deactivateChunk(chunkData);
				});
				return;
			}
		}

		if (!chunkData.isActive()) {
			// The chunk is already inactive.
			// Cancel any pending activations for the chunk:
			// Note: This needs access to the chunk's previous 'should-be-active' state.
			// This also resets the chunk's 'should-be-active' state, even if it is not pending a
			// deferred chunk activation.
			this.cancelDeferredActivation(chunkData);
			chunkData.cancelDelayedActivation();
			return;
		}
		assert !chunkData.isActivationDelayed();
		assert !this.isActivationDeferred(chunkData);

		// Mark the chunk as inactive:
		// This also sets its 'should-be-inactive' state.
		chunkData.setActive(false);

		// Get the chunk shopkeepers:
		Collection<? extends AbstractShopkeeper> shopkeepers = shopkeeperRegistry.getShopkeepersInChunkSnapshot(chunkCoords);

		Log.debug(DebugOptions.shopkeeperActivation,
				() -> "Deactivating " + shopkeepers.size() + " shopkeepers in chunk "
						+ TextUtils.getChunkString(chunkCoords)
		);

		if (plugin.getFoliaLib().isFolia()) {
			shopkeepers.forEach(shopkeeper -> shopkeeperRegistry.runOnOwner(shopkeeper, () -> {
				if (!stopped && chunks.get(chunkCoords) == chunkData && !chunkData.isActive()
						&& shopkeeper.isValid()
						&& chunkCoords.equals(shopkeeper.getLastChunkCoords())) {
					this.deactivateShopkeeper(shopkeeper);
				}

				return true;
			}));
			return;
		}

		// Mark the shopkeepers as inactive:
		shopkeepers.forEach(shopkeeper -> shopkeeper.setActive(false));

		for (AbstractShopkeeper shopkeeper : shopkeepers) {
			// Abort the chunk deactivation if it has been activated again in the meantime:
			if (chunkData.isActive()) {
				return;
			}

			// Skip if the shopkeeper's activation state has already changed again:
			if (shopkeeper.isActive()) {
				continue;
			}

			// Note: Even if the shopkeeper has changed its chunk in the meantime, we still need to
			// complete its deactivation, because no one else does (we have already set its
			// activation state earlier, so everyone else assumes that it is already inactive).

			// Stop ticking the shopkeeper:
			shopkeeperTicker.stopTicking(shopkeeper);
		}

		// Abort the chunk deactivation if it has been activated again in the meantime:
		if (chunkData.isActive()) {
			return;
		}

		// Despawn the shopkeepers:
		shopkeeperSpawner.despawnChunkShopkeepers(
				chunkCoords,
				"deactivation",
				shopkeepers,
				SHOPKEEPER_IS_INACTIVE,
				null
		);
	}

	// WORLD LOAD

	// Called by SKShopkeepersPlugin during enable.
	// TODO This might not be needed, because chunk entries for loaded chunks are automatically
	// marked as active when the first shopkeeper is added, and any shopkeepers added to an already
	// active chunk are immediately activated.
	// But this also logs summary debug messages about the activated shopkeepers. Maybe disable
	// automatic activation when loading shopkeepers and then activate them all in bulk via this
	// method?
	public void activateShopkeepersInAllWorlds() {
		// Activate (spawn) shopkeepers in loaded chunks of all loaded worlds:
		List<? extends World> worlds = Unsafe.castNonNull(Bukkit.getWorlds());
		worlds.forEach(this::activateChunks);
	}

	void onWorldLoad(World world) {
		assert world != null;
		this.activateChunks(world);
	}

	// Activates all loaded chunks of the given world.
	private void activateChunks(World world) {
		assert world != null;
		String worldName = world.getName();
		int shopkeeperCount = shopkeeperRegistry.getShopkeepersInWorld(worldName).size();
		if (shopkeeperCount == 0) return; // There are no shopkeepers in this world

		Log.debug(DebugOptions.shopkeeperActivation,
				() -> "Activating " + shopkeeperCount + " shopkeepers in world '" + worldName + "'"
		);

		// We need to iterate over a snapshot of these chunks, because the chunk map can change
		// during iteration.
		List<? extends ChunkCoords> chunks = new ArrayList<>(
				shopkeeperRegistry.getShopkeepersByChunks(worldName).keySet()
		);
		if (plugin.getFoliaLib().isFolia()) {
			chunks.forEach(coords -> {
				ChunkData chunkData = this.getChunkData(coords);
				if (chunkData != null) this.activateChunk(chunkData);
			});
			return;
		}

		// First, mark the chunks as 'should-be-active':
		chunks.forEach(chunkCoords -> {
			ChunkData chunkData = Unsafe.assertNonNull(this.getChunkData(chunkCoords));
			if (chunkData.needsActivation()) {
				chunkData.setShouldBeActive(true);
			}
		});

		// Activate the chunks, unless their target activation state has changed in the meantime:
		chunks.forEach(this::activateChunkIfShouldBeActive);
	}

	private void activateChunkIfShouldBeActive(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		ChunkData chunkData = this.getChunkData(chunkCoords);
		if (chunkData == null) return; // There are no shopkeepers in this chunk
		if (!chunkData.isShouldBeActive()) return;

		this.activateChunk(chunkData);
	}

	// WORLD UNLOAD

	// Called by SKShopkeepersPlugin during disable, before the shopkeepers are unloaded.
	public void deactivateShopkeepersInAllWorlds() {
		// Deactivate (despawn) shopkeepers in all loaded worlds:
		List<? extends World> worlds = Unsafe.castNonNull(Bukkit.getWorlds());
		worlds.forEach(this::deactivateChunks);
	}

	void onWorldUnload(World world) {
		assert world != null;
		this.deactivateChunks(world);
	}

	private void deactivateChunks(World world) {
		assert world != null;
		String worldName = world.getName();
		int shopkeeperCount = shopkeeperRegistry.getShopkeepersInWorld(worldName).size();
		if (shopkeeperCount == 0) return; // There are no shopkeepers in this world

		Log.debug(DebugOptions.shopkeeperActivation,
				() -> "Deactivating " + shopkeeperCount + " shopkeepers in world '" + worldName + "'"
		);

		// We need to iterate over a snapshot of these chunks, because the chunk map can change
		// during iteration.
		List<? extends ChunkCoords> chunks = new ArrayList<>(
				shopkeeperRegistry.getShopkeepersByChunks(worldName).keySet()
		);

		// First, mark the chunks as 'should-be-inactive':
		chunks.forEach(chunkCoords -> {
			ChunkData chunkData = Unsafe.assertNonNull(this.getChunkData(chunkCoords));
			chunkData.setShouldBeActive(false);
		});

		// Deactivate the chunks, unless their target activation state has changed in the meantime:
		chunks.forEach(this::deactivateChunkIfShouldBeInactive);
	}

	private void deactivateChunkIfShouldBeInactive(ChunkCoords chunkCoords) {
		assert chunkCoords != null;
		ChunkData chunkData = this.getChunkData(chunkCoords);
		if (chunkData == null) return; // There are no shopkeepers in this chunk
		if (chunkData.isShouldBeActive()) return;

		this.deactivateChunk(chunkData);
	}
}
