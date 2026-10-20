package dev.quest.runtime;
import android.app.Application;
import android.content.Context;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/** Initialize the APK container in its host/server/guest processes only. */
public final class RuntimeApplication extends Application {
 private boolean containerProcess;
 protected void attachBaseContext(Context base) {
  super.attachBaseContext(base);
  String name=Application.getProcessName();
  containerProcess=!name.equals("dev.quest.runtime:xr");
  if(!containerProcess)return;
  BlackBoxCore.get().doAttachBaseContext(base,new ClientConfiguration(){
   public String getHostPackageName(){return "dev.quest.runtime";}
   public boolean isEnableDaemonService(){return false;}
   public boolean isEnableLauncherActivity(){return false;}
   public String getLogSenderChatId(){return null;}
  });
 }
 public void onCreate(){super.onCreate();if(containerProcess)BlackBoxCore.get().doCreate();}
}
