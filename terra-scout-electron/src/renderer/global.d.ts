/**
 * 全局 window.kernel 声明（由 preload contextBridge 注入）。
 */
export interface KernelApi {
  getInfo: () => Promise<{ apiBaseUrl: string | null; appVersion: string }>;
  getToken: () => Promise<string | null>;
  onReady: (cb: (payload: { port: number }) => void) => () => void;
  onEvent: (
    cb: (payload: {
      type: string;
      code?: number | null;
      signal?: string | null;
      error?: string;
    }) => void,
  ) => () => void;
  restart: () => Promise<boolean>;
  selectDirectory: () => Promise<string | null>;
  /** 用系统资源管理器打开已安装 SDK 目录（仅接受绝对路径）。 */
  openPath: (targetPath: string) => Promise<{ ok: boolean; error?: string }>;
}

declare global {
  interface Window {
    kernel: KernelApi;
  }
}

export {};