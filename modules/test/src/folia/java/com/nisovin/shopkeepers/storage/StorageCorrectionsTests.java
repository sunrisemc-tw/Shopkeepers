package com.nisovin.shopkeepers.storage;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.FoliaCorrectionsTests;
import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.ShopkeeperData;
import com.nisovin.shopkeepers.shopkeeper.registry.SKShopkeeperRegistry;
import com.nisovin.shopkeepers.util.data.serialization.InvalidDataException;
import com.nisovin.shopkeepers.util.logging.Log;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Executable snapshot, disk persistence, and stale-callback regression tests.
 */
public class StorageCorrectionsTests {

	private final FoliaCorrectionsTests environment = new FoliaCorrectionsTests();
	private SKShopkeepersPlugin plugin;
	private PlatformScheduler scheduler;
	private SKShopkeeperStorage storage;
	private Path folder;
	private Object previousVersion;
	private Object previousLogger;
	private final ConcurrentLinkedQueue<Runnable> global = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Runnable> region = new ConcurrentLinkedQueue<>();

	@Before
	public void setup() throws Exception {
		environment.setup();
		plugin = (SKShopkeepersPlugin) get(environment, "plugin");
		scheduler = (PlatformScheduler) get(environment, "scheduler");
		previousVersion = getStatic(DataVersion.class, "current");
		setStatic(DataVersion.class, "current", new DataVersion(4, 3, 4325));
		previousLogger = getStatic(Log.class, "logger");
		Log.setLogger(Logger.getLogger("StorageCorrectionsTests"));
		folder = Files.createTempDirectory("shopkeepers-storage-tests-");
		Mockito.when(plugin.getDataFolder()).thenReturn(folder.toFile());
		Mockito.when(scheduler.runLater(Mockito.any(Runnable.class), Mockito.anyLong()))
				.thenAnswer(call -> {
					global.add(call.getArgument(0));
					return Mockito.mock(WrappedTask.class);
				});
		Mockito.when(scheduler.runAtLocationLater(Mockito.any(Location.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
					region.add(call.getArgument(1));
					return Mockito.mock(WrappedTask.class);
				});
		storage = new SKShopkeeperStorage(plugin);
		Mockito.when(plugin.getShopkeeperStorage()).thenReturn(storage);
	}

	@After
	public void teardown() throws Exception {
		storage.onDisable();
		setStatic(DataVersion.class, "current", previousVersion);
		setStatic(Log.class, "logger", previousLogger);
		environment.teardown();
	}

	@Test
	public void ownerCapturePersistsAndDeletionSurvivesAnotherWrite() throws Exception {
		AbstractShopkeeper shop = shop(1, "first");
		storage.markDirty(shop);
		Assert.assertEquals(1, storage.getUnsavedDirtyShopkeepersCount());
		storage.saveImmediate();
		Assert.assertEquals("first", disk().getString("1.name"));
		Assert.assertFalse(storage.isDirty());
		storage.deleteShopkeeper(shop);
		storage.saveImmediate();
		Assert.assertFalse(disk().contains("1"));
		storage.saveImmediate();
		Assert.assertFalse(disk().contains("1"));
	}

	@Test
	public void nonFoliaStorageRetainsOriginalSynchronousSaveAndAcknowledgement() throws Exception {
		Mockito.when(plugin.getFoliaLib().isFolia()).thenReturn(false);
		Mockito.when(scheduler.isGlobalTickThread()).thenReturn(true);
		AbstractShopkeeper shop = shop(1, "spigot");
		Mockito.when(shop.isDirty()).thenReturn(true);
		storage.markDirty(shop);
		storage.saveImmediate();
		Assert.assertEquals("spigot", disk().getString("1.name"));
		Assert.assertFalse(storage.isDirty());
		Assert.assertNull(get(storage, "foliaWriter"));
		Mockito.verify(shop).onSave();
		Mockito.verify(shop, Mockito.never()).onSave(Mockito.anyLong());
	}

	@Test
	public void startupPreservesUnloadedAndBrokenRecordsThroughAnotherSave() throws Exception {
		SKShopkeeperRegistry registry = Mockito.mock(SKShopkeeperRegistry.class);
		Mockito.when(plugin.getShopkeeperRegistry()).thenReturn(registry);
		Mockito.doThrow(new InvalidDataException("Injected load failure")).when(registry)
				.loadShopkeeper(Mockito.any(ShopkeeperData.class));
		Files.createDirectories(folder.resolve("data"));
		Files.writeString(folder.resolve("data/save.yml"),
				"data-version: '4|3|4325'\n'1': {name: preserved}\nbroken: {opaque: keep}\n");
		Assert.assertTrue(storage.reload());
		storage.saveImmediate();
		Assert.assertEquals("preserved", disk().getString("1.name"));
		Assert.assertEquals("keep", disk().getString("broken.opaque"));
		Assert.assertEquals("4|3|4325", disk().getString("data-version"));
		Assert.assertTrue(storage.getNextShopkeeperId() > 1);
	}

	@Test
	public void foreignRegionSaveNowQueuesOwnerCaptureInsteadOfGlobalSerialization() throws Exception {
		AbstractShopkeeper shop = shop(1, "region");
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(false);
		storage.markDirty(shop);
		storage.saveNow();
		global.remove().run();
		Mockito.verify(shop, Mockito.never())
				.save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(true);
		while (!region.isEmpty()) region.remove().run();
		while (!global.isEmpty()) global.remove().run();
		storage.saveImmediate();
		Assert.assertEquals("region", disk().getString("1.name"));
	}

	@Test
	public void deletionRejectsQueuedOwnerCallback() throws Exception {
		AbstractShopkeeper shop = shop(1, "deleted");
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(false);
		storage.markDirty(shop);
		storage.deleteShopkeeper(shop);
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(true);
		region.remove().run();
		storage.saveImmediate();
		Assert.assertFalse(disk().contains("1"));
		Mockito.verify(shop, Mockito.never())
				.save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
	}

	@Test
	public void failedSnapshotRetainsPreviouslyCapturedRecordAndDirtyVersion() throws Exception {
		AbstractShopkeeper shop = shop(1, "safe");
		storage.markDirty(shop);
		Mockito.when(shop.getPersistenceVersion()).thenReturn(2L);
		Mockito.doThrow(new IllegalStateException("Injected snapshot failure")).when(shop)
				.save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
		storage.markDirty(shop);
		storage.saveImmediate();
		Assert.assertEquals("safe", disk().getString("1.name"));
		Assert.assertTrue(storage.isDirty());
		Assert.assertEquals(1, storage.getUnsavedDirtyShopkeepersCount());
	}

	@Test
	public void disableFlushesCapturedDataWithoutWaitingForStoppedRegions() throws Exception {
		storage.markDirty(shop(1, "captured"));
		AbstractShopkeeper unavailable = shop(2, "pending");
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(false);
		storage.markDirty(unavailable);
		Mockito.when(plugin.isEnabled()).thenReturn(false);
		storage.onDisable();
		Assert.assertEquals("captured", disk().getString("1.name"));
		Assert.assertFalse(disk().contains("2"));
		Assert.assertEquals(1, storage.getUnsavedDirtyShopkeepersCount());
		region.remove().run();
		Mockito.verify(unavailable, Mockito.never())
				.save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
	}

	@Test
	public void mutationDuringCaptureCannotAcknowledgeNewerVersion() throws Exception {
		AbstractShopkeeper shop = shop(1, "old");
		AtomicLong version = new AtomicLong(1);
		Mockito.when(shop.getPersistenceVersion()).thenAnswer(call -> version.get());
		Mockito.doAnswer(call -> {
			call.getArgument(0, ShopkeeperData.class).set("name", "old");
			version.incrementAndGet();
			return null;
		}).when(shop).save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
		storage.markDirty(shop);
		Mockito.verify(shop, Mockito.never()).onSave(Mockito.anyLong());
		Assert.assertTrue(storage.isDirty());
		storage.saveImmediate();
		Assert.assertFalse(disk().contains("1"));
	}

	@Test
	public void uniqueReservationsAcrossEightConcurrentCreators() throws Exception {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Set<Integer>>> actions = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				actions.add(() -> {
					Set<Integer> ids = new HashSet<>();
					for (int j = 0; j < 1000; j++) ids.add(coordinator.reserveId());
					return ids;
				});
			}

			Set<Integer> all = new HashSet<>();
			for (Future<Set<Integer>> result : executor.invokeAll(actions, 10, TimeUnit.SECONDS)) {
				Set<Integer> ids = result.get();
				Assert.assertEquals(1000, ids.size());
				for (int id : ids) Assert.assertTrue(all.add(id));
			}
			Assert.assertEquals(8000, all.size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void failedConstructionReleasesReservation() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		int id = coordinator.reserveId();
		coordinator.releaseId(id);
		Assert.assertEquals(id, coordinator.reserveId());
	}

	@Test
	public void oldVersionCannotReplaceAlreadyTransferredNewVersion() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		Object owner = new Object();
		var latest = coordinator.markDirty(1, owner, 2);
		coordinator.publish(latest, "'1': {name: latest}\n");
		coordinator.acknowledge(coordinator.prepare(""));
		Assert.assertNull(coordinator.markDirty(1, owner, 1));
		Assert.assertNull(coordinator.markDirty(1, owner, 2));
		Assert.assertFalse(coordinator.isDirty());
		Assert.assertTrue(coordinator.prepare("").data().contains("latest"));
	}

	@Test
	public void idOverflowSkipsLoadedAndReservedIds() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		coordinator.seed("1", "'1': {}\n");
		coordinator.seed(String.valueOf(Integer.MAX_VALUE), "'2147483647': {}\n");
		Assert.assertEquals(2, coordinator.reserveId());
		Assert.assertEquals(3, coordinator.reserveId());
	}

	@Test
	public void queuedCoordinatorAfterDisableCannotRestartWriter() throws Exception {
		storage.markDirty(shop(1, "shutdown"));
		storage.saveNow();
		storage.onDisable();
		String before = Files.readString(folder.resolve("data/save.yml"));
		while (!global.isEmpty()) global.remove().run();
		Assert.assertNull(get(storage, "foliaWriter"));
		Assert.assertEquals(before, Files.readString(folder.resolve("data/save.yml")));
	}

	@Test
	public void immediateSavePersistsLateOwnerSnapshotWithoutAnotherExplicitRequest() throws Exception {
		AbstractShopkeeper shop = shop(1, "late");
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(false);
		storage.markDirty(shop);
		storage.saveImmediate();
		Assert.assertTrue(storage.isDirty());
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class)))
				.thenReturn(true);
		while (!region.isEmpty()) region.remove().run();
		global.remove().run();
		((Future<?>) get(storage, "foliaWrite")).get(10, TimeUnit.SECONDS);
		Assert.assertEquals("late", disk().getString("1.name"));
		Assert.assertFalse(storage.isDirty());
	}

	@Test
	public void immediateSaveInvalidatesPreviouslyQueuedDelayedCoordinator() throws Exception {
		boolean previous = Settings.saveInstantly;
		Settings.saveInstantly = true;
		try {
			storage.markDirty(shop(1, "immediate"));
			storage.saveDelayed();
			storage.saveImmediate();
			Future<?> write = (Future<?>) get(storage, "foliaWrite");
			global.remove().run();
			Assert.assertSame(write, get(storage, "foliaWrite"));
			Assert.assertEquals("immediate", disk().getString("1.name"));
			Assert.assertFalse(storage.isDirty());
		} finally {
			Settings.saveInstantly = previous;
		}
	}

	@Test
	public void shutdownFlushRejectsConcurrentSynchronousWriterSubmission() throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		CountDownLatch lifecycleFlush = new CountDownLatch(1);
		AtomicInteger submissions = new AtomicInteger();
		ExecutorService writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>()) {
			@Override
			public void execute(Runnable work) {
				super.execute(work);
				if (submissions.incrementAndGet() == 3) lifecycleFlush.countDown();
			}
		};
		writer.submit(() -> {
			try {
				gate.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		set(storage, "foliaWriter", writer);
		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			storage.markDirty(shop(1, "captured"));
			storage.saveNow();
			global.remove().run();
			Future<?> shutdown = callers.submit(storage::onDisable);
			Assert.assertTrue(lifecycleFlush.await(10, TimeUnit.SECONDS));
			Future<?> finalWrite;
			synchronized (get(storage, "foliaSaveLock")) {
				finalWrite = (Future<?>) get(storage, "foliaWrite");
			}

			callers.submit(storage::saveImmediate).get(2, TimeUnit.SECONDS);
			Assert.assertSame(finalWrite, get(storage, "foliaWrite"));
			Assert.assertEquals(3, submissions.get());
			gate.countDown();
			shutdown.get(10, TimeUnit.SECONDS);
			Assert.assertEquals("captured", disk().getString("1.name"));
			Assert.assertNull(get(storage, "foliaWriter"));
		} finally {
			gate.countDown();
			callers.shutdownNow();
		}
	}

	@Test
	public void delayedSaveCoalescesMutationsAndExplicitSaveNowExpeditesIt() throws Exception {
		boolean previous = Settings.saveInstantly;
		Settings.saveInstantly = true;
		try {
			AbstractShopkeeper changing = shop(1, "before");
			storage.markDirty(changing);
			storage.saveDelayed();
			Mockito.when(changing.getPersistenceVersion()).thenReturn(2L);
			Mockito.doAnswer(call -> {
				call.getArgument(0, ShopkeeperData.class).set("name", "after");
				return null;
			}).when(changing).save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
			storage.markDirty(changing);
			Mockito.verify(scheduler, Mockito.never())
					.runLater(Mockito.any(Runnable.class), Mockito.eq(0L));
			Mockito.verify(scheduler).runLater(Mockito.any(Runnable.class), Mockito.eq(600L));
			storage.saveNow();
			global.remove().run();
			Assert.assertNull(get(storage, "foliaWriter"));
			global.remove().run();
			storage.saveIfDirtyAndAwaitCompletion();
			Assert.assertEquals("after", disk().getString("1.name"));
		} finally {
			Settings.saveInstantly = previous;
		}
	}

	@Test
	public void staleSnapshotsAndOldDiskAcknowledgementsCannotClearNewerChanges() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		Object owner = new Object();
		var first = coordinator.markDirty(1, owner, 1);
		Assert.assertTrue(coordinator.publish(first, "'1': {name: first}\n"));
		var write = coordinator.prepare("");
		var second = coordinator.markDirty(1, owner, 2);
		Assert.assertFalse(coordinator.publish(first, "'1': {name: stale}\n"));
		Assert.assertTrue(coordinator.publish(second, "'1': {name: second}\n"));
		coordinator.acknowledge(write);
		Assert.assertTrue(coordinator.isDirty());
		var latest = coordinator.prepare("");
		Assert.assertTrue(latest.data().contains("second"));
		Assert.assertFalse(latest.data().contains("stale"));
		coordinator.acknowledge(latest);
		Assert.assertFalse(coordinator.isDirty());
	}

	@Test
	public void latePublicationCannotBeAcknowledgedByEarlierIncompleteWrite() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		var ticket = coordinator.markDirty(1, new Object(), 1);
		var incomplete = coordinator.prepare("");
		Assert.assertTrue(coordinator.publish(ticket, "'1': {name: late}\n"));
		var captured = coordinator.prepare("");
		coordinator.acknowledge(incomplete);
		Assert.assertTrue(coordinator.isDirty());
		Assert.assertEquals(1, coordinator.dirtyCount());
		var retry = coordinator.prepare("");
		Assert.assertEquals(captured.data(), retry.data());
		coordinator.acknowledge(retry);
		Assert.assertFalse(coordinator.isDirty());
	}

	@Test
	public void failedWriteCanRetrySnapshotsAndDeletionsWithoutResurrection() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		coordinator.seed("1", "'1': {name: loaded}\n");
		coordinator.seed("broken", "broken: {opaque: preserved}\n");
		var ticket = coordinator.markDirty(2, new Object(), 1);
		coordinator.publish(ticket, "'2': {name: new}\n");
		coordinator.delete(1);
		var failedWrite = coordinator.prepare("");
		Assert.assertTrue(coordinator.isDirty());
		Assert.assertEquals(1, coordinator.deletedCount());
		var retry = coordinator.prepare("");
		Assert.assertEquals(failedWrite.data(), retry.data());
		Assert.assertTrue(retry.data().contains("preserved"));
		Assert.assertFalse(retry.data().contains("loaded"));
		coordinator.acknowledge(retry);
		Assert.assertFalse(coordinator.isDirty());
	}

	@Test
	public void failedDiskWriteRetainsSnapshotAndDeletionUntilSuccessfulRetry() throws Exception {
		AbstractShopkeeper existing = shop(1, "existing");
		storage.markDirty(existing);
		storage.saveImmediate();
		storage.markDirty(shop(2, "new"));
		storage.deleteShopkeeper(existing);
		Path blocked = Files.createDirectory(folder.resolve("blocked"));
		Files.writeString(blocked.resolve("sentinel"), "keep");
		set(storage, "saveFile", blocked);
		Logger logger = Log.getLogger();
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.OFF);
		try {
			storage.saveImmediate();
		} finally {
			logger.setLevel(previousLevel);
		}
		Assert.assertTrue(storage.isDirty());
		Assert.assertEquals(1, storage.getUnsavedDirtyShopkeepersCount());
		Assert.assertEquals(1, storage.getUnsavedDeletedShopkeepersCount());
		Assert.assertEquals("keep", Files.readString(blocked.resolve("sentinel")));
		Assert.assertEquals("existing", disk().getString("1.name"));
		set(storage, "saveFile", folder.resolve("data/save.yml"));
		storage.saveImmediate();
		Assert.assertFalse(disk().contains("1"));
		Assert.assertEquals("new", disk().getString("2.name"));
		Assert.assertFalse(storage.isDirty());
	}

	@Test
	public void asyncWriteAcknowledgementKeepsNewerMutationAndDeletionPending() throws Exception {
		ExecutorService writer = Executors.newSingleThreadExecutor();
		CountDownLatch gate = new CountDownLatch(1);
		writer.submit(() -> {
			try {
				gate.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		set(storage, "foliaWriter", writer);
		try {
			AbstractShopkeeper changing = shop(1, "before");
			AbstractShopkeeper deleted = shop(2, "deleted");
			storage.markDirty(changing);
			storage.markDirty(deleted);
			storage.saveNow();
			global.remove().run();
			Future<?> firstWrite = (Future<?>) get(storage, "foliaWrite");
			Mockito.when(changing.getPersistenceVersion()).thenReturn(2L);
			Mockito.doAnswer(call -> {
				call.getArgument(0, ShopkeeperData.class).set("name", "after");
				return null;
			}).when(changing).save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
			storage.markDirty(changing);
			storage.deleteShopkeeper(deleted);
			gate.countDown();
			firstWrite.get(10, TimeUnit.SECONDS);
			Assert.assertEquals("before", disk().getString("1.name"));
			Assert.assertEquals("deleted", disk().getString("2.name"));
			Assert.assertEquals(1, storage.getUnsavedDirtyShopkeepersCount());
			Assert.assertEquals(1, storage.getUnsavedDeletedShopkeepersCount());
			Assert.assertTrue(storage.isDirty());
			storage.saveImmediate();
			Assert.assertEquals("after", disk().getString("1.name"));
			Assert.assertFalse(disk().contains("2"));
			Assert.assertFalse(storage.isDirty());
		} finally {
			gate.countDown();
		}
	}

	@Test
	public void coalescedSaveAtWriterCompletionDoesNotLoseFollowupWrite() throws Exception {
		ExecutorService writer = Executors.newSingleThreadExecutor();
		CountDownLatch gate = new CountDownLatch(1);
		writer.submit(() -> {
			try {
				gate.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		set(storage, "foliaWriter", writer);
		try {
			AbstractShopkeeper changing = shop(1, "before");
			storage.markDirty(changing);
			storage.saveNow();
			global.remove().run();
			Future<?> firstWrite = (Future<?>) get(storage, "foliaWrite");
			Mockito.when(changing.getPersistenceVersion()).thenReturn(2L);
			Mockito.doAnswer(call -> {
				call.getArgument(0, ShopkeeperData.class).set("name", "after");
				return null;
			}).when(changing).save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
			storage.markDirty(changing);
			global.remove().run();
			Assert.assertSame(firstWrite, get(storage, "foliaWrite"));
			Mockito.when(scheduler.runLater(Mockito.any(Runnable.class), Mockito.anyLong()))
					.thenAnswer(call -> {
						call.getArgument(0, Runnable.class).run();
						return Mockito.mock(WrappedTask.class);
					});
			gate.countDown();
			firstWrite.get(10, TimeUnit.SECONDS);
			Future<?> followup = (Future<?>) get(storage, "foliaWrite");
			Assert.assertNotSame(firstWrite, followup);
			followup.get(10, TimeUnit.SECONDS);
			Assert.assertEquals("after", disk().getString("1.name"));
			Assert.assertFalse(storage.isDirty());
		} finally {
			gate.countDown();
		}
	}

	@Test
	public void olderWriteCannotAcknowledgeNewerDeletion() {
		FoliaStorageCoordinator coordinator = new FoliaStorageCoordinator();
		coordinator.seed("1", "'1': {name: loaded}\n");
		var beforeDeletion = coordinator.prepare("");
		coordinator.delete(1);
		coordinator.acknowledge(beforeDeletion);
		Assert.assertTrue(coordinator.isDirty());
		Assert.assertEquals(1, coordinator.deletedCount());
		var deletion = coordinator.prepare("");
		Assert.assertFalse(deletion.data().contains("loaded"));
		coordinator.acknowledge(deletion);
		Assert.assertFalse(coordinator.isDirty());
	}

	@Test
	public void realShopkeeperDirtyAcknowledgementIsVersionConditional() {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class);
		Mockito.doCallRealMethod().when(shop).markDirty();
		Mockito.doCallRealMethod().when(shop).isDirty();
		Mockito.doCallRealMethod().when(shop).getPersistenceVersion();
		Mockito.doCallRealMethod().when(shop).onSave(Mockito.anyLong());
		shop.markDirty();
		long oldVersion = shop.getPersistenceVersion();
		shop.markDirty();
		shop.onSave(oldVersion);
		Assert.assertTrue(shop.isDirty());
		shop.onSave(shop.getPersistenceVersion());
		Assert.assertFalse(shop.isDirty());
	}

	private AbstractShopkeeper shop(int id, String name) {
		AbstractShopkeeper shop = Mockito.mock(AbstractShopkeeper.class);
		World world = (World) getUnchecked(environment, "world");
		Mockito.when(shop.isValid()).thenReturn(true);
		Mockito.when(shop.getId()).thenReturn(id);
		Mockito.when(shop.getLocation()).thenReturn(new Location(world, id * 10000, 64, 0));
		Mockito.when(shop.getPersistenceVersion()).thenReturn(1L);
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class))).thenReturn(true);
		Mockito.doAnswer(call -> {
			call.getArgument(0, ShopkeeperData.class).set("name", name);
			return null;
		}).when(shop).save(Mockito.any(ShopkeeperData.class), Mockito.anyBoolean());
		return shop;
	}

	private YamlConfiguration disk() throws Exception {
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString(Files.readString(folder.resolve("data/save.yml")));
		return config;
	}

	private static Object getUnchecked(Object target, String name) {
		try {
			return get(target, name);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
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

	private static Object getStatic(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static void setStatic(Class<?> type, String name, Object value) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}
}
