package com.nisovin.shopkeepers.shopkeeper.registry;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.NonNull;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperAddedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.shopkeeper.ShopType;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperCreateException;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperRegistry;
import com.nisovin.shopkeepers.api.shopkeeper.player.PlayerShopkeeper;
import com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopType;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.ShopkeeperData;
import com.nisovin.shopkeepers.shopkeeper.activation.ShopkeeperChunkActivator;
import com.nisovin.shopkeepers.shopkeeper.player.AbstractPlayerShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.registry.ShopkeeperChunkMap.ChangeListener;
import com.nisovin.shopkeepers.shopkeeper.spawning.ShopkeeperSpawner;
import com.nisovin.shopkeepers.shopkeeper.ticking.ShopkeeperTicker;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObjectType;
import com.nisovin.shopkeepers.shopobjects.block.BlockShopObjectIds;
import com.nisovin.shopkeepers.shopobjects.entity.EntityShopObjectIds;
import com.nisovin.shopkeepers.storage.SKShopkeeperStorage;
import com.nisovin.shopkeepers.util.bukkit.LocationUtils;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.TextUtils;
import com.nisovin.shopkeepers.util.data.serialization.InvalidDataException;
import com.nisovin.shopkeepers.util.java.StringUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

public class SKShopkeeperRegistry implements ShopkeeperRegistry {

	private final SKShopkeepersPlugin plugin;
	private final Object indexLock = new Object();
	private volatile long lifecycleGeneration;
	private volatile boolean stopping;
	private volatile boolean reloading;
	private final Set<CompletableFuture<?>> ownerOperations = ConcurrentHashMap.newKeySet();

	// All shopkeepers:
	private final Map<UUID, AbstractShopkeeper> shopkeepersByUUID = new LinkedHashMap<>();
	private final Collection<? extends AbstractShopkeeper> allShopkeepersView = Collections.unmodifiableCollection(shopkeepersByUUID.values());
	private final Map<Integer, AbstractShopkeeper> shopkeepersById = new HashMap<>();

	// TODO Shopkeepers by name TreeMap to speedup name lookups and prefix matching?
	// TODO TreeMaps for shopkeeper owners by name and uuid to speedup prefix matching?

	// Virtual shopkeepers:
	// Set: Allows for fast removal.
	private final Set<AbstractShopkeeper> virtualShopkeepers = new LinkedHashSet<>();
	private final Collection<? extends AbstractShopkeeper> virtualShopkeepersView = Collections.unmodifiableCollection(virtualShopkeepers);

	private final ShopkeeperChunkMap chunkMap;
	private final ChangeListener chunkMapChangeListener = new ChangeListener() {
		@Override
		public void onShopkeeperAdded(
				AbstractShopkeeper shopkeeper,
				ChunkShopkeepers chunkShopkeepers
		) {
		}

		@Override
		public void onShopkeeperRemoved(
				AbstractShopkeeper shopkeeper,
				ChunkShopkeepers chunkShopkeepers
		) {
		}

		@Override
		public void onWorldAdded(WorldShopkeepers worldShopkeepers) {
		}

		@Override
		public void onWorldRemoved(WorldShopkeepers worldShopkeepers) {
			Unsafe.assertNonNull(shopkeeperSpawner);
			shopkeeperSpawner.onShopkeeperWorldRemoved(worldShopkeepers.getWorldName());
		}

		@Override
		public void onChunkAdded(ChunkShopkeepers chunkShopkeepers) {
			// Also immediately set up the chunk activator's chunk data, but do not yet trigger
			// shopkeeper activation (ticking, spawning, etc.). This ensures that all queries
			// involving active chunks provide a consistent view.
			Unsafe.assertNonNull(chunkActivator);
			chunkActivator.onShopkeeperChunkAdded(chunkShopkeepers.getChunkCoords());
		}

		@Override
		public void onChunkRemoved(ChunkShopkeepers chunkShopkeepers) {
			Unsafe.assertNonNull(chunkActivator);
			chunkActivator.onShopkeeperChunkRemoved(chunkShopkeepers.getChunkCoords());
		}
	};

	// Player shopkeepers:
	private volatile int playerShopCount = 0;
	// Note: Already unmodifiable.
	private final Set<? extends AbstractPlayerShopkeeper> allPlayerShopkeepersView = new AbstractSet<AbstractPlayerShopkeeper>() {
		@Override
		public Iterator<AbstractPlayerShopkeeper> iterator() {
			if (this.isEmpty()) {
				return Collections.emptyIterator();
			}
			return Unsafe.initialized(SKShopkeeperRegistry.this).getAllShopkeepers().stream()
					.filter(shopkeeper -> shopkeeper instanceof PlayerShopkeeper)
					.<AbstractPlayerShopkeeper>map(shopkeeper -> (AbstractPlayerShopkeeper) shopkeeper)
					.iterator();
		}

		@Override
		public int size() {
			return playerShopCount;
		}
	};

	private final ShopObjectRegistry shopObjectRegistry = new ShopObjectRegistry();
	private final ShopkeeperTicker shopkeeperTicker;
	private final ShopkeeperSpawner shopkeeperSpawner;
	private final ShopkeeperChunkActivator chunkActivator;
	private final ActiveChunkQueries activeChunkQueries;

	public SKShopkeeperRegistry(SKShopkeepersPlugin plugin) {
		this.plugin = plugin;
		this.chunkMap = new ShopkeeperChunkMap(indexLock, plugin.getFoliaLib().isFolia(),
				chunkMapChangeListener);
		this.shopkeeperTicker = new ShopkeeperTicker(plugin);
		this.shopkeeperSpawner = new ShopkeeperSpawner(plugin, Unsafe.initialized(this));
		this.chunkActivator = new ShopkeeperChunkActivator(
				plugin,
				Unsafe.initialized(this),
				shopkeeperTicker,
				shopkeeperSpawner
		);
		this.activeChunkQueries = new ActiveChunkQueries(chunkMap, chunkActivator);
	}

	public void onEnable() {
		lifecycleGeneration++;
		stopping = false;
		reloading = false;
		shopObjectRegistry.onEnable();
		chunkActivator.onEnable();
		shopkeeperSpawner.onEnable();
		shopkeeperTicker.onEnable();
	}

	public void onDisable() {
		stopping = true;
		lifecycleGeneration++;
		ownerOperations.forEach(operation -> operation.completeExceptionally(
				new IllegalStateException("Shopkeeper registry stopped.")));
		if (plugin.getFoliaLib().isFolia()) {
			// Regions may already have stopped. Only discard published bookkeeping here.
			this.getAllShopkeepers().forEach(AbstractShopkeeper::discardOnShutdown);
			synchronized (indexLock) {
				shopkeepersByUUID.clear();
				shopkeepersById.clear();
				virtualShopkeepers.clear();
				playerShopCount = 0;
				chunkMap.discardOnShutdown();
			}
			shopObjectRegistry.discardOnShutdown();
		} else {
			this.unloadAllShopkeepers();
		}

		assert this.getAllShopkeepers().isEmpty();

		// Reset all (just in case):
		this.ensureEmpty();

		shopkeeperTicker.onDisable();
		shopkeeperSpawner.onDisable();
		chunkActivator.onDisable();
		shopObjectRegistry.onDisable();
	}

	private void ensureEmpty() {
		synchronized (indexLock) {
			if (!shopkeepersByUUID.isEmpty() || !shopkeepersById.isEmpty()
					|| !virtualShopkeepers.isEmpty() || playerShopCount != 0) {
				Log.warning("Some shopkeepers were not properly unregistered!");
				shopkeepersByUUID.clear();
				shopkeepersById.clear();
				virtualShopkeepers.clear();
				playerShopCount = 0;
			}

			chunkMap.ensureEmpty();
		}
	}

	public ShopkeeperSpawner getShopkeeperSpawner() {
		return shopkeeperSpawner;
	}

	public ShopkeeperChunkActivator getChunkActivator() {
		return chunkActivator;
	}

	// SHOPKEEPER CREATION

	private SKShopkeeperStorage getShopkeeperStorage() {
		return plugin.getShopkeeperStorage();
	}

	@Override
	public AbstractShopkeeper createShopkeeper(
			ShopCreationData creationData
	) throws ShopkeeperCreateException {
		Validate.notNull(creationData, "creationData is null");
		Validate.State.isTrue(!reloading && !stopping, "Shopkeeper registry is stopping or reloading.");
		this.validateOwner(creationData.getSpawnLocation());
		ShopType<?> shopType = creationData.getShopType();
		assert shopType != null;
		Validate.isTrue(shopType instanceof AbstractShopType,
				"shopType is not of type AbstractShopType, but: " + shopType.getClass().getName());
		AbstractShopType<?> abstractShopType = (AbstractShopType<?>) shopType;

		SKShopkeeperStorage shopkeeperStorage = this.getShopkeeperStorage();
		int id = shopkeeperStorage.getNextShopkeeperId();

		try {
			AbstractShopkeeper shopkeeper = abstractShopType.createShopkeeper(id, creationData);
			assert shopkeeper != null;

			// Add the shopkeeper to the registry and spawn it:
			this.addShopkeeper(shopkeeper, ShopkeeperAddedEvent.Cause.CREATED);
			return shopkeeper;
		} catch (RuntimeException e) {
			throw new ShopkeeperCreateException(e.getMessage(), e);
		} finally {
			shopkeeperStorage.releaseShopkeeperId(id);
		}
	}

	/**
	 * Recreates a shopkeeper by loading its previously saved data from the given
	 * {@link ShopkeeperData}.
	 * 
	 * @param shopkeeperData
	 *            the shopkeeper data
	 * @return the loaded shopkeeper, not <code>null</code>
	 * @throws InvalidDataException
	 *             if the shopkeeper data could not be loaded
	 */
	// Internal method: This is only supposed to be called by the built-in storage currently. If the
	// data comes from any other source, the storage would need to be made aware of the shopkeeper
	// (e.g. by marking the shopkeeper as dirty). Otherwise, certain operations (such as checking if
	// a certain shopkeeper id is already in use) would no longer work as expected.
	public AbstractShopkeeper loadShopkeeper(
			ShopkeeperData shopkeeperData
	) throws InvalidDataException {
		Validate.notNull(shopkeeperData, "shopkeeperData is null");

		AbstractShopType<?> shopType = shopkeeperData.get(AbstractShopkeeper.SHOP_TYPE);
		assert shopType != null;

		AbstractShopkeeper shopkeeper = shopType.loadShopkeeper(shopkeeperData);
		assert shopkeeper != null;

		try {
			this.addShopkeeper(shopkeeper, ShopkeeperAddedEvent.Cause.LOADED);
		} catch (RuntimeException e) {
			throw new InvalidDataException(e.getMessage(), e);
		}

		return shopkeeper;
	}

	private void validateUnusedShopkeeperIds(Shopkeeper shopkeeper) {
		Validate.isTrue(this.getShopkeeperById(shopkeeper.getId()) == null,
				() -> "There already exists a shopkeeper with the same id: " + shopkeeper.getId());
		Validate.isTrue(this.getShopkeeperByUniqueId(shopkeeper.getUniqueId()) == null,
				() -> "There already exists a shopkeeper with the same unique id: "
						+ shopkeeper.getUniqueId());
	}

	// ADD / REMOVE SHOPKEEPER

	private void addShopkeeper(AbstractShopkeeper shopkeeper, ShopkeeperAddedEvent.Cause cause) {
		assert shopkeeper != null && !shopkeeper.isValid();
		List<Runnable> notifications = new ArrayList<>();
		synchronized (indexLock) {
			Validate.State.isTrue(!reloading && !stopping,
					"Shopkeeper registry is stopping or reloading.");
			this.validateUnusedShopkeeperIds(shopkeeper);

			// Store by unique id and session id:
			UUID shopkeeperUniqueId = shopkeeper.getUniqueId();
			int shopkeeperId = shopkeeper.getId();
			shopkeepersByUUID.put(shopkeeperUniqueId, shopkeeper);
			shopkeepersById.put(shopkeeperId, shopkeeper);

			// Add shopkeeper to chunk-based storage:
			if (shopkeeper.isVirtual()) {
				virtualShopkeepers.add(shopkeeper);
			} else {
				chunkMap.addShopkeeper(shopkeeper, notifications);
			}

			// Update player shop count:
			if (shopkeeper instanceof PlayerShopkeeper) {
				playerShopCount++;
			}
		}

		this.getShopkeeperStorage().onShopkeeperIdUsed(shopkeeper.getId());
		notifications.forEach(Runnable::run);

		// Log a warning if either the shop type or the shop object type is disabled. The shopkeeper
		// is still added (so containers are still protected), but it might not get spawned, and
		// there is no guarantee that the shop still works as expected. Admins are advised to either
		// delete the shopkeeper, or change its object type to something else.
		AbstractShopType<?> shopType = shopkeeper.getType();
		if (!shopType.isEnabled()) {
			Log.warning(shopkeeper.getLogPrefix() + "Shop type '" + shopType.getIdentifier()
					+ "' is disabled! Consider deleting this shopkeeper.");
		}
		AbstractShopObjectType<?> shopObjectType = shopkeeper.getShopObject().getType();
		if (!shopObjectType.isEnabled()) {
			Log.warning(shopkeeper.getLogPrefix() + "Object type '" + shopObjectType.getIdentifier()
					+ "' is disabled! Consider changing the object type.");
		}

		if (plugin.getFoliaLib().isFolia() && cause == ShopkeeperAddedEvent.Cause.LOADED) {
			shopkeeper.publishLoaded();
			this.runOnOwner(shopkeeper, () -> {
				shopkeeper.completeLoaded();
				this.finishAddition(shopkeeper, cause);
				return true;
			});
			return;
		}

		// Inform shopkeeper:
		// If the shop object handles spawning itself, and the shop object is already spawned, this
		// might register the already spawned shop object.
		shopkeeper.informAdded(cause);
		this.finishAddition(shopkeeper, cause);
	}

	private void finishAddition(AbstractShopkeeper shopkeeper, ShopkeeperAddedEvent.Cause cause) {
		// Call event:
		Bukkit.getPluginManager().callEvent(new ShopkeeperAddedEvent(shopkeeper, cause));
		if (!shopkeeper.isValid()) {
			// The shopkeeper has already been removed again.
			return;
		}

		// If necessary, activate the shopkeeper (start ticking, spawn, etc.):
		if (!shopkeeper.isVirtual()) chunkActivator.checkShopkeeperActivation(shopkeeper);
	}

	private void removeShopkeeper(
			AbstractShopkeeper shopkeeper,
			ShopkeeperRemoveEvent.Cause cause
	) {
		assert shopkeeper != null && shopkeeper.isValid() && cause != null;

		// Call event:
		Bukkit.getPluginManager().callEvent(new ShopkeeperRemoveEvent(shopkeeper, cause));

		if (!shopkeeper.isValid()) {
			Log.warning(shopkeeper.getLogPrefix()
					+ "Aborting removal, because already removed during ShopkeeperRemoveEvent!");
			return;
		}

		// Delayed closing of all active UI sessions:
		// TODO Views might want/need to handle the UI closing immediately here (e.g. to save UI
		// state and apply shopkeeper changes).
		shopkeeper.abortUISessionsDelayed();

		// If necessary, deactivate the shopkeeper (stop ticking, despawn, etc.):
		if (!shopkeeper.isVirtual()) chunkActivator.deactivateShopkeeper(shopkeeper);

		// Inform shopkeeper:
		// If the shop object handles spawning itself, this is expected to unregister any currently
		// spawned shop object.
		shopkeeper.informRemoval(cause);

		// Verify that the shop object is no longer registered:
		if (shopObjectRegistry.isRegistered(shopkeeper)) {
			Log.warning(shopkeeper.getLogPrefix() + "Shop object of type '"
					+ shopkeeper.getShopObject().getType().getIdentifier()
					+ "' did not unregister itself during shopkeeper removal!");
		}

		// Remove shopkeeper by unique id and session id:
		List<Runnable> notifications = new ArrayList<>();
		synchronized (indexLock) {
			UUID shopkeeperUniqueId = shopkeeper.getUniqueId();
			shopkeepersByUUID.remove(shopkeeperUniqueId, shopkeeper);
			shopkeepersById.remove(shopkeeper.getId(), shopkeeper);

			// Remove shopkeeper from chunk-based storage:
			if (shopkeeper.isVirtual()) {
				virtualShopkeepers.remove(shopkeeper);
			} else {
				chunkMap.removeShopkeeper(shopkeeper, false, notifications);
			}

			// Update player shop count:
			if (shopkeeper instanceof PlayerShopkeeper) {
				playerShopCount--;
			}
		}

		notifications.forEach(Runnable::run);

		if (cause == ShopkeeperRemoveEvent.Cause.DELETE) {
			// Remove shopkeeper from storage:
			this.getShopkeeperStorage().deleteShopkeeper(shopkeeper);
		}
	}

	// This is not expected to be called for invalid or virtual shopkeepers.
	public void onShopkeeperMoved(AbstractShopkeeper shopkeeper) {
		Validate.notNull(shopkeeper, "shopkeeper is null");
		Validate.isTrue(shopkeeper.isValid(), "shopkeeper is not valid");
		Validate.isTrue(!shopkeeper.isVirtual(), "shopkeeper is virtual");

		ChunkCoords oldChunk = Unsafe.assertNonNull(shopkeeper.getLastChunkCoords());

		// Update the shopkeeper's location inside the chunk map:
		if (!chunkMap.moveShopkeeper(shopkeeper)) {
			// The shopkeeper's chunk did not change.
			return;
		}

		// Inform chunk activator:
		chunkActivator.onShopkeeperMoved(shopkeeper, oldChunk);
	}

	private void unloadShopkeeper(AbstractShopkeeper shopkeeper) {
		assert shopkeeper != null && shopkeeper.isValid();
		this.removeShopkeeper(shopkeeper, ShopkeeperRemoveEvent.Cause.UNLOAD);
	}

	public void unloadAllShopkeepers() {
		List<AbstractShopkeeper> shopkeepers = new ArrayList<>(this.getAllShopkeepers());
		if (plugin.getFoliaLib().isFolia() && plugin.isEnabled()) {
			shopkeepers.forEach(this::validateOwner);
		}

		// Note: One optimization idea is to clear the shopkeeper spawn queue here immediately,
		// instead of removing shopkeepers one by one during shopkeeper removals. However, we don't
		// expect this to actually provide much benefit, as the spawn queue is usually not very full
		// anyway: The spawn queue is intentionally not used in situations in which it could fill up
		// a lot (reloads, world save respawns, etc.) in order to not create a backlog that would
		// result in players waiting very long for shopkeepers to respawn. The same applies when
		// deleting all shopkeepers.
		shopkeepers.forEach(this::unloadShopkeeper);
	}

	public void deleteShopkeeper(AbstractShopkeeper shopkeeper) {
		Validate.notNull(shopkeeper, "shopkeeper is null");
		Validate.isTrue(shopkeeper.isValid(), "shopkeeper is invalid");
		this.validateOwner(shopkeeper);
		this.removeShopkeeper(shopkeeper, ShopkeeperRemoveEvent.Cause.DELETE);
	}

	public void deleteAllShopkeepers() {
		List<AbstractShopkeeper> shopkeepers = new ArrayList<>(this.getAllShopkeepers());
		if (plugin.getFoliaLib().isFolia()) {
			shopkeepers.forEach(this::validateOwner);
		}

		shopkeepers.forEach(this::deleteShopkeeper);
	}

	public CompletableFuture<@Nullable Void> unloadAllShopkeepersAsync() {
		reloading = true;
		List<AbstractShopkeeper> shops = new ArrayList<>(this.getAllShopkeepers());
		List<CompletableFuture<Boolean>> operations = new ArrayList<>();
		shops.forEach(shopkeeper -> operations.add(
				this.runOnOwnerDuringReload(shopkeeper, () -> {
					if (!shopkeeper.isValid()) return false;
					if (!shopkeeper.isVirtual() && shopkeeper.getLocation() == null
							&& !shopkeeper.isDirty()) return true;
					shopkeeper.save();
					Validate.State.isTrue(!plugin.getFoliaLib().isFolia() || !shopkeeper.isDirty(),
							"Latest shopkeeper snapshot could not be captured.");
					return true;
				})));
		return CompletableFuture.allOf(operations.toArray(new @NonNull CompletableFuture<?>[0]))
				.thenCompose(value -> this.flushCapturedSnapshots())
				.thenCompose(value -> {
					List<CompletableFuture<Boolean>> removals = new ArrayList<>();
					shops.forEach(shopkeeper -> removals.add(
							this.runOnOwnerDuringReload(shopkeeper, () -> {
								if (!shopkeeper.isValid()) return false;
								this.unloadShopkeeper(shopkeeper);
								return true;
							})));
					return CompletableFuture.allOf(removals.toArray(new @NonNull CompletableFuture<?>[0]));
				}).whenComplete((value, error) -> {
					if (error != null) reloading = false;
				});
	}

	private CompletableFuture<@Nullable Void> flushCapturedSnapshots() {
		if (!plugin.getFoliaLib().isFolia()) return CompletableFuture.completedFuture(null);
		CompletableFuture<@Nullable Void> result = this.trackOwnerOperation(new CompletableFuture<>());
		Runnable flush = () -> {
			if (result.isDone()) return;
			try {
				SKShopkeeperStorage storage = this.getShopkeeperStorage();
				storage.saveIfDirtyAndAwaitCompletion();
				Validate.State.isTrue(!storage.isDirty(),
						"Shopkeeper snapshots could not be written. Reload aborted.");
				result.complete(null);
			} catch (Throwable error) {
				result.completeExceptionally(error);
			}
		};
		if (SchedulerUtils.isGlobalThread()) flush.run();
		else if (SchedulerUtils.runTaskGloballyOrOmit(flush) == null) {
			result.completeExceptionally(new IllegalStateException("Reload coordinator unavailable."));
		}

		return result;
	}

	public CompletableFuture<@Nullable Void> awaitOwnerOperations() {
		@NonNull CompletableFuture<?>[] operations = ownerOperations.stream()
				.filter(operation -> !operation.isDone())
				.toArray(size -> new @NonNull CompletableFuture<?>[size]);
		if (operations.length == 0) return CompletableFuture.completedFuture(null);
		return CompletableFuture.allOf(operations).thenCompose(value -> this.awaitOwnerOperations());
	}

	public void runOnSender(CommandSender sender, Runnable action) {
		if (!plugin.getFoliaLib().isFolia()) {
			action.run();
			return;
		}

		var scheduler = plugin.getFoliaLib().getScheduler();
		if (!(sender instanceof Player) && scheduler.isGlobalTickThread()) {
			action.run();
			return;
		}

		if (!plugin.isEnabled()) return;
		try {
			if (sender instanceof Player player) {
				scheduler.runAtEntityLater(player, () -> {
					if (player.isOnline()) action.run();
				}, () -> {}, 1L);
			} else {
				scheduler.runLater(action, 1L);
			}
		} catch (RuntimeException error) {
			if (plugin.isEnabled()) Log.warning("Could not dispatch shopkeeper feedback.", error);
		}
	}

	public <T> CompletableFuture<T> trackOwnerOperation(CompletableFuture<T> result) {
		ownerOperations.add(result);
		result.whenComplete((value, error) -> ownerOperations.remove(result));
		if (stopping || !plugin.isEnabled()) {
			result.completeExceptionally(new IllegalStateException("Shopkeeper registry stopped."));
		}

		return result;
	}

	public CompletableFuture<@Nullable Void> deleteAllShopkeepersAsync() {
		List<CompletableFuture<Boolean>> operations = new ArrayList<>();
		this.getAllShopkeepers().forEach(shopkeeper -> operations.add(
				this.runOnOwner(shopkeeper, () -> {
					if (!shopkeeper.isValid()) return false;
					this.deleteShopkeeper(shopkeeper);
					return true;
				})));
		return CompletableFuture.allOf(operations.toArray(new @NonNull CompletableFuture<?>[0]));
	}

	/**
	 * Runs an internal operation on the current shop owner without blocking another region.
	 */
	public <T> CompletableFuture<T> runOnOwner(AbstractShopkeeper shopkeeper, Supplier<T> action) {
		if (reloading) {
			return CompletableFuture.failedFuture(new IllegalStateException("Shopkeeper registry is reloading."));
		}

		return this.runOnOwnerDuringReload(shopkeeper, action);
	}

	private <T> CompletableFuture<T> runOnOwnerDuringReload(
			AbstractShopkeeper shopkeeper,
			Supplier<T> action
	) {
		CompletableFuture<T> result = new CompletableFuture<>();
		long generation = lifecycleGeneration;
		this.trackOwnerOperation(result);
		this.dispatchOwner(shopkeeper, action, result, generation);
		return result;
	}

	private <T> void dispatchOwner(
			AbstractShopkeeper shopkeeper,
			Supplier<T> action,
			CompletableFuture<T> result,
			long generation
	) {
		if (result.isDone()) return;
		if (stopping || generation != lifecycleGeneration || !plugin.isEnabled()
				|| this.getShopkeeperByUniqueId(shopkeeper.getUniqueId()) != shopkeeper) {
			result.completeExceptionally(new IllegalStateException("Shopkeeper is no longer loaded."));
			return;
		}

		Runnable work = () -> {
			if (result.isDone()) return;
			if (!this.isOwnerThread(shopkeeper)) {
				this.dispatchOwner(shopkeeper, action, result, generation);
				return;
			}

			if (stopping || generation != lifecycleGeneration
					|| this.getShopkeeperByUniqueId(shopkeeper.getUniqueId()) != shopkeeper) {
				result.completeExceptionally(new IllegalStateException("Shopkeeper operation expired."));
				return;
			}

			try {
				result.complete(action.get());
			} catch (Throwable error) {
				result.completeExceptionally(error);
			}
		};
		if (this.isOwnerThread(shopkeeper)) {
			work.run();
			return;
		}

		Entity entity = this.getOwnerEntity(shopkeeper);
		try {
			if (entity != null) {
				if (plugin.getFoliaLib().getScheduler().runAtEntityLater(entity, work,
						() -> result.completeExceptionally(
								new IllegalStateException("Shopkeeper entity retired.")), 1L) != null) {
					return;
				}
			} else if (shopkeeper.isVirtual() || shopkeeper.getLocation() == null) {
				if (SchedulerUtils.runTaskGloballyOrOmit(work) != null) return;
			} else {
				Location location = Unsafe.assertNonNull(shopkeeper.getLocation());
				if (SchedulerUtils.runTaskOrOmit(location, work) != null) return;
			}
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
			return;
		}

		result.completeExceptionally(new IllegalStateException("Shopkeeper owner unavailable."));
	}

	private @Nullable Entity getOwnerEntity(AbstractShopkeeper shopkeeper) {
		return (shopkeeper.getShopObject() instanceof EntityShopObject)
				? ((EntityShopObject) shopkeeper.getShopObject()).getEntity() : null;
	}

	public boolean isOwnerThread(AbstractShopkeeper shopkeeper) {
		if (!plugin.getFoliaLib().isFolia()) return Bukkit.isPrimaryThread();
		Entity entity = this.getOwnerEntity(shopkeeper);
		if (entity != null) {
			return plugin.getFoliaLib().getScheduler().isOwnedByCurrentRegion(entity);
		}

		Location location = shopkeeper.getLocation();
		return location == null ? SchedulerUtils.isGlobalThread()
				: SchedulerUtils.isMainThread(location);
	}

	private void validateOwner(@Nullable Location location) {
		if (!plugin.getFoliaLib().isFolia()) return;
		Validate.State.isTrue(location != null ? SchedulerUtils.isMainThread(location)
				: SchedulerUtils.isGlobalThread(), "Shopkeeper mutation requires its owning thread.");
	}

	private void validateOwner(AbstractShopkeeper shopkeeper) {
		if (!plugin.getFoliaLib().isFolia()) return;
		Entity entity = (shopkeeper.getShopObject() instanceof EntityShopObject)
				? ((EntityShopObject) shopkeeper.getShopObject()).getEntity() : null;
		if (entity == null) {
			this.validateOwner(shopkeeper.getLocation());
		} else {
			Validate.State.isTrue(
					plugin.getFoliaLib().getScheduler().isOwnedByCurrentRegion(entity),
					"Shopkeeper mutation requires its owning thread.");
		}
	}

	///// QUERYING

	@Override
	public Collection<? extends AbstractShopkeeper> getAllShopkeepers() {
		synchronized (indexLock) {
			return plugin.getFoliaLib().isFolia()
					? Collections.unmodifiableList(new ArrayList<>(shopkeepersByUUID.values()))
					: allShopkeepersView;
		}
	}

	@Override
	public Collection<? extends AbstractShopkeeper> getVirtualShopkeepers() {
		synchronized (indexLock) {
			return plugin.getFoliaLib().isFolia()
					? Collections.unmodifiableSet(new LinkedHashSet<>(virtualShopkeepers))
					: virtualShopkeepersView;
		}
	}

	@Override
	public @Nullable AbstractShopkeeper getShopkeeperByUniqueId(UUID shopkeeperUniqueId) {
		synchronized (indexLock) {
			return shopkeepersByUUID.get(shopkeeperUniqueId);
		}
	}

	@Override
	public @Nullable AbstractShopkeeper getShopkeeperById(int shopkeeperId) {
		synchronized (indexLock) {
			return shopkeepersById.get(shopkeeperId);
		}
	}

	// PLAYER SHOPS

	@Override
	public Collection<? extends AbstractPlayerShopkeeper> getAllPlayerShopkeepers() {
		if (plugin.getFoliaLib().isFolia()) {
			List<AbstractPlayerShopkeeper> result = new ArrayList<>();
			this.getAllShopkeepers().forEach(shopkeeper -> {
				if (shopkeeper instanceof AbstractPlayerShopkeeper) {
					result.add((AbstractPlayerShopkeeper) shopkeeper);
				}
			});
			return Collections.unmodifiableList(result);
		}

		return allPlayerShopkeepersView;
	}

	@Override
	public Collection<? extends AbstractPlayerShopkeeper> getPlayerShopkeepersByOwner(
			UUID ownerUUID
	) {
		Validate.notNull(ownerUUID, "ownerUUID is null");
		if (plugin.getFoliaLib().isFolia()) {
			List<AbstractPlayerShopkeeper> result = new ArrayList<>();
			this.getAllPlayerShopkeepers().stream().filter(shopkeeper -> shopkeeper.isOwner(ownerUUID))
					.forEach(result::add);
			return Collections.unmodifiableList(result);
		}

		// TODO Improve? Maybe keep an index of player shops? Or even index by owner?
		// Note: Already unmodifiable.
		return new AbstractSet<AbstractPlayerShopkeeper>() {
			private Stream<? extends AbstractPlayerShopkeeper> createStream() {
				return getAllPlayerShopkeepers().stream()
						.filter(shopkeeper -> shopkeeper.isOwner(ownerUUID));
			}

			@Override
			public Iterator<AbstractPlayerShopkeeper> iterator() {
				if (allPlayerShopkeepersView.isEmpty()) {
					// There are no player shops at all:
					return Collections.emptyIterator();
				}
				return Unsafe.cast(this.createStream().iterator());
			}

			@Override
			public int size() {
				if (allPlayerShopkeepersView.isEmpty()) {
					// There are no player shops at all:
					return 0;
				}
				return this.createStream().mapToInt(shopkeeper -> 1).sum();
			}
		};
	}

	// BY NAME

	@Override
	public Stream<? extends AbstractShopkeeper> getShopkeepersByName(String shopName) {
		String normalizedShopName = StringUtils.normalize(TextUtils.stripColor(shopName));
		if (StringUtils.isEmpty(normalizedShopName)) return Stream.empty();

		// TODO Improve via (Tree)Map?
		return this.getAllShopkeepers().stream().filter(shopkeeper -> {
			String shopkeeperName = shopkeeper.getName(); // Can be empty
			if (shopkeeperName.isEmpty()) return false; // Has no name, filter

			shopkeeperName = TextUtils.stripColor(shopkeeperName);
			shopkeeperName = StringUtils.normalize(shopkeeperName);
			// Include shopkeeper if name matches:
			return (shopkeeperName.equals(normalizedShopName));
		});
	}

	@Override
	public Stream<? extends AbstractShopkeeper> getShopkeepersByNamePrefix(
			String shopNamePrefix
	) {
		String normalizedShopNamePrefix = StringUtils.normalize(TextUtils.stripColor(shopNamePrefix));
		if (StringUtils.isEmpty(normalizedShopNamePrefix)) return Stream.empty();

		// TODO Improve via TreeMap?
		return this.getAllShopkeepers().stream().filter(shopkeeper -> {
			String shopkeeperName = shopkeeper.getName(); // Can be empty
			if (shopkeeperName.isEmpty()) return false; // Has no name, filter

			shopkeeperName = TextUtils.stripColor(shopkeeperName);
			shopkeeperName = StringUtils.normalize(shopkeeperName);
			// Include shopkeeper if name matches:
			return shopkeeperName.startsWith(normalizedShopNamePrefix);
		});
	}

	// BY WORLD

	@Override
	public Collection<? extends String> getWorldsWithShopkeepers() {
		return chunkMap.getWorldsWithShopkeepers();
	}

	@Override
	public Collection<? extends AbstractShopkeeper> getShopkeepersInWorld(String worldName) {
		WorldShopkeepers worldShopkeepers = chunkMap.getWorldShopkeepers(worldName);
		if (worldShopkeepers == null) return Collections.emptySet();
		return worldShopkeepers.getShopkeepers();
	}

	@Override
	public Map<? extends ChunkCoords, ? extends Collection<? extends AbstractShopkeeper>> getShopkeepersByChunks(
			String worldName
	) {
		WorldShopkeepers worldShopkeepers = chunkMap.getWorldShopkeepers(worldName);
		if (worldShopkeepers == null) {
			return Collections.emptyMap();
		}
		return worldShopkeepers.getShopkeepersByChunk();
	}

	// ACTIVE CHUNKS

	@Override
	public Collection<? extends ChunkCoords> getActiveChunks(String worldName) {
		return activeChunkQueries.getActiveChunks(worldName);
	}

	@Override
	public boolean isChunkActive(ChunkCoords chunkCoords) {
		return chunkActivator.isChunkActive(chunkCoords);
	}

	// Note: This are the shopkeepers in active chunks. The shopkeepers might not necessarily be
	// spawned yet.
	@Override
	public Collection<? extends AbstractShopkeeper> getActiveShopkeepers() {
		return activeChunkQueries.getShopkeepersInActiveChunks();
	}

	@Override
	public Collection<? extends AbstractShopkeeper> getActiveShopkeepers(String worldName) {
		return activeChunkQueries.getShopkeepersInActiveChunks(worldName);
	}

	// BY CHUNK

	@Override
	public Collection<? extends AbstractShopkeeper> getShopkeepersInChunk(
			ChunkCoords chunkCoords
	) {
		Validate.notNull(chunkCoords, "chunkCoords is null");
		ChunkShopkeepers chunkShopkeepers = chunkMap.getChunkShopkeepers(chunkCoords);
		if (chunkShopkeepers == null) return Collections.emptySet();
		return chunkShopkeepers.getShopkeepers();
	}

	public Collection<? extends AbstractShopkeeper> getShopkeepersInChunkSnapshot(
			ChunkCoords chunkCoords
	) {
		Validate.notNull(chunkCoords, "chunkCoords is null");
		ChunkShopkeepers chunkShopkeepers = chunkMap.getChunkShopkeepers(chunkCoords);
		if (chunkShopkeepers == null) return Collections.emptySet();
		return chunkShopkeepers.getShopkeepersSnapshot();
	}

	// BY LOCATION

	@Override
	public Collection<? extends AbstractShopkeeper> getShopkeepersAtLocation(Location location) {
		World world = LocationUtils.getWorld(location);
		String worldName = world.getName();
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();

		List<AbstractShopkeeper> shopkeepers = new ArrayList<>();
		ChunkCoords chunkCoords = ChunkCoords.fromBlock(worldName, x, z);
		this.getShopkeepersInChunk(chunkCoords).forEach(shopkeeper -> {
			assert worldName.equals(shopkeeper.getWorldName());
			if (shopkeeper.getX() == x && shopkeeper.getY() == y && shopkeeper.getZ() == z) {
				shopkeepers.add(shopkeeper);
			}
		});
		return shopkeepers;
	}

	// BY SHOP OBJECT

	public ShopObjectRegistry getShopObjectRegistry() {
		return shopObjectRegistry;
	}

	@Override
	public @Nullable AbstractShopkeeper getShopkeeperByEntity(Entity entity) {
		Validate.notNull(entity, "entity is null");
		Object objectId = EntityShopObjectIds.getObjectId(entity);
		return shopObjectRegistry.getShopkeeperByObjectId(objectId);
	}

	@Override
	public boolean isShopkeeper(Entity entity) {
		return (this.getShopkeeperByEntity(entity) != null);
	}

	@Override
	public @Nullable AbstractShopkeeper getShopkeeperByBlock(Block block) {
		Validate.notNull(block, "block is null");
		return getShopkeeperByBlock(
				block.getWorld().getName(),
				block.getX(),
				block.getY(),
				block.getZ()
		);
	}

	@Override
	public @Nullable AbstractShopkeeper getShopkeeperByBlock(String worldName, int x, int y, int z) {
		Object objectId = BlockShopObjectIds.getSharedObjectId(worldName, x, y, z);
		return shopObjectRegistry.getShopkeeperByObjectId(objectId);
	}

	@Override
	public boolean isShopkeeper(Block block) {
		return (this.getShopkeeperByBlock(block) != null);
	}
}
