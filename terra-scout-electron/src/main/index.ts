import { app, BrowserWindow, Menu, session } from 'electron';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { existsSync } from 'node:fs';
import { JavaProcess, resolveJarPath } from './java-process';
import { setDataDir, getKernelInfo, type KernelInfo } from './kernel-info';
import { registerIpcHandlers, broadcastToRenderers } from './ipc-handlers';

/** 数据目录固定为 %USERPROFILE%\.terrascout（release 8.6 卸载保留用户数据）。 */
const DATA_DIR = (process.env.TERRASCOUT_DATA_DIR ?? join(homedir(), '.terrascout'));

/** 本机联调：未打包时 jar 指向后端 target。 */
const DEV_JAR = process.env.TERRA_SCOUT_JAR;
const resourcesPathTemplate = process.resourcesPath;

let javaProcess: JavaProcess | null = null;

function createWindow(info: KernelInfo): BrowserWindow {
  // 窗口/任务栏图标：dev 用仓库 build/icon.ico，打包后 exe 自带同名图标（路径不存在时回退）
  const iconPath = join(__dirname, '..', '..', 'build', 'icon.ico');
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    show: false,
    icon: existsSync(iconPath) ? iconPath : undefined,
    webPreferences: {
      preload: join(__dirname, '..', 'preload', 'index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });

  win.once('ready-to-show', () => win.show());

  if (process.env.VITE_DEV_SERVER_URL) {
    void win.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    void win.loadFile(join(__dirname, '..', 'renderer', 'index.html'));
  }

  void info.log(`window created, isPackaged=${app.isPackaged}`);
  return win;
}

/** Security 6.x CSP：connect-src 仅 'self' + 本机回环端口，禁用 unsafe-inline 脚本。 */
function applyCsp(): void {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [
          "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' http://127.0.0.1:*; img-src 'self' data:; font-src 'self' data:;",
        ],
      },
    });
  });
}

function wireJavaEvents(info: KernelInfo, win: BrowserWindow): void {
  if (!javaProcess) {
    return;
  }
  javaProcess.on('ready', async (port) => {
    info.setPort(port);
    info.setPid(javaProcess?.getPid() ?? 0);
    void info.log(`JAVA READY port=${port}`);
    broadcastToRenderers('kernel:ready', { port });
  });
  javaProcess.on('log', (line, stream) => {
    if (stream === 'stdout') {
      void info.log(`java: ${line}`);
    }
  });
  javaProcess.on('crashed', async (code, signal) => {
    void info.log(`JAVA CRASHED code=${code} signal=${signal}`);
    broadcastToRenderers('kernel:event', { type: 'crashed', code, signal });
  });
  javaProcess.on('giveUp', () => {
    void info.log('JAVA GIVEUP exceeded maxRestarts');
    broadcastToRenderers('kernel:event', { type: 'giveup' });
  });
  javaProcess.on('startupFailed', async (err) => {
    void info.log(`JAVA STARTUP FAILED: ${err}`);
    broadcastToRenderers('kernel:event', { type: 'startup-failed', error: err });
  });
}

function startKernel(): void {
  const info = getKernelInfo();
  const jarPath = resolveJarPath(DEV_JAR);
  if (!existsSync(jarPath)) {
    void info.log(`jar not found: ${jarPath}`);
  }
  javaProcess = new JavaProcess({
    javaBin: process.env.JAVA_BIN ?? join(resourcesPathTemplate, 'jre', 'bin', 'java.exe'),
    jarPath,
    dataDir: DATA_DIR,
    token: info.generateToken(),
  });
}

async function shutdown(): Promise<void> {
  const info = getKernelInfo();
  void info.log('shutdown requested');
  if (javaProcess) {
    await javaProcess.gracefulShutdown();
    javaProcess.cleanup();
  }
}

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null);
  applyCsp();
  setDataDir(DATA_DIR);
  const info = getKernelInfo();
  await info.log(`app v${app.getVersion()} starting, isPackaged=${app.isPackaged}`);
  startKernel();
  wireJavaEvents(info, createWindow(info));
  if (javaProcess) {
    registerIpcHandlers({ javaProcess, resourcesPath: app.isPackaged ? resourcesPathTemplate : null });
    javaProcess.start();
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow(info);
    }
  });
});

app.on('before-quit', (event) => {
  // 阻断默认退出，等待优雅关闭 Java 后再退出
  if (!javaProcess?.isShuttingDown) {
    event.preventDefault();
    void shutdown().finally(() => app.exit(0));
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});