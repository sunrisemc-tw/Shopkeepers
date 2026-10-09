#!/usr/bin/env python3
"""Isolated Folia harness controller. No RCON, no credentials, no production paths."""
from pathlib import Path
import sys, shutil, subprocess, zipfile, time, socket, os
ROOT = Path(__file__).resolve().parent
RUN = ROOT / 'server'
JAVA = ROOT / 'jdk-25.0.4.1/bin/java'
MARKER = RUN / '.scratch-harness'

def stage(jar):
    if RUN.exists(): raise SystemExit('server directory already exists; refusing to overwrite worlds or plugin data')
    jar = Path(jar).resolve()
    with zipfile.ZipFile(jar) as z:
        descriptor = z.read('plugin.yml').decode()
        if 'Shopkeepers' not in descriptor or 'folia-supported: true' not in descriptor:
            raise SystemExit('Expected a built Shopkeepers plugin with folia-supported: true')
    RUN.mkdir()
    MARKER.write_text('Shopkeepers Folia scratch only\n')
    (RUN / 'plugins/Shopkeepers').mkdir(parents=True)
    shutil.copyfile('/home/user/mc-test/folia/server.jar', RUN / 'server.jar')
    shutil.copyfile(jar, RUN / 'plugins/Shopkeepers.jar')
    shutil.copyfile(ROOT / 'build/ShopkeepersFoliaProbe.jar', RUN / 'plugins/ShopkeepersFoliaProbe.jar')
    (RUN / 'plugins/Shopkeepers/config.yml').write_text('save-instantly: true\n')
    (RUN / 'eula.txt').write_text('eula=true\n')
    (RUN / 'server.properties').write_text('''server-ip=127.0.0.1
server-port=25682
online-mode=false
enable-rcon=false
enable-query=false
level-name=probe_world
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=2
allow-nether=false
motd=Shopkeepers isolated scratch probe
''')
    print('STAGED ' + str(RUN) + '; server NOT started')

def run(phase):
    if not MARKER.is_file(): raise SystemExit('Run stage first with the actual built Shopkeepers dist jar')
    manifest = RUN / 'plugins/ShopkeepersFoliaProbe/manifest.tsv'
    if (phase == 'prepare') == manifest.exists():
        raise SystemExit('prepare requires no manifest; reload requires manifest from successful prepare')
    if phase == 'reload' and not (ROOT / 'prepare.ok').is_file():
        raise SystemExit('prepare must have completed successfully first')
    lock = ROOT / '.running'
    try: fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError: raise SystemExit('Harness run lock exists; refusing simultaneous startup')
    os.close(fd)
    process = None
    try:
        with socket.socket() as sock: sock.bind(('127.0.0.1', 25682))
        logpath = ROOT / (phase + '.log')
        with logpath.open('w') as output:
            process = subprocess.Popen([str(JAVA), '-Xms512M', '-Xmx2G', '-jar', 'server.jar', '--nogui'],
                cwd=RUN, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
            deadline = time.monotonic() + 240
            success = False
            target = 'PROBE PASS ' + ('PREPARE_COMPLETE' if phase == 'prepare' else 'RELOAD_COMPLETE')
            while process.poll() is None and time.monotonic() < deadline:
                text = logpath.read_text(errors='replace')
                if 'PROBE FAIL ' in text: break
                if target in text: success = True; break
                time.sleep(0.25)
            if process.poll() is None:
                try: process.stdin.write('stop\n'); process.stdin.flush()
                except BrokenPipeError: pass
                try: process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                    success = False
            text = logpath.read_text(errors='replace')
            errors = [line for line in text.splitlines() if any(token in line for token in
                ('/ERROR]', ' ERROR ', '/SEVERE]', 'PROBE FAIL ', 'UnsupportedOperationException',
                 'IllegalStateException', 'Exception in thread', 'Could not pass event'))]
            success = success and process.returncode == 0 and not errors
            print('\n'.join(line for line in text.splitlines() if 'PROBE ' in line))
            for line in errors: print('SERVER_ERROR: ' + line)
            print(('PASS' if success else 'FAIL') + ' launcher ' + phase + '; log=' + str(logpath))
            if success: (ROOT / (phase + '.ok')).write_text('passed\n')
            else: (ROOT / (phase + '.ok')).unlink(missing_ok=True)
            return 0 if success else 1
    finally:
        if process is not None and process.poll() is None: process.kill(); process.wait()
        lock.unlink(missing_ok=True)

if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == 'stage': stage(sys.argv[2])
    elif len(sys.argv) == 2 and sys.argv[1] in ('prepare', 'reload'): sys.exit(run(sys.argv[1]))
    else: raise SystemExit('Usage: harness.py stage /path/to/built/Shopkeepers.jar | prepare | reload')
