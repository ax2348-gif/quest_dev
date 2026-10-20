package dev.quest.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/** Serialized by AppControl's worker; never load guest code in the SSH process. */
final class NativeApps {
    private final Context context;
    private final File root;
    NativeApps(Context context) throws IOException {
        this.context=context;
        root=new File(context.getFilesDir(),"native");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create native app directory");
    }
    private File selection(){return new File(root,"selected");}
    private File statusFile(){return new File(root,"load-status.json");}
    private List<ActivityManager.RunningAppProcessInfo> processes(){
        return ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
    }
    private boolean isXr(ActivityManager.RunningAppProcessInfo p){
        return p.uid==Process.myUid() && p.pid!=Process.myPid()
            && p.processName.equals(context.getPackageName()+":xr");
    }
    private JSONArray xrPids(){
        JSONArray pids=new JSONArray();
        List<ActivityManager.RunningAppProcessInfo> list=processes();
        if(list!=null)for(ActivityManager.RunningAppProcessInfo p:list)if(isXr(p))pids.put(p.pid);
        return pids;
    }
    private void stop() throws IOException {
        // Remove the XR task before killing its process to avoid activity restoration.
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.AppTask task:manager.getAppTasks()) {
            Intent intent=task.getTaskInfo().baseIntent;
            if(intent!=null && intent.getComponent()!=null
                && intent.getComponent().getPackageName().equals(context.getPackageName())
                && intent.getComponent().getClassName().equals("android.app.NativeActivity"))
                task.finishAndRemoveTask();
        }
        List<ActivityManager.RunningAppProcessInfo> list=processes();
        if(list!=null)for(ActivityManager.RunningAppProcessInfo p:list)if(isXr(p))Process.killProcess(p.pid);
        long until=SystemClock.elapsedRealtime()+4000;
        while(xrPids().length()!=0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        if(xrPids().length()!=0)throw new IOException("XR process did not stop; selection unchanged");
    }
    private JSONObject status() throws Exception {
        JSONObject out=new JSONObject().put("ok",true).put("runtime_pid",Process.myPid()).put("xr_pids",xrPids());
        out.put("selected",selection().isFile()?new String(Files.readAllBytes(selection().toPath()),StandardCharsets.UTF_8).trim():"builtin");
        if(statusFile().isFile()) {
            JSONObject last=new JSONObject(new String(Files.readAllBytes(statusFile().toPath()),StandardCharsets.UTF_8));
            boolean alive=false;JSONArray pids=xrPids();
            for(int i=0;i<pids.length();i++)if(pids.getInt(i)==last.optInt("pid",-1))alive=true;
            out.put("last_load",last).put("last_load_process_alive",alive);
        }
        return out;
    }
    private File stage(String source) throws Exception {
        File original=new File(source).getCanonicalFile();
        if(!original.isFile()||!original.canRead())throw new IOException("Not a readable library: "+original);
        File temp=File.createTempFile("upload-",".so",root);
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(original);OutputStream out=new FileOutputStream(temp)) {
                byte[] b=new byte[65536];int n;long size=0;
                while((n=in.read(b))!=-1) {
                    size+=n;if(size>256L*1024*1024)throw new IOException("Library exceeds 256 MiB");
                    out.write(b,0,n);digest.update(b,0,n);
                }
            }
            try(RandomAccessFile in=new RandomAccessFile(temp,"r")) {
                byte[] h=new byte[64];in.readFully(h);
                if(h[0]!=127||h[1]!='E'||h[2]!='L'||h[3]!='F'||h[4]!=2||h[5]!=1
                    ||h[16]!=3||h[17]!=0||(h[18]&255)!=183||h[19]!=0)
                    throw new IOException("Expected an Android ARM64 ELF shared library");
            }
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format("%02x",b&255));
            File dir=new File(root,"apps/"+sha);
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create app snapshot");
            File staged=new File(dir,"libapp.so");
            if(!staged.exists()) {
                if(!temp.setReadOnly())throw new IOException("Cannot make app snapshot read-only");
                Os.rename(temp.getAbsolutePath(),staged.getAbsolutePath());
            }
            return staged;
        }finally{temp.delete();}
    }
    private void select(File library) throws Exception {
        File temp=File.createTempFile("selection-",".tmp",root);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)) {
                out.write((library.getAbsolutePath()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            Os.rename(temp.getAbsolutePath(),selection().getAbsolutePath());
        }finally{temp.delete();}
    }
    JSONObject handle(JSONObject request) throws Exception {
        String action=request.getString("action");
        if(action.equals("native-status"))return status();
        if(action.equals("native-stop")){stop();return status().put("result","XR process stopped; SSH remains running");}
        if(!action.equals("native-run")&&!action.equals("native-restart")&&!action.equals("native-builtin"))
            throw new IllegalArgumentException("Unknown native command");
        File staged=action.equals("native-run")?stage(request.getString("value")):null;
        stop(); // Never overwrite a library mapped by a live process.
        if(staged!=null)select(staged);
        if(action.equals("native-builtin")&&selection().exists()&&!selection().delete())
            throw new IOException("Cannot reset selection");
        if(statusFile().exists()&&!statusFile().delete())throw new IOException("Cannot clear previous loader status");
        context.startActivity(new Intent().setClassName(context,"android.app.NativeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until=SystemClock.elapsedRealtime()+6000;
        while(SystemClock.elapsedRealtime()<until) {
            if(statusFile().isFile()) {
                JSONObject out=status();String state=out.getJSONObject("last_load").optString("state");
                if(state.equals("error"))return out.put("ok",false).put("launch_requested",true);
                if(state.equals("entry-returned"))return out.put("launch_requested",true);
            }
            SystemClock.sleep(50);
        }
        return status().put("launch_requested",true).put("result","Launch requested; loader completion not yet confirmed");
    }
}
