package com.nisovin.shopkeepers.shopkeeper.registry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.FoliaCorrectionsTests;
import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperAddedEvent;
import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopType;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObject;
import com.nisovin.shopkeepers.shopobjects.AbstractShopObjectType;
import com.nisovin.shopkeepers.storage.SKShopkeeperStorage;
import com.tcoded.folialib.impl.PlatformScheduler;

public class RegistryCorrectionsTests {

	private final FoliaCorrectionsTests environment = new FoliaCorrectionsTests();

	@Before
	public void setup() throws Exception {
		environment.setup();
	}

	@After
	public void teardown() throws Exception {
		environment.teardown();
	}

	@Test
	public void concurrentChunkMutationsKeepImmutableWorldAndChunkSnapshots() throws Exception {
		Object lock = new Object();
		ShopkeeperChunkMap map = new ShopkeeperChunkMap(lock, true,
				new ShopkeeperChunkMap.ChangeListener() {
					@Override
					public void onChunkAdded(ChunkShopkeepers chunk) {
						Assert.assertFalse(Thread.holdsLock(lock));
					}

					@Override
					public void onChunkRemoved(ChunkShopkeepers chunk) {
						Assert.assertFalse(Thread.holdsLock(lock));
					}
				});
		List<AbstractShopkeeper> shops = new ArrayList<>();
		for (int i = 0; i < 800; i++) shops.add(shop(i / 100, i));
		concurrent(index -> {
			for (int i = index; i < shops.size(); i += 8) map.addShopkeeper(shops.get(i));
			return null;
		});
		WorldShopkeepers world = map.getWorldShopkeepers("world");
		Assert.assertEquals(800, world.getShopkeeperCount());
		Assert.assertEquals(8, world.getShopkeepersByChunk().size());
		Collection<? extends AbstractShopkeeper> snapshot = world.getShopkeepers();
		Collection<? extends String> worlds = map.getWorldsWithShopkeepers();
		concurrent(index -> {
			for (int i = index; i < shops.size(); i += 8) {
				for (AbstractShopkeeper ignored : world.getShopkeepers()) {
					Assert.assertNotNull(ignored);
				}
				map.removeShopkeeper(shops.get(i));
			}
			return null;
		});
		Assert.assertEquals(800, snapshot.size());
		Assert.assertEquals(1, worlds.size());
		Assert.assertEquals(0, world.getShopkeeperCount());
		Assert.assertTrue(map.getWorldsWithShopkeepers().isEmpty());
		Assert.assertTrue(world.getShopkeepersByChunk().isEmpty());
	}

	@Test
	public void spigotRetainsLiveCollectionViews() {
		ShopkeeperChunkMap map = new ShopkeeperChunkMap();
		AbstractShopkeeper first = shop(0, 1);
		map.addShopkeeper(first);
		WorldShopkeepers world = map.getWorldShopkeepers("world");
		Collection<? extends AbstractShopkeeper> view = world.getShopkeepers();
		map.addShopkeeper(shop(0, 2));
		Assert.assertEquals(2, view.size());
		Assert.assertEquals(2, world.getShopkeepersByChunk().values().iterator().next().size());
	}

	@Test
	public void uuidAndIdCommitIsAtomicAndEventsRunOutsideBookkeepingLock() throws Exception {
		SKShopkeepersPlugin plugin = (SKShopkeepersPlugin) get(environment, "plugin");
		Server server = (Server) get(environment, "server");
		SKShopkeeperStorage storage = Mockito.mock(SKShopkeeperStorage.class);
		Mockito.when(plugin.getShopkeeperStorage()).thenReturn(storage);
		SKShopkeeperRegistry registry = new SKShopkeeperRegistry(plugin);
		PluginManager manager = Mockito.mock(PluginManager.class);
		Mockito.when(server.getPluginManager()).thenReturn(manager);
		Object lock = get(registry, "indexLock");
		Mockito.doAnswer(call -> {
			Assert.assertFalse(Thread.holdsLock(lock));
			ShopkeeperAddedEvent event = call.getArgument(0);
			AbstractShopkeeper shop = (AbstractShopkeeper) event.getShopkeeper();
			Assert.assertSame(shop, registry.getShopkeeperById(shop.getId()));
			Assert.assertSame(shop, registry.getShopkeeperByUniqueId(shop.getUniqueId()));
			return null;
		}).when(manager).callEvent(Mockito.any(ShopkeeperAddedEvent.class));
		UUID uuid = UUID.randomUUID();
		AbstractShopkeeper first = virtualShop(1, uuid);
		AbstractShopkeeper duplicate = virtualShop(2, uuid);
		Method add = SKShopkeeperRegistry.class.getDeclaredMethod("addShopkeeper",
				AbstractShopkeeper.class, ShopkeeperAddedEvent.Cause.class);
		add.setAccessible(true);
		add.invoke(registry, first, ShopkeeperAddedEvent.Cause.CREATED);
		Collection<? extends AbstractShopkeeper> snapshot = registry.getAllShopkeepers();
		Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,
				() -> add.invoke(registry, duplicate, ShopkeeperAddedEvent.Cause.CREATED));
		Assert.assertEquals(1, registry.getAllShopkeepers().size());
		Assert.assertNull(registry.getShopkeeperById(2));
		Assert.assertEquals(1, snapshot.size());
	}

	@Test
	public void concurrentDuplicateUuidCommitsExactlyOneIdPair() throws Exception {
		SKShopkeepersPlugin plugin = (SKShopkeepersPlugin) get(environment, "plugin");
		Server server = (Server) get(environment, "server");
		Mockito.when(plugin.getShopkeeperStorage()).thenReturn(Mockito.mock(SKShopkeeperStorage.class));
		Mockito.when(server.getPluginManager()).thenReturn(Mockito.mock(PluginManager.class));
		SKShopkeeperRegistry registry = new SKShopkeeperRegistry(plugin);
		Method add = SKShopkeeperRegistry.class.getDeclaredMethod("addShopkeeper",
				AbstractShopkeeper.class, ShopkeeperAddedEvent.Cause.class);
		add.setAccessible(true);
		UUID uuid = UUID.randomUUID();
		List<AbstractShopkeeper> shops = new ArrayList<>();
		for (int i = 0; i < 8; i++) shops.add(virtualShop(i + 1, uuid));
		AtomicInteger successes = new AtomicInteger();
		concurrent(index -> {
			try {
				add.invoke(registry, shops.get(index), ShopkeeperAddedEvent.Cause.CREATED);
				successes.incrementAndGet();
			} catch (java.lang.reflect.InvocationTargetException e) {
				Assert.assertTrue(e.getCause() instanceof IllegalArgumentException);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			return null;
		});
		Assert.assertEquals(1, successes.get());
		Assert.assertEquals(1, registry.getAllShopkeepers().size());
		AbstractShopkeeper winner = registry.getShopkeeperByUniqueId(uuid);
		Assert.assertSame(winner, registry.getShopkeeperById(winner.getId()));
	}

	@Test
	public void synchronousCreationRejectsForeignRegionBeforeReservingOrConstructing() throws Exception {
		SKShopkeepersPlugin plugin = (SKShopkeepersPlugin) get(environment, "plugin");
		PlatformScheduler scheduler = (PlatformScheduler) get(environment, "scheduler");
		SKShopkeeperStorage storage = Mockito.mock(SKShopkeeperStorage.class);
		Mockito.when(plugin.getShopkeeperStorage()).thenReturn(storage);
		SKShopkeeperRegistry registry = new SKShopkeeperRegistry(plugin);
		ShopCreationData creation = Mockito.mock(ShopCreationData.class);
		World world = (World) get(environment, "world");
		Mockito.when(creation.getSpawnLocation()).thenReturn(new Location(world, 0, 64, 0));
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class))).thenReturn(false);
		Assert.assertThrows(IllegalStateException.class, () -> registry.createShopkeeper(creation));
		Mockito.verifyNoInteractions(storage);
		Mockito.verify(creation, Mockito.never()).getShopType();
	}

	private AbstractShopkeeper virtualShop(int id, UUID uuid) {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class);
		Mockito.when(shop.getId()).thenReturn(id);
		Mockito.when(shop.getUniqueId()).thenReturn(uuid);
		Mockito.when(shop.isVirtual()).thenReturn(true);
		AbstractShopType<?> type = Mockito.mock(AbstractShopType.class);
		Mockito.when(type.isEnabled()).thenReturn(true);
		Mockito.doReturn(type).when(shop).getType();
		AbstractShopObject object = Mockito.mock(AbstractShopObject.class);
		AbstractShopObjectType<?> objectType = Mockito.mock(AbstractShopObjectType.class);
		Mockito.when(objectType.isEnabled()).thenReturn(true);
		Mockito.doReturn(objectType).when(object).getType();
		Mockito.doReturn(object).when(shop).getShopObject();
		return shop;
	}

	private AbstractShopkeeper shop(int chunkX, int id) {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class);
		ChunkCoords coords = new ChunkCoords("world", chunkX, 0);
		Mockito.when(shop.getId()).thenReturn(id);
		Mockito.when(shop.getWorldName()).thenReturn("world");
		Mockito.when(shop.getChunkCoords()).thenReturn(coords);
		AtomicReference<ChunkCoords> last = new AtomicReference<>();
		Mockito.when(shop.getLastChunkCoords()).thenAnswer(call -> last.get());
		Mockito.doAnswer(call -> {
			last.set(call.getArgument(0));
			return null;
		}).when(shop).setLastChunkCoords(Mockito.any());
		return shop;
	}

	private static Object get(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void concurrent(java.util.function.IntFunction<Object> action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Object>> work = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				int index = i;
				work.add(() -> action.apply(index));
			}
			for (Future<Object> future : executor.invokeAll(work, 20, TimeUnit.SECONDS)) future.get();
		} finally {
			executor.shutdownNow();
		}
	}
}
