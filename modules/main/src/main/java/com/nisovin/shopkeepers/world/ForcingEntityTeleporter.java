package com.nisovin.shopkeepers.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;

/**
 * Tries to force an entity teleport, bypassing plugins cancelling or modifying its event.
 */
public class ForcingEntityTeleporter implements Listener {

	private static final class TeleportContext {
		final Location destination;
		final CompletableFuture<Boolean> result = new CompletableFuture<>();

		TeleportContext(Location destination) {
			this.destination = destination.clone();
		}
	}

	private final SKShopkeepersPlugin plugin;
	private final ConcurrentHashMap<UUID, TeleportContext> pending = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, TeleportContext> forcedEvents = new ConcurrentHashMap<>();

	public ForcingEntityTeleporter(SKShopkeepersPlugin plugin) {
		this.plugin = plugin;
	}

	public void onEnable() {
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	public void onDisable() {
		HandlerList.unregisterAll(this);
		this.resetForcedEntityTeleport();
		pending.forEach((uuid, context) -> context.result.complete(false));
		pending.clear();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	void onEntityTeleport(EntityTeleportEvent event) {
		TeleportContext context = forcedEvents.remove(event.getEntity().getUniqueId());
		if (context == null) return;

		event.setCancelled(false);
		event.setTo(context.destination.clone());
	}

	/**
	 * Teleports an entity. Call from the thread owning the entity.
	 * <p>
	 * On Spigot and Paper the result is completed synchronously. On Folia it completes after the
	 * async teleport and the client update on the entity scheduler. Never wait for it on a tick
	 * thread. A second teleport for the same entity is rejected until completion.
	 * 
	 * @param entity
	 *            the entity to teleport
	 * @param toLocation
	 *            the destination, copied before teleporting
	 * @return the actual teleport result, or false if the entity retires before the client update
	 */
	public CompletableFuture<Boolean> teleport(Entity entity, Location toLocation) {
		UUID uuid = entity.getUniqueId();
		TeleportContext context = new TeleportContext(toLocation);
		if (pending.putIfAbsent(uuid, context) != null) {
			return CompletableFuture.completedFuture(false);
		}

		forcedEvents.put(uuid, context);
		context.result.whenComplete((success, error) -> {
			forcedEvents.remove(uuid, context);
			pending.remove(uuid, context);
		});
		try {
			if (!plugin.getFoliaLib().isFolia()) {
				boolean success;
				try {
					success = entity.teleport(context.destination.clone());
				} finally {
					forcedEvents.remove(uuid, context);
				}

				this.updateClient(entity);
				context.result.complete(success);
			} else {
				plugin.getFoliaLib().getScheduler()
						.teleportAsync(entity, context.destination.clone())
						.whenComplete((success, error) -> {
							forcedEvents.remove(uuid, context);
							if (error != null) {
								context.result.completeExceptionally(error);
							} else if (!Boolean.TRUE.equals(success)) {
								context.result.complete(false);
							} else {
								this.finishOnEntity(entity, context);
							}
						});
			}
		} catch (RuntimeException error) {
			context.result.completeExceptionally(error);
		}
		return context.result.copy();
	}

	private void finishOnEntity(Entity entity, TeleportContext context) {
		if (context.result.isDone()) return;
		if (!plugin.isEnabled()) {
			context.result.complete(false);
			return;
		}

		try {
			plugin.getFoliaLib().getScheduler().runAtEntityLater(entity, () -> {
				if (context.result.isDone()) return;
				try {
					this.updateClient(entity);
					context.result.complete(true);
				} catch (RuntimeException error) {
					context.result.completeExceptionally(error);
				}
			}, () -> context.result.complete(false), 1L);
		} catch (NullPointerException error) {
			// FoliaLib 0.5.2 wraps the null native task returned for a retired entity.
			if ("nativeTask".equals(error.getMessage())) {
				context.result.complete(false);
			} else {
				context.result.completeExceptionally(error);
			}
		} catch (RuntimeException error) {
			context.result.completeExceptionally(error);
		}
	}

	private void updateClient(Entity entity) {
		// MC-44654: Some entities require another property update after changing position.
		boolean customNameVisible = entity.isCustomNameVisible();
		entity.setCustomNameVisible(!customNameVisible);
		entity.setCustomNameVisible(customNameVisible);
	}

	/**
	 * Resets pending forced teleport events without cancelling teleports already in progress.
	 */
	public void resetForcedEntityTeleport() {
		forcedEvents.clear();
	}
}
