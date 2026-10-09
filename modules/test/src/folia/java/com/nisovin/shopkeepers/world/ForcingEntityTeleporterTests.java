package com.nisovin.shopkeepers.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

public class ForcingEntityTeleporterTests {

	private ForcingEntityTeleporter teleporter;
	private PlatformScheduler scheduler;
	private FoliaLib folia;

	@Before
	public void setup() {
		SKShopkeepersPlugin plugin = Mockito.mock(SKShopkeepersPlugin.class);
		folia = Mockito.mock(FoliaLib.class);
		scheduler = Mockito.mock(PlatformScheduler.class);
		Mockito.when(plugin.getFoliaLib()).thenReturn(folia);
		Mockito.when(plugin.isEnabled()).thenReturn(true);
		Mockito.when(folia.isFolia()).thenReturn(true);
		Mockito.when(folia.getScheduler()).thenReturn(scheduler);
		teleporter = new ForcingEntityTeleporter(plugin);
	}

	private Entity entity() {
		Entity entity = Mockito.mock(Entity.class);
		Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
		return entity;
	}

	private EntityTeleportEvent event(Entity entity) {
		EntityTeleportEvent event = Mockito.mock(EntityTeleportEvent.class);
		Mockito.when(event.getEntity()).thenReturn(entity);
		return event;
	}

	@Test
	public void independentPendingEventsSurviveOtherEntitiesAndCopyDestination() throws Exception {
		Entity a = entity();
		Entity b = entity();
		CompletableFuture<Boolean> first = new CompletableFuture<>();
		CompletableFuture<Boolean> second = new CompletableFuture<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(a), Mockito.any(Location.class)))
				.thenReturn(first);
		Mockito.when(scheduler.teleportAsync(Mockito.same(b), Mockito.any(Location.class)))
				.thenReturn(second);
		Location target = new Location(null, 1, 2, 3);
		CompletableFuture<Boolean> result = teleporter.teleport(a, target);
		Thread otherRegion = new Thread(() -> teleporter.teleport(b, new Location(null, 4, 5, 6)));
		otherRegion.start();
		otherRegion.join();
		target.setWorld(null);
		target.setX(99);
		EntityTeleportEvent unrelated = event(entity());
		teleporter.onEntityTeleport(unrelated);
		Mockito.verify(unrelated, Mockito.never()).setCancelled(Mockito.anyBoolean());
		EntityTeleportEvent eventA = event(a);
		EntityTeleportEvent eventB = event(b);
		teleporter.onEntityTeleport(eventB);
		teleporter.onEntityTeleport(eventA);
		Mockito.verify(eventA).setTo(new Location(null, 1, 2, 3));
		Mockito.verify(eventB).setTo(new Location(null, 4, 5, 6));
		Assert.assertFalse(result.isDone());
		Assert.assertFalse(teleporter.teleport(a, target).getNow(true));
		first.complete(false);
		second.complete(false);
		Assert.assertFalse(result.getNow(true));
		EntityTeleportEvent after = event(a);
		teleporter.onEntityTeleport(after);
		Mockito.verify(after, Mockito.never()).setCancelled(Mockito.anyBoolean());
	}

	@Test
	public void successWaitsForEntitySchedulerAndRetirementCompletesFalse() {
		Entity entity = entity();
		CompletableFuture<Boolean> nativeResult = new CompletableFuture<>();
		AtomicReference<Runnable> update = new AtomicReference<>();
		AtomicReference<Runnable> retired = new AtomicReference<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenReturn(nativeResult);
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(entity), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(1L))).thenAnswer(call -> {
			update.set(call.getArgument(1));
			retired.set(call.getArgument(2));
			return Mockito.mock(WrappedTask.class);
		});
		CompletableFuture<Boolean> result = teleporter.teleport(entity, new Location(null, 1, 2, 3));
		nativeResult.complete(true);
		Assert.assertFalse(result.isDone());
		Mockito.verify(entity, Mockito.never()).setCustomNameVisible(Mockito.anyBoolean());
		update.get().run();
		Assert.assertTrue(result.getNow(false));
		Mockito.verify(entity).setCustomNameVisible(true);
		Mockito.verify(entity).setCustomNameVisible(false);
		Mockito.verify(entity, Mockito.never()).teleport(Mockito.any(Location.class));

		CompletableFuture<Boolean> result2 = teleporter.teleport(entity, new Location(null, 3, 2, 1));
		retired.get().run();
		Assert.assertFalse(result2.getNow(true));
	}

	@Test
	public void exceptionalCompletionCleansEventAndAllowsRetry() {
		Entity entity = entity();
		CompletableFuture<Boolean> nativeResult = new CompletableFuture<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenReturn(nativeResult);
		CompletableFuture<Boolean> result = teleporter.teleport(entity, new Location(null, 1, 2, 3));
		nativeResult.completeExceptionally(new IllegalStateException("failed"));
		Assert.assertTrue(result.isCompletedExceptionally());
		EntityTeleportEvent after = event(entity);
		teleporter.onEntityTeleport(after);
		Mockito.verify(after, Mockito.never()).setCancelled(Mockito.anyBoolean());
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenReturn(CompletableFuture.completedFuture(false));
		Assert.assertFalse(teleporter.teleport(entity, new Location(null, 2, 3, 4)).getNow(true));
	}

	@Test
	public void cancellingReturnedFutureDoesNotReleaseNativeTeleportContext() {
		Entity entity = entity();
		CompletableFuture<Boolean> nativeResult = new CompletableFuture<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenReturn(nativeResult);
		CompletableFuture<Boolean> result = teleporter.teleport(entity, new Location(null, 1, 2, 3));
		result.cancel(false);
		Assert.assertFalse(teleporter.teleport(entity, new Location(null, 2, 3, 4)).getNow(true));
		EntityTeleportEvent during = event(entity);
		teleporter.onEntityTeleport(during);
		Mockito.verify(during).setCancelled(false);
		nativeResult.complete(false);
		Mockito.when(scheduler.teleportAsync(Mockito.same(entity), Mockito.any(Location.class)))
				.thenReturn(CompletableFuture.completedFuture(false));
		teleporter.teleport(entity, new Location(null, 2, 3, 4));
		Mockito.verify(scheduler, Mockito.times(2))
				.teleportAsync(Mockito.same(entity), Mockito.any(Location.class));
	}

	@Test
	public void spigotStillCompletesImmediatelyAndTogglesOnFailure() {
		Mockito.when(folia.isFolia()).thenReturn(false);
		Entity entity = entity();
		Mockito.when(entity.teleport(Mockito.any(Location.class))).thenReturn(false);
		CompletableFuture<Boolean> result = teleporter.teleport(entity, new Location(null, 1, 2, 3));
		Assert.assertTrue(result.isDone());
		Assert.assertFalse(result.getNow(true));
		Mockito.verify(entity).setCustomNameVisible(true);
		Mockito.verify(entity).setCustomNameVisible(false);
		Mockito.verify(scheduler, Mockito.never()).teleportAsync(Mockito.any(), Mockito.any());
	}
}
