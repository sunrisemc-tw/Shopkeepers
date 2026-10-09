#!/usr/bin/env python3
from pathlib import Path
import subprocess, shutil, zipfile
ROOT = Path(__file__).resolve().parent
REPO = Path('/home/user/Data/Dev/Java/Shopkeepers')
JDK = ROOT / 'jdk-25.0.4.1'
API = REPO / 'modules/api/build/classes/java/main'
if not (API / 'com/nisovin/shopkeepers/api/ShopkeepersAPI.class').is_file():
    raise SystemExit('Built Shopkeepers API classes missing; no repository build is performed')
papers = list(Path('/home/user/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/1.21.5-R0.1-SNAPSHOT').rglob('*.jar'))
if not papers: raise SystemExit('Real cached Paper API missing')
paper = papers[0]
deps = sorted(p for p in Path('/home/user/.gradle/caches/modules-2/files-2.1').rglob('*.jar')
    if 'paper-api' not in str(p) and 'spigot-api' not in str(p) and not p.name.endswith(('-sources.jar', '-javadoc.jar')))
classpath = ':'.join(map(str, [API, paper, *deps]))
classes = ROOT / 'build/classes'
classes.mkdir(parents=True, exist_ok=True)
cmd = [str(JDK / 'bin/javac'), '--release', '25', '-encoding', 'UTF-8', '-classpath', classpath,
    '-d', str(classes), str(ROOT / 'src/harness/Probe.java')]
log = subprocess.run([str(JDK / 'bin/javac'), '-version'], capture_output=True, text=True)
result = subprocess.run(cmd, capture_output=True, text=True)
text = log.stdout + log.stderr + 'ShopkeepersAPI: ' + str(API) + '\nPaper API: ' + str(paper) + '\n' + result.stdout + result.stderr + 'javac exit=' + str(result.returncode) + '\n'
(ROOT / 'build/compilation.log').write_text(text)
print(text, end='')
if result.returncode: raise SystemExit(result.returncode)
shutil.copyfile(ROOT / 'plugin.yml', classes / 'plugin.yml')
jar = ROOT / 'build/ShopkeepersFoliaProbe.jar'
subprocess.run([str(JDK / 'bin/jar'), '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
with zipfile.ZipFile(jar) as z:
    assert 'harness/Probe.class' in z.namelist()
    assert not any(n.startswith(('org/bukkit/', 'com/nisovin/')) for n in z.namelist())
print('JAR OK: ' + str(jar))
