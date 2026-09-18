import { useEffect, useState } from 'react';
import { useKernelStore } from '../store/kernel';
import { restartApp } from '../api/client';
import styles from './kernel-status.module.css';

/** 派生展示态：异常（崩溃/重启中）> 启动中 > 就绪。 */
type KernelPhase = 'error' | 'booting' | 'ready';

/**
 * 内核状态浮动胶囊（右下角，替代原顶部横幅）。
 *
 * 为什么用浮动胶囊：顶部横幅在内核启动期间占据大片顶部空间、
 * 造成正文空白等待。改为右下角 34px 高的小胶囊后，
 * 主界面启动期间立刻渲染，状态感知退到角落，不打断阅读。
 *
 * 状态源：zustand store/kernel.ts（只读——Zustand 仅承载客户端进程状态，
 * 服务端数据一律走 TanStack Query，两者不交叉）。
 */
export default function KernelStatus(): React.JSX.Element | null {
  const ready = useKernelStore((s) => s.ready);
  const event = useKernelStore((s) => s.event);
  const restartRequired = useKernelStore((s) => s.restartRequired);

  // 就绪态展示 4 秒后整体淡出；启动中/异常常驻
  const [faded, setFaded] = useState(false);

  const phase: KernelPhase = event ? 'error' : ready ? 'ready' : 'booting';

  useEffect(() => {
    // 任何状态切换都先回到可见，再按需排定淡出计时
    setFaded(false);
    if (phase === 'ready') {
      const timer = window.setTimeout(() => setFaded(true), 4000);
      return () => window.clearTimeout(timer);
    }
  }, [phase]);

  // 状态文案与状态点颜色的唯一映射
  const dotClass =
    phase === 'error' ? styles.dotError : phase === 'booting' ? styles.dotBooting : styles.dotReady;
  const label = phase === 'error' ? event : phase === 'booting' ? '内核启动中…' : '内核就绪';

  return (
    <div
      className={`${styles.pill} ${faded ? styles.pillFaded : ''}`}
      role="status"
      aria-live="polite"
      aria-hidden={faded}
    >
      <span className={`${styles.dot} ${dotClass}`} aria-hidden="true" />
      <span className={styles.label}>{label}</span>
      {/* 仅"GIVEUP 需手动重启"场景提供动作：克制的暖色文字按钮 */}
      {phase === 'error' && restartRequired && (
        <button type="button" className={styles.restart} onClick={() => restartApp()}>
          重启
        </button>
      )}
    </div>
  );
}
