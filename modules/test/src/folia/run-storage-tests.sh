#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
storage_cache_root="${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2/files-2.1"
storage_java_root="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
storage_test_classes=$(mktemp -d /tmp/shopkeepers-storage-test-classes.XXXXXX)
storage_spigot_jar=$(rg --files "$storage_cache_root/org.spigotmc/spigot-api" |
	rg '/1\.21\.5-R0\.1-SNAPSHOT/.*\.jar$' | rg -v 'sources|javadoc' | head -1)
storage_cached_jars=$(rg --files "$storage_cache_root" |
	rg '\.jar$' | rg -v 'sources|javadoc' | paste -sd :)
storage_objenesis_jar=$(rg --files "${HOME}/.m2/repository/org/objenesis/objenesis" |
	rg '/3\.3/.*\.jar$' | head -1)
storage_test_cp="modules/main/build/classes/java/main:modules/api/build/classes/java/main"
storage_test_cp="$storage_test_cp:$storage_spigot_jar:$storage_cached_jars:$storage_objenesis_jar"

"$storage_java_root/bin/javac" -proc:none -cp "$storage_test_cp" -d "$storage_test_classes" \
	modules/main/src/main/java/com/nisovin/shopkeepers/storage/FoliaStorageCoordinator.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/storage/SKShopkeeperStorage.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/AbstractShopkeeper.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/SKShopkeeperRegistry.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/ShopkeeperChunkMap.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/WorldShopkeepers.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/ChunkShopkeepers.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/ActiveChunkQueries.java \
	modules/test/src/folia/java/com/nisovin/shopkeepers/FoliaCorrectionsTests.java \
	modules/test/src/folia/java/com/nisovin/shopkeepers/storage/StorageCorrectionsTests.java \
	modules/test/src/folia/java/com/nisovin/shopkeepers/shopkeeper/registry/RegistryCorrectionsTests.java
"$storage_java_root/bin/java" -ea -cp "$storage_test_classes:$storage_test_cp" \
	org.junit.runner.JUnitCore com.nisovin.shopkeepers.storage.StorageCorrectionsTests \
	com.nisovin.shopkeepers.shopkeeper.registry.RegistryCorrectionsTests
