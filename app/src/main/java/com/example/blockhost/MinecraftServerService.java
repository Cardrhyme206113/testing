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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs installs and Minecraft server processes as user-visible foreground work. */
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
    private Process process;
    private PrintWriter commandWriter;
    private Future<?> activeTask;
    private PowerManager.WakeLock wakeLock;

    public static final class RuntimeSnapshot {
        public final String serverId,status,phase,message;
        public final int progress;
        public final long ramBytes,startedAt;
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
            try { if(snapshot.serverId.equals(serverId))return snapshot.toJson(); return new JSONObject().put("serverId",serverId).put("status","stopped").put("phase","idle").put("progress",0).put("message","Stopped").put("ramBytes",0).put("cpuPercent",0).put("players",new JSONArray()).put("startedAt",0); }
            catch(Exception e){return new JSONObject();}
        }
    }
    public static boolean sendCommand(String command){MinecraftServerService current=instance;return current!=null&&current.sendCommandInternal(command);}
    public static void launchAction(Context context,String action,String serverId,boolean startAfterInstall){Intent intent=new Intent(context,MinecraftServerService.class).setAction(action).putExtra(EXTRA_SERVER_ID,serverId).putExtra(EXTRA_START_AFTER_INSTALL,startAfterInstall);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);}

    @Override public void onCreate(){super.onCreate();instance=this;repository=new ServerRepository(this);runtime=new LinuxRuntimeManager(this,repository);createNotificationChannel();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||intent.getAction()==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction(),serverId=intent.getStringExtra(EXTRA_SERVER_ID);if(serverId==null||serverId.isEmpty())serverId=repository.getActiveServerId();final String id=serverId;
        if(ACTION_STOP.equals(action)){requestStop(id);return START_NOT_STICKY;}
        startForeground(NOTIFICATION_ID,buildNotification("Preparing BlockHost…",false));
        if(activeTask!=null&&!activeTask.isDone()){updateSnapshot(id,"error","busy",0,"Another server operation is already running",0,0);return START_STICKY;}
        if(ACTION_INSTALL.equals(action)){boolean startAfter=intent.getBooleanExtra(EXTRA_START_AFTER_INSTALL,false);activeTask=executor.submit(()->installServer(id,startAfter));}
        else if(ACTION_START.equals(action))activeTask=executor.submit(()->startServer(id));
        return START_STICKY;
    }

    private void installServer(String serverId,boolean startAfter){
        acquireWakeLock();
        try {
            JSONObject server=repository.getServer(serverId);if(server==null)throw new IllegalArgumentException("Server not found");if(!server.optBoolean("eulaAccepted",false))throw new IllegalStateException("Accept the Minecraft EULA before installing");
            repository.clearLog(serverId);updateSnapshot(serverId,"installing","runtime",0,"Preparing Linux runtime",0,0);
            runtime.ensureReady((phase,progress,message)->{repository.appendLog(serverId,"[BlockHost] "+message);updateSnapshot(serverId,"installing",phase,progress,message,0,0);});
            File serverDir=repository.getServerDir(serverId),buildDir=new File(serverDir,".build");buildDir.mkdirs();File buildTools=new File(buildDir,"BuildTools.jar");
            updateSnapshot(serverId,"installing","buildtools-download",90,"Downloading official Spigot BuildTools",0,0);downloadBuildTools(buildTools,serverId);
            String version=server.optString("version","latest"),java=runtime.javaCommandForVersion(version);
            String command="set -o pipefail; mkdir -p /server/.build; cd /server/.build; git config --global --unset core.autocrlf >/dev/null 2>&1 || true; export SHELL=/bin/bash; "+java+" -Xmx1024M -jar /server/.build/BuildTools.jar --rev "+shellQuote(version)+" --output-dir /server --final-name server.jar";
            updateSnapshot(serverId,"installing","spigot-build",94,"Compiling Spigot "+version+" locally",0,0);
            repository.appendLog(serverId,"[BlockHost] Spigot provides BuildTools rather than direct server JAR downloads. Compiling locally now.");
            Process build=runtime.startShell(serverDir,command);
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(build.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){repository.appendLog(serverId,line);updateSnapshot(serverId,"installing","spigot-build",96,shorten(line,100),0,0);}}
            int exit=build.waitFor();File jar=new File(serverDir,"server.jar");if(exit!=0||!jar.isFile())throw new IOException("BuildTools failed with exit code "+exit+". Check Terminal for details.");
            repository.setInstalled(serverId,true);updateSnapshot(serverId,"stopped","installed",100,"Spigot "+version+" installed",0,0);updateNotification("Spigot installed: "+server.optString("name"),false);
            if(startAfter)startServer(serverId);else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        } catch(Exception e){repository.appendLog(serverId,"[BlockHost/ERROR] "+e.getMessage());updateSnapshot(serverId,"error","install-error",0,e.getMessage(),0,0);updateNotification("Install failed: "+shorten(e.getMessage(),70),false);}
        finally{releaseWakeLockIfIdle();}
    }

    private void startServer(String serverId){
        acquireWakeLock();
        try {
            JSONObject server=repository.getServer(serverId);if(server==null)throw new IllegalArgumentException("Server not found");File serverDir=repository.getServerDir(serverId);
            if(!new File(serverDir,"server.jar").isFile()){installServer(serverId,true);return;}
            repository.writeServerProperties(server);onlinePlayers.clear();String version=server.optString("version","latest"),java=runtime.javaCommandForVersion(version);
            int ramMb=Math.max(512,(int)Math.round(server.optDouble("ramMax",1.0)*1024));JSONObject settings=server.optJSONObject("settings");String extraArgs=settings==null?"nogui":settings.optString("extraArgs","nogui");
            String command="cd /server && "+java+" -Xms128M -Xmx"+ramMb+"M -jar /server/server.jar "+shellQuoteArgs(extraArgs);
            updateSnapshot(serverId,"starting","server-start",0,"Starting "+server.optString("name"),0,0);repository.appendLog(serverId,"[BlockHost] Starting server with a "+ramMb+" MB RAM cap");
            process=runtime.startShell(serverDir,command);commandWriter=new PrintWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8),true);long startedAt=System.currentTimeMillis();updateNotification("Starting "+server.optString("name")+"…",true);
            new Thread(()->sampleStatsLoop(serverId,startedAt),"BlockHost-stats").start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){repository.appendLog(serverId,line);parsePlayerLine(line);String status=line.contains("Done (")?"running":currentStatus(),message=line.contains("Done (")?"Server online":shorten(line,110);updateSnapshot(serverId,status,"server",100,message,currentRam(),currentCpu(),startedAt);if(line.contains("Done ("))updateNotification(server.optString("name")+" is online",true);}}
            int exit=process.waitFor();process=null;commandWriter=null;onlinePlayers.clear();String endMessage=exit==0?"Server stopped":"Server exited with code "+exit;updateSnapshot(serverId,exit==0?"stopped":"error","server-exit",0,endMessage,0,0);repository.appendLog(serverId,"[BlockHost] "+endMessage);updateNotification(endMessage,false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        } catch(Exception e){repository.appendLog(serverId,"[BlockHost/ERROR] "+e.getMessage());updateSnapshot(serverId,"error","start-error",0,e.getMessage(),0,0);updateNotification("Server failed: "+shorten(e.getMessage(),70),false);}
        finally{process=null;commandWriter=null;releaseWakeLockIfIdle();}
    }

    private void sampleStatsLoop(String serverId,long startedAt){while(process!=null&&process.isAlive()){try{JSONObject stats=processStats.sample(process.pid());RuntimeSnapshot current;synchronized(SNAPSHOT_LOCK){current=snapshot;}updateSnapshot(serverId,current.status,current.phase,current.progress,current.message,stats.optLong("ramBytes",0),stats.optDouble("cpuPercent",0),startedAt);Thread.sleep(1500);}catch(Exception ignored){}}}
    private void requestStop(String serverId){new Thread(()->{try{if(process!=null&&process.isAlive()){updateSnapshot(serverId,"stopping","server-stop",0,"Stopping server safely",currentRam(),currentCpu());sendCommandInternal("stop");if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}}catch(Exception ignored){if(process!=null)process.destroyForcibly();}finally{process=null;commandWriter=null;onlinePlayers.clear();updateSnapshot(serverId,"stopped","idle",0,"Stopped",0,0);releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}},"BlockHost-stop").start();}
    private synchronized boolean sendCommandInternal(String command){if(commandWriter==null||process==null||!process.isAlive())return false;commandWriter.println(command);commandWriter.flush();return true;}
    private void parsePlayerLine(String line){Matcher join=JOIN_PATTERN.matcher(line);if(join.find())synchronized(onlinePlayers){onlinePlayers.add(join.group(1).trim());}Matcher leave=LEAVE_PATTERN.matcher(line);if(leave.find())synchronized(onlinePlayers){onlinePlayers.remove(leave.group(1).trim());}}
    private long currentRam(){synchronized(SNAPSHOT_LOCK){return snapshot.ramBytes;}}private double currentCpu(){synchronized(SNAPSHOT_LOCK){return snapshot.cpuPercent;}}private String currentStatus(){synchronized(SNAPSHOT_LOCK){return snapshot.status.equals("starting")?"starting":snapshot.status;}}
    private void updateSnapshot(String serverId,String status,String phase,int progress,String message,long ram,double cpu){long started; synchronized(SNAPSHOT_LOCK){started=snapshot.startedAt;} updateSnapshot(serverId,status,phase,progress,message,ram,cpu,started);}
    private void updateSnapshot(String serverId,String status,String phase,int progress,String message,long ram,double cpu,long startedAt){JSONArray players=new JSONArray();synchronized(onlinePlayers){for(String player:onlinePlayers)players.put(player);}synchronized(SNAPSHOT_LOCK){snapshot=new RuntimeSnapshot(serverId,status,phase,progress,message==null?"":message,ram,cpu,players,startedAt);}}

    private void downloadBuildTools(File output,String serverId) throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL(SpigotVersionProvider.BUILDTOOLS_URL).openConnection();connection.setConnectTimeout(20000);connection.setReadTimeout(60000);connection.setRequestProperty("User-Agent","BlockHost-Android/0.2");connection.setInstanceFollowRedirects(true);int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("BuildTools download returned HTTP "+code);long total=connection.getContentLengthLong();File part=new File(output.getParentFile(),output.getName()+".part");try(InputStream input=connection.getInputStream();FileOutputStream file=new FileOutputStream(part)){byte[] buffer=new byte[65536];long done=0;int read;while((read=input.read(buffer))>=0){file.write(buffer,0,read);done+=read;if(total>0)updateSnapshot(serverId,"installing","buildtools-download",90+(int)Math.min(3,done*3/total),"Downloading BuildTools: "+(done/1024)+" KB",0,0);}}finally{connection.disconnect();}if(!part.renameTo(output))Files.move(part.toPath(),output.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    private void createNotificationChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL_ID,"Minecraft server",NotificationManager.IMPORTANCE_LOW);channel.setDescription("Keeps the selected Minecraft server running in the background");getSystemService(NotificationManager.class).createNotificationChannel(channel);}}
    private Notification buildNotification(String text,boolean showStop){Intent openIntent=new Intent(this,BlockHostActivity.class);PendingIntent openPending=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);builder.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("BlockHost").setContentText(text).setContentIntent(openPending).setOngoing(showStop).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);if(showStop){Intent stopIntent=new Intent(this,MinecraftServerService.class).setAction(ACTION_STOP).putExtra(EXTRA_SERVER_ID,snapshot.serverId);PendingIntent stopPending=PendingIntent.getService(this,1,stopIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);builder.addAction(android.R.drawable.ic_media_pause,"Stop server",stopPending);}return builder.build();}
    private void updateNotification(String text,boolean showStop){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification(text,showStop));}
    private void acquireWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())return;PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BlockHost:MinecraftServer");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
    private void releaseWakeLockIfIdle(){if(process==null||!process.isAlive())releaseWakeLock();}private void releaseWakeLock(){try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}wakeLock=null;}
    private static String shellQuote(String text){return "'"+text.replace("'","'\\''")+"'";}private static String shellQuoteArgs(String text){if(text==null||text.trim().isEmpty())return "nogui";String safe=text.replaceAll("[^A-Za-z0-9_./:=+,-\\s]","").trim();return safe.isEmpty()?"nogui":safe;}private static String shorten(String text,int max){if(text==null)return "";String clean=text.replace('\n',' ').replace('\r',' ');return clean.length()<=max?clean:clean.substring(0,max-1)+"…";}
    @Override public void onDestroy(){instance=null;if(process!=null&&process.isAlive())process.destroy();releaseWakeLock();executor.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
