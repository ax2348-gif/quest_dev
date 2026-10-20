package dev.quest.runtime;
import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Independent local PTYs. Windows can close without ending their sessions. */
final class TerminalSession {
    static { System.loadLibrary("questterminal"); }
    private static final LinkedHashMap<String,TerminalSession> sessions=new LinkedHashMap<>();
    private static int nextNumber=1;
    final String id;
    final String label;
    static synchronized TerminalSession get(Context context,String id) throws Exception {
        TerminalSession session=sessions.get(id);
        if(session==null){
            session=new TerminalSession(context.getApplicationContext(),id,"Terminal "+nextNumber++);
            sessions.put(id,session);
        }
        return session;
    }
    static synchronized List<TerminalSession> list(){return new ArrayList<>(sessions.values());}
    static void remove(String id) throws Exception {
        TerminalSession old;
        synchronized(TerminalSession.class){old=sessions.get(id);}
        if(old!=null){
            old.stop();
            synchronized(TerminalSession.class){sessions.remove(id);}
        }
    }
    boolean isRunning(){return running;}
    private static native int openPty();
    private static native String slaveName(int fd);
    private static native byte[] readPty(int fd);
    private static native int writePty(int fd,byte[] bytes);
    private static native void resizePty(int fd,int cols,int rows);
    private static native void closePty(int fd);
    private final Context context;
    private final ArrayDeque<Chunk> output=new ArrayDeque<>();
    private final Object inputLock=new Object();
    private long sequence;
    private int buffered;
    private volatile int master=-1;
    private volatile java.lang.Process child;
    private volatile boolean running;
    private Thread reader;
    private int cols=100,rows=28;
    private static final class Chunk {
        final long seq;final byte[] data;
        Chunk(long seq,byte[] data){this.seq=seq;this.data=data;}
    }
    private TerminalSession(Context context,String id,String label) throws Exception {this.context=context;this.id=id;this.label=label;start();}
    private void append(byte[] bytes){
        if(bytes.length==0)return;
        synchronized(output){
            output.add(new Chunk(++sequence,bytes));buffered+=bytes.length;
            while(buffered>512*1024&&output.size()>1)buffered-=output.removeFirst().data.length;
        }
    }
    private void note(String text){append(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void start() throws Exception {
        File home=RuntimeEnvironment.prepare(context);
        int fd=openPty();if(fd<0)throw new IOException("Cannot allocate a pseudo-terminal");
        String slave=slaveName(fd);
        if(slave==null){closePty(fd);throw new IOException("Cannot find PTY slave");}
        java.lang.Process process;
        try {
            ProcessBuilder builder=new ProcessBuilder(context.getApplicationInfo().nativeLibraryDir+"/libquestptyshell.so",slave);
            builder.directory(new File(home,"workspace")).redirectErrorStream(true);
            builder.redirectOutput(new File(home,"tmp/terminal-"+id+".log"));
            Map<String,String> env=builder.environment();
            env.put("HOME",home.getPath());env.put("QUEST_RUNTIME_HOME",home.getPath());
            env.put("PATH",home+"/bin:/system/bin");env.put("TMPDIR",home+"/tmp");
            env.put("TERM","xterm-256color");env.put("COLORTERM","truecolor");
            env.put("SHELL","/system/bin/sh");env.put("USER","quest");env.put("LOGNAME","quest");
            env.put("LANG","C.UTF-8");env.put("PS1","quest:\\w $ ");
            process=builder.start();
        }catch(Exception e){closePty(fd);throw e;}
        master=fd;child=process;running=true;resizePty(fd,cols,rows);
        note("\r\n\033[1;32mQUEST // LOCAL TERMINAL\033[0m\r\nSame workspace and commands as SSH.\r\n\r\n");
        reader=new Thread(()->{
            try {
                while(running){
                    byte[] data=readPty(fd);
                    if(data==null){
                        // Before the exec helper opens the slave, the master can return EIO.
                        if(process.isAlive()){Thread.sleep(30);continue;}
                        break;
                    }
                    append(data);
                    if(data.length==0&&!process.isAlive())break;
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{
                synchronized(inputLock){if(master==fd){master=-1;closePty(fd);}}
                running=false;
                String code=process.isAlive()?"closed":Integer.toString(process.exitValue());
                note("\r\n\033[33m[Shell "+code+". Use New shell to start again.]\033[0m\r\n");
            }
        },"QuestTerminalReader");reader.start();
    }
    void write(byte[] bytes){
        synchronized(inputLock){
            if(master<0||!running)return;
            if(writePty(master,bytes)!=bytes.length)note("\r\n[Input buffer full; part of the input was not sent.]\r\n");
        }
    }
    void resize(int cols,int rows){
        this.cols=Math.max(10,Math.min(cols,400));this.rows=Math.max(4,Math.min(rows,200));
        synchronized(inputLock){if(master>=0)resizePty(master,this.cols,this.rows);}
    }
    private synchronized void stop() throws Exception {
        running=false;
        if(child!=null&&child.isAlive())child.destroyForcibly();
        if(reader!=null){reader.join(1500);if(reader.isAlive())throw new IOException("Old terminal has not stopped");}
    }
    synchronized void restart() throws Exception {stop();start();}
    String drain(long after) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();long next=after;
        synchronized(output){
            for(Chunk c:output)if(c.seq>after){bytes.write(c.data);next=c.seq;if(bytes.size()>32768)break;}
        }
        return new JSONObject().put("seq",next).put("running",running).put("label",label)
            .put("data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)).toString();
    }
}
