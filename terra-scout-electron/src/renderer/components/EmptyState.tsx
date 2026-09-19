import type { LucideIcon } from 'lucide-react';
import { Inbox } from 'lucide-react';
import styles from './states.module.css';

interface EmptyStateProps {
  title: string;
  hint?: string;
  icon?: LucideIcon;
  action?: React.ReactNode;
}

/** 空状态（图标 ≤96px + 文案 ≤2 行 + 操作入口）。 */
export default function EmptyState({
  title,
  hint,
  icon: Icon = Inbox,
  action,
}: EmptyStateProps): React.JSX.Element {
  return (
    <div className={styles.empty}>
      <Icon size={44} strokeWidth={1.5} className={styles.emptyIcon} aria-hidden="true" />
      <p className={styles.emptyTitle}>{title}</p>
      {hint && <p className={styles.emptyHint}>{hint}</p>}
      {action && <div className={styles.emptyAction}>{action}</div>}
    </div>
  );
}