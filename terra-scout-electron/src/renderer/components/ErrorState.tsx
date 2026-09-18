import { AlertTriangle } from 'lucide-react';
import { describeError } from '../api/errorMap';
import { restartApp } from '../api/client';
import styles from './states.module.css';

interface ErrorStateProps {
  code: number;
  message?: string;
  onRetry?: () => void;
}

/** 错误状态（spec §5.3/§10.3：原因 + 解决路径 + 错误码 + 操作），复用错误码映射表。 */
export default function ErrorState({
  code,
  message,
  onRetry,
}: ErrorStateProps): React.JSX.Element {
  const pres = describeError(code);

  let action: React.ReactNode = null;
  if (pres.action === 'restart') {
    action = (
      <button type="button" onClick={() => restartApp()} className={styles.actionBtn}>
        重启
      </button>
    );
  } else if (pres.action === 'retry' && onRetry) {
    action = (
      <button type="button" onClick={onRetry} className={styles.actionBtn}>
        重试
      </button>
    );
  } else if (onRetry) {
    action = (
      <button type="button" onClick={onRetry} className={styles.actionBtn}>
        重试
      </button>
    );
  }

  return (
    <div className={styles.error} role="alert">
      <AlertTriangle size={44} strokeWidth={1.5} className={styles.errorIcon} aria-hidden="true" />
      <p className={styles.errorTitle}>{pres.title}</p>
      <p className={styles.errorDesc}>{message || '请根据提示处理'}</p>
      <p className={styles.errorCode}>错误码：{code}</p>
      {action && <div className={styles.emptyAction}>{action}</div>}
    </div>
  );
}