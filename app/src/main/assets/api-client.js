(() => {
  async function call(method,payload={}) {
    if (!window.AndroidBackend || typeof window.AndroidBackend.call !== 'function') throw new Error('This build requires the native Android backend');
    const response = JSON.parse(window.AndroidBackend.call(method, JSON.stringify(payload)));
    if (!response.ok) throw new Error(response.error || 'Native backend error');
    return response.data;
  }
  window.BlockHostAPI = {
    getState: () => call('getState'),
    listSpigotVersions: forceRefresh => call('listSpigotVersions',{forceRefresh:!!forceRefresh}),
    selectServer: id => call('selectServer',{id}),
    createServer: input => call('createServer',input),
    deleteServer: id => call('deleteServer',{id}),
    installServer: (id,startAfterInstall=false) => call('installServer',{id,startAfterInstall}),
    startServer: id => call('startServer',{id}),
    stopServer: id => call('stopServer',{id}),
    sendCommand: (id,command) => call('sendCommand',{id,command}),
    listFiles: (id,path='/') => call('listFiles',{id,path}),
    readFile: (id,path) => call('readFile',{id,path}).then(x=>x.content),
    writeFile: (id,path,content) => call('writeFile',{id,path,content}),
    listPlayers: id => call('listPlayers',{id}),
    updateSettings: (id,settings,restartIfRunning=false) => call('updateSettings',{id,settings,restartIfRunning}),
    backupServer: id => call('backupServer',{id}),
    clearConsole: id => call('clearConsole',{id})
  };
})();
