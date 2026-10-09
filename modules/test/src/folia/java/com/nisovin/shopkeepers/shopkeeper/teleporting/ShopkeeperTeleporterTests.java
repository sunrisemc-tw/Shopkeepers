package com.nisovin.shopkeepers.shopkeeper.teleporting;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

public class ShopkeeperTeleporterTests {

	private Object previousPlugin;
	private PlatformScheduler scheduler;
	private Player player;
	private Shopkeeper shop;

	@Before
	public void setup() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		previousPlugin = field.get(null);
		SKShopkeepersPlugin plugin = Mockito.mock(SKShopkeepersPlugin.class);
		FoliaLib folia = Mockito.mock(FoliaLib.class);
		scheduler = Mockito.mock(PlatformScheduler.class);
		Mockito.when(plugin.getFoliaLib()).thenReturn(folia);
		Mockito.when(plugin.isEnabled()).thenReturn(true);
		Mockito.when(folia.isFolia()).thenReturn(true);
		Mockito.when(folia.getPlugin()).thenReturn(plugin);
		Mockito.when(folia.getScheduler()).thenReturn(scheduler);
		field.set(null, plugin);
		player = Mockito.mock(Player.class);
		shop = Mockito.mock(Shopkeeper.class);
	}

	@After
	public void teardown() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		field.set(null, previousPlugin);
	}

	@Test
	public void retiredPlayerCompletesFalseWithoutReadingEntity() {
		AtomicReference<Runnable> retired = new AtomicReference<>();
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(player), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(1L))).thenAnswer(call -> {
			retired.set(call.getArgument(2));
			return Mockito.mock(WrappedTask.class);
		});
		CompletableFuture<Boolean> result = ShopkeeperTeleporter.teleport(player, shop, true, null);
		Assert.assertFalse(result.isDone());
		retired.get().run();
		Assert.assertFalse(result.getNow(true));
		Mockito.verifyNoInteractions(player, shop);
	}

	@Test
	public void forcedPlayerTeleportUsesSnapshotsAndActualAsyncResult() {
		World world = Mockito.mock(World.class);
		Block air = Mockito.mock(Block.class);
		Mockito.when(world.getBlockAt(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
				.thenReturn(air);
		Mockito.when(world.getBlockAt(Mockito.any(Location.class))).thenReturn(air);
		Mockito.when(air.getRelative(0, -1, 0)).thenReturn(air);
		Mockito.when(air.getType()).thenReturn(Material.AIR);
		Mockito.when(shop.getLocation()).thenReturn(new Location(world, 0.5, 65, 0.5));
		Mockito.when(player.getBoundingBox()).thenReturn(new BoundingBox(0, 0, 0, 0.6, 1.8, 0.6));
		Mockito.when(player.getName()).thenReturn("Player");
		Mockito.when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
		Mockito.when(shop.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
		Mockito.when(shop.getDisplayName()).thenReturn("Shop");
		Mockito.when(scheduler.isOwnedByCurrentRegion(Mockito.any(Location.class))).thenReturn(true);
		Mockito.when(scheduler.runAtLocationLater(Mockito.any(Location.class),
				Mockito.any(Runnable.class), Mockito.eq(0L))).thenAnswer(call -> {
			((Runnable) call.getArgument(1)).run();
			return Mockito.mock(WrappedTask.class);
		});
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(player), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.eq(1L))).thenAnswer(call -> {
			((Runnable) call.getArgument(1)).run();
			return Mockito.mock(WrappedTask.class);
		});
		CompletableFuture<Boolean> nativeResult = new CompletableFuture<>();
		Mockito.when(scheduler.teleportAsync(Mockito.same(player), Mockito.any(Location.class)))
				.thenReturn(nativeResult);
		CompletableFuture<Boolean> result = ShopkeeperTeleporter.teleport(player, shop, true, null);
		if (result.isCompletedExceptionally()) result.getNow(false);
		Assert.assertFalse(result.isDone());
		Mockito.verify(player, Mockito.never()).teleport(Mockito.any(Location.class));
		Mockito.verify(shop, Mockito.never()).getShopObject();
		Mockito.verify(scheduler).teleportAsync(Mockito.same(player), Mockito.any(Location.class));
		nativeResult.complete(false);
		Assert.assertFalse(result.getNow(true));
	}
}
