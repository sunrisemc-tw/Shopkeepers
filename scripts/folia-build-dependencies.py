#!/usr/bin/env python3
"""Build missing official CraftBukkit artifacts with system JDKs."""
from pathlib import Path
import subprocess
import os
import urllib.request

work = Path.home() / 'spigot-build-shopkeepers'
work.mkdir(exist_ok=True)
tool = work / 'BuildTools.jar'
if not tool.exists():
    urllib.request.urlretrieve('https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar', tool)
versions = [('1.21.5', 'R0.1', 21, True), ('1.21.6', 'R0.1', 21, True), ('1.21.8', 'R0.1', 21, True), ('1.21.10', 'R0.1', 21, True), ('1.21.11', 'R0.2', 21, True), ('26.1.2', 'R0.1', 25, False), ('26.2', 'R0.1', 25, False), ('26.3', 'R0.1', 25, False)]
for version, revision, jdk, remapped in versions:
    root = Path.home() / '.m2/repository/org/bukkit/craftbukkit' / f'{version}-{revision}-SNAPSHOT'
    classifier = '-remapped-mojang' if remapped else ''
    expected = root / f'craftbukkit-{version}-{revision}-SNAPSHOT{classifier}.jar'
    if expected.exists() and expected.stat().st_size > 0:
        print(f'CACHED {version}', flush=True)
        continue
    java_home = Path(f'/usr/lib/jvm/java-{jdk}-openjdk-amd64')
    if not (java_home / 'bin/javac').exists():
        java_home = Path('/tmp/shopkeepers-folia-harness/jdk-25.0.4.1')
    if not (java_home / 'bin/javac').exists():
        raise SystemExit(f'Full JDK {jdk} required; javac missing')
    args = [str(java_home / 'bin/java'), '-Xmx1500M', '-jar', str(tool),
            '--rev', version, '--compile', 'CRAFTBUKKIT,SPIGOT']
    if remapped:
        args.append('--remapped')
    print(f'BUILD {version}', flush=True)
    log = work / f'build-{version}.log'
    build_env = os.environ.copy()
    build_env['JAVA_HOME'] = str(java_home)
    build_env['PATH'] = build_env['JAVA_HOME'] + '/bin:' + build_env['PATH']
    with log.open('w') as output:
        result = subprocess.run(args, cwd=work, env=build_env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode != 0 or not expected.exists():
        print(log.read_text(errors='replace')[-7000:], flush=True)
        raise SystemExit(f'FAILED {version}: exit={result.returncode}; expected artifact exists={expected.exists()}')
    print(f'INSTALLED {version}', flush=True)
