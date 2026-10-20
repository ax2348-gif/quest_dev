package dev.quest.runtime;
import android.content.Context;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Shared by local and remote shells; no network or key provisioning required. */
final class RuntimeEnvironment {
    static synchronized File prepare(Context context) throws Exception {
        File home=new File(context.getFilesDir(),"home");
        for(String name:new String[]{"", ".ssh", "bin", "tmp", "workspace"}) {
            File dir=new File(home,name);
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create "+dir);
            Os.chmod(dir.getPath(),0700);
        }
        String nativeDir=context.getApplicationInfo().nativeLibraryDir;
        String[][] commands={{"libdropbearmulti.so","dropbear"},{"libdropbearmulti.so","dropbearkey"},
            {"librsync.so","rsync"},{"libquestlaunch.so","quest-launch"},
            {"libquestapp.so","quest-app"},{"libquestnative.so","quest-native"}};
        for(String[] item:commands){
            File dest=new File(home,"bin/"+item[1]);String source=nativeDir+"/"+item[0];
            if(Files.isSymbolicLink(dest.toPath())){
                if(Os.readlink(dest.getPath()).equals(source))continue;
            }else if(dest.exists())throw new IOException("Refusing to replace "+dest);
            File temp=new File(home,"bin/.link-"+UUID.randomUUID());
            try{Os.symlink(source,temp.getPath());Os.rename(temp.getPath(),dest.getPath());}
            finally{temp.delete();}
        }
        new File(context.getFilesDir(),"native").mkdirs();
        File profile=new File(home,".profile");
        if(!profile.exists())Files.write(profile.toPath(),("export TMPDIR=\"$HOME/tmp\"\ncd \"$HOME/workspace\"\n").getBytes(StandardCharsets.UTF_8));
        return home;
    }
}
