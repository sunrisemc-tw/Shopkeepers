package harness;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.DefaultShopTypes;
import com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopCreationData;
import com.nisovin.shopkeepers.api.shopobjects.DefaultShopObjectTypes;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Focused, player-free probe for the same-chunk static (sign) move respawn fix.
 * Creates a sign shop, teleports it to another block within the same chunk, and asserts the
 * destination sign block reappears and the source block clears. All work runs on the owning region.
 */
public final class SignMoveProbe extends JavaPlugin {

	private final AtomicBoolean terminal = new AtomicBoolean();

	@Override
	public void onEnable() {
		log("PASS", "enable", "sign-move probe; player interaction NOT tested");
		Bukkit.getAsyncScheduler().runDelayed(this, t -> {
			if (!terminal.get()) {
				fail("watchdog", new IllegalStateException("Probe exceeded 90 seconds"));
				finish(false);
			}
		}, 90, TimeUnit.SECONDS);
		World world = Bukkit.getWorld("probe_world");
		if (world == null) {
			fail("world", new IllegalStateException("scratch probe_world missing"));
			finish(false);
			return;
		}

		// Source and destination share the same chunk (chunk 0,0): both x/z within 0..15.
		Location source = new Location(world, 4.5, 80, 4.5);
		Location destination = new Location(world, 10.5, 80, 10.5);
		source.getWorld().getChunkAtAsync(source, true).whenComplete((chunk, error) -> {
			if (error != null) {
				fail("chunk-load", error);
				finish(false);
				return;
			}

			Bukkit.getRegionScheduler().run(this, source, t -> {
				chunk.addPluginChunkTicket(this);
				step(world, source, destination);
			});
		});
	}

	private void step(World world, Location source, Location destination) {
		try {
			require(Bukkit.isOwnedByCurrentRegion(source), "owns source region");
			require(source.getChunk().getX() == destination.getChunk().getX()
					&& source.getChunk().getZ() == destination.getChunk().getZ(), "same chunk");

			// A wall sign must attach to a solid block. Provide support behind each location.
			prepareSignSupport(source);
			prepareSignSupport(destination);

			var objectType = DefaultShopObjectTypes.SIGN();
			require(objectType != null, "sign object type available");
			Shopkeeper shop = ShopkeepersAPI.getShopkeeperRegistry().createShopkeeper(
					AdminShopCreationData.create(null, DefaultShopTypes.ADMIN_REGULAR(),
							objectType, source, BlockFace.SOUTH));
			require(shop != null && shop.isValid(), "created valid sign shop");
			log("PASS", "create-sign", "uuid=" + shop.getUniqueId());

			// Allow the async-spawned sign to materialize, then verify, teleport, and re-verify.
			awaitSourceSign(world, source, destination, shop, 0);
		} catch (Throwable ex) {
			fail("step", ex);
			finish(false);
		}
	}

	private void awaitSourceSign(World world, Location source, Location destination, Shopkeeper shop, int attempt) {
		Bukkit.getRegionScheduler().runDelayed(this, source, t -> {
			if (terminal.get()) return;
			try {
				Block sourceBlock = source.getBlock();
				if (!isSign(sourceBlock)) {
					if (attempt >= 60) {
						require(false, "sign present at source before move: " + sourceBlock.getType());
					}

					awaitSourceSign(world, source, destination, shop, attempt + 1);
					return;
				}

				log("PASS", "sign-at-source", "type=" + sourceBlock.getType());

				// Same-chunk teleport. This is the path that previously failed to respawn.
				shop.teleport(destination, BlockFace.SOUTH);

				// Assert the IMMEDIATE post-move state, before the block shop object's periodic tick
				// can self-heal a missing block. On the owning region the move completes
				// synchronously within this tick, so a 1-tick delayed check still precedes the
				// ticker's respawn.
				awaitDestinationSign(source, destination, shop, 0);
			} catch (Throwable ex) {
				fail("await-source", ex);
				finish(false);
			}
		}, 20);
	}

	private void awaitDestinationSign(Location source, Location destination, Shopkeeper shop, int attempt) {
		// Single immediate (1 tick) check to catch the pre-self-heal state. No retry: the block
		// ticker would otherwise respawn a missing block within a second and mask the move bug.
		Bukkit.getRegionScheduler().runDelayed(this, destination, t -> {
			if (terminal.get()) return;
			try {
				Block destBlock = destination.getBlock();
				Block oldBlock = source.getBlock();
				require(isSign(destBlock),
						"sign respawned at destination immediately after same-chunk move: " + destBlock.getType());
				require(!isSign(oldBlock), "source sign block cleared after move: " + oldBlock.getType());
				require(shop.getX() == destination.getBlockX() && shop.getZ() == destination.getBlockZ(),
						"registry location updated to destination");
				log("PASS", "sign-respawned-destination",
						"dest=" + destBlock.getType() + " source=" + oldBlock.getType());
				finish(true);
			} catch (Throwable ex) {
				fail("verify-destination", ex);
				finish(false);
			}
		}, 1);
	}

	private void prepareSignSupport(Location loc) {
		// Clear the sign location and place a solid block to the north so a south-facing wall sign
		// can attach.
		loc.getBlock().setType(Material.AIR);
		loc.clone().add(0, 0, -1).getBlock().setType(Material.STONE);
		loc.clone().subtract(0, 1, 0).getBlock().setType(Material.STONE);
	}

	private static boolean isSign(Block block) {
		return Tag.SIGNS.isTagged(block.getType()) || Tag.WALL_SIGNS.isTagged(block.getType())
				|| Tag.ALL_HANGING_SIGNS.isTagged(block.getType());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	private void fail(String operation, Throwable error) {
		log("FAIL", operation, error.toString());
		getLogger().log(Level.SEVERE, "SignMoveProbe exception on " + Thread.currentThread().getName(), error);
	}

	private void log(String result, String operation, String detail) {
		getLogger().info("PROBE " + result + " " + operation + " thread=" + Thread.currentThread().getName() + " " + detail);
	}

	private void finish(boolean ok) {
		if (!terminal.compareAndSet(false, true)) return;
		log(ok ? "PASS" : "FAIL", "SIGNMOVE_COMPLETE", ok ? "sign respawned" : "see failures above");
	}
}
