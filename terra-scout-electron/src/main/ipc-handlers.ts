import { ipcMain, BrowserWindow, app, dialog, shell } from 'electron';
import { isAbsolute } from 'node:path';
import type { JavaProcess } from './java-process';
import { getKernelInfo } from './kernel-info';

export interface IpcContext {
  javaProcess: JavaProcess;
  /** 打包后的主进程资源路径（dev 为 null）。 */
  resourcesPath: string | null;
}

/**
 * 注册 IPC 桥（契约：preload 经 contextBridge 暴露 window.kernel，
 * 渲染进程不接触 Node，只经 IPC 拿 token / 端口 / 事件）。
 */
export function registerIpcHandlers(ctx: IpcContext): void {
  const info = getKernelInfo();

  ipcMain.handle('kernel:get-info', () => {
    const port = info.getPort();
    return {
      token: undefined, // token 不由渲染进程直接持有，改由 getToken 单通道
      apiBaseUrl: port > 0 ? `http://127.0.0.1:${port}/api/v1` : null,
      appVersion: app.getVersion(),
    };
  });

  ipcMain.handle('kernel:get-token', () => {
    // token 仅内存，单次 IPC 通道给渲染层拼 X-TerraScout-Token 头
    return info.getToken();
  });

  // 渲染层请求重启（401001 / giveUp 后的"重启"按钮）
  ipcMain.handle('kernel:restart', () => {
    app.relaunch();
    app.exit(0);
    return true;
  });

  // 系统文件夹选择（项目导入）
  ipcMain.handle('dialog:select-directory', async () => {
    const win = BrowserWindow.getFocusedWindow();
    const result = await dialog.showOpenDialog(win ?? (BrowserWindow.getAllWindows()[0] as BrowserWindow), {
      properties: ['openDirectory'],
      title: '选择项目根目录',
    });
    if (result.canceled || !result.filePaths.length) {
      return null;
    }
    return result.filePaths[0];
  });

  // 用系统资源管理器打开已安装 SDK 目录（SDK 管理）：仅接受绝对路径
  ipcMain.handle('sdk:open-path', async (_e, targetPath: unknown) => {
    const target = typeof targetPath === 'string' ? targetPath : '';
    if (!target || !isAbsolute(target)) {
      return { ok: false, error: '路径无效' };
    }
    const error = await shell.openPath(target);
    return error ? { ok: false, error } : { ok: true };
  });

  // 渲染层告知 Java 就绪的端口（READY 解析后由主进程转发）
  ipcMain.on('kernel:notify-ready', (_e, port: number) => {
    info.setPort(port);
    BrowserWindow.getAllWindows().forEach((w) => {
      w.webContents.send('kernel:ready', port);
    });
  });
}

/** 把 Java 进程事件广播给所有渲染窗口。 */
export function broadcastToRenderers(channel: string, payload: unknown): void {
  BrowserWindow.getAllWindows().forEach((w) => {
    w.webContents.send(channel, payload);
  });
}