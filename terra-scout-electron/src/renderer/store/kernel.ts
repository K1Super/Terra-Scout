import { create } from 'zustand';

/**
 * 内核全局状态（Zustand，分工：仅承载跨页共享的客户端状态——内核就绪/崩溃/GIVEUP 横幅）。
 * 服务端数据一律走 TanStack Query，不进本 store。
 */
export type KernelEventKind = 'crashed' | 'giveup' | 'startup-failed';

interface KernelState {
  ready: boolean;
  event: string | null;
  /** 是否需要手动重启（giveup）。 */
  restartRequired: boolean;
  setReady: () => void;
  setError: (kind: KernelEventKind, error?: string) => void;
  clearEvent: () => void;
}

export const useKernelStore = create<KernelState>()((set) => ({
  ready: false,
  event: null,
  restartRequired: false,
  setReady: () => set({ ready: true, restartRequired: false }),
  setError: (kind, error) => {
    if (kind === 'crashed') {
      set({ event: 'Java 内核异常退出，正在自动重启…', restartRequired: false });
    } else if (kind === 'giveup') {
      set({ event: 'Java 内核连续崩溃，请手动重启工具', restartRequired: true });
    } else {
      set({ event: `Java 内核启动失败：${error ?? ''}`, restartRequired: false });
    }
  },
  clearEvent: () => set({ event: null }),
}));