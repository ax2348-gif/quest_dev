#!/usr/bin/env python3
"""Device test, run from a computer that can SSH to the Quest. Opens XR apps."""
import argparse,json,pathlib,subprocess,time
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('first',type=pathlib.Path);p.add_argument('second',type=pathlib.Path)
p.add_argument('--host',default='100.72.77.83');p.add_argument('--port',type=int,default=8022)
p.add_argument('--proxy-command');p.add_argument('--known-hosts');
p.add_argument('--identity');p.add_argument('--require-frames',action='store_true')
p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('native-swap-results.json'))
a=p.parse_args()
ssh=['ssh','-F','/dev/null','-p',str(a.port),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=8']
if a.identity:ssh+=['-i',a.identity,'-o','IdentitiesOnly=yes']
if a.proxy_command:ssh+=['-o','ProxyCommand='+a.proxy_command]
if a.known_hosts:ssh+=['-o','UserKnownHostsFile='+a.known_hosts]
ssh+=['quest@'+a.host]
def remote(command,data=None):
 r=subprocess.run(ssh+[command],input=data,capture_output=True,timeout=30)
 if r.returncode:raise RuntimeError(r.stderr.decode()+r.stdout.decode())
 return r.stdout.decode()
def control(op):return json.loads(remote('quest-native '+op))
results={}
try:
 before=control('status');results['before']=before
 pids=[]
 for name,path in [('first',a.first),('second',a.second)]:
  dest='$HOME/workspace/native-swap-'+name+'.so'
  remote('cat > "'+dest+'"',path.read_bytes())
  state=control('run "'+dest+'"');results[name]=state
  assert state['ok'],state
  assert state['runtime_pid']==before['runtime_pid'],'SSH service process restarted'
  assert state.get('last_load_process_alive'),state
  assert state.get('last_load',{}).get('state')=='entry-returned',state
  pids.append(state['last_load']['pid'])
  if a.require_frames:
   # Entry return precedes asynchronous OpenXR initialization and TCP listen.
   deadline=time.monotonic()+18;line=''
   while time.monotonic()<deadline:
    line=remote('timeout 3 nc -w 1 127.0.0.1 9391 | head -n 1').strip()
    if line:break
    time.sleep(0.3)
   if not line:raise RuntimeError('No telemetry frame after waiting for XR initialization/focus')
   frame=json.loads(line);assert 'frame_seq' in frame,frame
   results[name+'_frame']=frame
 assert pids[0]!=pids[1],'Swap did not create a new XR process'
 stopped=control('stop');results['stopped']=stopped
 assert not stopped['xr_pids'],stopped
 assert stopped['runtime_pid']==before['runtime_pid']
 results['passed']=True
 print('PASS: two uploaded libraries entered in different XR processes; SSH process survived; XR stop passed.')
except Exception as error:
 results['error']=str(error)
 raise
finally:
 if not results.get('passed'):
  try:results['cleanup']=control('stop')
  except Exception as error:results['cleanup_error']=str(error)
 a.output.write_text(json.dumps(results,indent=2)+'\n')
