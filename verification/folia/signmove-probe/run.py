#!/usr/bin/env python3
"""Build + run the isolated same-chunk sign-move probe against a given Shopkeepers jar.

Usage: run.py /path/to/Shopkeepers.jar
Exit 0 = probe PASS (sign respawned at destination). Exit 1 = FAIL.
This is a scratch-only harness: no RCON, no credentials, no production paths.
"""
from pathlib import Path
import sys, os, shutil, subprocess, zipfile, socket, time, hashlib

ROOT = Path(__file__).resolve().parent
HARNESS = Path('/tmp/shopkeepers-folia-harness')
JDK = HARNESS / 'jdk-25.0.4.1'
SERVER = ROOT / 'server'
PORT = 25686


def build_probe():
    api = Path('/home/user/Data/Dev/Java/Shopkeepers/modules/api/build/classes/java/main')
    if not (api / 'com/nisovin/shopkeepers/api/ShopkeepersAPI.class').is_file():
        raise SystemExit('Built Shopkeepers API classes missing')
    papers = list(Path('/home/user/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/1.21.5-R0.1-SNAPSHOT').rglob('*.jar'))
    if not papers:
        raise SystemExit('Real cached Paper API missing')
    deps = sorted(p for p in Path('/home/user/.gradle/caches/modules-2/files-2.1').rglob('*.jar')
        if 'spigot-api' not in str(p) and not p.name.endswith(('-sources.jar', '-javadoc.jar')))
    classpath = os.pathsep.join(map(str, [api, papers[0], *deps]))
    classes = ROOT / 'build/classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    subprocess.run([str(JDK / 'bin/javac'), '--release', '25', '-encoding', 'UTF-8',
        '-classpath', classpath, '-d', str(classes), str(ROOT / 'src/harness/SignMoveProbe.java')],
        check=True)
    shutil.copyfile(ROOT / 'plugin.yml', classes / 'plugin.yml')
    jar = ROOT / 'build/ShopkeepersSignMoveProbe.jar'
    subprocess.run([str(JDK / 'bin/jar'), '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
    with zipfile.ZipFile(jar) as z:
        assert 'harness/SignMoveProbe.class' in z.namelist()
        assert not any(n.startswith(('org/bukkit/', 'com/nisovin/')) for n in z.namelist())
    return jar


def stage(shopkeepers_jar, probe_jar):
    shopkeepers_jar = Path(shopkeepers_jar).resolve()
    with zipfile.ZipFile(shopkeepers_jar) as z:
        descriptor = z.read('plugin.yml').decode()
        if 'folia-supported: true' not in descriptor:
            raise SystemExit('Expected a Folia-enabled Shopkeepers jar')
    if SERVER.exists():
        shutil.rmtree(SERVER)
    (SERVER / 'plugins/Shopkeepers').mkdir(parents=True)
    shutil.copyfile(HARNESS / 'server/server.jar' if (HARNESS / 'server/server.jar').exists()
        else '/home/user/mc-test/folia/server.jar', SERVER / 'server.jar')
    shutil.copyfile(shopkeepers_jar, SERVER / 'plugins/Shopkeepers.jar')
    shutil.copyfile(probe_jar, SERVER / 'plugins/ShopkeepersSignMoveProbe.jar')
    (SERVER / 'plugins/Shopkeepers/config.yml').write_text('save-instantly: true\n')
    (SERVER / 'eula.txt').write_text('eula=true\n')
    (SERVER / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=%d\nonline-mode=false\nenable-rcon=false\n'
        'enable-query=false\nlevel-name=probe_world\nlevel-type=minecraft:flat\n'
        'generate-structures=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n'
        'max-players=1\nallow-nether=false\nmotd=sign move probe\n' % PORT)


def run():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', PORT))
    log = ROOT / 'signmove.log'
    process = subprocess.Popen([str(JDK / 'bin/java'), '-Xms512M', '-Xmx2G', '-jar', 'server.jar', '--nogui'],
        cwd=SERVER, stdin=subprocess.PIPE, stdout=log.open('w'), stderr=subprocess.STDOUT, text=True)
    try:
        deadline = time.monotonic() + 180
        success = None
        while process.poll() is None and time.monotonic() < deadline:
            text = log.read_text(errors='replace')
            if 'PROBE PASS SIGNMOVE_COMPLETE' in text:
                success = True
                break
            if 'PROBE FAIL ' in text:
                success = False
                break
            time.sleep(0.25)
        if process.poll() is None:
            try:
                process.stdin.write('stop\n'); process.stdin.flush()
            except BrokenPipeError:
                pass
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
        text = log.read_text(errors='replace')
        for line in text.splitlines():
            if 'PROBE ' in line:
                print(line.split('PROBE ', 1)[1])
        errors = [l for l in text.splitlines() if any(tok in l for tok in
            ('/ERROR]', '/SEVERE]', 'Exception in thread', 'Could not pass event'))]
        for line in errors:
            print('SERVER_ERROR: ' + line)
        ok = success is True and not errors
        print(('PASS' if ok else 'FAIL') + ' signmove; log=' + str(log))
        return 0 if ok else 1
    finally:
        if process.poll() is None:
            process.kill(); process.wait()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: run.py /path/to/Shopkeepers.jar')
    jar = Path(sys.argv[1]).resolve()
    print('Shopkeepers jar SHA256', hashlib.sha256(jar.read_bytes()).hexdigest())
    probe = build_probe()
    stage(jar, probe)
    sys.exit(run())
