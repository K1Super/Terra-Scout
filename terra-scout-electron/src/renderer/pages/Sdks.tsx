import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Download, FolderOpen, RefreshCw, Trash2, X } from 'lucide-react';
import { Modal, message } from 'antd';
import { api, ApiError } from '../api/client';
import type {
  SdkVersion,
  Language,
  SdkInstallResult,
  SdkInstallProgress,
} from '../api/types';
import { uuid } from '../lib/id';
import { PageLoading } from '../components/Common';
import OpenPathButton from '../components/OpenPathButton';
import { Segmented } from '../components/Segmented';
import EmptyState from '../components/EmptyState';
import styles from './pages.module.css';

const LANGUAGES: Language[] = ['JAVA', 'NODE', 'PYTHON', 'GO'];

/** 进行中的安装任务（同一时间仅一个）；切换语言时保留，切回后继续展示进度。 */
interface ActiveInstallJob {
  jobId: string;
  language: Language;
  version: string;
}

const INSTALL_POLL_INTERVAL_MS = 600;

/** 安装请求载荷：版本 + 可选自定义落位根目录（R45）。 */
interface InstallPayload {
  v: SdkVersion;
  installDir?: string;
}

/**
 * SDK 管理：按语言分 Tab，已安装 + 可用版本。
 * 页头：左侧 Segmented 语言切换（组件视觉保持原样）、右侧「刷新元数据」次级按钮。
 * 列表用白卡 + 发丝分隔行，徽标全部灰阶（LTS 中灰实底 / 停止维护暖色点缀）。
 */
export default function SdksPage(): React.JSX.Element {
  const [lang, setLang] = useState<Language>('JAVA');
  const [installJob, setInstallJob] = useState<ActiveInstallJob | null>(null);
  /** 安装确认弹层目标 + 自选落位根目录（null=标准仓库）。 */
  const [installTarget, setInstallTarget] = useState<SdkVersion | null>(null);
  const [customDir, setCustomDir] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const listQuery = useQuery({
    queryKey: ['sdk', lang],
    queryFn: () =>
      api.get<{ items: SdkVersion[] }>(`/sdk/list?language=${lang}&os=WINDOWS&arch=AMD64`),
    enabled: api.isReady(),
    retry: false,
    // 切换语言时保留上一语言数据（避免整页 loading 导致 Segmented 卸载重挂、胶囊频闪）
    placeholderData: (previous) => previous,
  });

  const reloadMutation = useMutation({
    mutationFn: () => api.post<unknown>('/sdk/metadata/reload'),
    onSuccess: () => {
      message.success('SDK 元数据已刷新');
      void queryClient.invalidateQueries({ queryKey: ['sdk', lang] });
    },
    onError: (e: unknown) => {
      if (e instanceof ApiError) {
        Modal.error({ title: `${e.code}`, content: e.message });
      }
    },
  });

  const installMutation = useMutation({
    mutationFn: ({ v, installDir }: InstallPayload) =>
      api.post<SdkInstallResult>('/sdk/install', {
        language: v.language,
        version: v.version,
        scope: 'GLOBAL',
        idempotencyKey: uuid(),
        installDir,
      }),
    onSuccess: (data: SdkInstallResult, { v }: InstallPayload) => {
      if (data.jobId) {
        // 异步安装：进入进度轮询，行内展示实时进度
        setInstallJob({ jobId: data.jobId, language: v.language, version: v.version });
      } else {
        // 已装复用（同步 SUCCESS）：直接刷新列表
        message.success(`${v.language} ${v.version} 已安装`);
        void queryClient.invalidateQueries({ queryKey: ['sdk', lang] });
      }
    },
    onError: (e: unknown) => {
      if (e instanceof ApiError) {
        Modal.error({ title: `${e.code}`, content: e.message });
      }
    },
  });

  const cancelMutation = useMutation({
    mutationFn: (jobId: string) =>
      api.post<unknown>(`/sdk/install/${encodeURIComponent(jobId)}/cancel`),
    onSuccess: () => {
      message.warning('正在停止安装并清理临时文件…');
    },
    onError: (e: unknown) => {
      if (e instanceof ApiError) {
        Modal.error({ title: `${e.code}`, content: e.message });
      }
    },
  });

  const installProgress = useQuery({
    queryKey: ['sdk-install-progress', installJob?.jobId],
    queryFn: () =>
      api.get<SdkInstallProgress>(`/sdk/install/${encodeURIComponent(installJob?.jobId ?? '')}`),
    // 仅在任务所属语言页轮询；切换语言后暂停，切回继续
    enabled: Boolean(installJob) && installJob?.language === lang && api.isReady(),
    refetchInterval: (query) =>
      query.state.data?.finished ? false : INSTALL_POLL_INTERVAL_MS,
    retry: false,
  });

  // 安装终态收尾：成功刷新列表 / 失败弹错误，然后清除任务
  useEffect(() => {
    const progress = installProgress.data;
    if (!installJob || !progress || !progress.finished) {
      return;
    }
    if (progress.success) {
      message.success(`已安装 ${progress.language} ${progress.version}`);
    } else if (progress.cancelled) {
      message.warning(`已取消安装 ${progress.language} ${progress.version}，临时文件已清理`);
    } else {
      Modal.error({
        title: `安装失败 ${progress.language} ${progress.version}`,
        content: progress.error ?? '未知错误',
      });
    }
    void queryClient.invalidateQueries({ queryKey: ['sdk', lang] });
    setInstallJob(null);
  }, [installProgress.data, installJob, lang, queryClient]);

  const uninstallMutation = useMutation({
    mutationFn: (v: SdkVersion) =>
      api.post<unknown>('/sdk/uninstall', { recordId: v.recordId, idempotencyKey: uuid() }),
    onSuccess: () => {
      message.success('已卸载');
      void queryClient.invalidateQueries({ queryKey: ['sdk', lang] });
    },
  });

  const onInstall = (v: SdkVersion): void => {
    setCustomDir(null);
    setInstallTarget(v);
  };

  const closeInstallModal = (): void => {
    setInstallTarget(null);
    setCustomDir(null);
  };

  /** 系统目录选择（R45）：所选目录作为仓库根，自动创建 {语言}\{版本} 两级结构。 */
  const pickCustomDir = async (): Promise<void> => {
    const dir = await window.kernel.selectDirectory();
    if (dir) {
      setCustomDir(dir);
    }
  };

  const confirmInstall = (): void => {
    if (!installTarget) {
      return;
    }
    installMutation.mutate({ v: installTarget, installDir: customDir ?? undefined });
    closeInstallModal();
  };

  /** 安装落位目标实时预览：自定义=所选根\{语言小写}\{版本}；默认=标准仓库路径。 */
  const installTargetPath = installTarget
    ? customDir
      ? `${customDir}\\${installTarget.language.toLowerCase()}\\${installTarget.version}`
      : (installTarget.installPath ?? '—')
    : '—';

  const onDelete = (v: SdkVersion): void => {
    Modal.confirm({
      title: `卸载 ${v.language} ${v.version}?`,
      content: (
        <div>
          <div>卸载将物理删除目录并回收磁盘空间：</div>
          <div>{v.installedPath ?? v.installPath ?? '—'}</div>
        </div>
      ),
      okButtonProps: { danger: true },
      onOk: () => uninstallMutation.mutateAsync(v),
    });
  };

  const items = listQuery.data?.items ?? [];
  const installed = items.filter((i) => i.installed);
  const available = items.filter((i) => !i.installed);

  return (
    <div>
      {/* 工具栏常驻：Segmented 永不因数据加载卸载，胶囊动画不被打断 */}
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderActions}>
          <Segmented<Language>
            ariaLabel="语言"
            options={LANGUAGES.map((l) => ({ value: l, label: l }))}
            value={lang}
            onChange={setLang}
          />
        </div>
        <div className={styles.pageHeaderActions}>
          <button
            type="button"
            className={styles.btn}
            onClick={() => reloadMutation.mutate()}
            disabled={reloadMutation.isPending}
          >
            <RefreshCw size={14} aria-hidden="true" /> 刷新元数据
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        {listQuery.isLoading ? (
          <PageLoading />
        ) : (
          <div className={listQuery.isFetching ? styles.updating : undefined}>
            <p className={styles.sectionLabel}>
              已安装 · {installed.length}
            </p>
            {!installed.length ? (
              <EmptyState title="还没有安装该语言的 SDK" />
            ) : (
              <div className={styles.card}>
                {installed.map((v) => (
                  <div key={v.recordId ?? v.version} className={styles.sdkRow}>
                    <div>
                      <div className={styles.sdkName}>
                        {v.language} {v.version}
                        {v.systemInstalled && <span className={styles.tagSys}>系统已装</span>}
                      </div>
                      <div className={styles.sdkMeta}>{v.installedPath ?? '—'}</div>
                    </div>
                    <div className={styles.sdkRowActions}>
                      <OpenPathButton path={v.installedPath ?? v.installPath} className={styles.btn} />
                      {v.recordId && (
                        <button
                          type="button"
                          className={styles.btnDanger}
                          onClick={() => onDelete(v)}
                          aria-label={`卸载 ${v.language} ${v.version}`}
                        >
                          <Trash2 size={14} aria-hidden="true" /> 卸载
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            <p className={styles.sectionLabel}>
              可用版本 · {available.length}
            </p>
            {!available.length ? (
              <EmptyState title="暂无可用版本" hint="点击右上角刷新元数据" />
            ) : (
              <div className={styles.card}>
                {available.map((v) => {
                  const isInstalling =
                    installJob?.language === v.language && installJob.version === v.version;
                  const progress = isInstalling ? installProgress.data : undefined;
                  return (
                    <div key={v.version} className={styles.sdkRow}>
                      <div>
                        <div className={styles.sdkName}>
                          {v.language} {v.version}
                          {v.lts && <span className={styles.tagLts}>LTS</span>}
                          {v.eol && <span className={styles.tagEol}>停止维护</span>}
                        </div>
                        <div className={styles.sdkMeta}>{v.installPath ?? '—'}</div>
                      </div>
                      {isInstalling ? (
                        <div className={styles.installProgressWrap}>
                          <div className={styles.installProgress} role="progressbar"
                               aria-valuemin={0} aria-valuemax={100}
                               aria-valuenow={progress?.percent ?? 0}
                               aria-label={`正在安装 ${v.language} ${v.version}`}>
                            <div className={styles.installProgressText}>
                              <span>{progress?.message ?? '正在安装…'}</span>
                              <span>{progress?.percent ?? 0}%</span>
                            </div>
                            <div className={styles.installPathNote}
                                 title={progress?.installPath ?? v.installPath}>
                              {progress?.installPath ?? v.installPath ?? '—'}
                            </div>
                            <div className={styles.installTrack}>
                              <div className={styles.installBar}
                                   style={{ width: `${progress?.percent ?? 0}%` }} />
                            </div>
                          </div>
                          <button
                            type="button"
                            className={styles.btnDanger}
                            onClick={() => installJob && cancelMutation.mutate(installJob.jobId)}
                            disabled={Boolean(progress?.finished) || cancelMutation.isPending}
                            aria-label={`取消安装 ${v.language} ${v.version}`}
                          >
                            <X size={14} aria-hidden="true" /> 取消
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          className={styles.btn}
                          onClick={() => onInstall(v)}
                          disabled={installMutation.isPending || Boolean(installJob)}
                        >
                          <Download size={14} aria-hidden="true" /> 安装
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>

      {/* R45：安装确认弹层 — 落位目录自选（默认标准仓库），目标路径实时预览 */}
      <Modal
        open={Boolean(installTarget)}
        title={installTarget ? `安装 ${installTarget.language} ${installTarget.version}?` : '安装'}
        okText="安装"
        cancelText="取消"
        confirmLoading={installMutation.isPending}
        onOk={confirmInstall}
        onCancel={closeInstallModal}
      >
        <div className={styles.installDirPicker}>
          <div className={styles.installDirLabel}>安装位置</div>
          <div className={styles.installDirValue} title={installTargetPath}>
            {installTargetPath}
          </div>
          <div className={styles.installDirNote}>
            {customDir
              ? '自定义目录：将在所选目录下自动创建 语言/版本 两级文件夹，避免混放。'
              : '默认标准仓库。安装中可随时取消，临时文件即时清理。'}
          </div>
          <div className={styles.installDirActions}>
            <button type="button" className={styles.btn} onClick={() => void pickCustomDir()}>
              <FolderOpen size={14} aria-hidden="true" /> 选择目录
            </button>
            {customDir && (
              <button type="button" className={styles.linkBtn} onClick={() => setCustomDir(null)}>
                恢复默认位置
              </button>
            )}
          </div>
        </div>
      </Modal>
    </div>
  );
}
