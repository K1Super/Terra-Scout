import { useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Check } from 'lucide-react';
import { Modal, Select } from 'antd';
import { api } from '../api/client';
import type { ExecuteRequest, ProjectDetail, VersionOverride } from '../api/types';
import { uuid } from '../lib/id';
import { PageLoading } from '../components/Common';
import styles from './pages.module.css';

/**
 * 项目详情：项目画像 + 装配计划 + 确认装配。
 * 页头只放语境信息（项目名 + 类型 + 返回），右侧挂唯一主操作；
 * 信息区用「label(11px 大写) / value(深灰)」的清单式排版替代传统表格。
 * R48：SDK 安装行附版本下拉（候选表），所选版本随 execute 携带 versionOverrides。
 */
export default function ProjectDetailPage(): React.JSX.Element {
  const { id } = useParams();
  const navigate = useNavigate();

  /** 各语言已选版本（R48）：缺省取推荐版本（s.version）。 */
  const [selectedVersions, setSelectedVersions] = useState<Record<string, string>>({});

  const detailQuery = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetail>(`/project/${id}`),
    enabled: api.isReady() && Boolean(id),
    retry: false,
  });

  const assembleMutation = useMutation({
    mutationFn: (request: ExecuteRequest) =>
      api.post<{ taskId: string }>('/task/execute', request),
    onSuccess: (d) => navigate(`/task/${d.taskId}`),
  });

  const confirmAssemble = (): void => {
    const plan = detailQuery.data?.plan;
    if (!plan) {
      return;
    }
    const overrides: VersionOverride[] = (plan.sdkInstalls ?? []).map((s) => ({
      language: s.language,
      version: selectedVersions[s.language] ?? s.version,
    }));
    Modal.confirm({
      title: '确认装配',
      content: (
        <div>
          <p>将按以下版本装配 SDK：</p>
          <ul>
            {(plan.sdkInstalls ?? []).map((s) => (
              <li key={s.language}>
                {s.language} {selectedVersions[s.language] ?? s.version}
              </li>
            ))}
          </ul>
          <p className={styles.cardMeta}>依赖将安装到 .devenv/</p>
        </div>
      ),
      okText: '确认装配',
      cancelText: '取消',
      onOk: () =>
        assembleMutation.mutateAsync({
          planId: plan.planId,
          confirm: true,
          idempotencyKey: uuid(),
          versionOverrides: overrides,
        }),
    });
  };

  if (detailQuery.isLoading) {
    return <PageLoading />;
  }
  const detail = detailQuery.data;

  if (!detail || detailQuery.isError) {
    return <div className={styles.loading}>未找到项目或加载失败</div>;
  }

  return (
    <div>
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <button type="button" className={styles.backLink} onClick={() => navigate('/')}>
            <ArrowLeft size={14} aria-hidden="true" /> 返回项目列表
          </button>
          <span className={styles.pageHeaderTitle}>{detail.name}</span>
          <span className={styles.pageHeaderMeta}>
            {detail.type}
            {(detail.constraints ?? []).length > 0 &&
              ` · ${detail.constraints.length} 条语言约束`}
          </span>
        </div>
        <div className={styles.pageHeaderActions}>
          <button
            type="button"
            className={styles.btn}
            onClick={confirmAssemble}
            disabled={!detail.plan || assembleMutation.isPending}
          >
            <Check size={15} aria-hidden="true" />
            确认装配
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        <div className={styles.cardStack}>
          <div className={styles.card}>
            <div className={styles.cardHeader}>
              <h3 className={styles.cardTitle}>项目画像</h3>
            </div>
            <div className={styles.kvGrid}>
              <div className={styles.kvItem}>
                <span className={styles.kvLabel}>类型</span>
                <span className={styles.kvValue}>{detail.type}</span>
              </div>
              <div className={styles.kvItem}>
                <span className={styles.kvLabel}>路径</span>
                <span className={`${styles.kvValue} ${styles.mono}`}>{detail.projectRootPath}</span>
              </div>
              <div className={styles.kvItem}>
                <span className={styles.kvLabel}>语言约束</span>
                <span className={styles.kvValue}>
                  {(detail.constraints ?? []).length === 0
                    ? '—'
                    : (detail.constraints ?? []).map((c, i) => (
                        <span key={i}>
                          {c.language} {c.constraint}（来源：{c.sourceFile}）
                          {i < (detail.constraints?.length ?? 0) - 1 ? '；' : ''}
                        </span>
                      ))}
                </span>
              </div>
            </div>
          </div>

          <div className={styles.card}>
            <div className={styles.cardHeader}>
              <h3 className={styles.cardTitle}>装配计划</h3>
            </div>
            <div className={styles.kvGrid}>
              <div className={styles.kvItem}>
                <span className={styles.kvLabel}>SDK 安装</span>
                <span className={styles.kvValue}>
                  {(detail.plan?.sdkInstalls ?? []).length === 0
                    ? '—'
                    : (detail.plan?.sdkInstalls ?? []).map((s) => {
                        const candidates = s.candidates ?? [];
                        const chosen = selectedVersions[s.language] ?? s.version;
                        return (
                          <div key={s.language} className={styles.sdkVersionRow}>
                            <span>
                              {s.action === 'REUSE' ? '复用' : '安装'} {s.language}
                            </span>
                            {candidates.length > 0 ? (
                              <Select
                                size="small"
                                className={styles.sdkVersionSelect}
                                value={chosen}
                                options={candidates.map((c) => ({
                                  value: c.version,
                                  label: `${c.version}${c.lts ? ' · LTS' : ''}${
                                    c.installed ? ' · 已装' : ''
                                  }`,
                                }))}
                                onChange={(value: string) =>
                                  setSelectedVersions((prev) => ({
                                    ...prev,
                                    [s.language]: value,
                                  }))
                                }
                              />
                            ) : (
                              <span className={styles.mono}>{s.version}</span>
                            )}
                          </div>
                        );
                      })}
                </span>
              </div>
              <div className={styles.kvItem}>
                <span className={styles.kvLabel}>依赖安装</span>
                <span className={styles.kvValue}>
                  {(detail.plan?.dependencyInstalls ?? []).length === 0
                    ? '—'
                    : (detail.plan?.dependencyInstalls ?? []).map((d, i) => (
                        <span key={i}>
                          {d.ecosystem} → {d.isolation};{' '}
                        </span>
                      ))}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
