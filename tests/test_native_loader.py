"""Host-only loader logic checks with minimal Android API stubs, not device proof."""
import json,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class LoaderTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.d=pathlib.Path(self.tmp.name)
  h=self.d/'android';h.mkdir();(self.d/'native').mkdir()
  (h/'native_activity.h').write_text('''#pragma once
#include <stddef.h>
#define JNIEXPORT
 typedef struct ANativeActivity { const char *internalDataPath; } ANativeActivity;
 void ANativeActivity_finish(ANativeActivity *);
''')
  (h/'log.h').write_text('#define ANDROID_LOG_INFO 4\nint __android_log_print(int,const char*,const char*,...);\n')
  (self.d/'driver.c').write_text('''#include <android/native_activity.h>
static int finished;
void ANativeActivity_finish(ANativeActivity *a){(void)a;finished=1;}
int __android_log_print(int p,const char*t,const char*f,...){return 0;}
extern void ANativeActivity_onCreate(ANativeActivity*,void*,size_t);
int main(int argc,char**argv){ANativeActivity a={argv[1]};ANativeActivity_onCreate(&a,0,0);return finished;}
''')
  self.cc=['cc','-I'+str(self.d),'-I'+str(ROOT/'native-sdk')]
  subprocess.run([*self.cc,'-rdynamic',self.d/'driver.c',ROOT/'app/src/main/cpp/quest_host.c','-ldl','-o',self.d/'driver'],check=True)
 def tearDown(self):self.tmp.cleanup()
 def run_loader(self,code=None):
  path=self.d/'app.so'
  if code is not None:
   (self.d/'app.c').write_text('#include "quest_native_app.h"\n#include <stdio.h>\n'+code)
   subprocess.run([*self.cc,'-fPIC','-shared',self.d/'app.c','-o',path],check=True)
  (self.d/'native/selected').write_text(str(path)+'\n')
  r=subprocess.run([self.d/'driver',self.d],capture_output=True)
  return r,json.loads((self.d/'native/load-status.json').read_text())
 def test_missing_library_finishes_with_diagnostic(self):
  r,s=self.run_loader();self.assertEqual(r.returncode,1);self.assertEqual(s['state'],'error')
 def test_missing_entry_finishes_with_diagnostic(self):
  r,s=self.run_loader('const QuestNativeAppV1 *quest_native_app_v1(void){return 0;}')
  self.assertEqual(r.returncode,1);self.assertIn('Missing',s['detail'])
 def test_wrong_abi_is_not_called(self):
  r,s=self.run_loader('''const QuestNativeAppV1 *quest_native_app_v1(void){static QuestNativeAppV1 v={99,sizeof(v),"bad"};return &v;}
 void ANativeActivity_onCreate(ANativeActivity*a,void*s,size_t n){__builtin_trap();}''')
  self.assertEqual(r.returncode,1);self.assertIn('Incompatible',s['detail'])
 def test_valid_entry_called_and_json_escaped(self):
  r,s=self.run_loader('''const QuestNativeAppV1 *quest_native_app_v1(void){static QuestNativeAppV1 v={1,sizeof(v),"test \\"app\\""};return &v;}
 void ANativeActivity_onCreate(ANativeActivity*a,void*s,size_t n){char p[1024];snprintf(p,sizeof(p),"%s/called",a->internalDataPath);fclose(fopen(p,"w"));}'''.replace('\\"','\\"'))
  self.assertEqual(r.returncode,0);self.assertEqual(s['state'],'entry-returned');self.assertTrue((self.d/'called').exists())
if __name__=='__main__':unittest.main()
