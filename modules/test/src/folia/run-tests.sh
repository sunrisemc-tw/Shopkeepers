#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after compileJava. Pipe through sed 's/\r$//' for CRLF files.
# Uses cached dependencies without changing Gradle dependency declarations.
folia_cache_root="${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2/files-2.1"
folia_java_root="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
folia_test_classes=$(mktemp -d /tmp/shopkeepers-folia-tests.XXXXXX)
folia_spigot_jar=$(rg --files "$folia_cache_root/org.spigotmc/spigot-api" |
	rg '/1\.21\.5-R0\.1-SNAPSHOT/.*\.jar$' | rg -v 'sources|javadoc' | head -1)
folia_cached_jars=$(rg --files "$folia_cache_root" |
	rg '\.jar$' | rg -v 'sources|javadoc' | paste -sd :)
folia_objenesis_jar=$(rg --files "${HOME}/.m2/repository/org/objenesis/objenesis" |
	rg '/3\.3/.*\.jar$' | head -1)
folia_test_cp="modules/main/build/classes/java/main:modules/api/build/classes/java/main"
folia_test_cp="$folia_test_cp:$folia_spigot_jar:$folia_cached_jars:$folia_objenesis_jar"

"$folia_java_root/bin/javac" -proc:none -cp "$folia_test_cp" -d "$folia_test_classes" \
	modules/test/src/folia/java/com/nisovin/shopkeepers/FoliaCorrectionsTests.java
"$folia_java_root/bin/java" -cp "$folia_test_classes:$folia_test_cp" \
	org.junit.runner.JUnitCore com.nisovin.shopkeepers.FoliaCorrectionsTests

# The native Folia API is available only in Paper's API jar.
folia_paper_jar=$(rg --files "$folia_cache_root/io.papermc.paper/paper-api" |
	rg '/1\.21\.5-R0\.1-SNAPSHOT/.*\.jar$' | rg -v 'sources|javadoc' | head -1)
folia_native_cp="$folia_paper_jar:$folia_test_cp"
"$folia_java_root/bin/javac" -proc:none -cp "$folia_native_cp" -d "$folia_test_classes" \
	modules/test/src/folia/java/com/nisovin/shopkeepers/FoliaNativeSchedulerTests.java
"$folia_java_root/bin/java" -cp "$folia_test_classes:$folia_native_cp" \
	org.junit.runner.JUnitCore com.nisovin.shopkeepers.FoliaNativeSchedulerTests
