package com.nisovin.shopkeepers.shopkeeper.teleporting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.lang.Messages;
import com.nisovin.shopkeepers.text.Text;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.TeleportHelper;
import com.nisovin.shopkeepers.util.bukkit.TextUtils;
import com.nisovin.shopkeepers.util.bukkit.WorldUtils;

/**
 * Helper for teleporting players to shopkeepers.
 */
public final class ShopkeeperTeleporter {

	private static final double TELEPORT_DISTANCE = 2.0D;
	private static final TeleportHelper TELEPORT_HELPER = TeleportHelper.DEFAULT;

	/**
	 * Returns the completed teleport result on Spigot, and an asynchronous result on Folia.
	 * Never wait for this future on a tick thread.
	 */
	public static CompletableFuture<Boolean> teleport(
			Player player, Shopkeeper shopkeeper, boolean force, @Nullable CommandSender sender
	) {
		if (!SKShopkeepersPlugin.getInstance().getFoliaLib().isFolia()) {
			return CompletableFuture.completedFuture(teleportSync(player, shopkeeper, force, sender));
		}

		CompletableFuture<Boolean> result = new CompletableFuture<>();
		runForEntity(player, () -> teleportFolia(player, shopkeeper, force, sender, result), result);
		return result;
	}

	private static boolean teleportSync(Player player, Shopkeeper shopkeeper, boolean force, @Nullable CommandSender sender) {
		if (shopkeeper.isVirtual()) {
			if (sender != null) {
				TextUtils.sendMessage(sender, Messages.teleportVirtualShopkeeper);
			}
			return false;
		}

		var shopObject = shopkeeper.getShopObject();
		@Nullable Location shopkeeperLocation = shopObject.getLocation();
		if (shopkeeperLocation == null) {
			shopkeeperLocation = shopkeeper.getLocation();

			if (shopkeeperLocation == null) {
				if (sender != null) {
					TextUtils.sendMessage(sender, Messages.teleportShopkeeperWorldNotLoaded);
				}
				return false;
			}
		}
		assert shopkeeperLocation != null;

		// Teleport the player a few blocks in front of the shopkeeper:
		shopkeeperLocation.setYaw(shopkeeper.getYaw());
		shopkeeperLocation.setPitch(0);
		Location destination = shopkeeperLocation.clone()
				.add(shopkeeperLocation.getDirection().multiply(TELEPORT_DISTANCE));

		final int shopOffsetX = shopkeeperLocation.getBlockX() - destination.getBlockX();
		final int shopOffsetZ = shopkeeperLocation.getBlockZ() - destination.getBlockZ();

		@Nullable Location teleportLocation = TELEPORT_HELPER.findSafeDestination(
				destination,
				player,
				// Skip the shopkeeper's location: If the shopkeeper is surrounded by blocks, this
				// can trap the player because the shopkeeper cannot be damaged and its bounding box
				// can prevent the player from breaking any of the surrounding blocks.
				// If the force parameter is used, we check the shopkeeper's location last but still
				// allow to teleport.
				offset -> offset.getX() == shopOffsetX && offset.getZ() == shopOffsetZ ? force
						? 1 : Integer.MAX_VALUE
						: 0
		);
		if (teleportLocation == null) {
			if (!force) {
				if (sender != null) {
					TextUtils.sendMessage(sender, Messages.teleportNoSafeLocationFound);
				}
				return false;
			}

			// Force: Teleport to the shopkeeper's location, even if it is unsafe.
			teleportLocation = shopkeeperLocation;
		}
		assert teleportLocation != null;

		// Let the player face the shopkeeper:
		// Ignoring pitch for now: This would require taking the shopkeeper's (eye) height into
		// account, for all types of shopkeepers.
		var teleportPlayerEyeLocationVector = teleportLocation.toVector();
		teleportPlayerEyeLocationVector.setY(teleportPlayerEyeLocationVector.getY() + player.getEyeHeight(true));
		teleportLocation.setDirection(shopkeeperLocation.toVector().subtract(teleportPlayerEyeLocationVector));
		teleportLocation.setPitch(0);

		if (!player.teleport(teleportLocation)) {
			if (sender != null) {
				TextUtils.sendMessage(sender, Messages.teleportFailed);
			}
			return false;
		}

		if (sender != null) {
			TextUtils.sendMessage(sender, Messages.teleportSuccess,
					"player", TextUtils.getPlayerText(player),
					"shop", TextUtils.getShopText(shopkeeper)
			);
		}
		return true;
	}

	private static void runForEntity(Entity entity, Runnable work, CompletableFuture<Boolean> result) {
		var plugin = SKShopkeepersPlugin.getInstance();
		if (!plugin.isEnabled()) {
			result.complete(false);
			return;
		}

		try {
			plugin.getFoliaLib().getScheduler().runAtEntityLater(entity, () -> {
				try {
					work.run();
				} catch (RuntimeException error) {
					result.completeExceptionally(error);
				}
			}, () -> result.complete(false), 1L);
		} catch (NullPointerException error) {
			if ("nativeTask".equals(error.getMessage())) result.complete(false);
			else result.completeExceptionally(error);
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}

	private static void sendFoliaMessage(@Nullable CommandSender sender, Text message) {
		if (sender == null) return;
		if (sender instanceof Entity) {
			runForEntity((Entity) sender, () -> TextUtils.sendMessage(sender, message),
					new CompletableFuture<>());
		} else {
			SchedulerUtils.runTaskGloballyOrOmit(() -> TextUtils.sendMessage(sender, message));
		}
	}

	private static void teleportFolia(
			Player player, Shopkeeper shopkeeper, boolean force, @Nullable CommandSender sender,
			CompletableFuture<Boolean> result
	) {
		if (shopkeeper.isVirtual()) {
			sendFoliaMessage(sender, Messages.teleportVirtualShopkeeper);
			result.complete(false);
			return;
		}

		// The persisted location avoids accessing a shop entity on a different region thread.
		Location shopLocation = shopkeeper.getLocation();
		if (shopLocation == null) {
			sendFoliaMessage(sender, Messages.teleportShopkeeperWorldNotLoaded);
			result.complete(false);
			return;
		}

		shopLocation = shopLocation.clone();
		shopLocation.setYaw(shopkeeper.getYaw());
		shopLocation.setPitch(0);
		Location destination = shopLocation.clone()
				.add(shopLocation.getDirection().multiply(TELEPORT_DISTANCE));
		BoundingBox boundingBox = player.getBoundingBox().clone();
		double eyeHeight = player.getEyeHeight(true);
		var playerText = TextUtils.getPlayerText(player);
		var shopText = TextUtils.getShopText(shopkeeper);
		List<Location> candidates = new ArrayList<>();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				if (!force && destination.getBlockX() + x == shopLocation.getBlockX()
						&& destination.getBlockZ() + z == shopLocation.getBlockZ()) continue;
				for (int y = -2; y <= 2; y++) {
					candidates.add(new Location(destination.getWorld(),
							destination.getBlockX() + x, destination.getBlockY() + y,
							destination.getBlockZ() + z));
				}
			}
		}

		final Location shop = shopLocation;
		candidates.sort(Comparator.<Location>comparingInt(location ->
				location.getBlockX() == shop.getBlockX()
				&& location.getBlockZ() == shop.getBlockZ() ? 1 : 0)
				.thenComparingDouble(location -> location.distanceSquared(new Location(
						destination.getWorld(), destination.getBlockX(), destination.getBlockY(),
						destination.getBlockZ()))));
		CompletableFuture<@Nullable Location> safeDestination = new CompletableFuture<>();
		findSafeDestination(candidates, 0, boundingBox, safeDestination);
		safeDestination.whenComplete((safe, error) -> {
			if (error != null) {
				result.completeExceptionally(error);
				return;
			}

			@Nullable Location target = safe != null ? safe : force ? shop.clone() : null;
			if (target == null) {
				sendFoliaMessage(sender, Messages.teleportNoSafeLocationFound);
				result.complete(false);
				return;
			}

			target.setDirection(shop.toVector().subtract(target.toVector().add(
					new org.bukkit.util.Vector(0, eyeHeight, 0))));
			target.setPitch(0);
			runForEntity(player, () -> SKShopkeepersPlugin.getInstance().getFoliaLib()
					.getScheduler().teleportAsync(player, target)
					.whenComplete((success, teleportError) -> {
						if (teleportError != null) {
							sendFoliaMessage(sender, Messages.teleportFailed);
							result.completeExceptionally(teleportError);
						} else {
							// Messages are sent on the sender's scheduler, after the real result.
							if (Boolean.TRUE.equals(success)) {
								if (sender != null) {
									Runnable message = () -> TextUtils.sendMessage(sender,
											Messages.teleportSuccess, "player", playerText, "shop", shopText);
									if (sender instanceof Entity) {
										runForEntity((Entity) sender, message, new CompletableFuture<>());
									} else {
										SchedulerUtils.runTaskGloballyOrOmit(message);
									}
								}
							} else {
								sendFoliaMessage(sender, Messages.teleportFailed);
							}
							result.complete(Boolean.TRUE.equals(success));
						}
					}), result);
		});
	}

	private static void findSafeDestination(
			List<Location> candidates, int index, BoundingBox boundingBox,
			CompletableFuture<@Nullable Location> result
	) {
		if (index == candidates.size()) {
			result.complete(null);
			return;
		}

		Location candidate = candidates.get(index);
		try {
			if (SchedulerUtils.runTaskOrOmit(candidate, () -> {
				try {
					int next = index;
					while (next < candidates.size()) {
						Location current = candidates.get(next);
						if (!SchedulerUtils.isMainThread(current)) break;
						Location safe = checkSafeDestination(current, boundingBox.clone());
						if (safe != null) {
							result.complete(safe);
							return;
						}
						next++;
					}

					findSafeDestination(candidates, next, boundingBox, result);
				} catch (RuntimeException error) {
					result.completeExceptionally(error);
				}
			}) == null) result.complete(null);
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}

	private static final Set<Material> AVOIDED_BLOCKS = Set.of(
			Material.CACTUS, Material.CAMPFIRE, Material.FIRE, Material.MAGMA_BLOCK,
			Material.SOUL_CAMPFIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH,
			Material.WITHER_ROSE, Material.LAVA, Material.WATER, Material.END_PORTAL,
			Material.NETHER_PORTAL, Material.FARMLAND
	);

	private static @Nullable Location checkSafeDestination(Location candidate, BoundingBox box) {
		Block block = candidate.getBlock();
		Block below = block.getRelative(0, -1, 0);
		if (below.getType().isAir() || AVOIDED_BLOCKS.contains(below.getType())
				|| block.isLiquid() || below.isLiquid()
				|| !WorldUtils.isBlockInsideWorldHeightBounds(block)
				|| !WorldUtils.isBlockInsideWorldBorder(block)) return null;

		// Match TeleportHelper's player checks, using only snapshots and the candidate's column.
		Location location = block.getLocation().add(0.5D, 0.98D, 0.5D);
		Location rayStart = location.clone().add(0, 0.01D, 0);
		var hit = block.getWorld().rayTraceBlocks(rayStart,
				new org.bukkit.util.Vector(0, -1, 0), 1.97D,
				org.bukkit.FluidCollisionMode.NEVER, true);
		if (hit == null) return null;
		double distance = Math.max(0, rayStart.toVector().distance(hit.getHitPosition()) - 0.01D);
		location.subtract(0, distance, 0);
		box.shift(0.5D - box.getCenterX(),
				location.getY() - location.getBlockY() - box.getMinY(), 0.5D - box.getCenterZ());
		int height = (int) Math.ceil(box.getHeight());
		for (int y = 0; y < height; y++) {
			if (y != 0) box.shift(0, -1, 0);
			Block above = block.getRelative(0, y, 0);
			if (above.isLiquid() || above.getCollisionShape().overlaps(box)) return null;
		}
		return location;
	}

	private ShopkeeperTeleporter() {
	}
}
