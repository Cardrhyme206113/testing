(() => {
  const defaults={"activeServerId":"srv-java-1","servers":[{"id":"srv-java-1","name":"Survival SMP","edition":"java","runtime":"Spigot","version":"1.21.11","port":25565,"status":"running","ramMax":2.5,"ramUsed":1.8,"cpu":31,"maxPlayers":12,"players":["p1","p2","p3"],"uptime":18642,"settings":{"viewDistance":8,"simulationDistance":6,"dynamicView":true,"pauseEmpty":true,"allowHighRam":false,"autoRestart":true,"wakeLock":true,"extraArgs":"--nogui","backupInterval":"1 hour"}},{"id":"srv-bed-1","name":"Bedrock Creative","edition":"bedrock","runtime":"PocketMine-MP","version":"Bedrock 1.26.30","port":19132,"status":"stopped","ramMax":1.5,"ramUsed":0,"cpu":0,"maxPlayers":20,"players":[],"uptime":0,"settings":{"viewDistance":8,"simulationDistance":6,"dynamicView":false,"pauseEmpty":true,"allowHighRam":false,"autoRestart":true,"wakeLock":true,"extraArgs":"","backupInterval":"6 hours"}},{"id":"srv-java-2","name":"Spigot Lab","edition":"java","runtime":"Spigot","version":"1.20.6","port":25566,"status":"stopped","ramMax":3,"ramUsed":0,"cpu":0,"maxPlayers":6,"players":[],"uptime":0,"settings":{"viewDistance":6,"simulationDistance":5,"dynamicView":true,"pauseEmpty":true,"allowHighRam":false,"autoRestart":false,"wakeLock":true,"extraArgs":"--nogui","backupInterval":"Daily"}}],"players":[{"id":"p1","name":"_Card","ping":31,"playtime":"18h 42m","role":"Owner"},{"id":"p2","name":"ThatRamattra","ping":48,"playtime":"6h 11m","role":"Player"},{"id":"p3","name":"Zojku","ping":72,"playtime":"2h 09m","role":"Player"}],"files":{"/":[{"name":"world","type":"folder","size":"246 MB"},{"name":"plugins","type":"folder","size":"38 MB"},{"name":"config","type":"folder","size":"2.4 MB"},{"name":"server.properties","type":"file","size":"1.8 KB"},{"name":"spigot.yml","type":"file","size":"12 KB"},{"name":"eula.txt","type":"file","size":"10 B"},{"name":"ops.json","type":"file","size":"442 B"}],"/world":[{"name":"level.dat","type":"file","size":"5.1 KB"},{"name":"region","type":"folder","size":"219 MB"},{"name":"playerdata","type":"folder","size":"182 KB"}],"/plugins":[{"name":"EssentialsX.jar","type":"file","size":"4.1 MB"},{"name":"LuckPerms.jar","type":"file","size":"1.6 MB"},{"name":"spark.jar","type":"file","size":"3.2 MB"}],"/config":[{"name":"bukkit.yml","type":"file","size":"14 KB"},{"name":"spark.conf","type":"file","size":"2 KB"}]},"contents":{"server.properties":"motd=A tiny server running on Android\nmax-players=12\nonline-mode=true\nwhite-list=false\nview-distance=8\nsimulation-distance=6\nserver-port=25565\nallow-flight=false\nspawn-protection=16\n","spigot.yml":"settings:\n  restart-on-crash: true\nworld-settings:\n  default:\n    view-distance: 8\n    simulation-distance: 6\n","eula.txt":"eula=true\n","ops.json":"[{\"uuid\":\"6f03...\",\"name\":\"_Card\",\"level\":4,\"bypassesPlayerLimit\":true}]\n","spark.conf":"backgroundProfiler=false\npermissionRequired=true\n"},"logs":[["info","[Server thread/INFO]: Starting Spigot server version 1.21.11"],["info","[Server thread/INFO]: Loading properties"],["ok","[Server thread/INFO]: Done (4.382s)! For help, type \"help\""],["info","[Server thread/INFO]: _Card joined the game"],["info","[Server thread/INFO]: ThatRamattra joined the game"],["warn","[Server thread/WARN]: Server is running on battery power"],["info","[Server thread/INFO]: Zojku joined the game"]]};
  let state;
  const clone=v=>JSON.parse(JSON.stringify(v));
  const reset=()=>{state=clone(defaults);return clone(state)};
  const server=id=>state.servers.find(s=>s.id===id);
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  const restartKeys=['ramMax','port','version','extraArgs'];
  reset();

  async function call(method,p={}){
    await sleep(method==='getState'?20:90);
    switch(method){
      case 'resetSession': return reset();
      case 'getState': return clone(state);
      case 'listServers': return clone(state.servers);
      case 'selectServer': {const s=server(p.id);if(!s)throw Error('Server not found');state.activeServerId=s.id;return clone(s)}
      case 'createServer': {
        const s={id:'srv-'+Math.random().toString(36).slice(2,9),name:p.name||'New Server',edition:p.edition||'java',runtime:p.runtime||(p.edition==='bedrock'?'PocketMine-MP':'Spigot'),version:p.version||(p.edition==='bedrock'?'Bedrock 1.26.30':'1.21.11'),port:p.edition==='bedrock'?19132:25565,status:'stopped',ramMax:Number(p.ramMax)||2,ramUsed:0,cpu:0,maxPlayers:10,players:[],uptime:0,settings:{viewDistance:8,simulationDistance:6,dynamicView:true,pauseEmpty:true,allowHighRam:false,autoRestart:true,wakeLock:true,extraArgs:p.edition==='bedrock'?'':'--nogui',backupInterval:'1 hour'}};
        state.servers.push(s);state.activeServerId=s.id;return clone(s);
      }
      case 'deleteServer': {if(state.servers.length<=1)throw Error('At least one server must remain');const i=state.servers.findIndex(s=>s.id===p.id);if(i<0)throw Error('Server not found');state.servers.splice(i,1);if(state.activeServerId===p.id)state.activeServerId=state.servers[0].id;return {ok:true}}
      case 'startServer': {const s=server(p.id);if(!s)throw Error('Server not found');s.status='running';s.ramUsed=Math.max(.2,Math.min(s.ramMax*.62,s.ramMax-.2));s.cpu=18;state.logs.push(['ok',`[BlockHost]: ${s.name} started successfully`]);return clone(s)}
      case 'stopServer': {const s=server(p.id);if(!s)throw Error('Server not found');s.status='stopped';s.ramUsed=0;s.cpu=0;s.players=[];state.logs.push(['warn',`[BlockHost]: ${s.name} stopped`]);return clone(s)}
      case 'tickStats': {const s=server(p.id);if(!s)throw Error('Server not found');if(s.status==='running'){s.cpu=Math.max(8,Math.min(78,s.cpu+(Math.random()*14-7)));s.ramUsed=Math.max(.6,Math.min(s.ramMax-.08,s.ramUsed+(Math.random()*.1-.04)));s.uptime+=3}return clone(s)}
      case 'listFiles': return clone(state.files[p.path||'/']||[]);
      case 'readFile': return {content:state.contents[(p.path||'').split('/').pop()]??'# Mock UTF-8 file\nenabled: true\n'};
      case 'writeFile': state.contents[(p.path||'').split('/').pop()]=p.content;return {ok:true};
      case 'listPlayers': {const s=server(p.id);return s?clone(state.players.filter(x=>s.players.includes(x.id))):[]}
      case 'kickPlayer': {const s=server(p.serverId);if(!s)throw Error('Server not found');const player=state.players.find(x=>x.id===p.playerId);s.players=s.players.filter(id=>id!==p.playerId);state.logs.push(['warn',`[Server thread/INFO]: Kicked ${player?.name||p.playerId}: ${p.reason||'Kicked by admin'}`]);return {ok:true}}
      case 'sendCommand': {const s=server(p.id);if(!s)throw Error('Server not found');const command=String(p.command||'').trim();state.logs.push(['info','> '+command]);let reply='Unknown or incomplete command';if(command==='list'){const names=state.players.filter(x=>s.players.includes(x.id)).map(x=>x.name);reply=`There are ${names.length} of a max of ${s.maxPlayers} players online: ${names.join(', ')}`}else if(command.startsWith('say '))reply='[Server] '+command.slice(4);else if(command==='save-all')reply='Saved the game';state.logs.push(['ok','[Server thread/INFO]: '+reply]);return {reply}}
      case 'updateSettings': {
        const s=server(p.id);if(!s)throw Error('Server not found');const next=p.settings||{};
        const changed=restartKeys.filter(k=>String(k==='extraArgs'?s.settings[k]:s[k])!==String(next[k]));
        if(s.status==='running'&&changed.length&&!p.restartIfRunning)return {ok:false,requiresConfirmation:true,restartFields:changed};
        if(next.name?.trim())s.name=next.name.trim();
        ['ramMax','port','version'].forEach(k=>{if(next[k]!==undefined)s[k]=next[k]});
        ['viewDistance','simulationDistance','dynamicView','pauseEmpty','allowHighRam','autoRestart','wakeLock','extraArgs','backupInterval'].forEach(k=>{if(next[k]!==undefined)s.settings[k]=next[k]});
        const restarted=s.status==='running'&&changed.length>0;
        if(restarted){state.logs.push(['warn',`[BlockHost]: Restarting ${s.name} to apply startup changes`]);s.ramUsed=Math.max(.2,Math.min(s.ramMax*.62,s.ramMax-.2));s.cpu=18;s.players=[];state.logs.push(['ok',`[BlockHost]: ${s.name} restarted successfully`])}
        return {ok:true,restartRequired:changed.length>0,restarted,restartFields:changed,server:clone(s)};
      }
      case 'exportServer': return {ok:true,format:'BlockHost mock export v1',server:clone(server(p.id)),files:clone(state.files),contents:clone(state.contents)};
      default: throw Error('Unknown backend method: '+method);
    }
  }
  window.BlockHostBackend={call};
})();
