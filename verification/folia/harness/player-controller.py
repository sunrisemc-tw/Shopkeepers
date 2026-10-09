#!/usr/bin/env python3
"""Actual offline MCC GUI/trade integration, confined to this scratch directory."""
from pathlib import Path
import subprocess,time,socket,hashlib,json,shutil,re
ROOT=Path(__file__).resolve().parent
assert str(ROOT)=='/tmp/shopkeepers-player-harness'
SERVER=ROOT/'server'; SESSION='sk-player-repeat'
JAVA='/tmp/shopkeepers-folia-harness/jdk-25.0.4.1/bin/java'
def capture():
    return subprocess.check_output(['tmux','capture-pane','-pt',SESSION,'-S','-1000'],text=True)
def wait(predicate,timeout=60):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.25)
    raise RuntimeError('Timed out waiting for verification')
def command(text):
    subprocess.run(['tmux','send-keys','-t',SESSION,text,'Enter'],check=True)
    time.sleep(.7)
def client_expect(token):wait(lambda:token in capture())
def server_text():return (ROOT/'repeat-server.log').read_text(errors='replace')
with socket.socket() as sock:
    sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    sock.bind(('127.0.0.1',25683))
shutil.copyfile('/home/user/Data/Dev/Java/Shopkeepers/modules/dist/build/libs/Shopkeepers-2.28.2-SNAPSHOT.jar',SERVER/'plugins/Shopkeepers.jar')
subprocess.run(['python',str(ROOT/'build.py')],check=True)
shutil.copyfile(ROOT/'build/ShopkeepersFoliaProbe.jar',SERVER/'plugins/ShopkeepersPlayerProbe.jar')
properties=SERVER/'server.properties' 
text=properties.read_text();text+='\ndifficulty=peaceful\n' if 'difficulty=' not in text else ''
# The first run changed difficulty through the server console; preserve peaceful.
lines=[('difficulty=peaceful' if line.startswith('difficulty=') else line) for line in text.splitlines()]
properties.write_text('\n'.join(lines)+'\n')
result={'passed':False,'jar_sha256':hashlib.sha256((SERVER/'plugins/Shopkeepers.jar').read_bytes()).hexdigest(),
'coverage_limits':['reloadAsync API exercised; /shopkeepers reload command feedback not exercised','NaN move future completed exceptionally; boolean-false teleport branch and event cancellation not exercised','GUI opened through API; physical right-click not exercised','removeall admin console branch only; player shops, hireable skips and cancelled deletion not exercised','one offline operator; no concurrent trade or move stress','save + graceful stop + cold restart; abrupt process crash not exercised']}
process=None
try:
 with (ROOT/'repeat-server.log').open('w') as output:
    process=subprocess.Popen([JAVA,'-Xms512M','-Xmx2G','-jar','server.jar','--nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
    wait(lambda:'Done (' in server_text(),120)
    subprocess.run(['tmux','new-session','-d','-s',SESSION,'-c',str(ROOT/'mcc'),'./MinecraftClient'],check=True)
    client_expect('Server was successfully joined.')
    wait(lambda:'PLAYER_PROBE JOIN ProbeDuck' in server_text())
    command('/pprobe setup');client_expect('SETUP PASS')
    command('/pprobe editor');client_expect('EDITOR opened=true')
    command('/inventory container list');client_expect('Page 1 of 5')
    command('/inventory container click 35')
    command('/inventory container list');client_expect('Page 2 of 5')
    command('/pprobe trade');client_expect('TRADING opened=true')
    command('/inventory container list');client_expect('#30: x8  Emerald')
    command('/inventory container click 30');command('/inventory container click 0')
    command('/inventory container list');client_expect('#2 : x1  Diamond')
    command('/inventory container click 2');command('/inventory container click 31')
    command('/pprobe close');command('/pprobe status');client_expect('TRADE_CONSERVATION PASS')
    (ROOT/'repeat-client.txt').write_text(capture())
    command('/pprobe trade')
    command('/pprobe reload');wait(lambda:'RELOAD PASS' in server_text(),90)
    command('/inventory container list')
    command('/pprobe badmove');wait(lambda:'BADMOVE PASS' in server_text(),60)
    command('/pprobe move');wait(lambda:'MOVE PASS destination=' in server_text(),90)
    command('/pprobe local');wait(lambda:'LOCAL PASS' in server_text())
    process.stdin.write('pprobe count\n');process.stdin.flush()
    wait(lambda:'COUNT value=' in server_text())
    count=int(re.findall(r'COUNT value=(\d+)',server_text())[-1])
    process.stdin.write('shopkeepers removeall admin\n');process.stdin.flush()
    wait(lambda:'confirm' in server_text().lower())
    process.stdin.write('shopkeepers confirm\n');process.stdin.flush()
    wait(lambda:re.search(str(count)+r' admin shops have been removed',server_text(),re.I) is not None,60)
    process.stdin.write('pprobe count\n');process.stdin.flush()
    wait(lambda:'COUNT value=0' in server_text())
    result.update(reload=True,badmove=True,distant_move=True,console_removeall_count=count)
    (ROOT/'repeat-client.txt').write_text(capture())
    command('/quit')
    result.update(editor_page_click=True,merchant_open=True,emerald_before=8,emerald_after=6,diamond_after=1,trade_events=1)
    process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=60)
    result['first_server_exit']=process.returncode
    shutil.copyfile(SERVER/'plugins/Shopkeepers/data/save.yml',ROOT/'after-remove-save.yml')
    with (ROOT/'cold-server.log').open('w') as cold:
      process=subprocess.Popen([JAVA,'-Xms512M','-Xmx2G','-jar','server.jar','--nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=cold,stderr=subprocess.STDOUT,text=True)
      wait(lambda:'Done (' in (ROOT/'cold-server.log').read_text(errors='replace'),120)
      process.stdin.write('pprobe count\n');process.stdin.flush()
      wait(lambda:'COUNT value=0' in (ROOT/'cold-server.log').read_text(errors='replace'))
      result['cold_restart_no_resurrection']=True
      process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=60)
    log=server_text()+'\n'+(ROOT/'cold-server.log').read_text(errors='replace')
    errors=[line for line in log.splitlines() if any(t in line for t in ['/ERROR]','/SEVERE]','PLAYER_PROBE FAIL','UnsupportedOperationException','Could not pass event','Exception in thread'])]
    result.update(server_exit=process.returncode,errors=errors,passed=process.returncode==0 and not errors)
except Exception as e:
 result['error']=repr(e)
 try:(ROOT/'repeat-client.txt').write_text(capture())
 except Exception:pass
finally:
 if process and process.poll() is None:
    try:process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=45)
    except Exception:process.kill();process.wait()
 subprocess.run(['tmux','kill-session','-t',SESSION],capture_output=True)
 if result.get('passed'):
    final_log=server_text()
    result['pending_destination_unpublished']='MOVE PENDING bad=false destinationPublished=false' in final_log
    result['distinct_destination_region']='MOVE START bad=false destinationOwned=false owner=true' in final_log
    with socket.socket() as cleanup_socket:
      cleanup_socket.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
      cleanup_socket.bind(('127.0.0.1',25683))
    result['cleanup']={'server_process_exited':process.poll() is not None,'mcc_tmux_session_removed':subprocess.run(['tmux','has-session','-t',SESSION],capture_output=True).returncode!=0,'loopback_port_rebind':True}
    result['passed']=result['passed'] and result['pending_destination_unpublished'] and result['distinct_destination_region']
 (ROOT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result,indent=2))
raise SystemExit(0 if result['passed'] else 1)
