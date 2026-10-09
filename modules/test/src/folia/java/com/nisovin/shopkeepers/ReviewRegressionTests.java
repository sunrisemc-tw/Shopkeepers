package com.nisovin.shopkeepers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.commands.Confirmations;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;

public class ReviewRegressionTests {

	private final LifecycleCorrectionsTests fixture = new LifecycleCorrectionsTests();
	private SKShopkeepersPlugin plugin;
	private PlatformScheduler scheduler;

	@Before
	public void setup() throws Exception {
		fixture.setup();
		plugin = (SKShopkeepersPlugin) get(fixture, "plugin");
		scheduler = (PlatformScheduler) get(fixture, "scheduler");
	}

	@After
	public void teardown() throws Exception {
		fixture.teardown();
	}

	@Test
	public void supersededConfirmationTimeoutCannotRemoveReplacement() {
		Player player = player();
		List<Runnable> callbacks = new ArrayList<>();
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(player), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
			callbacks.add(call.getArgument(1));
			return Mockito.mock(WrappedTask.class);
		});
		Confirmations confirmations = new Confirmations(plugin);
		Runnable replacement = () -> {};
		confirmations.awaitConfirmation(player, () -> {}, 10);
		confirmations.awaitConfirmation(player, replacement, 10);
		callbacks.get(0).run(); // Cancelled task already entered on another region thread.
		Assert.assertSame(replacement, confirmations.endConfirmation(player));
	}

	@Test
	public void concurrentPlayersRetainEveryConfirmation() throws Exception {
		Mockito.when(scheduler.runAtEntityLater(Mockito.any(Entity.class), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenReturn(Mockito.mock(WrappedTask.class));
		Confirmations confirmations = new Confirmations(plugin);
		List<Player> players = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();
		for (int i = 0; i < 4000; i++) {
			players.add(player());
			actions.add(() -> {});
		}
		var pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				int shard = i;
				results.add(pool.submit(() -> {
					for (int j = shard; j < players.size(); j += 8) {
						confirmations.awaitConfirmation(players.get(j), actions.get(j), 10);
					}
				}));
			}
			for (Future<?> result : results) result.get();
			for (int i = 0; i < players.size(); i++) {
				Assert.assertSame(actions.get(i), confirmations.endConfirmation(players.get(i)));
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void oldSelectionCallbackCannotRemoveNewSelection() throws Exception {
		Class<?> type = Class.forName(
				"com.nisovin.shopkeepers.shopcreation.ShopCreationItemSelectionTask");
		Method start = type.getDeclaredMethod("start", org.bukkit.plugin.Plugin.class, Player.class);
		Method cancel = type.getDeclaredMethod("cleanupAndCancel", Player.class);
		start.setAccessible(true);
		cancel.setAccessible(true);
		Player player = player(); // Offline, so no inventory access after callback cleanup.
		List<Runnable> callbacks = new ArrayList<>();
		Mockito.when(scheduler.runAtEntityLater(Mockito.same(player), Mockito.any(Runnable.class),
				Mockito.any(Runnable.class), Mockito.anyLong())).thenAnswer(call -> {
			callbacks.add(call.getArgument(1));
			return Mockito.mock(WrappedTask.class);
		});
		start.invoke(null, plugin, player);
		cancel.invoke(null, player);
		start.invoke(null, plugin, player);
		Field tasks = type.getDeclaredField("activeTasks");
		tasks.setAccessible(true);
		var map = (java.util.Map<?, ?>) tasks.get(null);
		Object replacement = map.get(player.getUniqueId());
		callbacks.get(0).run();
		Assert.assertSame(replacement, map.get(player.getUniqueId()));
		cancel.invoke(null, player);
	}

	private Player player() {
		Player player = Mockito.mock(Player.class);
		Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		return player;
	}

	private static Object get(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}