from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java" / "com" / "example" / "blockhost"

replacements = {
    "ServerRepository.java": {
        "Files.readString(stateFile.toPath(), StandardCharsets.UTF_8)": "FileIo.readUtf8(stateFile)",
        "Files.readString(file.toPath(), StandardCharsets.UTF_8)": "FileIo.readUtf8(file)",
        "Files.writeString(file.toPath(), content, StandardCharsets.UTF_8)": "FileIo.writeUtf8(file, content)",
        "Files.writeString(tmp.toPath(), state.toString(2), StandardCharsets.UTF_8)": "FileIo.writeUtf8(tmp, state.toString(2))",
        "Files.writeString(stateFile.toPath(), state.toString(2), StandardCharsets.UTF_8)": "FileIo.writeUtf8(stateFile, state.toString(2))",
        "Files.writeString(file.toPath(), text, StandardCharsets.UTF_8)": "FileIo.writeUtf8(file, text)",
        "file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : \"\"": "file.isFile() ? FileIo.readUtf8(file) : \"\"",
    },
    "BlockHostBackend.java": {
        "Files.readString(file.toPath(),StandardCharsets.UTF_8)": "FileIo.readUtf8(file)",
    },
    "MinecraftServerService.java": {
        "processStats.sample(process.pid())": "processStats.sample(ProcessId.get(process))",
        "String action=intent.getAction(),serverId=intent.getStringExtra(EXTRA_SERVER_ID);": "repository=new ServerRepository(this);runtime=new LinuxRuntimeManager(this,repository);String action=intent.getAction(),serverId=intent.getStringExtra(EXTRA_SERVER_ID);",
        "JSONObject server=repository.getServer(serverId);if(server==null)throw new IllegalArgumentException(\"Server not found\");if(!server.optBoolean(\"eulaAccepted\",false))throw new IllegalStateException(\"Accept the Minecraft EULA before installing\");": "JSONObject server=EulaManager.reconcile(repository,serverId);if(server==null)throw new IllegalArgumentException(\"Server not found\");if(!server.optBoolean(\"eulaAccepted\",false))throw new IllegalStateException(\"Accept the Minecraft EULA before installing\");",
        "JSONObject server=repository.getServer(serverId);if(server==null)throw new IllegalArgumentException(\"Server not found\");File serverDir=repository.getServerDir(serverId);": "JSONObject server=EulaManager.reconcile(repository,serverId);if(server==null)throw new IllegalArgumentException(\"Server not found\");if(!server.optBoolean(\"eulaAccepted\",false))throw new IllegalStateException(\"Accept the Minecraft EULA before starting\");File serverDir=repository.getServerDir(serverId);",
        "updateNotification(\"Install failed: \"+shorten(e.getMessage(),70),false);}": "updateNotification(\"Install failed: \"+shorten(e.getMessage(),70),false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}",
        "updateNotification(\"Server failed: \"+shorten(e.getMessage(),70),false);}": "updateNotification(\"Server failed: \"+shorten(e.getMessage(),70),false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}",
    },
    "BlockHostActivity.java": {
        "webView.setWebViewClient(new WebViewClient());": "webView.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(WebView view, String url) { view.evaluateJavascript(\"(function(){if(document.getElementById('eulaFixV6'))return;var s=document.createElement('script');s.id='eulaFixV6';s.src='file:///android_asset/eula-fix-v6.js';document.body.appendChild(s);})()\", null); } });",
    },
}

for name, pairs in replacements.items():
    path = root / name
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in pairs.items():
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"patched {name}")
    else:
        print(f"no changes needed for {name}")
