package com.example.blockhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads ready Paper JARs and runs them with Android-native OpenJDK. */
public final class MinecraftServerService extends Service {
    public static final String ACTION_INSTALL = "com.example.blockhost.INSTALL";
    public static final String ACTION_START = "com.example.blockhost.START";
    public static final String ACTION_STOP = "com.example.blockhost.STOP";
    public static final String EXTRA_SERVER_ID = "serverId";
    public static final String EXTRA_START_AFTER_INSTALL = "startAfterInstall";
    private static final String CHANNEL_ID = "minecraft_server";
    private static final int NOTIFICATION_ID = 7321;
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?:\\]: |: )(.+?) joined the game");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(?:\\]: |: )(.+?) left the game");

    private static volatile MinecraftServerService instance;
    private static final Object SNAPSHOT_LOCK = new Object();
    private static RuntimeSnapshot snapshot = RuntimeSnapshot.idle();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ProcessStats processStats = new ProcessStats();
    private final Set<String> onlinePlayers = new LinkedHashSet<>();
    private ServerRepository repository;
    private LinuxRuntimeManager runtime;
    private SpigotVersionProvider paper;
    private NativeJvmRunner jvm;
    private PrintWriter commandWriter;
    private volatile boolean jvmRunning;
    private Future<?> activeTask;
    private PowerManager.WakeLock wakeLock;

    public static final class RuntimeSnapshot {
        public final String serverId, status, phase, message;
        public final int progress;
        public final long ramBytes, startedAt;
        public final double cpuPercent;
        public final JSONArray players;
        RuntimeSnapshot(String serverId,String status,String phase,int progress,String message,long ramBytes,double cpuPercent,JSONArray players,long startedAt) {
            this.serverId=serverId;this.status=status;this.phase=phase;this.progress=progress;this.message=message;this.ramBytes=ramBytes;this.cpuPercent=cpuPercent;this.players=players;this.startedAt=startedAt;
        }
        static RuntimeSnapshot idle(){return new RuntimeSnapshot("","stopped","idle",0,"Stopped",0,0,new JSONArray(),0);}
        JSONObject toJson() throws Exception {return new JSONObject().put("serverId",serverId).put("status",status).put("phase",phase).put("progress",progress).put("message",message).put("ramBytes",ramBytes).put("cpuPercent",cpuPercent).put("players",new JSONArray(players.toString())).put("startedAt",startedAt);}
    }

    public static JSONObject getSnapshotJson(String serverId) {
        synchronized (SNAPSHOT_LOCK) {
            try { if(snapshot.serverId.equals(serverId)) return snapshot.toJson(); return new JSONObject().put("serverId",serverId).put("status","stopped").put("phase","idle").put("progress",0).put("message","Stopped").put("ramBytes",0).put("cpuPercent",0).put("players",new JSONArray()).put("startedAt",0); }
            catch(Exception e){return new JSONObject();}
        }
    }

    public static boolean sendCommand(String command) {
        MinecraftServerService current = instance;
        return current != null && current.sendCommandInternal(command);
    }

    public static void launchAction(Context context,String action,String serverId,boolean startAfterInstall) {
        Intent intent = new Intent(context,MinecraftServerService.class).setAction(action).putExtra(EXTRA_SERVER_ID,serverId).putExtra(EXTRA_START_AFTER_INSTALL,startAfterInstall);
        if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate(); instance=this; repository=new ServerRepository(this);
        runtime=new LinuxRuntimeManager(this,repository); paper=new SpigotVersionProvider(this); createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||intent.getAction()==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction(),serverId=intent.getStringExtra(EXTRA_SERVER_ID);
        if(serverId==null||serverId.isEmpty()) serverId=repository.getActiveServerId(); final String id=serverId;
        if(ACTION_STOP.equals(action)){requestStop(id);return START_NOT_STICKY;}
        startForeground(NOTIFICATION_ID,buildNotification("Preparing BlockHost…",false));
        if(activeTask!=null&&!activeTask.isDone()){updateSnapshot(id,"error","busy",0,"Another server operation is already running",0,0);return START_STICKY;}
        if(ACTION_INSTALL.equals(action)){boolean startAfter=intent.getBooleanExtra(EXTRA_START_AFTER_INSTALL,false);activeTask=executor.submit(()->installServer(id,startAfter));}
        else if(ACTION_START.equals(action))activeTask=executor.submit(()->startServer(id));
        return START_STICKY;
    }

    private void installServer(String serverId, boolean startAfter) {
        acquireWakeLock();
        try {
            JSONObject server=repository.getServer(serverId);
            if(server==null) throw new IllegalArgumentException("Server not found");
            if(!server.optBoolean("eulaAccepted",false)) throw new IllegalStateException("Accept the Minecraft EULA before installing");
            repository.clearLog(serverId);
            updateSnapshot(serverId,"installing","java",0,"Preparing Android Java runtime",0,0);
            runtime.ensureReady((phase,progress,message)->{repository.appendLog(serverId,"[BlockHost] "+message);updateSnapshot(serverId,"installing",phase,progress,message,0,0);});

            String requested=server.optString("version","latest");
            updateSnapshot(serverId,"installing","paper-resolve",91,"Finding ready Paper build",0,0);
            SpigotVersionProvider.PaperDownload download=paper.resolveDownload(requested);
            File serverDir=repository.getServerDir(serverId); serverDir.mkdirs();
            File jar=new File(serverDir,"server.jar");
            downloadPaperJar(download,jar,serverId);
            File legacyBuild=new File(serverDir,".build"); deleteQuietly(legacyBuild);
            repository.setInstalled(serverId,true);
            updateSnapshot(serverId,"stopped","installed",100,"Paper "+download.version+" build "+download.build+" installed",0,0);
            updateNotification("Paper installed: "+server.optString("name"),false);
            if(startAfter) startServer(serverId); else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        } catch(Exception e) {
            repository.appendLog(serverId,"[BlockHost/ERROR] "+e.getMessage());
            updateSnapshot(serverId,"error","install-error",0,e.getMessage(),0,0);
            updateNotification("Install failed: "+shorten(e.getMessage(),70),false);
        } finally { releaseWakeLockIfIdle(); }
    }

    private void startServer(String serverId) {
        acquireWakeLock();
        try {
            JSONObject server=repository.getServer(serverId);
            if(server==null) throw new IllegalArgumentException("Server not found");
            File serverDir=repository.getServerDir(serverId), jar=new File(serverDir,"server.jar");
            if(!jar.isFile()){installServer(serverId,true);return;}
            repository.writeServerProperties(server); onlinePlayers.clear();
            LinuxRuntimeManager.Layout layout=runtime.ensureReady((phase,progress,message)->{repository.appendLog(serverId,"[BlockHost] "+message);updateSnapshot(serverId,"starting",phase,progress,message,0,0);});
            int ramMb=Math.max(512,(int)Math.round(server.optDouble("ramMax",1.0)*1024));
            JSONObject settings=server.optJSONObject("settings"); String extra=settings==null?"nogui":settings.optString("extraArgs","nogui");
            List<String> args=new ArrayList<>();
            args.add("java"); args.add("-Djava.awt.headless=true"); args.add("-Dfile.encoding=UTF-8");
            args.add("-Xms128M"); args.add("-Xmx"+ramMb+"M"); args.add("-XX:+UseG1GC");
            args.add("-jar"); args.add(jar.getAbsolutePath());
            for(String token:splitArgs(extra)) args.add(token);

            updateSnapshot(serverId,"starting","server-start",0,"Starting "+server.optString("name"),0,0);
            repository.appendLog(serverId,"[BlockHost] Starting Paper with Android Java 21 and a "+ramMb+" MB RAM cap");
            jvm=new NativeJvmRunner();
            commandWriter=new PrintWriter(new OutputStreamWriter(jvm.commands(),StandardCharsets.UTF_8),true);
            long startedAt=System.currentTimeMillis(); jvmRunning=true;
            updateNotification("Starting "+server.optString("name")+"…",true);
            Thread outputThread=new Thread(()->readJvmOutput(serverId,server.optString("name"),startedAt),"BlockHost-JVM-output");
            outputThread.start();
            new Thread(()->sampleStatsLoop(serverId,startedAt),"BlockHost-stats").start();
            int exit=jvm.launch(layout,serverDir,args.toArray(new String[0]));
            jvmRunning=false; commandWriter=null; try{outputThread.join(3000);}catch(Exception ignored){}
            jvm.close(); jvm=null; onlinePlayers.clear();
            String end=exit==0?"Server stopped":"Server exited with code "+exit;
            updateSnapshot(serverId,exit==0?"stopped":"error","server-exit",0,end,0,0);
            repository.appendLog(serverId,"[BlockHost] "+end); updateNotification(end,false);
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        } catch(Exception e) {
            jvmRunning=false; repository.appendLog(serverId,"[BlockHost/ERROR] "+e.getMessage());
            updateSnapshot(serverId,"error","start-error",0,e.getMessage(),0,0); updateNotification("Server failed: "+shorten(e.getMessage(),70),false);
        } finally { if(jvm!=null){jvm.close();jvm=null;} commandWriter=null; releaseWakeLockIfIdle(); }
    }

    private void readJvmOutput(String serverId,String name,long startedAt) {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(jvm.output(),StandardCharsets.UTF_8))){
            String line; while((line=reader.readLine())!=null){repository.appendLog(serverId,line);parsePlayerLine(line);String status=line.contains("Done (")?"running":currentStatus(),message=line.contains("Done (")?"Server online":shorten(line,110);updateSnapshot(serverId,status,"server",100,message,currentRam(),currentCpu(),startedAt);if(line.contains("Done ("))updateNotification(name+" is online",true);}
        }catch(Exception ignored){}
    }

    private void downloadPaperJar(SpigotVersionProvider.PaperDownload info, File output, String serverId) throws Exception {
        File part=new File(output.getPath()+".part"); part.delete();
        HttpURLConnection c=(HttpURLConnection)new URL(info.url).openConnection();c.setConnectTimeout(30_000);c.setReadTimeout(120_000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","BlockHost-Android/0.4");
        if(c.getResponseCode()/100!=2)throw new java.io.IOException("Paper download returned HTTP "+c.getResponseCode());
        long total=c.getContentLengthLong(),done=0;int last=-1;
        try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[]buf=new byte[262144];int n;while((n=in.read(buf))>=0){out.write(buf,0,n);done+=n;int p=total>0?92+(int)Math.min(7,done*7/total):95;if(p!=last){last=p;String msg="Downloading ready Paper JAR: "+human(done)+(total>0?" / "+human(total):"");repository.appendLog(serverId,"[BlockHost] "+msg);updateSnapshot(serverId,"installing","paper-download",p,msg,0,0);}}}finally{c.disconnect();}
        if(!info.sha256.isEmpty()&&!info.sha256.equalsIgnoreCase(sha256(part))){part.delete();throw new java.io.IOException("Paper JAR checksum mismatch");}
        if(!part.renameTo(output))Files.move(part.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[]b=new byte[262144];int n;while((n=in.read(b))>=0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static List<String> splitArgs(String value){List<String>out=new ArrayList<>();if(value==null)return out;for(String x:value.trim().split("\\s+"))if(!x.isEmpty())out.add(x);return out;}
    private static void deleteQuietly(File f){try{if(f.isDirectory()){File[]fs=f.listFiles();if(fs!=null)for(File x:fs)deleteQuietly(x);}f.delete();}catch(Exception ignored){}}
    private static String human(long b){double v=b;String[]u={"B","KB","MB","GB"};int i=0;while(v>=1024&&i<u.length-1){v/=1024;i++;}return String.format(Locale.US,i==0?"%.0f %s":"%.1f %s",v,u[i]);}

    private void sampleStatsLoop(String serverId,long startedAt){while(jvmRunning){try{JSONObject stats=processStats.sample(android.os.Process.myPid());RuntimeSnapshot current;synchronized(SNAPSHOT_LOCK){current=snapshot;}updateSnapshot(serverId,current.status,current.phase,current.progress,current.message,stats.optLong("ramBytes",0),stats.optDouble("cpuPercent",0),startedAt);Thread.sleep(1500);}catch(Exception ignored){}}}
    private void requestStop(String serverId){new Thread(()->{try{if(jvmRunning){updateSnapshot(serverId,"stopping","server-stop",0,"Stopping server safely",currentRam(),currentCpu());sendCommandInternal("stop");long deadline=System.currentTimeMillis()+25_000;while(jvmRunning&&System.currentTimeMillis()<deadline)Thread.sleep(250);if(jvmRunning)repository.appendLog(serverId,"[BlockHost/ERROR] Server did not stop within 25 seconds");}}catch(Exception ignored){}finally{if(!jvmRunning){onlinePlayers.clear();updateSnapshot(serverId,"stopped","idle",0,"Stopped",0,0);releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}},"BlockHost-stop").start();}
    private synchronized boolean sendCommandInternal(String command){if(commandWriter==null||!jvmRunning)return false;commandWriter.println(command);commandWriter.flush();return true;}
    private void parsePlayerLine(String line){Matcher join=JOIN_PATTERN.matcher(line);if(join.find())synchronized(onlinePlayers){onlinePlayers.add(join.group(1).trim());}Matcher leave=LEAVE_PATTERN.matcher(line);if(leave.find())synchronized(onlinePlayers){onlinePlayers.remove(leave.group(1).trim());}}
    private long currentRam(){synchronized(SNAPSHOT_LOCK){return snapshot.ramBytes;}} private double currentCpu(){synchronized(SNAPSHOT_LOCK){return snapshot.cpuPercent;}} private String currentStatus(){synchronized(SNAPSHOT_LOCK){return snapshot.status.equals("starting")?"starting":snapshot.status;}}
    private void updateSnapshot(String id,String status,String phase,int progress,String message,long ram,double cpu){long started;synchronized(SNAPSHOT_LOCK){started=snapshot.startedAt;}updateSnapshot(id,status,phase,progress,message,ram,cpu,started);}
    private void updateSnapshot(String id,String status,String phase,int progress,String message,long ram,double cpu,long started){JSONArray players=new JSONArray();synchronized(onlinePlayers){for(String p:onlinePlayers)players.put(p);}synchronized(SNAPSHOT_LOCK){snapshot=new RuntimeSnapshot(id,status,phase,progress,message==null?"":message,ram,cpu,players,started);}}

    private void createNotificationChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Minecraft server",NotificationManager.IMPORTANCE_LOW);c.setDescription("Keeps the selected Minecraft server running in the background");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification buildNotification(String text,boolean showStop){Intent open=new Intent(this,BlockHostActivity.class);PendingIntent openPi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);b.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("BlockHost").setContentText(text).setContentIntent(openPi).setOngoing(showStop).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);if(showStop){Intent stop=new Intent(this,MinecraftServerService.class).setAction(ACTION_STOP).putExtra(EXTRA_SERVER_ID,snapshot.serverId);PendingIntent pi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);b.addAction(android.R.drawable.ic_media_pause,"Stop server",pi);}return b.build();}
    private void updateNotification(String text,boolean showStop){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification(text,showStop));}
    private void acquireWakeLock(){if(wakeLock==null){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BlockHost:Server");wakeLock.setReferenceCounted(false);}if(!wakeLock.isHeld())wakeLock.acquire();}
    private void releaseWakeLockIfIdle(){if(!jvmRunning)releaseWakeLock();} private void releaseWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}
    private static String shorten(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max-1)+"…";}
    @Override public void onDestroy(){instance=null;if(jvm!=null)jvm.close();releaseWakeLock();executor.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
