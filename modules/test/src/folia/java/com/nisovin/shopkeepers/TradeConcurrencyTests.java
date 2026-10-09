package com.nisovin.shopkeepers;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.*;
import org.mockito.Mockito;
import com.nisovin.shopkeepers.FoliaCorrectionsTests;
import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.util.trading.*;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
public class TradeConcurrencyTests {
 private FoliaCorrectionsTests fixture;
 private SKShopkeepersPlugin plugin;
 private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
 @Before public void setup() throws Exception {
  fixture=new FoliaCorrectionsTests(); fixture.setup();
  plugin=SKShopkeepersPlugin.getInstance();
  PlatformScheduler scheduler=plugin.getFoliaLib().getScheduler();
  Mockito.when(scheduler.runAtEntityLater(Mockito.any(),Mockito.any(Runnable.class),Mockito.any(Runnable.class),Mockito.anyLong())).thenAnswer(c->{callbacks.add(c.getArgument(1));return Mockito.mock(WrappedTask.class);});
 }
 @After public void teardown() throws Exception {fixture.teardown();}
 private ShopkeeperTradeEvent trade(Player player, InventoryClickEvent click) {
  ShopkeeperTradeEvent event=Mockito.mock(ShopkeeperTradeEvent.class);
  TradingRecipe recipe=Mockito.mock(TradingRecipe.class);
  UnmodifiableItemStack item=Mockito.mock(UnmodifiableItemStack.class);
  Mockito.when(item.getAmount()).thenReturn(1);
  Mockito.when(recipe.getResultItem()).thenReturn(item);
  Mockito.when(event.getTradingRecipe()).thenReturn(recipe);
  Mockito.when(event.getOfferedItem1()).thenReturn(item);
  Mockito.when(event.getPlayer()).thenReturn(player);
  Mockito.when(event.getClickEvent()).thenReturn(click);
  return event;
 }
 private Player player() {Player p=Mockito.mock(Player.class);Mockito.when(p.getUniqueId()).thenReturn(UUID.randomUUID());return p;}
 @Test public void concurrentPlayersAreMergedIndependentlyAndFlushedOnce() throws Exception {
  List<MergedTrades> delivered=new CopyOnWriteArrayList<>();
  TradeMerger merger=new TradeMerger(plugin,TradeMerger.MergeMode.SAME_CLICK_EVENT,delivered::add);
  ExecutorService pool=Executors.newFixedThreadPool(8);
  List<Future<?>> futures=new ArrayList<>();
  for(int i=0;i<8;i++) {ShopkeeperTradeEvent event=trade(player(),Mockito.mock(InventoryClickEvent.class));futures.add(pool.submit(()->{for(int j=0;j<100;j++)merger.mergeTrade(event);}));}
  try {for(Future<?> f:futures)f.get(10,TimeUnit.SECONDS);} finally {pool.shutdownNow();}
  Assert.assertTrue(delivered.isEmpty());
  merger.onDisable();
  Assert.assertEquals(8,delivered.size());
  for(MergedTrades trades:delivered)Assert.assertEquals(100,trades.getTradeCount());
  for(Runnable callback:callbacks)callback.run();
  Assert.assertEquals(8,delivered.size());
 }
 @Test public void staleTimeoutCannotFlushReplacementBatch() {
  List<MergedTrades> delivered=new ArrayList<>();
  TradeMerger merger=new TradeMerger(plugin,TradeMerger.MergeMode.SAME_CLICK_EVENT,delivered::add);
  Player p=player();
  merger.mergeTrade(trade(p,Mockito.mock(InventoryClickEvent.class)));
  Runnable old=callbacks.get(0);
  merger.mergeTrade(trade(p,Mockito.mock(InventoryClickEvent.class)));
  Assert.assertEquals(1,delivered.size()); old.run();Assert.assertEquals(1,delivered.size());
  callbacks.get(1).run();Assert.assertEquals(2,delivered.size());
  merger.onDisable();Assert.assertEquals(2,delivered.size());
 }
 @Test public void disabledMergerRejectsLateTrades() {
  List<MergedTrades> delivered=new ArrayList<>();
  TradeMerger merger=new TradeMerger(plugin,TradeMerger.MergeMode.DURATION,delivered::add).withMergeDurations(0,0);
  ShopkeeperTradeEvent event=trade(player(),Mockito.mock(InventoryClickEvent.class));
  merger.mergeTrade(event);merger.onDisable();merger.mergeTrade(event);
  Assert.assertEquals(1,delivered.size());
 }
}
