package com.nisovin.shopkeepers.tradelog;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeCompletedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.tradelog.csv.CsvTradeLogger;
import com.nisovin.shopkeepers.tradelog.data.TradeRecord;
import com.nisovin.shopkeepers.tradelog.history.TradingHistoryProvider;
import com.nisovin.shopkeepers.tradelog.sqlite.SQLiteTradeLogger;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.trading.MergedTrades;
import com.nisovin.shopkeepers.util.trading.TradeMerger;
import com.nisovin.shopkeepers.util.trading.TradeMerger.MergeMode;

public class TradeLoggers implements Listener {

	private final SKShopkeepersPlugin plugin;
	private final List<TradeLogger> loggers = new CopyOnWriteArrayList<>();
	// In order to represent the logged trades more compactly, we merge equivalent trades that are
	// triggered in quick succession over a certain period of time. The maximum merge duration is
	// configurable, and the trade merging can also be disabled.
	// Player and shop data is captured when the initial trade takes place. Merged records are
	// passed to the loggers on the global thread, preserving each player's trade order.
	private @Nullable TradeMerger tradeMerger;
	private volatile boolean enabled = false;
	private final Map<ShopkeeperTradeEvent, TradeRecord> tradeSnapshots =
			Collections.synchronizedMap(new WeakHashMap<>());
	private final ConcurrentLinkedQueue<TradeRecord> pendingTrades = new ConcurrentLinkedQueue<>();

	public TradeLoggers(SKShopkeepersPlugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		this.plugin = plugin;
	}

	public void onEnable() {
		int mergeDuration = Settings.tradeLogMergeDurationTicks;
		if (mergeDuration == 1) {
			// Only merge trades that are triggered by the same click event:
			tradeMerger = new TradeMerger(plugin, MergeMode.SAME_CLICK_EVENT, this::processTrades);
		} else {
			// Note: A merge duration of 0 disables the trade merging.
			tradeMerger = new TradeMerger(plugin, MergeMode.DURATION, this::processTrades)
					.withMergeDurations(mergeDuration, Settings.tradeLogNextMergeTimeoutTicks);
		}
		assert tradeMerger != null;
		tradeMerger.onEnable();

		switch (Settings.tradeLogStorage) {
		case CSV:
			loggers.add(new CsvTradeLogger(plugin));
			break;
		case SQLITE:
			loggers.add(new SQLiteTradeLogger(plugin));
			break;
		case DISABLED:
		default:
			break;
		}

		loggers.forEach(TradeLogger::setup);

		enabled = !loggers.isEmpty();
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	public void onDisable() {
		if (!enabled) return;
		enabled = false;

		// Stop reacting to new trades:
		HandlerList.unregisterAll(this);

		// Process any pending previous trades:
		Unsafe.assertNonNull(tradeMerger).onDisable();

		this.drainPendingTrades();
		tradeSnapshots.clear();

		// Wait for any pending writes to complete:
		loggers.forEach(TradeLogger::flush);
		loggers.clear();
	}

	/**
	 * Gets the currently active {@link TradingHistoryProvider}.
	 * <p>
	 * There can only be one active {@link TradingHistoryProvider}: This returns the first active
	 * {@link TradeLogger} that implements {@link TradingHistoryProvider}.
	 * 
	 * @return the active {@link TradingHistoryProvider}, or <code>null</code> if there is none
	 */
	public @Nullable TradingHistoryProvider getTradingHistoryProvider() {
		for (var logger : loggers) {
			if (logger instanceof TradingHistoryProvider tradingHistoryProvider) {
				return tradingHistoryProvider;
			}
		}
		return null;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	void onTradeCompleted(ShopkeeperTradeCompletedEvent event) {
		TradeMerger merger = tradeMerger;
		if (!enabled || merger == null) return;
		synchronized (merger) {
			if (!enabled) return;
			var trade = event.getCompletedTrade();
			if (plugin.getFoliaLib().isFolia()) {
				tradeSnapshots.put(trade, TradeRecord.create(trade));
			}
			merger.mergeTrade(trade);
		}
	}

	private void processTrades(MergedTrades trades) {
		TradeRecord snapshot = tradeSnapshots.remove(trades.getInitialTrade());
		if (snapshot == null && plugin.getFoliaLib().isFolia()) return;
		TradeRecord trade = snapshot == null ? TradeRecord.create(trades) : new TradeRecord(
				trades.getTimestamp(), snapshot.getPlayer(), snapshot.getShop(),
				trades.getResultItem(), trades.getOfferedItem1(),
				trades.getOfferedItem2(), trades.getTradeCount());
		pendingTrades.add(trade);
		if (SchedulerUtils.isGlobalThread()) {
			this.drainPendingTrades();
		} else if (enabled) {
			SchedulerUtils.runTaskGloballyOrOmit(this::drainPendingTrades);
		}
	}

	private void drainPendingTrades() {
		TradeRecord trade;
		while ((trade = pendingTrades.poll()) != null) {
			for (TradeLogger logger : loggers) {
				logger.logTrade(trade);
			}
		}
	}
}
