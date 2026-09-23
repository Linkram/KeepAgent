const { app, BrowserWindow } = require('electron');
app.whenReady().then(() => {
  const window = new BrowserWindow({show:false, width:800, height:600});
  window.loadFile(require('node:path').join(__dirname, 'index.html'));
});
app.on('window-all-closed', () => app.quit());
