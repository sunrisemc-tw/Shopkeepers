package harness;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.DefaultShopTypes;
import com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopCreationData;
import com.nisovin.shopkeepers.api.shopobjects.DefaultShopObjectTypes;
import com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;

/** Player-free, two-region integration probe. All waits and disk IO are async. */
public final class Probe extends JavaPlugin {
    private record Entry(int region, int slot, UUID uuid, String name, boolean deleted) {}
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private Location[] anchors;
    private Path manifest;
    private boolean verify;

    @Override public void onEnable() {
        manifest = getDataFolder().toPath().resolve("manifest.tsv");
        log("PASS", "enable", "player-free probe; trade interaction is NOT tested");
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                Files.createDirectories(manifest.getParent());
                verify = Files.exists(manifest);
                if (verify) {
                    for (String line : Files.readAllLines(manifest)) {
                        String[] v = line.split("\\t");
                        Entry e = new Entry(Integer.parseInt(v[0]), Integer.parseInt(v[1]),
                            UUID.fromString(v[2]), v[3], Boolean.parseBoolean(v[4]));
                        entries.put(key(e.region, e.slot), e);
                    }
                    if (entries.size() != 6) throw new IllegalStateException("Expected six manifest entries");
                }
                Bukkit.getGlobalRegionScheduler().run(this, t -> begin());
            } catch (Throwable ex) { fail("manifest-load", ex); finish(false); }
        });
    }

    private void begin() {
        try {
            require(ShopkeepersAPI.isEnabled(), "API enabled");
            World world = Bukkit.getWorld("probe_world");
            require(world != null, "scratch probe_world exists");
            anchors = new Location[]{new Location(world, 8.5, 80, 8.5),
                new Location(world, 8200.5, 80, 8200.5)};
            log("PASS", "global-dispatch", "phase=" + (verify ? "cold-reload" : "create") + " distance=8192 blocks per axis");
            Bukkit.getAsyncScheduler().runDelayed(this, t -> {
                if (!terminal.get()) { fail("watchdog", new TimeoutException("Probe exceeded 120 seconds")); finish(false); }
            }, 120, TimeUnit.SECONDS);
            for (int i = 0; i < 2; i++) loadRegion(i);
        } catch (Throwable ex) { fail("begin", ex); finish(false); }
    }

    private void loadRegion(int region) {
        Location loc = anchors[region].clone();
        // Never join/get this future from a scheduler callback.
        loc.getWorld().getChunkAtAsync(loc, true).whenComplete((chunk, error) -> {
            if (error != null) { fail("chunk-load-r" + region, error); finish(false); return; }
            dispatch(region, "region-ready", () -> {
                require(Bukkit.isOwnedByCurrentRegion(loc), "owns static region");
                chunk.addPluginChunkTicket(this);
                log("PASS", "region-ticket-r" + region, "chunk=" + chunk.getX() + "," + chunk.getZ());
                if (verify) verifyRegion(region); else createRegion(region);
            });
        });
    }

    private void dispatch(int region, String operation, Runnable action) {
        try {
            Bukkit.getRegionScheduler().run(this, anchors[region].clone(), t -> {
                if (terminal.get()) return;
                try {
                    require(Bukkit.isOwnedByCurrentRegion(anchors[region]), "scheduler ownership");
                    action.run();
                } catch (Throwable ex) { fail(operation + "-r" + region, ex); finish(false); }
            });
        } catch (Throwable ex) { fail("dispatch-" + operation, ex); finish(false); }
    }

    private void createRegion(int region) throws RuntimeException {
        try {
            Location base = anchors[region];
            for (int slot = 0; slot < 3; slot++) {
                Location loc = base.clone().add(slot * 2, 0, 0);
                loc.clone().subtract(0, 1, 0).getBlock().setType(Material.STONE);
                loc.getBlock().setType(Material.AIR);
                loc.clone().add(0, 1, 0).getBlock().setType(Material.AIR);
                var objectType = DefaultShopObjectTypes.LIVING().get(EntityType.VILLAGER);
                require(objectType != null, "villager type available");
                Shopkeeper shop = ShopkeepersAPI.getShopkeeperRegistry().createShopkeeper(
                    AdminShopCreationData.create(null, DefaultShopTypes.ADMIN_REGULAR(), objectType, loc, null));
                require(shop != null && shop.isValid(), "created valid admin shop");
                String name = "Probe" + region + "_" + slot;
                shop.setName(name);
                entries.put(key(region, slot), new Entry(region, slot, shop.getUniqueId(), name, false));
                log("PASS", "create-r" + region + "-s" + slot, "uuid=" + shop.getUniqueId());
                dirtySave(shop, region, slot);
            }
            if (completed.incrementAndGet() == 2) {
                completed.set(0);
                // This continuation is initiated from whichever region completed last.
                // Dispatch both destinations; never access a foreign shop on this thread.
                log("PASS", "cross-region-dispatch", "fanout rename/delete to A and B");
                dispatch(0, "rename-delete", () -> mutateRegion(0));
                dispatch(1, "rename-delete", () -> mutateRegion(1));
            }
        } catch (Throwable ex) { throw new RuntimeException(ex); }
    }

    private void dirtySave(Shopkeeper shop, int region, int slot) throws Exception {
        // markDirty is public on AbstractShopkeeper, but absent from ShopkeepersAPI.
        shop.getClass().getMethod("markDirty").invoke(shop);
        log("PASS", "markDirty-r" + region + "-s" + slot, "implementation method returned");
        shop.save();
        ShopkeepersAPI.getShopkeeperStorage().save();
        log("PASS", "save-request-r" + region + "-s" + slot, "nonblocking request returned; disk verification pending");
    }

    private void mutateRegion(int region) {
        try {
            for (int slot = 0; slot < 3; slot++) {
                Entry old = entries.get(key(region, slot));
                Shopkeeper shop = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(old.uuid);
                require(shop != null, "shop lookup");
                if (slot == 2) {
                    shop.delete();
                    require(ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(old.uuid) == null,
                        "deleted from registry");
                    entries.put(key(region, slot), new Entry(region, slot, old.uuid, old.name, true));
                    log("PASS", "delete-r" + region + "-s" + slot, "uuid=" + old.uuid);
                } else {
                    String name = "Renamed" + region + "_" + slot;
                    shop.setName(name);
                    require(name.equals(shop.getName()), "renamed value");
                    entries.put(key(region, slot), new Entry(region, slot, old.uuid, name, false));
                    log("PASS", "rename-r" + region + "-s" + slot, name);
                    dirtySave(shop, region, slot);
                }
            }
            ShopkeepersAPI.getShopkeeperStorage().save();
            log("PASS", "delete-save-request-r" + region, "nonblocking request returned");
            if (completed.incrementAndGet() == 2) {
                completed.set(0);
                dispatch(0, "live-villagers", () -> checkEntities(0, 0));
                dispatch(1, "live-villagers", () -> checkEntities(1, 0));
            }
        } catch (Throwable ex) { fail("mutate-r" + region, ex); finish(false); }
    }

    private void persistAndPoll() {
        // Immutable expected values are captured before crossing to the disk worker.
        List<Entry> snapshot = entries.values().stream()
            .sorted(Comparator.comparingInt(Entry::region).thenComparingInt(Entry::slot)).toList();
        Bukkit.getAsyncScheduler().runNow(this, t -> {
            try {
                StringBuilder data = new StringBuilder();
                for (Entry e : snapshot) data.append(e.region).append('\t').append(e.slot).append('\t')
                    .append(e.uuid).append('\t').append(e.name).append('\t').append(e.deleted).append('\n');
                Path tmp = manifest.resolveSibling("manifest.tmp");
                Files.writeString(tmp, data);
                Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                pollDisk(snapshot, 0);
            } catch (Throwable ex) { fail("manifest-write", ex); finish(false); }
        });
    }

    private void pollDisk(List<Entry> expected, int attempt) {
        try {
            Path save = manifest.getParent().getParent().resolve("Shopkeepers/data/save.yml");
            boolean matched = false;
            if (Files.isRegularFile(save)) {
                // YAML parsing uses only detached data. No live Bukkit objects on the async thread.
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(save));
                Map<UUID, String> persisted = new HashMap<>();
                collect(yaml.getValues(false), persisted);
                matched = expected.stream().allMatch(e -> e.deleted
                    ? !persisted.containsKey(e.uuid) : e.name.equals(persisted.get(e.uuid)));
            }
            if (matched) {
                for (Entry e : expected) log("PASS", "disk-persistence-r" + e.region + "-s" + e.slot,
                    e.deleted ? "deleted UUID absent" : "UUID and renamed name persisted");
                finish(true);
            } else if (attempt >= 60) {
                fail("disk-persistence", new TimeoutException("save.yml did not match within 60 async polls"));
                finish(false);
            } else {
                Bukkit.getAsyncScheduler().runDelayed(this, t -> pollDisk(expected, attempt + 1), 1, TimeUnit.SECONDS);
            }
        } catch (Throwable ex) {
            // File replacement/partial reads may race the asynchronous writer; bounded retries.
            if (attempt < 60) Bukkit.getAsyncScheduler().runDelayed(this,
                t -> pollDisk(expected, attempt + 1), 1, TimeUnit.SECONDS);
            else { fail("disk-read", ex); finish(false); }
        }
    }

    private void collect(Object node, Map<UUID, String> result) {
        if (node instanceof org.bukkit.configuration.ConfigurationSection section) collect(section.getValues(false), result);
        else if (node instanceof Map<?, ?> map) {
            Object id = map.get("uniqueId");
            if (id == null) id = map.get("uuid");
            if (id != null && map.get("name") != null) {
                try { result.put(UUID.fromString(id.toString()), map.get("name").toString()); }
                catch (IllegalArgumentException ignored) { }
            }
            for (Object value : map.values()) collect(value, result);
        } else if (node instanceof Iterable<?> list) for (Object value : list) collect(value, result);
    }

    private void verifyRegion(int region) {
        for (int slot = 0; slot < 3; slot++) {
            Entry e = entries.get(key(region, slot));
            Shopkeeper shop = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(e.uuid);
            if (e.deleted) require(shop == null, "deleted UUID remains absent after cold reload");
            else {
                require(shop != null && shop.isValid(), "UUID restored after cold reload");
                require(e.name.equals(shop.getName()), "renamed name restored");
                require(shop.getX() == anchors[region].getBlockX() + slot * 2
                    && shop.getZ() == anchors[region].getBlockZ(), "location restored");
            }
            log("PASS", "cold-reload-r" + region + "-s" + slot, "uuid=" + e.uuid + " deleted=" + e.deleted);
        }
        // Activation may be deferred after chunk load. Retry without blocking the region.
        checkEntities(region, 0);
    }

    private void checkEntities(int region, int attempt) {
        try {
            boolean ready = true;
            for (int slot = 0; slot < 2; slot++) {
                Entry e = entries.get(key(region, slot));
                Shopkeeper shop = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(e.uuid);
                require(shop != null, "restored shop remains registered");
                var object = shop.getShopObject();
                if (!(object instanceof EntityShopObject entityObject) || entityObject.getEntity() == null) ready = false;
                else {
                    var entity = entityObject.getEntity();
                    require(Bukkit.isOwnedByCurrentRegion(entity), "owns restored entity");
                    require(entity.getType() == EntityType.VILLAGER && entity.isValid(), "live restored villager");
                }
            }
            if (ready) {
                log("PASS", (verify ? "cold-reload" : "created") + "-live-villagers-r" + region,
                    "two live villagers on owning region");
                if (completed.incrementAndGet() == 2) {
                    if (verify) finish(true); else persistAndPoll();
                }
            } else if (attempt >= 100) throw new IllegalStateException("Villagers did not activate in 100 region polls");
            else Bukkit.getRegionScheduler().runDelayed(this, anchors[region].clone(), t -> {
                if (!terminal.get()) checkEntities(region, attempt + 1);
            }, 2);
        } catch (Throwable ex) { fail("cold-reload-entities-r" + region, ex); finish(false); }
    }

    private static String key(int region, int slot) { return region + ":" + slot; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private void fail(String operation, Throwable error) {
        failures.incrementAndGet();
        log("FAIL", operation, error.toString());
        getLogger().log(Level.SEVERE, "Probe exception on " + Thread.currentThread().getName(), error);
    }
    private void log(String result, String operation, String detail) {
        getLogger().info("PROBE " + result + " " + operation + " thread=" + Thread.currentThread().getName() + " " + detail);
    }
    private void finish(boolean ok) {
        if (!terminal.compareAndSet(false, true)) return;
        log(ok && failures.get() == 0 ? "PASS" : "FAIL", verify ? "RELOAD_COMPLETE" : "PREPARE_COMPLETE",
            "failures=" + failures.get() + "; trades NOT tested");
    }
}
