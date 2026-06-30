package com.example.blockhost;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Native backend boundary exposed to the WebView. */
public final class BlockHostBackend {
    private final Context context;
    private final ServerRepository repository;
    private final SpigotVersionProvider versionProvider;

    public BlockHostBackend(Context context) {
        this.context=context.getApplicationContext();
        repository=new ServerRepository(context);
        versionProvider=new SpigotVersionProvider(context);
    }

    public synchronized String call(String method,String payloadText) {
        try {
            JSONObject payload=payloadText==null||payloadText.isEmpty()?new JSONObject():new JSONObject(payloadText);
            Object data;
            switch(method) {
                case "getState": data=buildState(); break;
                case "listSpigotVersions": data=versionProvider.getVersions(payload.optBoolean("forceRefresh",false)); break;
                case "selectServer": data=repository.selectServer(payload.getString("id")); break;
                case "createServer": {
                    JSONObject server=repository.createServer(payload);data=server;
                    if(payload.optBoolean("install",true))MinecraftServerService.launchAction(context,MinecraftServerService.ACTION_INSTALL,server.getString("id"),payload.optBoolean("startAfterInstall",false));
                    break;
                }
                case "deleteServer": {
                    String id=payload.getString("id");JSONObject runtime=MinecraftServerService.getSnapshotJson(id);
                    if(isBusy(runtime.optString("status")))throw new IllegalStateException("Stop this server before deleting it");
                    repository.deleteServer(id);data=new JSONObject().put("ok",true);break;
                }
                case "installServer": {String id=payload.getString("id");MinecraftServerService.launchAction(context,MinecraftServerService.ACTION_INSTALL,id,payload.optBoolean("startAfterInstall",false));data=new JSONObject().put("queued",true);break;}
                case "startServer": {String id=payload.getString("id");MinecraftServerService.launchAction(context,MinecraftServerService.ACTION_START,id,false);data=new JSONObject().put("queued",true);break;}
                case "stopServer": {String id=payload.getString("id");MinecraftServerService.launchAction(context,MinecraftServerService.ACTION_STOP,id,false);data=new JSONObject().put("queued",true);break;}
                case "sendCommand": {boolean sent=MinecraftServerService.sendCommand(payload.optString("command",""));if(!sent)throw new IllegalStateException("The server is not accepting commands");data=new JSONObject().put("sent",true);break;}
                case "listFiles": data=repository.listFiles(payload.getString("id"),payload.optString("path","/"));break;
                case "readFile": data=new JSONObject().put("content",repository.readFile(payload.getString("id"),payload.getString("path")));break;
                case "writeFile": repository.writeFile(payload.getString("id"),payload.getString("path"),payload.optString("content",""));data=new JSONObject().put("saved",true);break;
                case "listPlayers": data=listPlayers(payload.getString("id"));break;
                case "updateSettings": data=updateSettings(payload);break;
                case "backupServer": data=repository.createBackup(payload.getString("id"));break;
                case "clearConsole": repository.clearLog(payload.getString("id"));data=new JSONObject().put("cleared",true);break;
                default: throw new IllegalArgumentException("Unknown backend method: "+method);
            }
            return success(data).toString();
        } catch(Exception e) {return failure(e).toString();}
    }

    private JSONObject buildState() throws Exception {
        JSONObject persisted=repository.getStateCopy();JSONArray servers=persisted.getJSONArray("servers");
        for(int i=0;i<servers.length();i++) {
            JSONObject server=servers.getJSONObject(i);String id=server.getString("id");File dir=repository.getServerDir(id);
            server.put("installed",new File(dir,"server.jar").isFile());JSONObject runtime=MinecraftServerService.getSnapshotJson(id);
            server.put("status",runtime.optString("status","stopped")).put("phase",runtime.optString("phase","idle")).put("progress",runtime.optInt("progress",0)).put("statusMessage",runtime.optString("message","Stopped"));
            server.put("ramUsedBytes",runtime.optLong("ramBytes",0)).put("ramUsed",runtime.optLong("ramBytes",0)/1073741824.0).put("cpu",runtime.optDouble("cpuPercent",0));
            JSONArray players=runtime.optJSONArray("players");server.put("players",players==null?new JSONArray():players);
            server.put("uptime",runtime.optLong("startedAt",0)==0?0:Math.max(0,(System.currentTimeMillis()-runtime.optLong("startedAt"))/1000));
            server.put("fileCount",countVisibleFiles(dir)).put("diskBytes",folderSize(dir));
        }
        JSONObject active=repository.getActiveServer();
        if(active!=null){String id=active.getString("id");persisted.put("logs",repository.readLogEntries(id,400));persisted.put("players",listPlayers(id));}
        else persisted.put("logs",new JSONArray()).put("players",new JSONArray());
        persisted.put("backend",new JSONObject().put("kind","native").put("storage",repository.getRoot().getAbsolutePath()).put("dataDeletedOnUninstall",true).put("buildToolsUrl",SpigotVersionProvider.BUILDTOOLS_URL));
        return persisted;
    }

    private JSONArray listPlayers(String id) throws Exception {
        JSONObject runtime=MinecraftServerService.getSnapshotJson(id);JSONArray names=runtime.optJSONArray("players"),result=new JSONArray();if(names==null)return result;
        Set<String> ops=readOperatorNames(repository.getServerDir(id));
        for(int i=0;i<names.length();i++){String name=names.getString(i);result.put(new JSONObject().put("id",name).put("name",name).put("role",ops.contains(name)?"Operator":"Player").put("ping",JSONObject.NULL).put("playtime",JSONObject.NULL));}
        return result;
    }

    private JSONObject updateSettings(JSONObject payload) throws Exception {
        String id=payload.getString("id");JSONObject changes=payload.getJSONObject("settings"),before=repository.getServer(id);if(before==null)throw new IllegalArgumentException("Server not found");
        JSONObject beforeCopy=new JSONObject(before.toString());Set<String> restartFields=changedRestartFields(beforeCopy,changes);JSONObject runtime=MinecraftServerService.getSnapshotJson(id);
        boolean running=isRunning(runtime.optString("status")),confirmed=payload.optBoolean("restartIfRunning",false);
        if(running&&!restartFields.isEmpty()&&!confirmed){JSONArray fields=new JSONArray();for(String field:restartFields)fields.put(field);return new JSONObject().put("requiresConfirmation",true).put("restartFields",fields);}
        boolean versionChanged=changes.has("version")&&!beforeCopy.optString("version").equals(changes.optString("version"));
        JSONObject updated=repository.updateServer(id,changes);
        if(versionChanged){File jar=new File(repository.getServerDir(id),"server.jar");if(jar.exists())jar.delete();repository.setInstalled(id,false);}
        boolean restartQueued=false;
        if(running&&!restartFields.isEmpty()&&confirmed){
            restartQueued=true;MinecraftServerService.launchAction(context,MinecraftServerService.ACTION_STOP,id,false);
            new Thread(()->{try{long deadline=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<deadline){String status=MinecraftServerService.getSnapshotJson(id).optString("status","stopped");if(!isRunning(status))break;Thread.sleep(500);}String action=versionChanged?MinecraftServerService.ACTION_INSTALL:MinecraftServerService.ACTION_START;MinecraftServerService.launchAction(context,action,id,versionChanged);}catch(Exception ignored){}},"BlockHost-restart").start();
        }
        JSONArray fields=new JSONArray();for(String field:restartFields)fields.put(field);
        return new JSONObject().put("requiresConfirmation",false).put("restartRequired",!restartFields.isEmpty()).put("restartQueued",restartQueued).put("restartFields",fields).put("server",updated);
    }

    private static Set<String> changedRestartFields(JSONObject before,JSONObject changes) {
        Set<String> changed=new HashSet<>();compare(changed,"ramMax",before.optDouble("ramMax",1),changes);compare(changed,"port",before.optInt("port",25565),changes);compare(changed,"version",before.optString("version","latest"),changes);compare(changed,"maxPlayers",before.optInt("maxPlayers",12),changes);
        JSONObject oldSettings=before.optJSONObject("settings"),newSettings=changes.optJSONObject("settings");
        if(newSettings!=null){compare(changed,"extraArgs",oldSettings==null?"nogui":oldSettings.optString("extraArgs","nogui"),newSettings);compare(changed,"viewDistance",oldSettings==null?8:oldSettings.optInt("viewDistance",8),newSettings);compare(changed,"simulationDistance",oldSettings==null?6:oldSettings.optInt("simulationDistance",6),newSettings);}
        return changed;
    }
    private static void compare(Set<String> changed,String key,Object oldValue,JSONObject changes){if(changes.has(key)&&!String.valueOf(oldValue).equals(String.valueOf(changes.opt(key))))changed.add(key);}
    private static boolean isRunning(String status){return "running".equals(status)||"starting".equals(status)||"installing".equals(status)||"stopping".equals(status);}private static boolean isBusy(String status){return isRunning(status);}
    private static JSONObject success(Object data) throws Exception{return new JSONObject().put("ok",true).put("data",data==null?JSONObject.NULL:data);}private static JSONObject failure(Exception e){try{return new JSONObject().put("ok",false).put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}catch(Exception ignored){return new JSONObject();}}
    private static int countVisibleFiles(File dir){File[] files=dir.listFiles(file->!file.getName().equals(".build"));return files==null?0:files.length;}
    private static long folderSize(File file){if(file==null||!file.exists())return 0;if(file.isFile())return file.length();if(file.getName().equals(".build"))return 0;long total=0;File[] children=file.listFiles();if(children!=null)for(File child:children)total+=folderSize(child);return total;}
    private static Set<String> readOperatorNames(File serverDir){Set<String> names=new HashSet<>();try{File file=new File(serverDir,"ops.json");if(!file.isFile())return names;JSONArray ops=new JSONArray(Files.readString(file.toPath(),StandardCharsets.UTF_8));for(int i=0;i<ops.length();i++)names.add(ops.getJSONObject(i).optString("name"));}catch(Exception ignored){}return names;}
}
