#!/usr/bin/env python3
"""Check definitions, not mere type references, in every APK DEX file."""
import argparse,struct,zipfile
REQUIRED={
 'Ldev/quest/runtime/RuntimeApplication;',
 'Ldev/quest/runtime/RuntimeService;',
 'Ldev/quest/runtime/NativeApps;',
 'Ldev/quest/runtime/AppControl;',
 'Ldev/quest/runtime/TerminalActivity;',
 'Ldev/quest/runtime/TerminalSession;',
 'Ldev/quest/runtime/RuntimeEnvironment;',
 'Ltop/niunaijun/blackbox/BlackBoxCore;',
 'Ltop/niunaijun/blackbox/app/configuration/ClientConfiguration;',
 'Ltop/niunaijun/blackbox/core/system/SystemCallProvider;',
}
def definitions(data):
 if not data.startswith(b'dex\n'):raise ValueError('Not standard DEX')
 def u32(offset):return struct.unpack_from('<I',data,offset)[0]
 strings=u32(60);types=u32(68);count=u32(96);classes=u32(100)
 result=set()
 for i in range(count):
  type_index=u32(classes+32*i);string_index=u32(types+4*type_index)
  offset=u32(strings+4*string_index)
  while data[offset]&128:offset+=1
  offset+=1
  result.add(data[offset:data.index(b'\0',offset)].decode('utf-8',errors='replace'))
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk');a=p.parse_args()
 with zipfile.ZipFile(a.apk) as z:
  classes=set().union(*(definitions(z.read(n)) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')))
  missing=REQUIRED-classes
  for lib in ['libquesthost.so','libquesttelemetry.so','libquestnative.so','libdropbearmulti.so','libopenxr_loader.so','libblackbox.so','libquestterminal.so','libquestptyshell.so']:
   if 'lib/arm64-v8a/'+lib not in z.namelist():missing.add(lib)
  for asset in ['index.html','terminal.js','terminal.css','xterm.js','xterm.css']:
   if 'assets/terminal/'+asset not in z.namelist():missing.add(asset)
 if missing:raise SystemExit('APK INCOMPLETE: '+', '.join(sorted(missing)))
 print('PASS: required class definitions and native libraries present ('+str(len(classes))+' classes)')
