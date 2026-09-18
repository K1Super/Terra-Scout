import { useLayoutEffect, useRef, useState } from 'react';

import styles from './segmented.module.css';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
}

export interface SegmentedProps<T extends string> {
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  ariaLabel?: string;
}

/**
 * 分段切换标签栏（滑动胶囊版，白灰主题）。
 *
 * <p>结构：浅灰圆角容器 → 独立胶囊背景元素（绝对定位，随选中项平滑位移/缩放，
 * left/width 过渡动画）→ 上层一排透明背景按钮（z-index 高于胶囊，仅承载文字）。
 * 高亮背景是独立的胶囊元素，不是按钮自身的背景。
 */
export function Segmented<T extends string>(props: SegmentedProps<T>) {
  const { options, value, onChange, ariaLabel } = props;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRefs = useRef(new Map<string, HTMLButtonElement | null>());
  const [thumb, setThumb] = useState({ left: 0, width: 0 });

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const sync = () => {
      const active = buttonRefs.current.get(value);
      if (active) {
        setThumb({ left: active.offsetLeft, width: active.offsetWidth });
      }
    };
    sync();
    // 窗口/字体尺寸变化时重算胶囊位置与宽度
    const observer = new ResizeObserver(sync);
    observer.observe(container);
    return () => observer.disconnect();
  }, [value]);

  return (
    <div ref={containerRef} className={styles.segmented} role="tablist" aria-label={ariaLabel}>
      <div className={styles.thumb} style={{ width: thumb.width, transform: `translateX(${thumb.left}px)` }} />
      {options.map((o) => (
        <button
          key={o.value}
          ref={(el) => {
            buttonRefs.current.set(o.value, el);
          }}
          type="button"
          role="tab"
          aria-selected={o.value === value}
          className={o.value === value ? styles.itemActive : styles.item}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
