#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
lifecycle_cache_root="${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2/files-2.1"
lifecycle_java_root="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
lifecycle_classes=$(mktemp -d /tmp/shopkeepers-lifecycle-tests.XXXXXX)
lifecycle_spigot_jar=$(rg --files "$lifecycle_cache_root/org.spigotmc/spigot-api" |
	rg '/1\.21\.5-R0\.1-SNAPSHOT/.*\.jar$' | rg -v 'sources|javadoc' | head -1)
lifecycle_jars=$(rg --files "$lifecycle_cache_root" |
	rg '\.jar$' | rg -v 'sources|javadoc' | paste -sd :)
lifecycle_objenesis=$(rg --files "${HOME}/.m2/repository/org/objenesis/objenesis" |
	rg '/3\.3/.*\.jar$' | head -1)
lifecycle_cp="modules/main/build/classes/java/main:modules/api/build/classes/java/main"
lifecycle_cp="$lifecycle_cp:$lifecycle_spigot_jar:$lifecycle_jars:$lifecycle_objenesis"

"$lifecycle_java_root/bin/javac" -proc:none -cp "$lifecycle_cp" -d "$lifecycle_classes" \
	modules/main/src/main/java/com/nisovin/shopkeepers/SKShopkeepersPlugin.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/SKShopkeeperRegistry.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/ShopkeeperChunkMap.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/registry/ShopObjectRegistry.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/ticking/ShopkeeperTicker.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/AbstractShopkeeper.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopobjects/entity/base/BaseEntityShopObject.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/activation/ChunkData.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/activation/ShopkeeperChunkActivator.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/spawning/ShopkeeperSpawner.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/spawning/WorldData.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/spawning/WorldSaveDespawner.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/commands/shopkeepers/CommandRemote.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/commands/shopkeepers/CommandEdit.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/commands/shopkeepers/CommandReload.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/commands/shopkeepers/CommandRemoveAll.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/commands/shopkeepers/CommandReplaceAllWithVanillaVillagers.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/naming/ShopkeeperNaming.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/moving/ShopkeeperMoving.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/editor/ActionButton.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/editor/EditorView.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/editor/ShopkeeperEditorView.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/editor/ShopkeeperEditorLayout.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/editor/ShopkeeperEditorViewProvider.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/ui/lib/UISessionManager.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/player/AbstractPlayerShopkeeper.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/player/PlayerShopEditorLayout.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopkeeper/admin/regular/RegularAdminShopEditorViewProvider.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopobjects/living/SKLivingShopObject.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/shopobjects/living/types/MannequinShop.java \
	modules/main/src/main/java/com/nisovin/shopkeepers/world/ForcingEntityTeleporter.java \
	modules/test/src/folia/java/com/nisovin/shopkeepers/FoliaCorrectionsTests.java \
	modules/test/src/folia/java/com/nisovin/shopkeepers/LifecycleCorrectionsTests.java
"$lifecycle_java_root/bin/java" -ea -cp "$lifecycle_classes:$lifecycle_cp" \
	org.junit.runner.JUnitCore com.nisovin.shopkeepers.LifecycleCorrectionsTests
