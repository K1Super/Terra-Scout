import EmptyState from './EmptyState';
import styles from './log-viewer.module.css';

interface LogViewerProps {
  lines: string[];
  truncated: boolean;
}

/**
 * 任务日志查看器（spec §10.4 / §8.6.2）。
 * aria-live="polite" 动态追加；日志追加而非替换。
 */
export default function LogViewer({ lines, truncated }: LogViewerProps): React.JSX.Element {
  if (!lines.length) {
    return <EmptyState title="暂无日志" hint="任务运行后日志将显示在此" />;
  }
  return (
    <div
      className={styles.wrap}
      role="log"
      aria-live="polite"
      aria-atomic="false"
      aria-relevant="additions"
    >
      {truncated && <p className={styles.truncate}>…… 日志过长，仅显示末尾 {lines.length} 行 ……</p>}
      {lines.map((line, i) => {
        const [cls, text] = classify(line);
        return (
          <p key={i} className={styles.line}>
            <span className={styles.time}>{fmtTime(line)}</span>
            {cls && <span className={`${styles.level} ${cls === 'info' ? styles.info : cls === 'error' ? styles.err : styles.warn}`}>{cls}</span>}
            <span className={styles.msg}>{text}</span>
          </p>
        );
      })}
    </div>
  );
}

function classify(line: string): [string, string] {
  const m = line.match(/^(\d{2}:\d{2}:\d{2})\s+(INFO|WARN|ERROR|DEBUG)?\s*(.*)$/);
  if (!m) {
    return ['', line];
  }
  return [(m[2] ?? '').toLowerCase(), m[3] ?? ''];
}

function fmtTime(line: string): string {
  const m = line.match(/^(\d{2}:\d{2}:\d{2})/);
  return m ? m[1] : '';
}