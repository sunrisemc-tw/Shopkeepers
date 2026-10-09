#!/usr/bin/env python3
"""Run the standalone Folia JUnit suites after compiling the main module."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"


def main():
    jars = sorted(p for p in CACHE.rglob("*.jar") if not any(s in p.name for s in ("sources", "javadoc")))
    api = next(p for p in jars if "/org.spigotmc/spigot-api/1.21.5-R0.1-SNAPSHOT/" in str(p))
    paper = next(p for p in jars if "/io.papermc.paper/paper-api/1.21.5-R0.1-SNAPSHOT/" in str(p))
    objenesis = list((Path.home() / ".m2/repository/org/objenesis/objenesis").rglob("*.jar"))
    classpath = os.pathsep.join(map(str, [api, ROOT / "modules/main/build/classes/java/main", ROOT / "modules/api/build/classes/java/main"] + jars + objenesis))
    sources = sorted((ROOT / "modules/test/src/folia/java").rglob("*.java"))
    classes = [str(p.relative_to(ROOT / "modules/test/src/folia/java").with_suffix("")).replace(os.sep, ".") for p in sources if p.name.endswith("Tests.java")]
    native = "com.nisovin.shopkeepers.FoliaNativeSchedulerTests"
    with tempfile.TemporaryDirectory(prefix="shopkeepers-folia-all-") as output:
        regular = [p for p in sources if p.name != "FoliaNativeSchedulerTests.java"]
        native_sources = [p for p in sources if p.name == "FoliaNativeSchedulerTests.java"]
        for group, cp in ((regular, classpath), (native_sources, str(paper) + os.pathsep + classpath)):
            subprocess.run([str(JAVA / "javac"), "-proc:none", "-cp", cp, "-d", output] + list(map(str, group)), cwd=ROOT, check=True)
        for group, cp in (([c for c in classes if c != native], classpath), ([native], str(paper) + os.pathsep + classpath)):
            result = subprocess.run([str(JAVA / "java"), "-ea", "-cp", output + os.pathsep + cp, "org.junit.runner.JUnitCore"] + group, cwd=ROOT)
            if result.returncode:
                return result.returncode
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
