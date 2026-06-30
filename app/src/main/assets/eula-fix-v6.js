(() => {
  const $ = selector => document.querySelector(selector);
  let pending = null;

  async function nativeCall(method, payload = {}) {
    const response = JSON.parse(window.AndroidBackend.call(method, JSON.stringify(payload)));
    if (!response.ok) throw new Error(response.error || 'Backend error');
    return response.data;
  }

  function notify(message) {
    const toast = $('#toast');
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(toast._v6Timer);
    toast._v6Timer = setTimeout(() => toast.classList.remove('show'), 2200);
  }

  const oldRow = $('.eulaRow');
  if (oldRow) oldRow.remove();

  const modal = document.createElement('div');
  modal.className = 'modalShade';
  modal.id = 'eulaPopupV6';
  modal.innerHTML = `
    <div class="sheet confirmSheet">
      <h2>Accept Minecraft EULA?</h2>
      <p id="eulaPopupText">This server needs acceptance before it can run.</p>
      <p>Accepting writes <b>eula=true</b> to eula.txt and saves the same value in BlockHost.</p>
      <div class="sheetActions">
        <button class="secondary" id="eulaPopupCancel">Not now</button>
        <button class="primary" id="eulaPopupAccept">Accept</button>
      </div>
    </div>`;
  document.body.appendChild(modal);

  function closePopup() {
    modal.classList.remove('open');
    pending = null;
  }

  function openPopup(server, action) {
    pending = { id: server.id, name: server.name, action };
    $('#eulaPopupText').textContent = `“${server.name}” needs EULA acceptance before it can be ${action === 'start' ? 'started' : 'installed'}.`;
    $('#eulaPopupAccept').textContent = action === 'start' ? 'Accept & start' : 'Accept & install';
    modal.classList.add('open');
  }

  async function currentServer() {
    const state = await BlockHostAPI.getState();
    return state.servers.find(server => server.id === state.activeServerId) || state.servers[0];
  }

  $('#eulaPopupCancel').onclick = closePopup;
  modal.onclick = event => { if (event.target === modal) closePopup(); };
  $('#eulaPopupAccept').onclick = async () => {
    if (!pending) return;
    const request = pending;
    const button = $('#eulaPopupAccept');
    button.disabled = true;
    try {
      await nativeCall('acceptEula', { id: request.id });
      closePopup();
      await new Promise(resolve => setTimeout(resolve, 300));
      if (request.action === 'start') await BlockHostAPI.startServer(request.id);
      else await BlockHostAPI.installServer(request.id, false);
      notify(request.action === 'start' ? 'EULA accepted · starting server' : 'EULA accepted · installation started');
    } catch (error) {
      notify(error.message || String(error));
    } finally {
      button.disabled = false;
    }
  };

  const powerButton = $('#powerBtn');
  if (powerButton) {
    powerButton.onclick = async () => {
      try {
        const server = await currentServer();
        if (!server) throw new Error('Server not found');
        if (server.status === 'running' || server.status === 'starting') {
          await BlockHostAPI.stopServer(server.id);
          return;
        }
        if (!server.eulaAccepted) {
          openPopup(server, server.installed ? 'start' : 'install');
          return;
        }
        if (server.installed) await BlockHostAPI.startServer(server.id);
        else await BlockHostAPI.installServer(server.id, true);
      } catch (error) {
        notify(error.message || String(error));
      }
    };
  }

  const createButton = $('#confirmCreate');
  if (createButton) {
    createButton.textContent = 'Create';
    createButton.onclick = async () => {
      createButton.disabled = true;
      try {
        const created = await BlockHostAPI.createServer({
          name: $('#newName').value.trim() || 'New Server',
          version: $('#newVersion').value,
          ramMax: Number($('#newRam').value),
          eulaAccepted: false,
          install: false,
          startAfterInstall: false
        });
        $('#createModal').classList.remove('open');
        await BlockHostAPI.selectServer(created.id);
        openPopup(created, 'install');
      } catch (error) {
        notify(error.message || String(error));
      } finally {
        createButton.disabled = false;
      }
    };
  }
})();
