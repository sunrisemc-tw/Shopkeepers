package com.nisovin.shopkeepers.shopobjects.entity.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.compat.Compat;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.util.bukkit.EntityUtils;
import com.nisovin.shopkeepers.util.bukkit.MutableChunkCoords;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.WorldUtils;
import com.nisovin.shopkeepers.util.java.CyclicCounter;
import com.nisovin.shopkeepers.util.java.RateLimiter;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.timer.Timer;
import com.nisovin.shopkeepers.util.timer.Timings;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Handles the gravity and AI behavior, e.g. looking at nearby players, of
 * {@link BaseEntityShopObject}s.
 * <p>
 * Note: Our gravity and entity AI is also available for non-living entity shop objects. It is up to
 * the {@link BaseEntityShopObject#tickAI()} to implement the behavior for the entity.
 * <p>
 * Shop objects must be {@link #addShopObject(BaseEntityShopObject) added} when their entity has
 * been spawned, and {@link #removeShopObject(BaseEntityShopObject) removed} again when their entity
 * is despawned.
 * <p>
 * It is assumed that the shop objects / entities don't change their initial location (chunk). If
 * they do change their location, the AI system must be informed via
 * {@link #updateLocation(BaseEntityShopObject)} in order for their gravity and AI activation to
 * still function correctly.
 */
public class EntityAI implements Listener {

	/**
	 * The range at which shopkeeper mobs look at players.
	 */
	public static final float LOOK_RANGE = 6.0F;

	/**
	 * Determines how often AI activations are rechecked (every X ticks).
	 * <p>
	 * We also separately react to player joins and teleports in order to quickly activate the AI of
	 * nearby shopkeepers in those cases. Note that this only has an effect if the nearby chunks
	 * were already loaded and their shopkeepers were already spawned. However, if this is not the
	 * case, the chunk will be marked as active by default anyway already once the shopkeepers are
	 * spawned (which can happen deferred to chunk loading, due to the deferred chunk activation and
	 * the spawn queue).
	 */
	// 30 ticks is quick enough to fluently react even to players flying in creative mode with
	// default flying speed.
	public static final int AI_ACTIVATION_TICK_RATE = 30;

	/**
	 * The range in chunks around players in which AI is active.
	 * <p>
	 * The look-at-players AI goal only targets players in a {@link #LOOK_RANGE} radius, so we can
	 * limit the AI ticking to the direct chunks around the player.
	 */
	private static final int AI_ACTIVATION_CHUNK_RANGE = 1;
	// Regarding gravity activation range:
	// Players can see shop entities from further away, so we use a large enough range for the
	// activation of falling checks (configurable in the config, default 4).
	// TODO Take view/tracking distances into account? (spigot-config specific though..)

	// Entities won't fall, if their distance-to-ground is smaller than this:
	private static final double DISTANCE_TO_GROUND_THRESHOLD = 0.01D;
	// Determines the max. falling speed:
	// Note: We allow a falling step size that is slightly larger than this, if we reach the end of
	// the fall by that.
	// Note: Entities get spawned 0.5 above the ground.
	// By using 0.5 here (and allowing slightly larger step sizes if they stop the fall) we can be
	// sure to require at most a single step for the most common falls and have the entity
	// positioned perfectly on the ground.
	// TODO Dynamically increase an entities falling speed? Need to dynamically adjust the collision
	// check range as well then.
	// Note: This is scaled according to the used tick rate.
	private static final double MAX_FALLING_DISTANCE_PER_TICK = 0.5D;

	/**
	 * The period in ticks in which we check if an entity is supposed to fall.
	 */
	private static final int FALLING_CHECK_PERIOD_TICKS = 10;
	private static final CyclicCounter nextFallingCheckOffset = new CyclicCounter(
			1,
			FALLING_CHECK_PERIOD_TICKS + 1
	);

	// Temporarily re-used objects:
	// Thread-local, because on Folia the per-entity processing runs on different region threads
	// concurrently and must not share these mutable buffers.
	private static final ThreadLocal<Location> sharedLocation = ThreadLocal.withInitial(
			() -> new Location(null, 0, 0, 0)
	);
	private static final ThreadLocal<MutableChunkCoords> sharedChunkCoords = ThreadLocal.withInitial(
			MutableChunkCoords::new
	);

	private final SKShopkeepersPlugin plugin;
	/**
	 * The MAX_FALLING_DISTANCE_PER_TICK scaled according to the configured tick rate.
	 */
	private double maxFallingDistancePerUpdate;
	/**
	 * The range in which we check for block collisions.
	 * <p>
	 * Has to be slightly larger than the
	 * {@code maxFallingDistancePerUpdate + DISTANCE_TO_GROUND_THRESHOLD} in order to take into
	 * account the max falling speed and to detect the end of the falling without having to check
	 * for block collisions another time in the next behavior update.
	 */
	private double gravityCollisionCheckRange;
	/**
	 * Whether we use our custom gravity handling.
	 * <p>
	 * The value of this depends on the plugin configuration (gravity can be disabled) and the
	 * specific Minecraft version (on some Minecraft versions the NoAI entity flag does not disable
	 * the gravity of mobs).
	 */
	private boolean customGravityEnabled;

	private static class EntityData {

		private final BaseEntityShopObject<?> shopObject;
		private final Entity entity;
		private final ChunkData chunkData;
		private final boolean affectedByGravity;
		// Initial threshold between [1, FALLING_CHECK_PERIOD_TICKS] for load balancing:
		public final RateLimiter fallingCheckLimiter = new RateLimiter(
				FALLING_CHECK_PERIOD_TICKS,
				nextFallingCheckOffset()
		);
		// Note: This is used to check if the mob is currently falling and should therefore receive
		// more frequent gravity updates. Flying entities do not use this 'falling' state, since
		// they are expected to remain in their flying state for longer, so we can use the reduced
		// fallingCheckLimiter rate for them.
		public boolean falling = false;
		public double distanceToGround = 0.0D;

		public EntityData(
				BaseEntityShopObject<?> shopObject,
				Entity entity,
				ChunkData chunkData,
				boolean affectedByGravity
		) {
			this.shopObject = shopObject;
			this.entity = entity;
			this.chunkData = chunkData;
			this.affectedByGravity = affectedByGravity;
		}

		public boolean isAffectedByGravity() {
			// Note: Flying mobs are also "affected" by gravity: The gravity logic periodically
			// checks if the mob is still flying and should therefore play its flying animation.
			return affectedByGravity;
		}
	}

	private static int nextFallingCheckOffset() {
		synchronized (nextFallingCheckOffset) {
			return nextFallingCheckOffset.getAndIncrement();
		}
	}

	private static class ChunkData {

		private final ChunkCoords chunkCoords;
		// We don't expect there to be many entities within a single chunk, so using a list is okay.
		private final List<EntityData> entities = new ArrayList<>();
		// Active by default for fast initial reactions in case players are nearby:
		public boolean activeGravity;
		public boolean activeAI = true;

		public ChunkData(ChunkCoords chunkCoords, boolean activeGravity) {
			this.chunkCoords = chunkCoords;
			this.activeGravity = activeGravity;
		}
	}

	// Guards both indexes, chunk activations, statistics, and task lifecycle state. Scheduler and
	// world operations must remain outside this lock.
	private final Object stateLock = new Object();
	private final Map<ChunkCoords, ChunkData> chunks = new LinkedHashMap<>();
	// Index for fast removal: Shop object -> EntityData
	private final Map<BaseEntityShopObject<?>, EntityData> shopObjects = new HashMap<>();

	private @Nullable WrappedTask aiTask = null;
	private long startingTaskLifecycle = -1L;
	private boolean enabled = false;
	private long lifecycle = 0L;
	private long activationGeneration = 0L;
	private volatile boolean currentlyRunning = false;

	// Statistics:
	// On Folia these count the currently registered entities in active chunks, not callbacks in
	// flight. Region timings count completed per-entity samples since the last reset. The global
	// total and activation timings measure dispatch only, without waiting for region callbacks.
	private int activeAIChunksCount = 0;
	private int activeAIEntityCount = 0;

	private int activeGravityChunksCount = 0;
	private int activeGravityEntityCount = 0;

	private final Timer totalTimings = new Timer();
	// Note: This only captures the periodic full activation updates, and not the player-specific
	// activations triggered
	// by player joins and teleports.
	private final Timer activationTimings = new Timer();
	private final Timer gravityTimings = new Timer();
	private final Timer aiTimings = new Timer();

	public EntityAI(SKShopkeepersPlugin plugin) {
		this.plugin = plugin;
	}

	public void onEnable() {
		// Setup values based on settings:
		// TODO: Also update these on dynamic setting changes.
		maxFallingDistancePerUpdate = Settings.entityBehaviorTickPeriod * MAX_FALLING_DISTANCE_PER_TICK;
		gravityCollisionCheckRange = maxFallingDistancePerUpdate + 0.1D;
		customGravityEnabled = _isCustomGravityEnabled();
		synchronized (stateLock) {
			enabled = true;
			lifecycle++;
		}

		// Register listener:
		Bukkit.getPluginManager().registerEvents(this, plugin);

		// Start task:
		this.startTask();
	}

	public void onDisable() {
		assert !currentlyRunning;
		synchronized (stateLock) {
			enabled = false;
			lifecycle++;
			activationGeneration++;
			chunks.clear();
			shopObjects.clear();
			this.resetStatistics();
		}

		HandlerList.unregisterAll(this); // Unregister listener
		this.stopTask();
	}

	// SHOP OBJECTS

	public void addShopObject(BaseEntityShopObject<?> shopObject) {
		this.addShopObject(shopObject, false);
	}

	private void addShopObject(BaseEntityShopObject<?> shopObject, boolean replace) {
		Validate.notNull(shopObject, "shopObject is null");
		Validate.State.isTrue(!currentlyRunning,
				"Cannot add shop objects while the AI task is running!");
		long currentLifecycle;
		synchronized (stateLock) {
			if (!enabled) return;
			currentLifecycle = lifecycle;
		}

		// Note: We expect that the shop object is unregistered again when its entity is despawned.
		Entity entity = shopObject.getEntity();
		Validate.notNull(entity, "shopObject is not spawned currently!");
		assert entity != null;
		Validate.isTrue(entity.isValid(), "entity is invalid");

		// Determine entity chunk (asserts that the entity won't move!):
		// We assert that the chunk is loaded (checked above by isValid call).
		Location sharedLocation = EntityAI.sharedLocation.get();
		MutableChunkCoords sharedChunkCoords = EntityAI.sharedChunkCoords.get();
		Location entityLocation = Unsafe.assertNonNull(entity.getLocation(sharedLocation));
		sharedChunkCoords.set(entityLocation);
		sharedLocation.setWorld(null); // Reset

		boolean folia = plugin.getFoliaLib().isFolia();
		boolean affectedByGravity = shopObject.getEntityType() != EntityType.SHULKER;
		synchronized (stateLock) {
			if (!enabled || lifecycle != currentLifecycle) return;
			if (replace) this.removeShopObject(shopObject, folia);
			Validate.isTrue(!shopObjects.containsKey(shopObject), "shopObject is already added");

			// Add chunk entry:
			ChunkData chunkData = chunks.get(sharedChunkCoords);
			if (chunkData == null) {
				ChunkCoords chunkCoords = new ChunkCoords(sharedChunkCoords); // Copy
				chunkData = new ChunkData(chunkCoords, customGravityEnabled);
				chunks.put(chunkCoords, chunkData);

				// Update chunk statistics:
				if (chunkData.activeAI) {
					activeAIChunksCount++;
				}
				if (chunkData.activeGravity) {
					activeGravityChunksCount++;
				}
			}

			// Add entity entry:
			EntityData entityData = new EntityData(shopObject, entity, chunkData, affectedByGravity);
			shopObjects.put(shopObject, entityData);
			chunkData.entities.add(entityData);

			// Update entity statistics:
			if (chunkData.activeAI) {
				activeAIEntityCount++;
			}
			if (chunkData.activeGravity && (!folia || affectedByGravity)) {
				activeGravityEntityCount++;
			}
		}

		// Start the AI task, if it isn't already running:
		this.startTask();
	}

	public void removeShopObject(BaseEntityShopObject<?> shopObject) {
		Validate.State.isTrue(!currentlyRunning,
				"Cannot remove entities while the AI task is running!");
		boolean folia = plugin.getFoliaLib().isFolia();
		synchronized (stateLock) {
			this.removeShopObject(shopObject, folia);
		}
	}

	private void removeShopObject(BaseEntityShopObject<?> shopObject, boolean folia) {
		assert Thread.holdsLock(stateLock);
		@Nullable EntityData entityData = shopObjects.remove(shopObject);
		if (entityData == null) return; // Shop object was not added

		ChunkData chunkData = entityData.chunkData;
		chunkData.entities.remove(entityData);
		if (chunkData.entities.isEmpty()) {
			chunks.remove(chunkData.chunkCoords);

			// Update chunk statistics:
			if (chunkData.activeAI) {
				activeAIChunksCount--;
			}
			if (chunkData.activeGravity) {
				activeGravityChunksCount--;
			}
		}

		// Update entity statistics:
		if (chunkData.activeAI) {
			activeAIEntityCount--;
		}
		if (chunkData.activeGravity && (!folia || entityData.isAffectedByGravity())) {
			activeGravityEntityCount--;
		}
	}

	public void updateLocation(BaseEntityShopObject<?> shopObject) {
		this.addShopObject(shopObject, true);
	}

	// STATISTICS

	private void resetStatistics() {
		activeAIChunksCount = 0;
		activeAIEntityCount = 0;

		activeGravityChunksCount = 0;
		activeGravityEntityCount = 0;

		totalTimings.reset();
		activationTimings.reset();
		gravityTimings.reset();
		aiTimings.reset();
	}

	public int getEntityCount() {
		synchronized (stateLock) {
			return shopObjects.size();
		}
	}

	public int getActiveAIChunksCount() {
		synchronized (stateLock) {
			return activeAIChunksCount;
		}
	}

	public int getActiveAIEntityCount() {
		synchronized (stateLock) {
			return activeAIEntityCount;
		}
	}

	public int getActiveGravityChunksCount() {
		synchronized (stateLock) {
			return activeGravityChunksCount;
		}
	}

	public int getActiveGravityEntityCount() {
		synchronized (stateLock) {
			return activeGravityEntityCount;
		}
	}

	public Timings getTotalTimings() {
		return totalTimings;
	}

	public Timings getActivationTimings() {
		return activationTimings;
	}

	public Timings getGravityTimings() {
		return gravityTimings;
	}

	public Timings getAITimings() {
		return aiTimings;
	}

	// TASK

	private void startTask() {
		long taskLifecycle;
		synchronized (stateLock) {
			if (!enabled || aiTask != null || startingTaskLifecycle == lifecycle) return;
			startingTaskLifecycle = lifecycle;
			taskLifecycle = lifecycle;
		}

		// Start AI task:
		// The task drives on the global thread; on Folia the per-entity processing is dispatched onto
		// the respective entity's region (see processEntity).
		int tickPeriod = Settings.entityBehaviorTickPeriod;
		@Nullable WrappedTask task = null;
		try {
			task = SchedulerUtils.runTaskTimerGloballyOrOmit(
					new TickTask(taskLifecycle), tickPeriod, tickPeriod
			);
		} finally {
			synchronized (stateLock) {
				if (startingTaskLifecycle == taskLifecycle) startingTaskLifecycle = -1L;
				if (enabled && lifecycle == taskLifecycle) {
					aiTask = task;
					task = null;
				}
			}

			if (task != null) task.cancel();
		}
	}

	private void stopTask() {
		@Nullable WrappedTask task;
		synchronized (stateLock) {
			task = aiTask;
			aiTask = null;
		}

		if (task != null) task.cancel();
	}

	private class TickTask implements Runnable {

		private final RateLimiter aiActivationLimiter = new RateLimiter(AI_ACTIVATION_TICK_RATE);
		private final long taskLifecycle;

		TickTask(long taskLifecycle) {
			this.taskLifecycle = taskLifecycle;
		}

		@Override
		public void run() {
			// Skip if there are no entities with AI currently:
			// Note: We keep the task running, because frequently starting and stopping the task
			// would be associated with a certain overhead as well.
			synchronized (stateLock) {
				if (!enabled || lifecycle != taskLifecycle || shopObjects.isEmpty()) return;
			}

			// On Folia, the per-entity processing is dispatched onto region threads (see
			// processEntity), so it does not run synchronously within this driver. We therefore do not
			// mark the AI system as 'currently running' (which would otherwise block concurrent
			// add/remove operations from region threads), and we only measure the driver's own
			// (synchronous) work via the total and activation timings.
			boolean folia = plugin.getFoliaLib().isFolia();

			if (!folia) {
				currentlyRunning = true;
			}

			// Start timings:
			totalTimings.start();
			if (!folia) {
				gravityTimings.startPaused();
				aiTimings.startPaused();
			}

			try {
				// Recheck active chunks/entities every AI_ACTIVATION_TICK_RATE ticks:
				if (aiActivationLimiter.request(Settings.entityBehaviorTickPeriod)) {
					updateChunkActivations();
				}

				processEntities();
			} finally {
				totalTimings.stop();
				if (!folia) {
					gravityTimings.stop();
					aiTimings.stop();
					currentlyRunning = false;
				}
			}
		}
	}

	// CHUNK ACTIVATIONS

	private void updateChunkActivations() {
		activationTimings.start();

		boolean folia = plugin.getFoliaLib().isFolia();
		try {
			long currentLifecycle;
			long generation;
			synchronized (stateLock) {
				if (!enabled) return;
				currentLifecycle = lifecycle;
				generation = ++activationGeneration;
				for (ChunkData chunkData : chunks.values()) {
					chunkData.activeAI = false;
					chunkData.activeGravity = false;
				}

				activeAIChunksCount = 0;
				activeGravityChunksCount = 0;
				if (folia) {
					activeAIEntityCount = 0;
					activeGravityEntityCount = 0;
				}
			}

			// Player locations are read only on their owning regions.
			for (Player player : Bukkit.getOnlinePlayers()) {
				assert player != null;
				if (folia) {
					SchedulerUtils.runTaskOrOmit(player,
							() -> this.activateNearbyChunks(player, currentLifecycle, generation));
				} else {
					this.activateNearbyChunks(player, currentLifecycle, generation);
				}
			}
		} finally {
			activationTimings.stop();
		}
	}

	// Note: This only activates chunks around the player, but does not deactivate any chunks that
	// have previously been activated by the player. The periodic full activation update deactivates
	// all chunks that no longer require activation.
	private void activateNearbyChunks(Player player, long currentLifecycle, long generation) {
		synchronized (stateLock) {
			if (!enabled || lifecycle != currentLifecycle
					|| activationGeneration != generation) return;
		}

		if (!player.isOnline()) return;
		World world = player.getWorld();
		String worldName = world.getName();
		boolean folia = plugin.getFoliaLib().isFolia();
		Location sharedLocation = EntityAI.sharedLocation.get();
		Location location = Unsafe.assertNonNull(player.getLocation(sharedLocation));
		// Note: On some Paper versions with their async chunk loading, the player's current chunk
		// may sometimes not be loaded yet. We therefore avoid accessing (and thereby loading) that
		// chunk here, but instead only use its coordinates. The subsequent activation of nearby
		// chunks only considers loaded chunks.
		int chunkX = ChunkCoords.fromBlock(location.getBlockX());
		int chunkZ = ChunkCoords.fromBlock(location.getBlockZ());
		sharedLocation.setWorld(null); // Reset

		synchronized (stateLock) {
			// Ignore callbacks from previous activation scans or plugin lifecycles.
			if (!enabled || lifecycle != currentLifecycle
					|| activationGeneration != generation) return;

			this.activateNearbyChunks(
					worldName,
					chunkX,
					chunkZ,
					AI_ACTIVATION_CHUNK_RANGE,
					ActivationType.AI,
					folia
			);
			if (customGravityEnabled) {
				assert Settings.gravityChunkRange >= 0;
				this.activateNearbyChunks(
						worldName,
						chunkX,
						chunkZ,
						Settings.gravityChunkRange,
						ActivationType.GRAVITY,
						folia
				);
			}
		}
	}

	private void activateNearbyChunksDelayed(Player player) {
		long currentLifecycle;
		long generation;
		synchronized (stateLock) {
			if (!enabled) return;
			currentLifecycle = lifecycle;
			generation = activationGeneration;
		}

		SchedulerUtils.runTaskOrOmit(player,
				() -> this.activateNearbyChunks(player, currentLifecycle, generation));
	}

	private enum ActivationType {
		GRAVITY,
		AI;
	}

	private void activateNearbyChunks(
			String worldName,
			int centerChunkX,
			int centerChunkZ,
			int chunkRadius,
			ActivationType activationType,
			boolean folia
	) {
		assert Thread.holdsLock(stateLock) && chunkRadius >= 0;
		MutableChunkCoords sharedChunkCoords = EntityAI.sharedChunkCoords.get();
		int minChunkX = centerChunkX - chunkRadius;
		int maxChunkX = centerChunkX + chunkRadius;
		int minChunkZ = centerChunkZ - chunkRadius;
		int maxChunkZ = centerChunkZ + chunkRadius;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				sharedChunkCoords.set(worldName, chunkX, chunkZ);
				ChunkData chunkData = chunks.get(sharedChunkCoords);
				if (chunkData == null) continue;

				switch (activationType) {
				case GRAVITY:
					if (!chunkData.activeGravity) {
						chunkData.activeGravity = true;
						activeGravityChunksCount++;
						if (folia) {
							for (EntityData entityData : chunkData.entities) {
								if (entityData.isAffectedByGravity()) activeGravityEntityCount++;
							}
						}
					}
					break;
				case AI:
					if (!chunkData.activeAI) {
						chunkData.activeAI = true;
						activeAIChunksCount++;
						if (folia) activeAIEntityCount += chunkData.entities.size();
					}
					break;
				default:
					throw new IllegalStateException("Unexpected activation type: "
							+ activationType);
				}
			}
		}
	}

	// ENTITY PROCESSING

	private void processEntities() {
		boolean folia = plugin.getFoliaLib().isFolia();
		List<EntityData> snapshot = new ArrayList<>();
		synchronized (stateLock) {
			if (!enabled) return;
			if (!folia) {
				activeAIEntityCount = 0;
				activeGravityEntityCount = 0;
			}

			for (ChunkData chunkData : chunks.values()) {
				if (chunkData.activeGravity || chunkData.activeAI) {
					snapshot.addAll(chunkData.entities);
				}
			}
		}

		snapshot.forEach(this::processEntity);
	}

	private void processEntity(EntityData entityData) {
		assert entityData != null;
		Entity entity = entityData.entity;

		if (plugin.getFoliaLib().isFolia()) {
			SchedulerUtils.runTaskOrOmit(entity, () -> this.processEntityRegion(entityData, entity));
			return;
		}

		if (entityData.shopObject.getEntity() != entity) return;

		// Note: Checking entity.isValid() is relatively heavy (compared to other operations) due to
		// a chunk lookup. The entity's entry is already immediately getting removed as reaction to
		// its chunk being unloaded. So there should be no need to check for that here.
		// TODO Actually, if the entity moved into a different chunk and we did not update its
		// location in the chunk index yet, it may already have been unloaded but still getting
		// ticked here. However, this is not the case currently, since all shopkeeper entities are
		// stationary (unless some other plugin teleports them).
		if (entity.isDead()) {
			// Some plugin might have removed the entity. The shop object will remove the entity's
			// entry once it recognizes that the entity has been removed. Until then, we simply skip
			// it here.
			return;
		}

		ChunkData chunkData = entityData.chunkData;

		// Process gravity:
		gravityTimings.resume();
		if (chunkData.activeGravity && entityData.isAffectedByGravity()) {
			synchronized (stateLock) {
				activeGravityEntityCount++;
			}

			this.processGravity(entityData);
		}
		gravityTimings.pause();

		// Process AI:
		aiTimings.resume();
		if (chunkData.activeAI) {
			synchronized (stateLock) {
				activeAIEntityCount++;
			}

			this.processAI(entityData);
		}
		aiTimings.pause();
	}

	private boolean isRegistered(EntityData entityData) {
		assert Thread.holdsLock(stateLock);
		return enabled && shopObjects.get(entityData.shopObject) == entityData;
	}

	// Each region measures its own completed durations, without sharing timer start/pause state.
	private void processEntityRegion(EntityData entityData, Entity entity) {
		boolean gravity;
		long currentLifecycle;
		synchronized (stateLock) {
			if (!this.isRegistered(entityData)) return;
			currentLifecycle = lifecycle;
			gravity = entityData.chunkData.activeGravity && entityData.isAffectedByGravity();
		}

		if (entityData.shopObject.getEntity() != entity || entity.isDead()) return;

		if (gravity) {
			long start = System.nanoTime();
			try {
				this.processGravity(entityData);
			} finally {
				long elapsed = System.nanoTime() - start;
				synchronized (stateLock) {
					if (enabled && lifecycle == currentLifecycle) gravityTimings.record(elapsed);
				}
			}
		}

		synchronized (stateLock) {
			if (!this.isRegistered(entityData) || !entityData.chunkData.activeAI) return;
		}

		if (entityData.shopObject.getEntity() != entity || entity.isDead()) return;
		long start = System.nanoTime();
		try {
			this.processAI(entityData);
		} finally {
			long elapsed = System.nanoTime() - start;
			synchronized (stateLock) {
				if (enabled && lifecycle == currentLifecycle) aiTimings.record(elapsed);
			}
		}
	}

	// GRAVITY

	// The result of this check is cached on plugin enable.
	private boolean _isCustomGravityEnabled() {
		// Gravity is enabled and not already handled by Minecraft itself:
		return !Settings.disableGravity && Compat.getProvider().isNoAIDisablingGravity();
	}

	private void processGravity(EntityData entityData) {
		// Check periodically, or if already falling, if the entity is meant to (continue to) fall:
		// Note: The falling check limiter is not invoked while the entity is already falling. This
		// ensures that once the entity stops its current fall the limiter will wait a full cycle
		// before we check again if the entity is falling again.
		if (entityData.falling
				|| entityData.fallingCheckLimiter.request(Settings.entityBehaviorTickPeriod)) {
			// Check if the entity is supposed to (continue to) fall by performing a ray cast
			// towards the ground:
			// Note: One attempt of optimizing this has been to only perform the raytrace if the
			// data of the block below the entity is still the same. However, it turns out that,
			// performance-wise, even accessing the chunk / the block's type is already comparable
			// to the raytrace itself, and that this optimization attempt even adds a small
			// performance impact on top instead.
			Entity entity = entityData.entity;
			Location sharedLocation = EntityAI.sharedLocation.get();
			Location entityLocation = Unsafe.assertNonNull(entity.getLocation(sharedLocation));

			// The entity may be able to stand on certain types of fluids:
			Set<? extends Material> collidableFluids = EntityUtils.getCollidableFluids(
					entity.getType()
			);
			// However, if the entity is inside a fluid (i.e. if it is spawned underwater or inside
			// of lava), we ignore this aspect (i.e. it sinks to the ground even if it can usually
			// stand on top of the liquid).
			// We check the block above the entity's location, because fluids are usually not a full
			// block high (even if the block at the entity's foot location is liquid, it may
			// actually stand on top of the liquid).
			if (!collidableFluids.isEmpty()) {
				Block blockAbove = entity.getWorld().getBlockAt(
						entityLocation.getBlockX(),
						entityLocation.getBlockY() + 1,
						entityLocation.getBlockZ()
				);
				if (blockAbove.isLiquid()) {
					collidableFluids = Collections.emptySet();
				}
			}

			entityData.distanceToGround = WorldUtils.getCollisionDistanceToGround(
					entityLocation,
					gravityCollisionCheckRange,
					collidableFluids
			);
			sharedLocation.setWorld(null); // Reset
			boolean isInAir = (entityData.distanceToGround >= DISTANCE_TO_GROUND_THRESHOLD);
			boolean falling = isInAir && !EntityUtils.canFly(entity.getType());
			entityData.falling = falling;

			if (isInAir && !falling) {
				// The entity is flying.
				// Required for flying mobs to play their flying animation.
				Compat.getProvider().setOnGround(entity, false);
			} else {
				if (falling) {
					// Tick falling:
					// Prevents SPIGOT-3948 / MC-130725
					Compat.getProvider().setOnGround(entity, false);
					this.tickFalling(entityData);
				}

				if (!entityData.falling) {
					// No longer falling (and also not flying):
					// Prevents SPIGOT-3948 / MC-130725
					Compat.getProvider().setOnGround(entity, true);
				}
			}
		}
	}

	// Gets run every behavior update while falling:
	private void tickFalling(EntityData entityData) {
		assert entityData.falling && entityData.distanceToGround >= DISTANCE_TO_GROUND_THRESHOLD;
		Entity entity = entityData.entity;

		// Determine falling step size:
		double fallingStepSize;
		double remainingDistance = (entityData.distanceToGround - maxFallingDistancePerUpdate);
		if (remainingDistance <= DISTANCE_TO_GROUND_THRESHOLD) {
			// We are nearly there: Let's position the entity exactly on the ground and stop the
			// falling.
			fallingStepSize = entityData.distanceToGround;
			entityData.falling = false;
		} else {
			fallingStepSize = maxFallingDistancePerUpdate;
			// We continue the falling and check for collisions again in the next tick.
		}

		// Teleport the entity to its new location:
		Location sharedLocation = EntityAI.sharedLocation.get();
		Location newLocation = Unsafe.assertNonNull(entity.getLocation(sharedLocation));
		newLocation.add(0.0D, -fallingStepSize, 0.0D);

		plugin.getForcingEntityTeleporter().teleport(entity, newLocation);

		sharedLocation.setWorld(null); // Reset
	}

	// ENTITY AI

	// Gets run every behavior update while in range of players:
	private void processAI(EntityData entityData) {
		entityData.shopObject.tickAI();
	}

	// EVENT HANDLERS

	// By reacting to player joins and teleports we can very quickly activate chunks around players
	// that suddenly appear near shopkeepers.

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	void onPlayerJoin(PlayerJoinEvent event) {
		// Activate chunks around the player after the server has completely handled the join.
		// Note: This also checks if the player is still online (some other plugin might have kicked
		// the player during the event) and otherwise ignores the request.
		Player player = event.getPlayer();
		this.activateNearbyChunksDelayed(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	void onPlayerTeleport(PlayerTeleportEvent event) {
		// The target location can be null in some circumstances (e.g. when a player enters an end
		// gateway, but there is no end world). We ignore the event in this case.
		Location targetLocation = event.getTo();
		if (targetLocation == null) return;

		// Activate chunks around the player after the teleport:
		Player player = event.getPlayer();
		this.activateNearbyChunksDelayed(player);
	}
}
