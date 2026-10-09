package com.nisovin.shopkeepers.playershops.expiration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.PlayerShopkeeperExpireEvent;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.config.Settings.DerivedSettings;
import com.nisovin.shopkeepers.shopkeeper.player.AbstractPlayerShopkeeper;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.Ticks;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Periodically checks for expired player shops, handles their expiration, and notifies online shop
 * owners and members about approaching expirations.
 */
class ShopExpirationCheckTask implements Runnable {

	// The check interval:
	// Also used as the initial delay.
	// Expirations and reached notification thresholds are only detected with this granularity.
	// Thresholds shorter than this interval might not be reached before the shop expires.
	private static final long INTERVAL_TICKS = Ticks.fromMinutes(5);

	private final SKShopkeepersPlugin plugin;
	private final ShopExpirationNotifier notifier;

	private @Nullable WrappedTask task = null;

	ShopExpirationCheckTask(
			SKShopkeepersPlugin plugin,
			ShopExpirationNotifier notifier
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(notifier, "notifier is null");
		this.plugin = plugin;
		this.notifier = notifier;
	}

	void start() {
		this.stop(); // Stop the task if it is already running

		// The expiration check iterates over player shopkeepers across all worlds, so it runs on the
		// global thread:
		task = SchedulerUtils.runTaskTimerGloballyOrOmit(this, INTERVAL_TICKS, INTERVAL_TICKS);
	}

	void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	@Override
	public void run() {
		Instant now = Instant.now();
		var notify = Settings.notifyShopMembersAboutExpiration;
		var registry = plugin.getShopkeeperRegistry();

		// Each shop's expiration read and mutation is dispatched onto its owning region. On
		// non-Folia servers this runs inline on the primary thread, preserving the original
		// behavior. Owners and members are notified via their own scheduler.
		for (AbstractPlayerShopkeeper shop : registry.getAllPlayerShopkeepers()) {
			registry.runOnOwner(shop, () -> {
				if (!shop.isValid()) return true;

				@Nullable Instant shopExpiration = shop.getExpiration();
				if (shopExpiration == null) {
					// The shop does not expire:
					return true;
				}

				if (now.isAfter(shopExpiration)) {
					this.processExpired(shop);
					return true;
				}

				if (!notify) {
					return true;
				}

				// Remind online members about this shop's expiration and reached threshold:
				Duration timeLeft = Duration.between(now, shopExpiration);
				@Nullable Duration threshold = this.getReachedThreshold(timeLeft);
				if (threshold != null) {
					this.remindMembers(shop, shopExpiration, threshold, now);
				}

				return true;
			});
		}

		plugin.getShopkeeperStorage().saveDelayed();
	}

	// Runs on the shop's owning region.
	private void processExpired(AbstractPlayerShopkeeper shop) {
		// Call event:
		var expireEvent = new PlayerShopkeeperExpireEvent(shop);
		Bukkit.getPluginManager().callEvent(expireEvent);

		if (!shop.isValid()) {
			// Removed during event handling:
			Log.debug(() -> shop.getUniqueIdLogPrefix()
					+ "Removed during expiration event handling.");
			return;
		}

		if (expireEvent.isCancelled()) {
			Log.debug(() -> shop.getUniqueIdLogPrefix()
					+ "Expiration was cancelled by a plugin.");

			// Automatically reset the shop's expiration:
			shop.resetExpiration();
			return;
		}

		// Note: We do not re-check here if the shop is no longer expired. If a plugin wants to
		// cancel the expiration, they have to cancel the event.

		// Always inform online owners and members that the shop has expired, regardless of the
		// notification setting.
		// Note: Players are only informed if they are currently online when the shop expires.
		this.notifyExpired(shop);

		// Expire the shop:
		shop.expire();
	}

	// Runs on the shop's owning region. Collects online members to remind and dispatches the
	// reminder to each member's own scheduler.
	private void remindMembers(
			AbstractPlayerShopkeeper shop,
			Instant shopExpiration,
			Duration threshold,
			Instant now
	) {
		var registry = plugin.getShopkeeperRegistry();
		shop.forEachMember(member -> {
			if (!member.isOnline()) return; // Offline

			// Already reminded about this (or a more urgent) threshold:
			@Nullable Duration lastNotified = shop.getLastExpirationNotified(member.getUniqueId());
			if (lastNotified != null && threshold.compareTo(lastNotified) >= 0) {
				return;
			}

			@Nullable Player player = member.getPlayer();
			if (player == null) return;

			UUID memberId = member.getUniqueId();
			List<ExpiringShop> expiringShops = new ArrayList<>();
			expiringShops.add(new ExpiringShop(shop, shopExpiration, threshold));

			// Remember the reached threshold on the owning region, and send the reminder on the
			// member's scheduler:
			shop.setLastExpirationNotified(memberId, threshold);
			registry.runOnSender(player, () -> {
				if (player.isOnline()) notifier.sendExpirationReminders(player, expiringShops, now);
			});
		});
	}

	// Returns the most urgent notification threshold that has been reached for the given remaining
	// time, or null if no threshold has been reached yet.
	@Nullable
	private Duration getReachedThreshold(Duration timeLeft) {
		for (Duration threshold : DerivedSettings.playerShopExpirationNotificationThresholds) {
			if (timeLeft.compareTo(threshold) <= 0) {
				return threshold;
			}
		}

		return null;
	}

	// Informs the shop members that the given shop has expired.
	private void notifyExpired(AbstractPlayerShopkeeper shop) {
		shop.forEachMember(member -> {
			@Nullable Player player = member.getPlayer();
			if (player == null) return; // Offline

			notifier.sendShopExpired(player, shop);
		});
	}
}
