import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Pause, XCircle } from 'lucide-react';
import { Modal } from 'antd';
import { api } from '../api/client';
import type { TaskDetail, TaskLogs, TaskStatus } from '../api/types';
import StepProgress from '../components/StepProgress';
import LogViewer from '../components/LogViewer';
import { PageLoading } from '../components/Common';
import styles from './pages.module.css';

/** 任务详情：状态 + 步骤 + 日志 + 操作。 */
export default function TaskDetailPage(): React.JSX.Element {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const detailQuery = useQuery({
    queryKey: ['task', id],
    queryFn: () => api.get<TaskDetail>(`/task/${id}`),
    enabled: api.isReady() && Boolean(id),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'RUNNING' || status === 'QUEUED' ? 2_000 : false;
    },
    retry: false,
  });

  const logsQuery = useQuery({
    queryKey: ['taskLogs', id],
    queryFn: () => api.get<TaskLogs>(`/task/${id}/logs?tail=200`),
    enabled: api.isReady() && Boolean(id),
    refetchInterval: 2_000,
    retry: false,
  });

  const act = (action: 'pause' | 'cancel'): void => {
    const doAct = (): Promise<unknown> =>
      api.post<unknown>(`/task/${action}`, { taskId: id }).then((r) => {
        void queryClient.invalidateQueries({ queryKey: ['task', id] });
        return r;
      });
    if (action === 'cancel') {
      Modal.confirm({
        title: '取消任务',
        content: '取消后未完成的步骤将标记为已取消，且不可恢复。',
        okButtonProps: { danger: true },
        onOk: doAct,
      });
    } else {
      void doAct();
    }
  };

  if (detailQuery.isLoading) {
    return <PageLoading />;
  }
  const detail = detailQuery.data;

  if (detailQuery.isError || !detail) {
    return <div className={styles.loading}>未找到任务或加载失败</div>;
  }

  const running = detail.status === 'RUNNING' || detail.status === 'QUEUED';

  return (
    <div>
      {/* 语境化页头：任务类型 + 编号 + 状态胶囊 + 运行中操作，不设大标题 */}
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <button type="button" className={styles.backLink} onClick={() => navigate('/tasks')}>
            <ArrowLeft size={14} aria-hidden="true" /> 返回任务列表
          </button>
          <span className={styles.pageHeaderTitle}>{detail.type}</span>
          <span className={`${styles.pageHeaderMeta} ${styles.mono} nums`}>{detail.taskId}</span>
        </div>
        <div className={styles.pageHeaderActions}>
          <span className={`${styles.pill} ${pillClass(detail.status)}`}>
            <span className={styles.pillDot} aria-hidden="true" />
            {statusLabel(detail.status)}
          </span>
          <button type="button" className={styles.btn} disabled={!running} onClick={() => act('pause')}>
            <Pause size={14} aria-hidden="true" /> 暂停
          </button>
          <button type="button" className={styles.btnDanger} disabled={!running} onClick={() => act('cancel')}>
            <XCircle size={14} aria-hidden="true" /> 取消
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        {detail.errorCode != null && (
          <div className={styles.errorLine}>
            {detail.errorCode} {detail.errorMsg}
          </div>
        )}

        <div className={styles.cardStack}>
          <div className={styles.card}>
            <div className={styles.cardHeader}>
              <h3 className={styles.cardTitle}>执行步骤</h3>
            </div>
            <StepProgress steps={detail.steps ?? []} />
          </div>

          <div className={styles.card}>
            <div className={styles.cardHeader}>
              <h3 className={styles.cardTitle}>运行日志</h3>
            </div>
            {/* 日志面板是全页唯一深色块（#18181b 底），作为视觉锚点 */}
            <LogViewer lines={logsQuery.data?.lines ?? []} truncated={logsQuery.data?.truncated ?? false} />
          </div>
        </div>
      </div>
    </div>
  );
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

/** 状态 → 灰阶胶囊修饰类：语义色只落在小点上。 */
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
