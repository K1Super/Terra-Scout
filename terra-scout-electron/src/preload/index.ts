import { contextBridge, ipcRenderer } from 'electron';

/**
 * preload（contextBridge）：暴露 window.kernel 给渲染进程。
 * 渲染进程不接触 Node/命令行，仅经 IPC 获取 token、端口与内核事件。
 */
export interface KernelReadyPayload {
  port: number;
}

contextBridge.exposeInMainWorld('kernel', {
  /** 内核基础信息（端口就绪后才有 apiBaseUrl）。 */
  getInfo: (): Promise<{ apiBaseUrl: string | null; appVersion: string }> =>
    ipcRenderer.invoke('kernel:get-info'),
  /** 仅内存 token 单通道，供拼 X-TerraScout-Token 头。 */
  getToken: (): Promise<string | null> => ipcRenderer.invoke('kernel:get-token'),
  /** Java READY 后收到实际端口。 */
  onReady: (cb: (payload: KernelReadyPayload) => void): (() => void) => {
    const handler = (_e: unknown, payload: KernelReadyPayload): void => cb(payload);
    ipcRenderer.on('kernel:ready', handler);
    return () => ipcRenderer.removeListener('kernel:ready', handler);
  },
  /** 内核事件：crashed / giveup / startup-failed。 */
  onEvent: (
    cb: (payload: { type: string; code?: number | null; signal?: string | null; error?: string }) => void,
  ): (() => void) => {
    const handler = (_e: unknown, payload: Parameters<typeof cb>[0]): void => cb(payload);
    ipcRenderer.on('kernel:event', handler);
    return () => ipcRenderer.removeListener('kernel:event', handler);
  },
  /** 请求重启（401001 / 内核放弃重启后的"重启"按钮）。 */
  restart: (): Promise<boolean> => ipcRenderer.invoke('kernel:restart'),
  /** 弹出系统目录选择，返回选中路径或 null（项目导入）。 */
  selectDirectory: (): Promise<string | null> => ipcRenderer.invoke('dialog:select-directory'),
  /** 用系统资源管理器打开已安装 SDK 目录（仅接受绝对路径）。 */
  openPath: (targetPath: string): Promise<{ ok: boolean; error?: string }> =>
    ipcRenderer.invoke('sdk:open-path', targetPath),
});