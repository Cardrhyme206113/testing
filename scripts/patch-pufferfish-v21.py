from pathlib import Path
import subprocess

path = Path('app/src/main/java/com/example/blockhost/MinecraftServerService.java')
text = path.read_text(encoding='utf-8')

text = text.replace(
    '/** Downloads ready Paper JARs and runs them with Android-native OpenJDK. */',
    '/** Downloads ready Pufferfish/Paper JARs and runs them with Android-native OpenJDK. */'
)
text = text.replace(
    'updateSnapshot(serverId,"installing","paper-resolve",91,"Finding ready Paper build",0,0);',
    'updateSnapshot(serverId,"installing","server-resolve",91,"Finding ready Pufferfish build",0,0);'
)
text = text.replace(
    'SpigotVersionProvider.PaperDownload download=paper.resolveDownload(requested);',
    'SpigotVersionProvider.ServerDownload download=paper.resolveDownload(requested);\n'
    '            if(!download.note.isEmpty()) repository.appendLog(serverId,"[BlockHost] "+download.note);'
)
text = text.replace(
    'downloadPaperJar(download,jar,serverId);',
    'downloadServerJar(download,jar,serverId);'
)
text = text.replace(
    'updateSnapshot(serverId,"stopped","installed",100,"Paper "+download.version+" build "+download.build+" installed",0,0);',
    'updateSnapshot(serverId,"stopped","installed",100,download.source+" "+download.version+" build "+download.build+" installed",0,0);'
)
text = text.replace(
    'updateNotification("Paper installed: "+server.optString("name"),false);',
    'updateNotification(download.source+" installed: "+server.optString("name"),false);'
)
text = text.replace(
    'repository.appendLog(serverId,"[BlockHost] Starting Paper with Android Java 21 and a "+ramMb+" MB RAM cap");',
    'repository.appendLog(serverId,"[BlockHost] Starting server with Android Java 21 and a "+ramMb+" MB RAM cap");'
)
text = text.replace(
    'private void downloadPaperJar(SpigotVersionProvider.PaperDownload info, File output, String serverId) throws Exception {',
    'private void downloadServerJar(SpigotVersionProvider.ServerDownload info, File output, String serverId) throws Exception {'
)
text = text.replace(
    'c.setRequestProperty("User-Agent","BlockHost-Android/0.4");',
    'c.setRequestProperty("User-Agent","BlockHost/0.5.0 (https://github.com/Cardrhyme206113/testing)");'
)
text = text.replace(
    'throw new java.io.IOException("Paper download returned HTTP "+c.getResponseCode());',
    'throw new java.io.IOException(info.source+" download returned HTTP "+c.getResponseCode());'
)
text = text.replace(
    'String msg="Downloading ready Paper JAR: "+human(done)+(total>0?" / "+human(total):"");',
    'String msg="Downloading ready "+info.source+" JAR: "+human(done)+(total>0?" / "+human(total):"");'
)
text = text.replace(
    'updateSnapshot(serverId,"installing","paper-download",p,msg,0,0);',
    'updateSnapshot(serverId,"installing","server-download",p,msg,0,0);'
)
text = text.replace(
    'throw new java.io.IOException("Paper JAR checksum mismatch");',
    'throw new java.io.IOException(info.source+" JAR checksum mismatch");'
)

path.write_text(text, encoding='utf-8')
print('Patched MinecraftServerService for Pufferfish-preferred downloads')

index = Path('app/src/main/assets/index.html')
if index.exists():
    html = index.read_text(encoding='utf-8')
    html = html.replace('Java · Paper', 'Java · Pufferfish/Paper')
    html = html.replace('<input value="Paper" readonly />', '<input value="Pufferfish preferred; Paper fallback" readonly />')
    html = html.replace('Downloads a ready Paper server JAR directly. Nothing is compiled on your phone.',
                        'Downloads a ready Pufferfish JAR when available, otherwise the matching stable Paper JAR. Nothing is compiled on your phone.')
    html = html.replace('Create Paper server', 'Create Minecraft server')
    html = html.replace('The app downloads a ready Paper server JAR and an Android-native Java 21 runtime.',
                        'The app prefers a ready Pufferfish server JAR, falls back to stable Paper, and downloads an Android-native Java 21 runtime.')
    index.write_text(html, encoding='utf-8')

app = Path('app/src/main/assets/app-native.js')
if app.exists():
    js = app.read_text(encoding='utf-8')
    js = js.replace('Java · Paper ·', 'Java · Pufferfish/Paper ·')
    js = js.replace('Paper ${esc(x.version)}', 'Minecraft ${esc(x.version)}')
    app.write_text(js, encoding='utf-8')

subprocess.run(['python3', 'scripts/patch-server-process-v22.py'], check=True)
