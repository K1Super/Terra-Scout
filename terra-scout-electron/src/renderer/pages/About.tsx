import { useQuery, useMutation } from '@tanstack/react-query';
import { FileArchive } from 'lucide-react';
import { Modal, message } from 'antd';
import { api, ApiError } from '../api/client';
import type { SystemInfo } from '../api/types';
import { formatBytes } from '../lib/id';
import { PageLoading } from '../components/Common';
import OpenPathButton from '../components/OpenPathButton';
import styles from './pages.module.css';

/**
 * 关于页：版本 + 系统信息 + 诊断包导出。
 * 页头放语境信息（运行环境摘要）与诊断动作；信息区用
 * label(11px 大写)/value 清单式排版，与详情页同一卡片语言。
 */
export default function AboutPage(): React.JSX.Element {
  const infoQuery = useQuery({
    queryKey: ['systemInfo'],
    queryFn: () => api.get<SystemInfo>('/system/info'),
    enabled: api.isReady(),
    retry: false,
  });

  const diagMutation = useMutation({
    mutationFn: () => api.get<{ zipPath: string }>('/system/diagnostic'),
    onSuccess: (d) => message.success(`诊断包已导出：${d.zipPath}`),
    onError: (e: unknown) => {
      if (e instanceof ApiError) {
        Modal.error({ title: `${e.code} 导出失败`, content: e.message });
      }
    },
  });

  if (infoQuery.isLoading) {
    return <PageLoading />;
  }
  const info = infoQuery.data;

  return (
    <div>
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <span className={styles.pageHeaderMeta}>
            {info ? `内核 ${info.javaVersion} · 数据 ${formatBytes(info.dbSizeBytes)}` : '运行环境信息'}
          </span>
        </div>
        <div className={styles.pageHeaderActions}>
          <button
            type="button"
            className={styles.btn}
            onClick={() => diagMutation.mutate()}
            disabled={diagMutation.isPending}
          >
            <FileArchive size={14} aria-hidden="true" /> 导出诊断包
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        <div className={styles.card}>
          <p className={styles.sectionLabel}>版本</p>
          <div className={styles.kvGrid}>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>Terra Scout</span>
              <span className={styles.kvValue}>{info?.version ?? '-'}</span>
            </div>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>Java</span>
              <span className={styles.kvValue}>{info?.javaVersion ?? '-'}</span>
            </div>
          </div>

          <p className={styles.sectionLabel}>存储</p>
          <div className={styles.kvGrid}>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>数据目录</span>
              <div className={styles.kvValueLine}>
                <span className={`${styles.kvValue} ${styles.mono}`}>{info?.dataDir ?? '-'}</span>
                <OpenPathButton path={info?.dataDir} className={styles.btn} />
              </div>
            </div>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>SDK 仓库</span>
              <div className={styles.kvValueLine}>
                <span className={`${styles.kvValue} ${styles.mono}`}>{info?.sdkRepoDir ?? '-'}</span>
                <OpenPathButton path={info?.sdkRepoDir} className={styles.btn} />
              </div>
            </div>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>数据库大小</span>
              <span className={`${styles.kvValue} nums`}>{formatBytes(info?.dbSizeBytes)}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
