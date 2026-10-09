package com.nisovin.shopkeepers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.PluginManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopType;
import com.nisovin.shopkeepers.shopkeeper.ShopkeeperComponentHolder;
import com.nisovin.shopkeepers.shopkeeper.registry.SKShopkeeperRegistry;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObject;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObjectType;
import com.nisovin.shopkeepers.shopobjects.entity.base.BaseEntityShopObject;
import com.nisovin.shopkeepers.shopobjects.entity.base.BaseEntityShopObjectType;
import com.nisovin.shopkeepers.shopobjects.entity.base.BaseEntityShopObjectCreationContext;
import com.nisovin.shopkeepers.shopobjects.entity.base.BaseEntityShops;
import com.nisovin.shopkeepers.shopobjects.entity.base.EntityAI;
import com.nisovin.shopkeepers.storage.SKShopkeeperStorage;
import com.nisovin.shopkeepers.ui.editor.ActionButton;
import com.nisovin.shopkeepers.ui.editor.Button;
import com.nisovin.shopkeepers.ui.editor.EditorLayout;
import com.nisovin.shopkeepers.ui.editor.ShopkeeperEditorView;
import com.nisovin.shopkeepers.util.bukkit.BlockLocation;
import com.nisovin.shopkeepers.util.logging.Log;
import com.nisovin.shopkeepers.world.ForcingEntityTeleporter;
import com.nisovin.shopkeepers.api.events.ShopkeeperAddedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

public class LifecycleCorrectionsTests {

	private final FoliaCorrectionsTests environment = new FoliaCorrectionsTests();
	private final Queue<Runnable> pending = new ArrayDeque<>();
	private final AtomicReference<Object> owner = new AtomicReference<>();
	private SKShopkeepersPlugin plugin;
	private PlatformScheduler scheduler;
	private World world;
	private SKShopkeeperRegistry registry;
	private SKShopkeeperStorage storage;
	private Object previousLogger;
	private Object previousUISessions;

	@Before
	public void setup() throws Exception {
		environment.setup();
		Field logger = Log.class.getDeclaredField("logger");
		logger.setAccessible(true);
		previousLogger = logger.get(null);
		Log.setLogger(Logger.getLogger("LifecycleCorrectionsTests"));
		plugin = (SKShopkeepersPlugin) get(environment, "plugin");
		scheduler = (PlatformScheduler) get(environment, "scheduler");
		world = (World) get(environment, "world");
		storage = Mockito.mock(SKShopkeeperStorage.class);
		Mockito.when(plugin.getShopkeeperStorage()).thenReturn(storage);
		var uiTypes = Mockito.mock(com.nisovin.shopkeepers.ui.SKDefaultUITypes.class);
		Mockito.when(uiTypes.getTradingUIType())
				.thenReturn(Mockito.mock(com.nisovin.shopkeepers.ui.trading.TradingUIType.class));
		Mockito.when(uiTypes.getEditorUIType())
				.thenReturn(Mockito.mock(com.nisovin.shopkeepers.ui.editor.EditorUIType.class));
		Mockito.when(plugin.getDefaultUITypes()).thenReturn(uiTypes);
		Server server = (Server) get(environment, "server");
		Mockito.when(server.getPluginManager()).thenReturn(Mockito.mock(PluginManager.class));
		Mockito.when(server.getWorld("world")).thenReturn(world);
		Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenAnswer(call -> Integer.valueOf(call.getArgument(0, Location.class).getBlockX() >> 4)
						.equals(owner.get()));
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Entity.class)))
				.thenAnswer(call -> call.getArgument(0) == owner.get());
		Mockito.when(scheduler.isGlobalTickThread()).thenAnswer(call -> "global".equals(owner.get()));
		Mockito.when(scheduler.runAtLocationLater(Mockito.any(Location.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
					this.enqueue(call.getArgument(0, Location.class).getBlockX() >> 4,
							call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
		Mockito.when(scheduler.runAtEntityLater(Mockito.any(Entity.class),
				Mockito.any(Runnable.class), Mockito.any(Runnable.class), Mockito.anyLong()))
				.thenAnswer(call -> {
					this.enqueue(call.getArgument(0), call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
		Mockito.when(scheduler.runLater(Mockito.any(Runnable.class), Mockito.anyLong()))
				.thenAnswer(call -> {
					this.enqueue("global", call.getArgument(0));
					return Mockito.mock(WrappedTask.class);
				});
		registry = new SKShopkeeperRegistry(plugin);
		Mockito.when(plugin.getShopkeeperRegistry()).thenReturn(registry);
		Field sessions = com.nisovin.shopkeepers.ui.lib.UISessionManager.class
				.getDeclaredField("instance");
		sessions.setAccessible(true);
		previousUISessions = sessions.get(null);
		sessions.set(null, null);
		com.nisovin.shopkeepers.ui.lib.UISessionManager.initialize(plugin,
				com.nisovin.shopkeepers.ui.lib.UISessionManager.SessionHandler.DEFAULT);
	}

	@After
	public void teardown() throws Exception {
		environment.teardown();
		Log.setLogger((Logger) previousLogger);
		Field sessions = com.nisovin.shopkeepers.ui.lib.UISessionManager.class
				.getDeclaredField("instance");
		sessions.setAccessible(true);
		sessions.set(null, previousUISessions);
	}

	@Test
	public void stoppedRegionsOnlyDiscardRegistryBookkeeping() throws Exception {
		Mockito.when(plugin.isEnabled()).thenReturn(false);
		Server server = (Server) get(environment, "server");
		Mockito.when(server.getPluginManager()).thenReturn(Mockito.mock(PluginManager.class));
		SKShopkeeperRegistry registry = new SKShopkeeperRegistry(plugin);
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class);
		Mockito.when(shop.isValid()).thenReturn(true);
		ChunkCoords chunk = new ChunkCoords("world", 0, 0);
		Mockito.when(shop.getLastChunkCoords()).thenReturn(chunk);
		Mockito.when(shop.isActive()).thenReturn(true);
		Mockito.when(shop.getUniqueId()).thenReturn(UUID.randomUUID());
		Mockito.doAnswer(call -> {
			Assert.fail("Stopped regions must not run shopkeeper/entity removal.");
			return null;
		}).when(shop).informRemoval(Mockito.any());
		Mockito.doAnswer(call -> {
			Assert.fail("Stopped regions must not change live activation state.");
			return null;
		}).when(shop).setActive(Mockito.anyBoolean());
		shops(registry).put(shop.getUniqueId(), shop);
		registry.getChunkActivator().onShopkeeperChunkAdded(chunk);

		registry.onDisable();

		Assert.assertTrue(registry.getAllShopkeepers().isEmpty());
		Mockito.verify(shop, Mockito.never()).informRemoval(Mockito.any());
		Mockito.verify(shop, Mockito.never()).abortUISessionsDelayed();
	}

	@Test
	public void startupPublishesIndexesButActivatesOnlyOnOwner() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, false);
		AtomicInteger loaded = new AtomicInteger();
		Mockito.doAnswer(call -> {
			Assert.assertEquals(0, owner.get());
			loaded.incrementAndGet();
			return null;
		}).when(shop).completeLoaded();
		this.add(shop, ShopkeeperAddedEvent.Cause.LOADED);
		Assert.assertSame(shop, registry.getShopkeeperById(1));
		Mockito.verify(shop, Mockito.never()).setActive(Mockito.anyBoolean());
		Assert.assertEquals(0, loaded.get());
		this.drain();
		Assert.assertEquals(1, loaded.get());
		Assert.assertTrue(shop.isActive());
		Mockito.verify(shop).informStartTicking();
	}

	@Test
	public void stoppedStartupCallbacksCannotActivateAfterRestart() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, false);
		this.add(shop, ShopkeeperAddedEvent.Cause.LOADED);
		registry.onDisable();
		registry.onEnable();
		this.drain();
		Mockito.verify(shop, Mockito.never()).completeLoaded();
		Mockito.verify(shop, Mockito.never()).setActive(Mockito.anyBoolean());
	}

	@Test
	public void dispatchRechecksChangedOwnerBeforeMutating() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, true);
		this.register(shop);
		AtomicInteger edits = new AtomicInteger();
		CompletableFuture<Integer> result = registry.runOnOwner(shop, () -> {
			Assert.assertEquals(200, owner.get());
			return edits.incrementAndGet();
		});
		Mockito.when(shop.getLocation()).thenReturn(new Location(world, 3200, 64, 0));
		pending.remove().run();
		Assert.assertEquals(0, edits.get());
		this.drain();
		Assert.assertEquals(Integer.valueOf(1), result.getNow(0));
	}

	@Test
	public void foreignOwnerBulkDeletionWaitsForBothRegions() throws Exception {
		AbstractShopkeeper first = this.shop(0, 1, true);
		AbstractShopkeeper second = this.shop(200, 2, true);
		this.register(first);
		this.register(second);
		CompletableFuture<Void> result = registry.deleteAllShopkeepersAsync();
		Assert.assertFalse(result.isDone());
		Assert.assertEquals(2, registry.getAllShopkeepers().size());
		pending.remove().run();
		Assert.assertFalse(result.isDone());
		Assert.assertEquals(1, registry.getAllShopkeepers().size());
		this.drain();
		Assert.assertTrue(result.isDone());
		Assert.assertFalse(result.isCompletedExceptionally());
		Assert.assertTrue(registry.getAllShopkeepers().isEmpty());
		Mockito.verify(storage).deleteShopkeeper(first);
		Mockito.verify(storage).deleteShopkeeper(second);
	}

	@Test
	public void removeAllCommandConfirmsActualCountAfterOwnerCompletions() throws Exception {
		AbstractShopkeeper first = this.shop(0, 1, true);
		AbstractShopkeeper second = this.shop(200, 2, true);
		this.register(first);
		this.register(second);
		var console = Mockito.mock(org.bukkit.command.ConsoleCommandSender.class);
		Mockito.when(console.hasPermission(Mockito.anyString())).thenReturn(true);
		var spigot = Mockito.mock(CommandSender.Spigot.class);
		Mockito.when(console.spigot()).thenReturn(spigot);
		List<String> messages = new ArrayList<>();
		Mockito.doAnswer(call -> {
			messages.add(call.getArgument(0, String.class));
			return null;
		}).when(console).sendMessage(Mockito.anyString());
		Mockito.doAnswer(call -> {
			messages.add(call.getArgument(0, net.md_5.bungee.api.chat.BaseComponent.class).toPlainText());
			return null;
		}).when(spigot).sendMessage(Mockito.any(net.md_5.bungee.api.chat.BaseComponent.class));
		var confirmations = Mockito.mock(com.nisovin.shopkeepers.commands.Confirmations.class);
		AtomicReference<Runnable> confirmed = new AtomicReference<>();
		Mockito.doAnswer(call -> {
			confirmed.set(call.getArgument(1));
			return null;
		}).when(confirmations).awaitConfirmation(Mockito.same(console), Mockito.any(Runnable.class));
		Class<?> commandClass = Class.forName(
				"com.nisovin.shopkeepers.commands.shopkeepers.CommandRemoveAll");
		var constructor = commandClass.getDeclaredConstructor(
				com.nisovin.shopkeepers.api.ShopkeepersPlugin.class,
				com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperRegistry.class,
				com.nisovin.shopkeepers.commands.Confirmations.class);
		constructor.setAccessible(true);
		Object command = constructor.newInstance(plugin, registry, confirmations);
		var input = Mockito.mock(com.nisovin.shopkeepers.commands.lib.CommandInput.class);
		Mockito.when(input.getSender()).thenReturn(console);
		var context = Mockito.mock(com.nisovin.shopkeepers.commands.lib.context.CommandContextView.class);
		Mockito.when(context.has("all-admin")).thenReturn(true);
		Method execute = commandClass.getDeclaredMethod("execute",
				com.nisovin.shopkeepers.commands.lib.CommandInput.class,
				com.nisovin.shopkeepers.commands.lib.context.CommandContextView.class);
		execute.setAccessible(true);
		execute.invoke(command, input, context);
		Assert.assertNotNull(confirmed.get());
		messages.clear();
		confirmed.get().run();
		Assert.assertTrue(first.isValid());
		Assert.assertTrue(second.isValid());
		Assert.assertTrue(messages.isEmpty());
		pending.remove().run();
		Assert.assertTrue(messages.isEmpty());
		Mockito.doAnswer(call -> {
			Assert.assertEquals("global", owner.get());
			return null;
		}).when(storage).save();
		this.drain();
		Assert.assertFalse(first.isValid());
		Assert.assertFalse(second.isValid());
		Assert.assertTrue(messages.stream().anyMatch(message ->
				message.contains("2") && message.contains("admin shops have been removed")));
	}

	@Test
	public void shutdownInvalidatesQueuedDeletionWithoutTouchingLiveObject() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, true);
		this.register(shop);
		CompletableFuture<Void> result = registry.deleteAllShopkeepersAsync();
		Mockito.when(plugin.isEnabled()).thenReturn(false);
		registry.onDisable();
		this.drain();
		Assert.assertTrue(result.isCompletedExceptionally());
		Mockito.verify(shop, Mockito.never()).informRemoval(Mockito.any());
		Mockito.verify(storage, Mockito.never()).deleteShopkeeper(Mockito.any());
	}

	@Test
	public void cancellationAndRetirementDoNotRunOwnerMutation() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, true);
		this.register(shop);
		AtomicInteger edits = new AtomicInteger();
		CompletableFuture<Integer> cancelled = registry.runOnOwner(shop, edits::incrementAndGet);
		cancelled.cancel(false);
		this.drain();
		Assert.assertEquals(0, edits.get());
		Entity entity = Mockito.mock(Entity.class);
		BaseEntityShopObject<?> object = Mockito.mock(BaseEntityShopObject.class);
		Mockito.doReturn(entity).when(object).getEntity();
		Mockito.doReturn(object).when(shop).getShopObject();
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(entity), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
					call.getArgument(2, Runnable.class).run();
					return null;
				});
		Assert.assertTrue(registry.runOnOwner(shop, edits::incrementAndGet).isCompletedExceptionally());
		Assert.assertEquals(0, edits.get());
		Mockito.verify(entity, Mockito.never()).getLocation();
	}

	@Test
	public void reloadCapturesEveryOwnerBeforeRemovingAnyShop() throws Exception {
		AbstractShopkeeper first = this.shop(0, 1, true);
		AbstractShopkeeper second = this.shop(200, 2, true);
		this.register(first);
		this.register(second);
		CompletableFuture<Void> reload = registry.unloadAllShopkeepersAsync();
		pending.remove().run();
		Mockito.verify(first).save();
		Mockito.verify(first, Mockito.never()).informRemoval(Mockito.any());
		Mockito.verify(second, Mockito.never()).informRemoval(Mockito.any());
		pending.remove().run();
		this.drain();
		Assert.assertTrue(reload.isDone());
		Assert.assertFalse(reload.isCompletedExceptionally());
		Mockito.verify(second).save();
		Mockito.verify(first).informRemoval(ShopkeeperRemoveEvent.Cause.UNLOAD);
		Mockito.verify(second).informRemoval(ShopkeeperRemoveEvent.Cause.UNLOAD);
		Mockito.verify(storage, Mockito.never()).deleteShopkeeper(Mockito.any());
	}

	@Test
	public void rejectedReloadCaptureDoesNotUnloadOtherOwners() throws Exception {
		AbstractShopkeeper first = this.shop(0, 1, true);
		AbstractShopkeeper second = this.shop(200, 2, true);
		this.register(first);
		this.register(second);
		Mockito.when(scheduler.runAtLocationLater(
				Mockito.argThat(location -> location.getBlockX() == 3200),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenReturn(null);
		CompletableFuture<Void> result = registry.unloadAllShopkeepersAsync();
		this.drain();
		Assert.assertTrue(result.isCompletedExceptionally());
		Mockito.verify(first, Mockito.never()).informRemoval(Mockito.any());
		Mockito.verify(second, Mockito.never()).informRemoval(Mockito.any());
		Assert.assertEquals(2, registry.getAllShopkeepers().size());
	}

	@Test
	public void failedWriterCheckpointDoesNotUnloadCapturedOwners() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, true);
		this.register(shop);
		Mockito.when(storage.isDirty()).thenReturn(true);
		Mockito.doAnswer(call -> {
			Assert.assertEquals("global", owner.get());
			return null;
		}).when(storage).saveIfDirtyAndAwaitCompletion();
		CompletableFuture<Void> result = registry.unloadAllShopkeepersAsync();
		this.drain();
		Assert.assertTrue(result.isCompletedExceptionally());
		Assert.assertTrue(shop.isValid());
		Mockito.verify(shop, Mockito.never()).informRemoval(Mockito.any());
	}

	@Test
	public void failedSnapshotCaptureDoesNotReachWriterOrUnload() throws Exception {
		AbstractShopkeeper shop = this.shop(0, 1, true);
		this.register(shop);
		Mockito.when(shop.isDirty()).thenReturn(true);
		CompletableFuture<Void> result = registry.unloadAllShopkeepersAsync();
		this.drain();
		Assert.assertTrue(result.isCompletedExceptionally());
		Mockito.verify(storage, Mockito.never()).saveIfDirtyAndAwaitCompletion();
		Mockito.verify(shop, Mockito.never()).informRemoval(Mockito.any());
	}

	@Test
	public void pluginReloadIsNonBlockingAndCompletesAfterGlobalRebuild() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, true);
		this.register(shop);
		this.set(SKShopkeepersPlugin.class, plugin, "foliaLib", get(environment, "foliaLib"));
		this.set(SKShopkeepersPlugin.class, plugin, "shopkeeperRegistry", registry);
		Mockito.doCallRealMethod().when(plugin).reloadAsync();
		AtomicInteger rebuilds = new AtomicInteger();
		Mockito.doAnswer(call -> {
			Assert.assertEquals("global", owner.get());
			Assert.assertTrue(registry.getAllShopkeepers().isEmpty());
			registry.onDisable();
			return null;
		}).when(plugin).onDisable();
		Mockito.doAnswer(call -> {
			Assert.assertEquals("global", owner.get());
			rebuilds.incrementAndGet();
			registry.onEnable();
			return null;
		}).when(plugin).onEnable();
		CompletableFuture<Boolean> result = plugin.reloadAsync();
		Assert.assertFalse(result.isDone());
		Assert.assertEquals(0, rebuilds.get());
		Mockito.verify(shop, Mockito.never()).informRemoval(Mockito.any());
		this.drain();
		Assert.assertTrue(result.getNow(false));
		Assert.assertEquals(1, rebuilds.get());
	}

	@Test
	public void uiShutdownInvalidatesSessionsWithoutTouchingStoppedPlayer() throws Exception {
		Player player = Mockito.mock(Player.class);
		var provider = Mockito.mock(com.nisovin.shopkeepers.ui.lib.ViewProvider.class);
		var view = Mockito.mock(com.nisovin.shopkeepers.ui.lib.View.class,
				Mockito.withSettings().useConstructor(provider, player,
						com.nisovin.shopkeepers.ui.lib.UIState.EMPTY)
						.defaultAnswer(Mockito.CALLS_REAL_METHODS));
		var sessions = com.nisovin.shopkeepers.ui.lib.UISessionManager.getInstance();
		@SuppressWarnings("unchecked")
		Map<UUID, com.nisovin.shopkeepers.ui.lib.View> views =
				(Map<UUID, com.nisovin.shopkeepers.ui.lib.View>) get(sessions, "uiSessions");
		views.put(UUID.randomUUID(), view);
		Mockito.when(plugin.isEnabled()).thenReturn(false);
		sessions.onDisable();
		Assert.assertFalse(view.isValid());
		Assert.assertTrue(sessions.getUISessions().isEmpty());
		Mockito.verify(player, Mockito.never()).closeInventory();
		Mockito.verify(player, Mockito.never()).getLocation();
	}

	@Test
	public void worldSaveDispatchesDespawnAndRespawnToOwner() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, false);
		AbstractShopObject object = shop.getShopObject();
		AbstractShopObjectType<?> type = object.getType();
		Mockito.when(type.mustBeSpawned()).thenReturn(true);
		Mockito.when(type.mustDespawnDuringWorldSave()).thenReturn(true);
		AtomicBoolean spawned = new AtomicBoolean(true);
		Mockito.when(object.isSpawned()).thenAnswer(call -> spawned.get());
		Mockito.doAnswer(call -> {
			Assert.assertEquals(200, owner.get());
			spawned.set(false);
			return null;
		}).when(object).despawn();
		Mockito.when(object.spawn()).thenAnswer(call -> {
			Assert.assertEquals(200, owner.get());
			spawned.set(true);
			return true;
		});
		Object objectId = UUID.randomUUID();
		Mockito.when(object.getId()).thenReturn(objectId);
		Mockito.when(object.getLastId()).thenReturn(objectId);
		owner.set(200);
		this.add(shop, ShopkeeperAddedEvent.Cause.CREATED);
		owner.set(null);
		Mockito.clearInvocations(object);
		Object despawner = get(registry.getShopkeeperSpawner(), "worldSaveDespawner");
		Method save = despawner.getClass().getDeclaredMethod("onWorldSave", World.class);
		save.setAccessible(true);
		save.invoke(despawner, world);
		Mockito.verify(object, Mockito.never()).despawn();
		List<Runnable> callbacks = new ArrayList<>(pending);
		pending.clear();
		Assert.assertEquals(2, callbacks.size());
		callbacks.get(1).run();
		Assert.assertFalse(spawned.get());
		callbacks.get(0).run();
		this.drain();
		Assert.assertTrue(spawned.get());
		Mockito.verify(object).despawn();
		Mockito.verify(object).spawn();

		Mockito.clearInvocations(object);
		save.invoke(despawner, world);
		callbacks = new ArrayList<>(pending);
		pending.clear();
		// An old despawn arriving after the save completed must not remove the respawned shop.
		callbacks.get(0).run();
		callbacks.get(1).run();
		this.drain();
		Mockito.verify(object, Mockito.never()).despawn();
	}

	@Test
	public void failedEntityMoveKeepsRealSourceLocationAndIndexes() throws Exception {
		MoveFixture fixture = this.moveFixture();
		CompletableFuture<Boolean> result = fixture.shop.teleportAsync(
				new Location(world, 3200, 70, 0), null);
		this.drain();
		Assert.assertFalse(result.isDone());
		Assert.assertEquals(0, fixture.shop.getX());
		fixture.nativeResult.complete(false);
		this.drain();
		Assert.assertFalse(result.getNow(true));
		Assert.assertEquals(0, fixture.shop.getX());
		Assert.assertEquals(new ChunkCoords("world", 0, 0), fixture.shop.getLastChunkCoords());
		Assert.assertSame(fixture.shop, registry.getShopkeeperById(1));
		Assert.assertEquals(1, registry.getShopkeepersInChunkSnapshot(
				new ChunkCoords("world", 0, 0)).size());
		Assert.assertTrue(registry.getShopkeepersInChunkSnapshot(
				new ChunkCoords("world", 200, 0)).isEmpty());
		Mockito.verify(fixture.shop, Mockito.never()).setLocation(
				Mockito.any(Location.class), Mockito.any());
		Mockito.verify(storage, Mockito.never()).markDirty(Mockito.any());
		Mockito.verify(fixture.ai).updateLocation(Mockito.any());
		Mockito.verify(fixture.ai, Mockito.never()).addShopObject(Mockito.any());
	}

	@Test
	public void successfulEntityMoveCommitsOnlyAfterNativeSuccess() throws Exception {
		MoveFixture fixture = this.moveFixture();
		CompletableFuture<Boolean> result = fixture.shop.teleportAsync(
				new Location(world, 3200, 70, 0), null);
		this.drain();
		Assert.assertEquals(0, fixture.shop.getX());
		fixture.nativeResult.complete(true);
		Assert.assertEquals(0, fixture.shop.getX());
		this.drain();
		Assert.assertTrue(result.getNow(false));
		Assert.assertEquals(3200, fixture.shop.getX());
		Assert.assertEquals(new ChunkCoords("world", 200, 0), fixture.shop.getLastChunkCoords());
		Assert.assertTrue(registry.getShopkeepersInChunkSnapshot(
				new ChunkCoords("world", 0, 0)).isEmpty());
		Assert.assertEquals(1, registry.getShopkeepersInChunkSnapshot(
				new ChunkCoords("world", 200, 0)).size());
		Mockito.verify(fixture.ai).updateLocation(Mockito.any());
	}

	@Test
	public void deletionDuringNativeMoveCannotCommitOrRestoreOldEntity() throws Exception {
		MoveFixture fixture = this.moveFixture();
		CompletableFuture<Boolean> result = fixture.shop.teleportAsync(
				new Location(world, 3200, 70, 0), null);
		this.drain();
		fixture.shop.discardOnShutdown();
		shops(registry).remove(fixture.shop.getUniqueId());
		fixture.nativeResult.complete(true);
		this.drain();
		Assert.assertTrue(result.isDone());
		Assert.assertEquals(0, fixture.shop.getX());
		Mockito.verify(fixture.ai, Mockito.never()).addShopObject(Mockito.any());
		Mockito.verify(fixture.ai, Mockito.never()).updateLocation(Mockito.any());
	}

	@Test
	public void remoteCommandUsesTargetPlayersScheduler() throws Exception {
		Player player = Mockito.mock(Player.class);
		Mockito.when(player.isOnline()).thenReturn(true);
		CommandSender console = Mockito.mock(CommandSender.class);
		Mockito.when(console.hasPermission(Mockito.anyString())).thenReturn(true);
		AbstractShopkeeper shop = this.shop(200, 1, true);
		Mockito.doAnswer(call -> {
			Assert.assertSame(player, owner.get());
			return true;
		}).when(shop).openTradingWindow(player);
		Class<?> commandClass = Class.forName(
				"com.nisovin.shopkeepers.commands.shopkeepers.CommandRemote");
		var constructor = commandClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object command = constructor.newInstance();
		var input = Mockito.mock(com.nisovin.shopkeepers.commands.lib.CommandInput.class);
		Mockito.when(input.getSender()).thenReturn(console);
		var context = Mockito.mock(com.nisovin.shopkeepers.commands.lib.context.CommandContextView.class);
		Mockito.when(context.get("shopkeeper")).thenReturn(shop);
		Mockito.when(context.get("player")).thenReturn(player);
		Method execute = commandClass.getDeclaredMethod("execute",
				com.nisovin.shopkeepers.commands.lib.CommandInput.class,
				com.nisovin.shopkeepers.commands.lib.context.CommandContextView.class);
		execute.setAccessible(true);
		execute.invoke(command, input, context);
		Mockito.verify(shop, Mockito.never()).openTradingWindow(Mockito.any());
		this.drain();
		Mockito.verify(shop).openTradingWindow(player);
	}

	@Test
	public void editorMutationAndFeedbackUseDifferentOwners() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, true);
		this.register(shop);
		Player player = Mockito.mock(Player.class);
		Mockito.when(player.isOnline()).thenReturn(true);
		ShopkeeperEditorView view = Mockito.mock(ShopkeeperEditorView.class);
		Mockito.when(view.getShopkeeperNonNull()).thenReturn(shop);
		Mockito.when(view.getPlayer()).thenReturn(player);
		Mockito.when(view.isValid()).thenReturn(true);
		Mockito.when(view.isUIActive()).thenReturn(true);
		EditorLayout layout = Mockito.mock(EditorLayout.class);
		Mockito.when(layout.getEditorView()).thenReturn(view);
		AtomicInteger edits = new AtomicInteger();
		AtomicInteger feedback = new AtomicInteger();
		ActionButton button = new ActionButton() {
			@Override
			public org.bukkit.inventory.ItemStack getIcon() {
				return null;
			}

			@Override
			protected boolean requiresShopOwner() {
				return true;
			}

			@Override
			protected boolean runAction(InventoryClickEvent event) {
				Assert.assertEquals(200, owner.get());
				edits.incrementAndGet();
				return true;
			}

			@Override
			protected void playButtonClickSound(boolean success) {
				Assert.assertSame(player, owner.get());
				feedback.incrementAndGet();
			}

			@Override
			protected boolean isUpdateIconOnActionSuccess() {
				return false;
			}
		};
		Field field = Button.class.getDeclaredField("editorLayout");
		field.setAccessible(true);
		field.set(button, layout);
		InventoryClickEvent click = Mockito.mock(InventoryClickEvent.class);
		Mockito.when(click.getClick()).thenReturn(ClickType.LEFT);
		Method onClick = ActionButton.class.getDeclaredMethod("onClick", InventoryClickEvent.class);
		onClick.setAccessible(true);
		onClick.invoke(button, click);
		Assert.assertEquals(0, edits.get());
		pending.remove().run();
		Assert.assertEquals(1, edits.get());
		Assert.assertEquals(0, feedback.get());
		this.drain();
		Assert.assertEquals(1, feedback.get());
	}

	@Test
	public void editorRecipeSaveHandsAnIndependentSnapshotToOwner() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, true);
		this.register(shop);
		Player player = Mockito.mock(Player.class);
		var provider = Mockito.mock(com.nisovin.shopkeepers.ui.editor.ShopkeeperEditorViewProvider.class);
		Mockito.when(provider.getContext()).thenReturn(
				new com.nisovin.shopkeepers.ui.ShopkeeperViewContext(shop));
		var adapter = Mockito.mock(com.nisovin.shopkeepers.ui.editor.TradingRecipesAdapter.class);
		this.set(com.nisovin.shopkeepers.ui.editor.AbstractEditorViewProvider.class,
				provider, "tradingRecipesAdapter", adapter);
		var view = Mockito.mock(ShopkeeperEditorView.class,
				Mockito.withSettings().useConstructor(provider, player,
						com.nisovin.shopkeepers.ui.lib.UIState.EMPTY)
						.defaultAnswer(Mockito.CALLS_REAL_METHODS));
		var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIRT, 2);
		var draft = new com.nisovin.shopkeepers.shopkeeper.TradingRecipeDraft(
				item, (org.bukkit.inventory.ItemStack) null, null);
		List<com.nisovin.shopkeepers.shopkeeper.TradingRecipeDraft> recipes = new ArrayList<>();
		recipes.add(draft);
		this.set(com.nisovin.shopkeepers.ui.editor.EditorView.class, view, "recipes", recipes);
		AtomicInteger applied = new AtomicInteger();
		Mockito.when(adapter.updateTradingRecipes(Mockito.same(player), Mockito.anyList()))
				.thenAnswer(call -> {
					Assert.assertEquals(200, owner.get());
					List<com.nisovin.shopkeepers.shopkeeper.TradingRecipeDraft> snapshot = call.getArgument(1);
					Assert.assertNotSame(recipes, snapshot);
					Assert.assertEquals(2, snapshot.get(0).getResultItem().getAmount());
					applied.incrementAndGet();
					return 0;
				});
		Method save = ShopkeeperEditorView.class.getDeclaredMethod("saveRecipes");
		save.setAccessible(true);
		save.invoke(view);
		item.setAmount(64);
		recipes.clear();
		Assert.assertEquals(0, applied.get());
		this.drain();
		Assert.assertEquals(1, applied.get());
	}

	@Test
	public void remoteNamingMutatesShopBeforePlayerFeedback() throws Exception {
		AbstractShopkeeper shop = this.shop(200, 1, true);
		this.register(shop);
		Player player = Mockito.mock(Player.class);
		Mockito.when(player.isOnline()).thenReturn(true);
		Player.Spigot spigot = Mockito.mock(Player.Spigot.class);
		Mockito.when(player.spigot()).thenReturn(spigot);
		AtomicReference<String> name = new AtomicReference<>("old name");
		Mockito.when(shop.getName()).thenAnswer(call -> name.get());
		Mockito.when(shop.isValidName(Mockito.anyString())).thenReturn(true);
		Mockito.doAnswer(call -> {
			Assert.assertEquals(200, owner.get());
			name.set(call.getArgument(0));
			return null;
		}).when(shop).setName(Mockito.anyString());
		AtomicInteger feedback = new AtomicInteger();
		Mockito.doAnswer(call -> {
			Assert.assertSame(player, owner.get());
			feedback.incrementAndGet();
			return null;
		}).when(player).sendMessage(Mockito.anyString());
		Mockito.doAnswer(call -> {
			Assert.assertSame(player, owner.get());
			feedback.incrementAndGet();
			return null;
		}).when(spigot).sendMessage(
				Mockito.any(net.md_5.bungee.api.chat.BaseComponent.class));
		var naming = new com.nisovin.shopkeepers.naming.ShopkeeperNaming(
				Mockito.mock(com.nisovin.shopkeepers.input.chat.ChatInput.class));
		CompletableFuture<Boolean> result = naming.requestNameChangeAsync(player, shop, "new name");
		Assert.assertEquals("old name", name.get());
		Assert.assertEquals(0, feedback.get());
		pending.remove().run();
		Assert.assertEquals("new name", name.get());
		Assert.assertEquals(0, feedback.get());
		this.drain();
		Assert.assertTrue(result.getNow(false));
		Assert.assertEquals(1, feedback.get());
		Mockito.verify(shop).save();
	}
	private record MoveFixture(
			AbstractShopkeeper shop,
			CompletableFuture<Boolean> nativeResult,
			EntityAI ai
	) {}

	private MoveFixture moveFixture() throws Exception {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class,
				Mockito.withSettings().useConstructor().defaultAnswer(Mockito.CALLS_REAL_METHODS));
		Mockito.doReturn(UUID.randomUUID()).when(shop).getUniqueId();
		Mockito.doReturn(1).when(shop).getId();
		this.set(AbstractShopkeeper.class, shop, "valid", true);
		this.set(AbstractShopkeeper.class, shop, "location",
				BlockLocation.of(new Location(world, 0, 64, 0)));
		this.set(AbstractShopkeeper.class, shop, "chunkCoords", new ChunkCoords("world", 0, 0));
		this.set(AbstractShopkeeper.class, shop, "lastChunkCoords", new ChunkCoords("world", 0, 0));
		BaseEntityShops shops = Mockito.mock(BaseEntityShops.class);
		EntityAI ai = Mockito.mock(EntityAI.class);
		Mockito.when(shops.getEntityAI()).thenReturn(ai);
		BaseEntityShopObjectType<?> type = Mockito.mock(BaseEntityShopObjectType.class);
		Mockito.when(type.getEntityType()).thenReturn(EntityType.VILLAGER);
		BaseEntityShopObject<Entity> object = new BaseEntityShopObject<>(
				new BaseEntityShopObjectCreationContext(shops), type, shop, null) {
			@Override
			protected boolean shallAdjustSpawnLocation() {
				return false;
			}
		};
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
		Mockito.when(entity.isValid()).thenReturn(true);
		this.set(BaseEntityShopObject.class, object, "entity", entity);
		this.set(AbstractShopkeeper.class, shop, "shopObject", object);
		this.register(shop);
		shop.setLastChunkCoords(null);
		Object chunkMap = get(registry, "chunkMap");
		Method add = chunkMap.getClass().getDeclaredMethod("addShopkeeper", AbstractShopkeeper.class);
		add.setAccessible(true);
		add.invoke(chunkMap, shop);
		CompletableFuture<Boolean> nativeResult = new CompletableFuture<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenAnswer(call -> {
					Assert.assertSame(entity, owner.get());
					return nativeResult;
				});
		ForcingEntityTeleporter teleporter = new ForcingEntityTeleporter(plugin);
		Mockito.when(plugin.getForcingEntityTeleporter()).thenReturn(teleporter);
		return new MoveFixture(shop, nativeResult, ai);
	}

	private AbstractShopkeeper shop(int chunkX, int id, boolean initiallyValid) {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class,
				Mockito.withSettings().extraInterfaces(
						com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopkeeper.class));
		AtomicBoolean valid = new AtomicBoolean(initiallyValid);
		AtomicBoolean active = new AtomicBoolean();
		ChunkCoords chunk = new ChunkCoords("world", chunkX, 0);
		AtomicReference<ChunkCoords> last = new AtomicReference<>(chunk);
		Mockito.when(shop.getUniqueId()).thenReturn(UUID.randomUUID());
		Mockito.when(shop.getId()).thenReturn(id);
		Mockito.when(shop.getWorldName()).thenReturn("world");
		Mockito.when(shop.getLocation()).thenReturn(new Location(world, chunkX * 16, 64, 0));
		Mockito.when(shop.getChunkCoords()).thenReturn(chunk);
		Mockito.when(shop.getLastChunkCoords()).thenAnswer(call -> last.get());
		Mockito.doAnswer(call -> {
			last.set(call.getArgument(0));
			return null;
		}).when(shop).setLastChunkCoords(Mockito.any());
		Mockito.when(shop.isValid()).thenAnswer(call -> valid.get());
		Mockito.when(shop.isActive()).thenAnswer(call -> active.get());
		Mockito.when(shop.getComponents()).thenReturn(new ShopkeeperComponentHolder(shop));
		Mockito.doAnswer(call -> {
			valid.set(true);
			return null;
		}).when(shop).publishLoaded();
		Mockito.doAnswer(call -> {
			valid.set(true);
			return null;
		}).when(shop).informAdded(Mockito.any());
		Mockito.doAnswer(call -> {
			Assert.assertEquals(chunkX, owner.get());
			valid.set(false);
			return null;
		}).when(shop).informRemoval(Mockito.any());
		Mockito.doAnswer(call -> {
			Assert.assertEquals(chunkX, owner.get());
			active.set(call.getArgument(0));
			return null;
		}).when(shop).setActive(Mockito.anyBoolean());
		AbstractShopType<?> type = Mockito.mock(AbstractShopType.class);
		Mockito.when(type.isEnabled()).thenReturn(true);
		Mockito.doReturn(type).when(shop).getType();
		AbstractShopObject object = Mockito.mock(AbstractShopObject.class);
		AbstractShopObjectType<?> objectType = Mockito.mock(AbstractShopObjectType.class);
		Mockito.when(objectType.isEnabled()).thenReturn(true);
		Mockito.doReturn(objectType).when(object).getType();
		Mockito.doReturn(object).when(shop).getShopObject();
		Mockito.doAnswer(call -> {
			registry.deleteShopkeeper(shop);
			return null;
		}).when(shop).delete(Mockito.nullable(Player.class));
		Mockito.doAnswer(call -> {
			registry.deleteShopkeeper(shop);
			return null;
		}).when(shop).delete();
		return shop;
	}

	private void register(AbstractShopkeeper shop) throws Exception {
		shops(registry).put(shop.getUniqueId(), shop);
		@SuppressWarnings("unchecked")
		Map<Integer, AbstractShopkeeper> ids =
				(Map<Integer, AbstractShopkeeper>) get(registry, "shopkeepersById");
		ids.put(shop.getId(), shop);
		registry.getChunkActivator().onShopkeeperChunkAdded(shop.getLastChunkCoords());
	}

	private void add(AbstractShopkeeper shop, ShopkeeperAddedEvent.Cause cause) throws Exception {
		shop.setLastChunkCoords(null);
		Method add = SKShopkeeperRegistry.class.getDeclaredMethod("addShopkeeper",
				AbstractShopkeeper.class, ShopkeeperAddedEvent.Cause.class);
		add.setAccessible(true);
		add.invoke(registry, shop, cause);
	}

	private void enqueue(Object taskOwner, Runnable action) {
		pending.add(() -> {
			Object previous = owner.getAndSet(taskOwner);
			try {
				action.run();
			} finally {
				owner.set(previous);
			}
		});
	}

	private void drain() {
		int limit = 1000;
		while (!pending.isEmpty()) {
			Assert.assertTrue("Owner callbacks must converge.", --limit > 0);
			pending.remove().run();
		}
	}

	private void set(Class<?> type, Object target, String name, Object value) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static Map<UUID, AbstractShopkeeper> shops(SKShopkeeperRegistry registry)
			throws Exception {
		return (Map<UUID, AbstractShopkeeper>) get(registry, "shopkeepersByUUID");
	}

	private static Object get(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
