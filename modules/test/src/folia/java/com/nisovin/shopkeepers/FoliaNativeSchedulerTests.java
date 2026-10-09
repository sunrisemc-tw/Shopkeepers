package com.nisovin.shopkeepers;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.FoliaImplementation;
import com.tcoded.folialib.util.InvalidTickDelayNotifier;
import com.tcoded.folialib.wrapper.task.WrappedTask;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Exercises the real FoliaLib 0.5.2 implementation with mocked native scheduler boundaries.
 */
public class FoliaNativeSchedulerTests {

	private Object previousPlugin;
	private SKShopkeepersPlugin plugin;
	private Entity entity;
	private EntityScheduler entityScheduler;

	@Before
	public void setup() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		previousPlugin = field.get(null);
		plugin = Mockito.mock(SKShopkeepersPlugin.class);
		Server server = Mockito.mock(Server.class);
		FoliaLib foliaLib = Mockito.mock(FoliaLib.class);
		Mockito.when(plugin.getServer()).thenReturn(server);
		Mockito.when(plugin.isEnabled()).thenReturn(true);
		Mockito.when(plugin.getFoliaLib()).thenReturn(foliaLib);
		Mockito.when(foliaLib.getPlugin()).thenReturn(plugin);
		Mockito.when(foliaLib.isFolia()).thenReturn(true);
		Mockito.when(foliaLib.getInvalidTickDelayNotifier())
				.thenReturn(Mockito.mock(InvalidTickDelayNotifier.class));
		FoliaImplementation implementation = new FoliaImplementation(foliaLib);
		Mockito.when(foliaLib.getScheduler()).thenReturn(implementation);
		entity = Mockito.mock(Entity.class);
		entityScheduler = Mockito.mock(EntityScheduler.class);
		Mockito.when(entity.getScheduler()).thenReturn(entityScheduler);
		field.set(null, plugin);
	}

	@After
	public void teardown() throws Exception {
		Field field = SKShopkeepersPlugin.class.getDeclaredField("plugin");
		field.setAccessible(true);
		field.set(null, previousPlugin);
	}

	@Test
	public void realLibraryRejectionReturnsNoHandleAndDoesNotRunWork() {
		AtomicInteger work = new AtomicInteger();
		Assert.assertNull(SchedulerUtils.runTaskLaterOrOmit(entity, work::incrementAndGet, 2L));
		Assert.assertEquals(0, work.get());
		Mockito.verify(entityScheduler).runDelayed(Mockito.same(plugin), Mockito.any(),
				Mockito.any(Runnable.class), Mockito.eq(2L));
		Mockito.verify(entity, Mockito.never()).getLocation();
	}

	@Test
	public void realLibraryHandleCancelsNativeTaskAndRetirementOmitsWork() {
		ScheduledTask nativeTask = Mockito.mock(ScheduledTask.class);
		Mockito.when(entityScheduler.runDelayed(Mockito.same(plugin), Mockito.any(),
				Mockito.any(Runnable.class), Mockito.eq(1L))).thenReturn(nativeTask);
		AtomicInteger work = new AtomicInteger();
		WrappedTask task = SchedulerUtils.runTaskOrOmit(entity, work::incrementAndGet);
		Assert.assertNotNull(task);
		task.cancel();
		Mockito.verify(nativeTask).cancel();
		ArgumentCaptor<Runnable> retirement = ArgumentCaptor.forClass(Runnable.class);
		Mockito.verify(entityScheduler).runDelayed(Mockito.same(plugin), Mockito.any(),
				retirement.capture(), Mockito.eq(1L));
		retirement.getValue().run();
		Assert.assertEquals(0, work.get());
		Mockito.verify(entity, Mockito.never()).getLocation();
	}

	@Test
	public void realLibraryTimerRetirementAndNativeHandleAreUsed() {
		AtomicInteger work = new AtomicInteger();
		SchedulerUtils.runTaskTimerOrOmit(entity, task -> {
			work.incrementAndGet();
			task.cancel();
		}, 2L, 3L);
		ArgumentCaptor<Runnable> retirement = ArgumentCaptor.forClass(Runnable.class);
		@SuppressWarnings({ "unchecked", "rawtypes" })
		ArgumentCaptor<Consumer<ScheduledTask>> callback =
				ArgumentCaptor.forClass((Class) Consumer.class);
		Mockito.verify(entityScheduler).runAtFixedRate(Mockito.same(plugin), callback.capture(),
				retirement.capture(), Mockito.eq(2L), Mockito.eq(3L));
		retirement.getValue().run();
		Assert.assertEquals(0, work.get());
		ScheduledTask nativeTask = Mockito.mock(ScheduledTask.class);
		callback.getValue().accept(nativeTask);
		Assert.assertEquals(1, work.get());
		Mockito.verify(nativeTask).cancel();
		Mockito.verify(entity, Mockito.never()).getLocation();
	}
}
