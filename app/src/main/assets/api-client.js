(() => {
  async function call(method,payload={}) {
    if(!window.BlockHostBackend) throw new Error('Mock backend unavailable');
    return window.BlockHostBackend.call(method,payload);
  }
  window.BlockHostAPI={
    resetSession:()=>call('resetSession'),
    getState:()=>call('getState'),
    listServers:()=>call('listServers'),
    selectServer:id=>call('selectServer',{id}),
    createServer:input=>call('createServer',input),
    deleteServer:id=>call('deleteServer',{id}),
    startServer:id=>call('startServer',{id}),
    stopServer:id=>call('stopServer',{id}),
    tickStats:id=>call('tickStats',{id}),
    listFiles:(path='/')=>call('listFiles',{path}),
    readFile:path=>call('readFile',{path}).then(x=>x.content),
    writeFile:(path,content)=>call('writeFile',{path,content}),
    listPlayers:id=>call('listPlayers',{id}),
    kickPlayer:(serverId,playerId,reason='Kicked by admin')=>call('kickPlayer',{serverId,playerId,reason}),
    sendCommand:(id,command)=>call('sendCommand',{id,command}),
    updateSettings:(id,settings,restartIfRunning=false)=>call('updateSettings',{id,settings,restartIfRunning}),
    exportServer:id=>call('exportServer',{id})
  };
})();
