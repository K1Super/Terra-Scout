import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { api } from '../api/client';
import type { TaskListItem, TaskStatus } from '../api/types';
import { describeError } from '../api/errorMap';
import { formatTime } from '../lib/id';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import TaskProgress from '../components/TaskProgress';
import { PageLoading } from '../components/Common';
import styles from './pages.module.css';

/**
 * 任务中心：进行中 / 已完成 / 失败分组。
 * 页头是纯语境统计（"进行中 N · 已完成 N · 失败 N"），不重复导航词；
 * 状态标签一律灰阶胶囊 + 语义色小点，分组标题用 11px 大写小标。
 */
export default function TasksPage(): React.JSX.Element {
  const navigate = useNavigate();

  const tasksQuery = useQuery({
    queryKey: ['tasks'],
    queryFn: () => api.get<{ items: TaskListItem[] }>('/task?page=1&size=100'),
    enabled: api.isReady(),
    staleTime: 1_000,
    refetchInterval: (query) => {
      const items = (query.state.data as { items?: TaskListItem[] } | undefined)?.items ?? [];
      const hasActive = items.some((t) => isActive(t.status));
      return hasActive ? 2_000 : false;
    },
    retry: false,
  });

  if (tasksQuery.isError) {
    const e = tasksQuery.error;
    return (
      <ErrorState
        code={typeof e === 'object' && e !== null && 'code' in e ? Number((e as { code: number }).code) : 500000}
        message={String(e)}
        onRetry={() => void tasksQuery.refetch()}
      />
    );
  }

  const tasks = tasksQuery.data?.items ?? [];
  const active = tasks.filter((t) => t.status === 'RUNNING' || t.status === 'QUEUED');
  const done = tasks.filter((t) => t.status === 'SUCCESS' || t.status === 'CANCELED' || t.status === 'SKIPPED');
  const failed = tasks.filter((t) => t.status === 'FAILED');

  const renderCard = (t: TaskListItem): React.JSX.Element => (
    <div key={t.taskId} className={styles.card}>
      <div className={styles.cardHeader}>
        <div>
          <div className={styles.cardTitle}>{t.type}</div>
          <div className={styles.cardMeta}>更新于 {formatTime(t.updatedAt)}</div>
        </div>
        <span className={`${styles.pill} ${pillClass(t.status)}`}>
          <span className={styles.pillDot} aria-hidden="true" />
          {statusLabel(t.status)}
        </span>
      </div>
      {t.status === 'RUNNING' && (
        <div style={{ margin: '8px 0 12px' }}>
          <TaskProgress percent={t.progress} label="任务进度" />
        </div>
      )}
      {t.status === 'FAILED' && t.errorCode != null && (
        <div className={styles.errorLine}>
          {t.errorCode} {t.errorMsg ?? describeError(t.errorCode).title}
        </div>
      )}
      <button
        type="button"
        className={styles.linkBtn}
        onClick={() => navigate(`/task/${t.taskId}`)}
        aria-label={`查看任务 ${t.taskId}`}
      >
        查看详情 <ChevronRight size={14} aria-hidden="true" />
      </button>
    </div>
  );

  if (tasksQuery.isLoading) {
    return (
      <div>
        <header className={styles.pageHeader}>
          <span className={styles.pageHeaderMeta}>正在读取任务…</span>
        </header>
        <div className={styles.pageBody}>
          <PageLoading />
        </div>
      </div>
    );
  }

  return (
    <div>
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <span className={styles.pageHeaderMeta}>
            {tasks.length === 0
              ? '暂无任务'
              : `共 ${tasks.length} 个任务 · 进行中 ${active.length} · 失败 ${failed.length}`}
          </span>
        </div>
      </header>

      <div className={styles.pageBody}>
        {!tasks.length ? (
          <EmptyState title="还没有任务" hint="从项目详情开始装配任务" />
        ) : (
          <>
            {active.length > 0 && (
              <section>
                <p className={styles.sectionLabel}>进行中</p>
                <div className={styles.cardGrid}>{active.map(renderCard)}</div>
              </section>
            )}
            {done.length > 0 && (
              <section>
                <p className={styles.sectionLabel}>已完成</p>
                <div className={styles.cardGrid}>{done.map(renderCard)}</div>
              </section>
            )}
            {failed.length > 0 && (
              <section>
                <p className={styles.sectionLabel}>失败</p>
                <div className={styles.cardGrid}>{failed.map(renderCard)}</div>
              </section>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function isActive(s: TaskStatus): boolean {
  return s === 'RUNNING' || s === 'QUEUED' || s === 'PAUSED';
}

function statusLabel(s: TaskStatus): string {
  const map: Record<TaskStatus, string> = {
    QUEUED: '排队中',
    RUNNING: '进行中',
    PAUSED: '已暂停',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELED: '已取消',
    SKIPPED: '已跳过',
  };
  return map[s] ?? s;
}

/** 状态 → 灰阶胶囊修饰类：语义色只落在 7px 小点上。 */
function pillClass(s: TaskStatus): string {
  switch (s) {
    case 'SUCCESS':
      return styles.pillSuccess;
    case 'FAILED':
      return styles.pillError;
    case 'RUNNING':
      return styles.pillRun;
    case 'PAUSED':
      return styles.pillWarn;
    default:
      return '';
  }
}
