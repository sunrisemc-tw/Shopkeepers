# Folia fork

This branch adds region-aware execution to Shopkeepers. It includes owner-thread snapshot capture, revisioned deletion records, a single storage writer, asynchronous reload/move completion, per-player trade merging, and concurrent notification preferences. Player-shop expiration and inactivity processing, shopkeeper ticking, same-chunk static (sign) moves, command confirmations and shop creation item selection are all dispatched to the owning region, and on non-Folia servers these paths run inline on the primary thread, so Spigot/Paper behavior is unchanged. FoliaLib 0.5.2 is shaded and relocated into the plugin.

## Build and test

Run the repository's Spigot dependency installer first. `scripts/folia-build-dependencies.py` provides the version-specific JDK driver. Building requires the cached Spigot/Paper dependencies and the JDK versions used by the individual NMS modules.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :shopkeepers-main:compileJava :shopkeepers-main:ecjAnalysis :shopkeepers-dist:shadowJar --console=plain --max-workers=2
python3 scripts/run-folia-tests.py
```

The standalone JUnit runner uses the local Gradle cache and an Objenesis jar in the local Maven repository. The native scheduler tests run in a separate JVM with Paper API first on the classpath; the remaining tests use Spigot API first. This avoids incompatible registry initialization between the API implementations.

## Verified behavior

The tested distribution is `Shopkeepers-2.28.2-SNAPSHOT.jar`, SHA-256 `955b9640af95df0cae1268511427388208bd97fbc47a128fdef9c0ef6f0c947f`.

- Complete distribution build and main-module ECJ null analysis succeeded.
- 97 standalone JUnit tests passed (94 general tests and 3 native scheduler tests).
- The upstream Gradle test suite also passed: 41 tests across 11 suites, zero failures or errors.
- On local Folia 26.2, shops in two regions 8192 blocks apart were created, renamed, deleted and saved. After a clean shutdown and cold restart, the four retained shops and two deletion records matched; two live villagers were present in each region.
- A real MCC player opened the editor, clicked to page 2, opened a merchant, paid two emeralds and received one diamond. One uncancelled trade event was observed. Inventory changed from eight to six emeralds and one diamond.
- Both scratch servers shut down with exit code zero and no detected server error/ownership exception.

## Review-round fixes

An independent review of the diff found seven owner-affinity and concurrency issues, all fixed in this round:

1. `SKShopkeeperRegistry.isOwnerThread` returned `true` unconditionally on non-Folia servers, so the asynchronous mannequin profile completion ran its shopkeeper mutation, event and save off the main thread (a Spigot regression). It now returns `Bukkit.isPrimaryThread()` on non-Folia, so the existing re-dispatch runs the work on the main thread.
2. A spawned static object (e.g. a sign, `mustBeSpawned`) moved within the same chunk was despawned at the source but never respawned at the destination, because same-chunk moves do not trigger the chunk activator. The destination branch now respawns active `mustBeSpawned` objects.
3. Player-shop expiration processing ran on the global thread and mutated each shop there; it now dispatches each shop's expiration to its owner and sends player reminders on the player scheduler.
4. Inactive-player shop deletion/for-hire restore ran on the global thread; it now dispatches each shop to its owner.
5. `Confirmations` used a plain `HashMap` across region threads; it is now a `ConcurrentHashMap` and the timeout removes only its own entry (identity check).
6. `ShopCreationItemSelectionTask` used a static `HashMap` across region threads; it is now a `ConcurrentHashMap` and cleanup removes only its own task instance.
7. Shopkeeper ticking bound to the dispatch-time location and only re-checked `isTicking`; it now dispatches via `runOnOwner` and re-validates the current owner so a shopkeeper whose entity changed region is not ticked on a stale region.

The same-chunk sign respawn fix (#2) was verified with a live Folia server probe (`verification/folia/signmove-probe/`): on the pre-fix jar the destination block was AIR immediately after the move (`signmove-red.out`), and on the fixed jar the sign is present immediately (`signmove-green.out`). The map-concurrency and confirmation/selection identity fixes (#5, #6) are covered by `ReviewRegressionTests`.

## Limits

Live validation covered villager admin shops and the listed workflows on Folia 26.2. Citizens integration, every shop/entity type, every supported Minecraft version at runtime, multiplayer load and long-running stress were not exercised by this harness. Compilation of version modules is not a runtime compatibility guarantee. Production installation has not been performed.

Synchronous cross-region API calls cannot wait for other region threads. Use asynchronous completion paths for owner-scoped persistence, reload and moving. Shutdown storage flushes already captured snapshots without spawning entities on stopped region threads.
