import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { Modal, message } from 'antd';
import { api, ApiError, KernelNotReadyError } from '../api/client';
import { describeError } from '../api/errorMap';
import type { ProjectListItem } from '../api/types';
import { uuid } from '../lib/id';
import ProjectCard from '../components/ProjectCard';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import { PageLoading } from '../components/Common';
import styles from './pages.module.css';

/**
 * 首页：项目列表 + 导入。
 * 页头采用「语境化页头」：左侧是该页上下文统计（"N 个项目"），
 * 右侧是唯一主操作「导入项目」——不重复侧栏导航词，也不设大标题。
 */
export default function HomePage(): React.JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.get<{ items: ProjectListItem[] }>('/project?page=1&size=100'),
    enabled: api.isReady(),
    retry: false,
  });

  const importMutation = useMutation<{ projectId: string }, unknown, string>({
    mutationFn: (path: string) =>
      api.post<{ projectId: string }>('/project/analyze', { path, idempotencyKey: uuid() }),
    onSuccess: (data) => {
      message.success('导入成功');
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/project/${data.projectId}`);
    },
    onError: (e: unknown, path?: string) => {
      if (e instanceof KernelNotReadyError) {
        Modal.error({ title: '内核未就绪', content: '请稍候片刻后重试' });
      } else if (e instanceof ApiError) {
        const presentation = describeError(e.code);
        Modal.error({
          title: `${presentation.title}（${e.code}）`,
          content: (
            <div>
              <div>{e.message}</div>
              {path && <div className={styles.errorMeta}>所选目录：{path}</div>}
            </div>
          ),
        });
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.delete(`/project/${id}`),
    onSuccess: () => {
      message.success('已删除');
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  const openImport = async (): Promise<void> => {
    const dir = await window.kernel?.selectDirectory();
    if (!dir) {
      return;
    }
    importMutation.mutate(dir);
  };

  const onDelete = (id: string): void => {
    Modal.confirm({
      title: '删除项目',
      content: '仅删除记录，不触碰磁盘项目文件。重新导入可恢复。',
      okButtonProps: { danger: true },
      onOk: () => deleteMutation.mutateAsync(id),
    });
  };

  if (projectsQuery.isError) {
    const e = projectsQuery.error;
    return (
      <ErrorState
        code={e instanceof ApiError ? e.code : 500000}
        message={e.message}
        onRetry={() => void projectsQuery.refetch()}
      />
    );
  }

  const projects = projectsQuery.data?.items ?? [];

  return (
    <div>
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <span className={styles.pageHeaderMeta}>
            {projectsQuery.isLoading ? '正在读取项目…' : `${projects.length} 个项目`}
          </span>
        </div>
        <div className={styles.pageHeaderActions}>
          <button
            type="button"
            className={styles.btn}
            onClick={() => void openImport()}
            disabled={importMutation.isPending}
          >
            <Plus size={15} aria-hidden="true" />
            导入项目
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        {projectsQuery.isLoading ? (
          <PageLoading />
        ) : !projects.length ? (
          <EmptyState title="还没有项目" hint="点击右上角「导入项目」导入第一个项目" />
        ) : (
          <div className={styles.cardGrid}>
            {projects.map((p) => (
              <ProjectCard key={p.projectId} project={p} onDelete={onDelete} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
