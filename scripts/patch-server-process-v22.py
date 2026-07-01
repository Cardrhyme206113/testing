from pathlib import Path

service_path = Path('app/src/main/java/com/example/blockhost/MinecraftServerService.java')
text = service_path.read_text(encoding='utf-8')

text = text.replace(
    '    public static final String ACTION_STOP = "com.example.blockhost.STOP";\n',
    '    public static final String ACTION_STOP = "com.example.blockhost.STOP";\n'
    '    public static final String ACTION_COMMAND = "com.example.blockhost.COMMAND";\n'
)
text = text.replace(
    '    public static final String EXTRA_START_AFTER_INSTALL = "startAfterInstall";\n',
    '    public static final String EXTRA_START_AFTER_INSTALL = "startAfterInstall";\n'
    '    public static final String EXTRA_COMMAND = "command";\n'
)

old_send = '''    public static boolean sendCommand(String command) {
        MinecraftServerService current = instance;
        return current != null && current.sendCommandInternal(command);
    }

'''
new_send = '''    public static void launchCommand(Context context, String serverId, String command) {
        Intent intent = new Intent(context, MinecraftServerService.class)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_SERVER_ID, serverId)
                .putExtra(EXTRA_COMMAND, command == null ? "" : command);
        context.startService(intent);
    }

'''
if old_send in text:
    text = text.replace(old_send, new_send, 1)

old_start = '''        if(ACTION_STOP.equals(action)){requestStop(id);return START_NOT_STICKY;}
        startForeground(NOTIFICATION_ID,buildNotification("Preparing BlockHost…",false));
'''
new_start = '''        if(ACTION_STOP.equals(action)){requestStop(id);return START_NOT_STICKY;}
        if(ACTION_COMMAND.equals(action)){
            String command=intent.getStringExtra(EXTRA_COMMAND);
            if(!sendCommandInternal(command==null?"":command)){
                repository.appendLog(id,"[BlockHost/ERROR] Server is not accepting commands");
            }
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID,buildNotification("Preparing BlockHost…",false));
'''
if old_start not in text:
    raise SystemExit('onStartCommand insertion point not found')
text = text.replace(old_start, new_start, 1)

old_ram = '''            int ramMb=Math.max(512,(int)Math.round(server.optDouble("ramMax",1.0)*1024));
            JSONObject settings=server.optJSONObject("settings"); String extra=settings==null?"nogui":settings.optString("extraArgs","nogui");
            List<String> args=new ArrayList<>();
            args.add("java"); args.add("-Djava.awt.headless=true"); args.add("-Dfile.encoding=UTF-8");
            args.add("-Xms128M"); args.add("-Xmx"+ramMb+"M"); args.add("-XX:+UseG1GC");
'''
new_ram = '''            int memoryBudgetMb=Math.max(768,(int)Math.round(server.optDouble("ramMax",1.0)*1024));
            int nativeHeadroomMb=Math.max(256,Math.min(768,(int)Math.round(memoryBudgetMb*0.20)));
            int heapMb=Math.max(384,memoryBudgetMb-nativeHeadroomMb);
            JSONObject settings=server.optJSONObject("settings"); String extra=settings==null?"nogui":settings.optString("extraArgs","nogui");
            List<String> args=new ArrayList<>();
            args.add("java");
            args.add("-Djava.awt.headless=true");
            args.add("-Dfile.encoding=UTF-8");
            args.add("-DPaper.IgnoreJavaVersion=true");
            args.add("-XX:+IgnoreUnrecognizedVMOptions");
            args.add("-Xms128M");
            args.add("-Xmx"+heapMb+"M");
            args.add("-XX:ErrorFile="+new File(serverDir,"hs_err_pid%p.log").getAbsolutePath());
            args.add("-XX:+UseG1GC");
'''
if old_ram not in text:
    raise SystemExit('RAM argument block not found')
text = text.replace(old_ram, new_ram, 1)

text = text.replace(
    'repository.appendLog(serverId,"[BlockHost] Starting server with Android Java 21 and a "+ramMb+" MB RAM cap");',
    'repository.appendLog(serverId,"[BlockHost] Starting server with a "+memoryBudgetMb+" MB total memory budget: -Xmx"+heapMb+"M and "+nativeHeadroomMb+" MB JVM/native headroom");\n'
    '            repository.appendLog(serverId,"[BlockHost] Paper Java-version check bypass enabled for the Android Java runtime");'
)
text = text.replace(
    'repository.appendLog(serverId,"[BlockHost] Starting Paper with Android Java 21 and a "+ramMb+" MB RAM cap");',
    'repository.appendLog(serverId,"[BlockHost] Starting server with a "+memoryBudgetMb+" MB total memory budget: -Xmx"+heapMb+"M and "+nativeHeadroomMb+" MB JVM/native headroom");\n'
    '            repository.appendLog(serverId,"[BlockHost] Paper Java-version check bypass enabled for the Android Java runtime");'
)

old_update = '''    private void updateSnapshot(String id,String status,String phase,int progress,String message,long ram,double cpu,long started){JSONArray players=new JSONArray();synchronized(onlinePlayers){for(String p:onlinePlayers)players.put(p);}synchronized(SNAPSHOT_LOCK){snapshot=new RuntimeSnapshot(id,status,phase,progress,message==null?"":message,ram,cpu,players,started);}}
'''
new_update = '''    private void updateSnapshot(String id,String status,String phase,int progress,String message,long ram,double cpu,long started){
        JSONArray players=new JSONArray();
        synchronized(onlinePlayers){for(String p:onlinePlayers)players.put(p);}
        RuntimeSnapshot next=new RuntimeSnapshot(id,status,phase,progress,message==null?"":message,ram,cpu,players,started);
        synchronized(SNAPSHOT_LOCK){snapshot=next;}
        try{ServerProcessState.write(this,next.toJson());}catch(Exception ignored){}
    }
'''
if old_update not in text:
    raise SystemExit('updateSnapshot block not found')
text = text.replace(old_update, new_update, 1)

service_path.write_text(text, encoding='utf-8')
print('Patched isolated server process, Paper Java bypass, crash logs, and RAM headroom')

backend_path = Path('app/src/main/java/com/example/blockhost/BlockHostBackend.java')
backend = backend_path.read_text(encoding='utf-8')
backend = backend.replace('MinecraftServerService.getSnapshotJson(id)', 'ServerProcessState.read(context, id)')
old_command = '''                case "sendCommand": {
                    boolean sent = MinecraftServerService.sendCommand(payload.optString("command", ""));
                    if (!sent) throw new IllegalStateException("The server is not accepting commands");
                    data = new JSONObject().put("sent", true);
                    break;
                }
'''
new_command = '''                case "sendCommand": {
                    MinecraftServerService.launchCommand(
                            context,
                            payload.getString("id"),
                            payload.optString("command", "")
                    );
                    data = new JSONObject().put("queued", true);
                    break;
                }
'''
if old_command not in backend:
    raise SystemExit('backend command block not found')
backend = backend.replace(old_command, new_command, 1)
backend_path.write_text(backend, encoding='utf-8')
print('Patched UI backend for cross-process state and commands')

index_path = Path('app/src/main/assets/index.html')
html = index_path.read_text(encoding='utf-8')
html = html.replace('Maximum server RAM:', 'Total server RAM budget:')
html = html.replace('Memory limit', 'Total memory budget')
headroom_note = '<small class="softwareNote">The Java heap is set below this budget so the JVM, threads, native libraries and direct buffers have headroom.</small>'
needle = '<div class="rangeLabels"><span>0.5 GB</span><span>Default: 1 GB</span><span>6 GB</span></div></div>'
if headroom_note not in html and needle in html:
    html = html.replace(needle, needle + headroom_note, 1)
index_path.write_text(html, encoding='utf-8')
print('Updated RAM controls to describe a total budget rather than a heap limit')
