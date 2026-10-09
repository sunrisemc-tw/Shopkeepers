package com.nisovin.shopkeepers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.api.internal.InternalShopkeepersAPI;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.internals.SKApiInternals;
import com.nisovin.shopkeepers.shopobjects.entity.base.BaseEntityShopObject;
import com.nisovin.shopkeepers.shopobjects.entity.base.EntityAI;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.java.RateLimiter;
import com.nisovin.shopkeepers.util.timer.Timer;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Standalone focused tests using the cached FoliaLib and Mockito jars.
 */
public class FoliaCorrectionsTests {

	private Object previousPlugin;
	private Object previousServer;
	private Object previousApiPlugin;
	private int previousTickPeriod;
	private SKShopkeepersPlugin plugin;
	private FoliaLib foliaLib;
	private PlatformScheduler scheduler;
	private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
	private World world;
	private Server server;

	@Before
	public void setup() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		previousPlugin = field.get(null);
		plugin = Mockito.mock(SKShopkeepersPlugin.class);
		foliaLib = Mockito.mock(FoliaLib.class);
		scheduler = Mockito.mock(PlatformScheduler.class);
		Mockito.when(plugin.getFoliaLib()).thenReturn(foliaLib);
		Mockito.when(plugin.getApiInternals()).thenReturn(new SKApiInternals());
		Mockito.when(plugin.isEnabled()).thenReturn(true);
		Mockito.when(foliaLib.getPlugin()).thenReturn(plugin);
		Mockito.when(foliaLib.isFolia()).thenReturn(true);
		Mockito.when(foliaLib.getScheduler()).thenReturn(scheduler);
		field.set(null, plugin);
		Field apiField = InternalShopkeepersAPI.class.getDeclaredField("plugin");
		apiField.setAccessible(true);
		previousApiPlugin = apiField.get(null);
		apiField.set(null, plugin);
		Field serverField = Bukkit.class.getDeclaredField("server");
		serverField.setAccessible(true);
		previousServer = serverField.get(null);
		server = Mockito.mock(Server.class);
		serverField.set(null, server);
		Mockito.when(server.getOnlinePlayers()).thenReturn(Collections.emptyList());
		Mockito.when(server.getItemFactory()).thenReturn(Mockito.mock(ItemFactory.class));
		Mockito.doAnswer(call -> registry(call.getArgument(0))).when(server)
				.getRegistry(Mockito.any());
		world = Mockito.mock(World.class);
		Mockito.when(world.getName()).thenReturn("world");
		previousTickPeriod = Settings.entityBehaviorTickPeriod;
		Settings.entityBehaviorTickPeriod = 1;
		Mockito.when(scheduler.runTimer(Mockito.any(Runnable.class),
				Mockito.anyLong(), Mockito.anyLong())).thenReturn(Mockito.mock(WrappedTask.class));
	}

	@After
	public void teardown() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		field.set(null, previousPlugin);
		Field apiField = InternalShopkeepersAPI.class.getDeclaredField("plugin");
		apiField.setAccessible(true);
		apiField.set(null, previousApiPlugin);
		Field serverField = Bukkit.class.getDeclaredField("server");
		serverField.setAccessible(true);
		serverField.set(null, previousServer);
		Settings.entityBehaviorTickPeriod = previousTickPeriod;
	}

	@Test
	public void entitySchedulingDoesNotReadLocation() {
		Entity entity = Mockito.mock(Entity.class);
		Runnable work = () -> {};
		WrappedTask handle = Mockito.mock(WrappedTask.class);
		Mockito.when(scheduler.runAtEntityLater(
				Mockito.same(entity), Mockito.same(work), Mockito.any(Runnable.class),
				Mockito.eq(0L)
		)).thenReturn(handle);
		Assert.assertSame(handle, SchedulerUtils.runTaskOrOmit(entity, work));
		Mockito.verify(entity, Mockito.never()).getLocation();
	}

	@Test
	public void locationSchedulingCopiesMutableLocation() {
		Location location = new Location(null, 1, 2, 3);
		SchedulerUtils.runTaskLaterOrOmit(location, () -> {}, 4L);
		Mockito.verify(scheduler).runAtLocationLater(
				Mockito.argThat(copy -> copy != location && copy.equals(location)),
				Mockito.any(Runnable.class), Mockito.eq(4L)
		);
	}

	@Test
	public void rejectedEntityTaskIsOmitted() {
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(scheduler.runAtEntityLater(
				Mockito.same(entity), Mockito.any(Runnable.class), Mockito.any(Runnable.class),
				Mockito.eq(2L)
		)).thenThrow(new NullPointerException("nativeTask"));
		Assert.assertNull(SchedulerUtils.runTaskLaterOrOmit(entity, () -> {}, 2L));
	}

	@Test
	public void retiredEntityDoesNotRunWork() {
		Entity entity = Mockito.mock(Entity.class);
		AtomicInteger executions = new AtomicInteger();
		Mockito.when(scheduler.runAtEntityLater(
				Mockito.same(entity), Mockito.any(Runnable.class), Mockito.any(Runnable.class),
				Mockito.eq(2L)
		)).thenAnswer(call -> {
			call.getArgument(2, Runnable.class).run();
			return null;
		});
		SchedulerUtils.runTaskLaterOrOmit(entity, executions::incrementAndGet, 2L);
		Assert.assertEquals(0, executions.get());
	}

	@Test
	public void independentTimerSamplesAreAggregated() throws Exception {
		Timer timer = new Timer();
		Method record = timer.getClass().getMethod("record", long.class);
		record.invoke(timer, 2_000_000L);
		record.invoke(timer, 4_000_000L);
		Assert.assertEquals(2, timer.getCounter());
		Assert.assertEquals(3.0D, timer.getAverageTimeMillis(), 0.0D);
		Assert.assertEquals(4.0D, timer.getMaxTimeMillis(), 0.0D);
	}

	@Test
	public void entityTimerUsesEntityAndRetirementCallback() {
		Entity entity = Mockito.mock(Entity.class);
		AtomicInteger executions = new AtomicInteger();
		Consumer<WrappedTask> work = task -> executions.incrementAndGet();
		Mockito.doAnswer(call -> {
			call.getArgument(2, Runnable.class).run();
			return null;
		}).when(scheduler).runAtEntityTimer(Mockito.same(entity),
				Mockito.<Consumer<WrappedTask>>any(),
				Mockito.any(Runnable.class), Mockito.eq(3L), Mockito.eq(7L));
		SchedulerUtils.runTaskTimerOrOmit(entity, work, 3L, 7L);
		Mockito.verify(scheduler).runAtEntityTimer(Mockito.same(entity),
				Mockito.<Consumer<WrappedTask>>any(),
				Mockito.any(Runnable.class), Mockito.eq(3L), Mockito.eq(7L));
		Mockito.verify(entity, Mockito.never()).getLocation();
		Assert.assertEquals(0, executions.get());
	}

	@Test
	public void locationTimerCopiesMutableLocation() {
		Location location = new Location(null, 1, 2, 3);
		SchedulerUtils.runTaskTimerOrOmit(location, task -> {}, 3L, 7L);
		Mockito.verify(scheduler).runAtLocationTimer(
				Mockito.argThat(copy -> copy != location && copy.equals(location)),
				Mockito.<Consumer<WrappedTask>>any(), Mockito.eq(3L), Mockito.eq(7L));
	}

	@Test
	public void nonFoliaEntityTimingIsUnchanged() {
		Mockito.when(foliaLib.isFolia()).thenReturn(false);
		Entity entity = entity(0);
		Runnable work = () -> {};
		SchedulerUtils.runTaskLaterOrOmit(entity, work, 0L);
		SchedulerUtils.runTaskTimerOrOmit(entity, task -> {}, 0L, 5L);
		Mockito.verify(scheduler).runAtLocationLater(Mockito.any(Location.class),
				Mockito.same(work), Mockito.eq(0L));
		Mockito.verify(scheduler).runAtLocationTimer(Mockito.any(Location.class),
				Mockito.<Consumer<WrappedTask>>any(), Mockito.eq(0L), Mockito.eq(5L));
	}

	@Test
	public void disabledPluginDoesNotRegisterEntityWork() {
		Mockito.when(plugin.isEnabled()).thenReturn(false);
		Entity entity = Mockito.mock(Entity.class);
		Assert.assertNull(SchedulerUtils.runTaskOrOmit(entity, () -> {}));
		SchedulerUtils.runTaskTimerOrOmit(entity, task -> {}, 1L, 1L);
		Mockito.verifyNoInteractions(entity, scheduler);
	}

	@Test
	public void disableRaceOmitsEntityWork() {
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(entity), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(0L)))
				.thenThrow(new IllegalPluginAccessException());
		Assert.assertNull(SchedulerUtils.runTaskOrOmit(entity, () -> {}));
	}

	@Test
	public void unrelatedNullPointerExceptionIsNotHidden() {
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(entity), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(0L)))
				.thenThrow(new NullPointerException("unexpected"));
		Assert.assertThrows(NullPointerException.class,
				() -> SchedulerUtils.runTaskOrOmit(entity, () -> {}));
	}

	@Test
	public void cancellationUsesNativeWrappedHandle() {
		Entity entity = Mockito.mock(Entity.class);
		WrappedTask nativeHandle = Mockito.mock(WrappedTask.class);
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(entity), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(0L))).thenReturn(nativeHandle);
		WrappedTask handle = SchedulerUtils.runTaskOrOmit(entity, () -> {});
		Assert.assertSame(nativeHandle, handle);
		handle.cancel();
		Mockito.verify(nativeHandle).cancel();
	}

	@Test
	public void staleCallbacksAfterRespawnAreSkipped() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		ai.removeShopObject(shop);
		Mockito.doReturn(entity(0)).when(shop).getEntity();
		ai.addShopObject(shop);
		pending.remove().run();
		Mockito.verify(shop, Mockito.never()).tickAI();
	}

	@Test
	public void staleCallbacksAfterLocationUpdateAreSkipped() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		ai.updateLocation(shop);
		pending.remove().run();
		Mockito.verify(shop, Mockito.never()).tickAI();
		Assert.assertEquals(1, ai.getEntityCount());
		Assert.assertEquals(1, ai.getActiveAIEntityCount());
	}

	@Test
	public void replacementEntityWithoutRegistrationIsSkipped() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		Mockito.doReturn(entity(0)).when(shop).getEntity();
		pending.remove().run();
		Mockito.verify(shop, Mockito.never()).tickAI();
	}

	@Test
	public void callbacksAfterDisableAreSkipped() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		ai.onDisable();
		pending.remove().run();
		Mockito.verify(shop, Mockito.never()).tickAI();
		Assert.assertEquals(0, ai.getEntityCount());
		Assert.assertEquals(0, ai.getActiveAIEntityCount());
		Assert.assertEquals(0, ai.getAITimings().getCounter());
	}

	@Test
	public void regionProcessingRecordsIndependentDurations() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		set(ai, "customGravityEnabled", true);
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		ai.addShopObject(shop);
		Object data = ((Map<?, ?>) get(ai, "shopObjects")).get(shop);
		((RateLimiter) get(data, "fallingCheckLimiter")).setRemainingThreshold(1000);
		invoke(ai, "processEntities");
		pending.remove().run();
		Mockito.verify(shop).tickAI();
		Assert.assertEquals(1, ai.getGravityTimings().getCounter());
		Assert.assertTrue(ai.getGravityTimings().getMaxTimeMillis() > 0.0D);
		Assert.assertEquals(1, ai.getAITimings().getCounter());
		Assert.assertTrue(ai.getAITimings().getMaxTimeMillis() > 0.0D);
		Assert.assertEquals(1, ai.getActiveAIEntityCount());
		Assert.assertEquals(1, ai.getActiveGravityEntityCount());
	}

	@Test
	public void timerAggregationDoesNotLoseConcurrentSamples() throws Exception {
		Timer timer = new Timer();
		Method record = Timer.class.getMethod("record", long.class);
		runConcurrent(8, index -> {
			for (int i = 0; i < 1000; i++) record.invoke(timer, 2_000_000L);
			return null;
		});
		Assert.assertEquals(8000, timer.getCounter());
		Assert.assertEquals(2.0D, timer.getAverageTimeMillis(), 0.0D);
		Assert.assertEquals(2.0D, timer.getMaxTimeMillis(), 0.0D);
		timer.reset();
		Assert.assertEquals(0, timer.getCounter());
		Assert.assertEquals(0.0D, timer.getAverageTimeMillis(), 0.0D);
		Assert.assertEquals(0.0D, timer.getMaxTimeMillis(), 0.0D);
	}

	@Test
	public void concurrentRegistrationsKeepBothIndexesAndCountsConsistent() throws Exception {
		EntityAI ai = ai();
		set(ai, "customGravityEnabled", true);
		List<BaseEntityShopObject<?>> shops = new ArrayList<>();
		for (int i = 0; i < 64; i++) shops.add(shop(entity(0), EntityType.VILLAGER));
		runConcurrent(8, index -> {
			for (int i = index; i < shops.size(); i += 8) ai.addShopObject(shops.get(i));
			return null;
		});
		Assert.assertEquals(64, ai.getEntityCount());
		Assert.assertEquals(1, ai.getActiveAIChunksCount());
		Assert.assertEquals(64, ai.getActiveAIEntityCount());
		Assert.assertEquals(64, ai.getActiveGravityEntityCount());
		Map<?, ?> chunks = (Map<?, ?>) get(ai, "chunks");
		Assert.assertEquals(1, chunks.size());
		Assert.assertEquals(64, ((List<?>) get(chunks.values().iterator().next(), "entities")).size());
		runConcurrent(8, index -> {
			for (int i = index; i < shops.size(); i += 8) ai.removeShopObject(shops.get(i));
			return null;
		});
		Assert.assertEquals(0, ai.getEntityCount());
		Assert.assertTrue(chunks.isEmpty());
		Assert.assertEquals(0, ai.getActiveAIChunksCount());
		Assert.assertEquals(0, ai.getActiveAIEntityCount());
		Assert.assertEquals(0, ai.getActiveGravityChunksCount());
		Assert.assertEquals(0, ai.getActiveGravityEntityCount());
	}

	@Test
	public void stalePlayerScansAndOfflinePlayersDoNotActivateChunks() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		ai.addShopObject(shop(entity(0), EntityType.VILLAGER));
		Player player = player();
		Mockito.doReturn(Collections.singleton(player)).when(server).getOnlinePlayers();
		invoke(ai, "updateChunkActivations");
		Runnable oldScan = pending.remove();
		invoke(ai, "updateChunkActivations");
		Runnable currentScan = pending.remove();
		oldScan.run();
		Assert.assertEquals(0, ai.getActiveAIChunksCount());
		Mockito.verify(player, Mockito.never()).getLocation(Mockito.any(Location.class));
		currentScan.run();
		Assert.assertEquals(1, ai.getActiveAIChunksCount());
		Assert.assertEquals(1, ai.getActiveAIEntityCount());
		invoke(ai, "updateChunkActivations");
		Mockito.when(player.isOnline()).thenReturn(false);
		pending.remove().run();
		Assert.assertEquals(0, ai.getActiveAIChunksCount());
	}

	@Test
	public void stalePlayerCallbackAfterDisableDoesNotReadWorld() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		Player player = player();
		Mockito.doReturn(Collections.singleton(player)).when(server).getOnlinePlayers();
		invoke(ai, "updateChunkActivations");
		ai.onDisable();
		pending.remove().run();
		Mockito.verify(player, Mockito.never()).getWorld();
		Mockito.verify(player, Mockito.never()).getLocation(Mockito.any(Location.class));
	}

	@Test
	public void concurrentRegionsRecordSamplesWithoutInflatingActivationCounts() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		for (int i = 0; i < 8; i++) ai.addShopObject(shop(entity(i), EntityType.VILLAGER));
		invoke(ai, "processEntities");
		List<Runnable> callbacks = new ArrayList<>(pending);
		runConcurrent(8, index -> {
			for (int i = 0; i < 100; i++) callbacks.get(index).run();
			return null;
		});
		Assert.assertEquals(800, ai.getAITimings().getCounter());
		Assert.assertTrue(ai.getAITimings().getAverageTimeMillis() > 0.0D);
		Assert.assertEquals(8, ai.getActiveAIEntityCount());
		Assert.assertEquals(8, ai.getActiveAIChunksCount());
	}

	@Test
	public void overlappingPlayerActivationsDoNotDoubleCount() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		set(ai, "customGravityEnabled", true);
		ai.addShopObject(shop(entity(0), EntityType.VILLAGER));
		ai.addShopObject(shop(entity(0), EntityType.SHULKER));
		List<Player> players = new ArrayList<>();
		for (int i = 0; i < 8; i++) players.add(player());
		Mockito.doReturn(players).when(server).getOnlinePlayers();
		invoke(ai, "updateChunkActivations");
		List<Runnable> callbacks = new ArrayList<>(pending);
		runConcurrent(8, index -> {
			callbacks.get(index).run();
			return null;
		});
		Assert.assertEquals(1, ai.getActiveAIChunksCount());
		Assert.assertEquals(2, ai.getActiveAIEntityCount());
		Assert.assertEquals(1, ai.getActiveGravityChunksCount());
		Assert.assertEquals(1, ai.getActiveGravityEntityCount());
	}

	@Test
	public void registrationPreparedBeforeLifecycleChangeIsRejected() throws Exception {
		EntityAI ai = ai();
		Entity entity = entity(0);
		Mockito.when(entity.getLocation(Mockito.any(Location.class))).thenAnswer(call -> {
			ai.onDisable();
			set(ai, "enabled", true);
			set(ai, "lifecycle", 2L);
			return new Location(world, 0, 0, 0);
		});
		ai.addShopObject(shop(entity, EntityType.VILLAGER));
		Assert.assertEquals(0, ai.getEntityCount());
		Assert.assertEquals(0, ai.getActiveAIChunksCount());
	}

	@Test
	public void staleTaskRegistrationCannotReplaceNewLifecycleTask() throws Exception {
		EntityAI ai = ai();
		WrappedTask staleTask = Mockito.mock(WrappedTask.class);
		WrappedTask currentTask = Mockito.mock(WrappedTask.class);
		AtomicInteger registrations = new AtomicInteger();
		Mockito.when(scheduler.runTimer(Mockito.any(Runnable.class),
				Mockito.anyLong(), Mockito.anyLong())).thenAnswer(call -> {
					if (registrations.incrementAndGet() == 1) {
						ai.onDisable();
						set(ai, "enabled", true);
						set(ai, "lifecycle", 2L);
						invoke(ai, "startTask");
						return staleTask;
					}
					return currentTask;
				});
		invoke(ai, "startTask");
		Assert.assertEquals(2, registrations.get());
		Assert.assertSame(currentTask, get(ai, "aiTask"));
		Mockito.verify(staleTask).cancel();
		Mockito.verify(currentTask, Mockito.never()).cancel();
	}

	@Test
	public void completionAfterDisableDoesNotRepopulateTimings() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		Mockito.doAnswer(call -> {
			ai.onDisable();
			return null;
		}).when(shop).tickAI();
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		pending.remove().run();
		Assert.assertEquals(0, ai.getAITimings().getCounter());
		Assert.assertEquals(0, ai.getActiveAIEntityCount());
	}

	@Test
	public void completedWorkIsMeasuredEvenIfItUnregistersTheEntity() throws Exception {
		captureTasks();
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		Mockito.doAnswer(call -> {
			ai.removeShopObject(shop);
			return null;
		}).when(shop).tickAI();
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		pending.remove().run();
		Assert.assertEquals(1, ai.getAITimings().getCounter());
		Assert.assertTrue(ai.getAITimings().getMaxTimeMillis() > 0.0D);
		Assert.assertEquals(0, ai.getEntityCount());
	}

	@Test
	public void nonFoliaTimingsStillCountDriverTicks() throws Exception {
		Mockito.when(foliaLib.isFolia()).thenReturn(false);
		EntityAI ai = ai();
		BaseEntityShopObject<?> shop = shop(entity(0), EntityType.VILLAGER);
		Mockito.doReturn(Collections.singleton(player())).when(server).getOnlinePlayers();
		ai.addShopObject(shop);
		ArgumentCaptor<Runnable> driver = ArgumentCaptor.forClass(Runnable.class);
		Mockito.verify(scheduler).runTimer(driver.capture(), Mockito.eq(1L), Mockito.eq(1L));
		driver.getValue().run();
		driver.getValue().run();
		Mockito.verify(shop, Mockito.times(2)).tickAI();
		Assert.assertEquals(2, ai.getTotalTimings().getCounter());
		Assert.assertEquals(2, ai.getGravityTimings().getCounter());
		Assert.assertEquals(2, ai.getAITimings().getCounter());
		Assert.assertEquals(1, ai.getActiveAIEntityCount());
	}

	@Test
	public void worldAccessAndSchedulingStayOutsideRegistryLock() throws Exception {
		EntityAI ai = ai();
		Object lock = get(ai, "stateLock");
		Entity entity = entity(0);
		Mockito.when(entity.isValid()).thenAnswer(call -> {
			Assert.assertFalse(Thread.holdsLock(lock));
			return true;
		});
		Mockito.when(world.getName()).thenAnswer(call -> {
			Assert.assertFalse(Thread.holdsLock(lock));
			return "world";
		});
		Mockito.when(scheduler.runTimer(Mockito.any(Runnable.class),
				Mockito.anyLong(), Mockito.anyLong())).thenAnswer(call -> {
					Assert.assertFalse(Thread.holdsLock(lock));
					return Mockito.mock(WrappedTask.class);
				});
		Mockito.when(scheduler.runAtEntityLater(Mockito.any(Entity.class),
				Mockito.any(Runnable.class), Mockito.any(Runnable.class), Mockito.anyLong()))
				.thenAnswer(call -> {
					Assert.assertFalse(Thread.holdsLock(lock));
					pending.add(call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
		BaseEntityShopObject<?> shop = shop(entity, EntityType.VILLAGER);
		Mockito.doAnswer(call -> {
			Assert.assertFalse(Thread.holdsLock(lock));
			return null;
		}).when(shop).tickAI();
		ai.addShopObject(shop);
		invoke(ai, "processEntities");
		pending.remove().run();
		Mockito.doReturn(Collections.singleton(player())).when(server).getOnlinePlayers();
		invoke(ai, "updateChunkActivations");
		pending.remove().run();
		ai.removeShopObject(shop);
	}

	private EntityAI ai() throws Exception {
		EntityAI ai = new EntityAI(plugin);
		set(ai, "enabled", true);
		return ai;
	}

	private static Object registry(Class<?> type) {
		return Proxy.newProxyInstance(Registry.class.getClassLoader(),
				new Class<?>[] { Registry.class }, (proxy, method, args) -> {
					if (method.getName().equals("get") || method.getName().equals("getOrThrow")) {
						return Proxy.newProxyInstance(type.getClassLoader(),
								new Class<?>[] { type }, (entry, entryMethod, entryArgs) -> {
									if (entryMethod.getName().equals("getKey")) return args[0];
									if (entryMethod.getName().equals("isAir")) {
										String key = args[0].toString();
										return key.equals("minecraft:air")
												|| key.equals("minecraft:cave_air")
												|| key.equals("minecraft:void_air");
									}
									if (entryMethod.getReturnType() == boolean.class) return false;
									return null;
								});
					}
					return null;
				});
	}

	private Entity entity(int chunkX) {
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(entity.isValid()).thenReturn(true);
		Mockito.when(entity.getLocation()).thenReturn(new Location(world, chunkX * 16, 0, 0));
		Mockito.when(entity.getLocation(Mockito.any(Location.class))).thenAnswer(call -> {
			Location location = call.getArgument(0);
			location.setWorld(world);
			location.setX(chunkX * 16);
			location.setY(0);
			location.setZ(0);
			return location;
		});
		return entity;
	}

	private Player player() {
		Player player = Mockito.mock(Player.class);
		Mockito.when(player.isOnline()).thenReturn(true);
		Mockito.when(player.getWorld()).thenReturn(world);
		Mockito.when(player.getLocation()).thenReturn(new Location(world, 0, 0, 0));
		Mockito.when(player.getLocation(Mockito.any(Location.class)))
				.thenAnswer(call -> new Location(world, 0, 0, 0));
		return player;
	}

	private BaseEntityShopObject<?> shop(Entity entity, EntityType type) {
		BaseEntityShopObject<?> shop = Mockito.mock(BaseEntityShopObject.class);
		Mockito.doReturn(entity).when(shop).getEntity();
		Mockito.when(shop.getEntityType()).thenReturn(type);
		return shop;
	}

	private void captureTasks() {
		Mockito.when(scheduler.runAtEntityLater(Mockito.any(Entity.class),
				Mockito.any(Runnable.class), Mockito.any(Runnable.class), Mockito.anyLong()))
				.thenAnswer(call -> {
					pending.add(call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
		Mockito.when(scheduler.runAtLocationLater(Mockito.any(Location.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
					pending.add(call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
	}

	private static void invoke(Object target, String name) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(target);
	}

	private static Object get(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private interface ConcurrentAction {
		Object run(int index) throws Exception;
	}

	private static void runConcurrent(int count, ConcurrentAction action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			List<Callable<Object>> actions = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				actions.add(() -> action.run(index));
			}

			for (Future<Object> result : executor.invokeAll(actions, 10, TimeUnit.SECONDS)) {
				result.get();
			}
		} finally {
			executor.shutdownNow();
		}
	}
}
