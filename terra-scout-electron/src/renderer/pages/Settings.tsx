import { useQuery, useMutation } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Database, FileArchive, Save } from 'lucide-react';
import { Form, Input, InputNumber, Select, Switch, Modal, message } from 'antd';
import { api, ApiError } from '../api/client';
import type { AiTestResult, Settings } from '../api/types';
import { AI_PROVIDER_TEMPLATES, aiTemplateOf, applyProviderTemplate } from '../lib/aiTemplates';
import { PageLoading } from '../components/Common';
import styles from './pages.module.css';

const LOG_LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR'];

/**
 * 设置页：AI 辅助（标准配置模板 + 连通性测试）+ 镜像源 + 日志 + 备份/诊断。
 * 页头放语境统计（就绪的设置分组数）与保存动作；
 * 分组用 11px 大写小标分隔，替代粗分隔线。
 */
export default function SettingsPage(): React.JSX.Element {
  const [form] = Form.useForm<Settings>();
  const [testing, setTesting] = useState(false);
  const provider = Form.useWatch('aiProvider', form) as string | undefined;

  const settingsQuery = useQuery({
    queryKey: ['settings'],
    queryFn: () => api.get<Settings>('/settings'),
    enabled: api.isReady(),
    staleTime: Infinity,
    retry: false,
  });

  useEffect(() => {
    if (settingsQuery.data) {
      form.setFieldsValue(settingsQuery.data);
    }
  }, [settingsQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (values: Settings) => api.put<Settings>('/settings', values),
    onSuccess: () => message.success('设置已保存'),
    onError: (e: unknown) => {
      if (e instanceof ApiError) {
        Modal.error({ title: `${e.code} 保存失败`, content: e.message });
      }
    },
  });

  const backupMutation = useMutation({
    mutationFn: () => api.post<{ backupFile: string }>('/system/backup'),
    onSuccess: (d) => message.success(`已备份：${d.backupFile}`),
  });

  const diagMutation = useMutation({
    mutationFn: () => api.get<{ zipPath: string }>('/system/diagnostic'),
    onSuccess: (d) => message.success(`诊断包已导出：${d.zipPath}`),
  });

  /** 用当前表单值（不落盘）执行一次真实连通性探测。 */
  async function testConnection(): Promise<void> {
    const current = form.getFieldsValue();
    const currentProvider = (current.aiProvider as string | undefined) ?? 'deepseek';
    const template = aiTemplateOf(currentProvider);
    const baseUrl = (current.aiBaseUrl ?? '').trim() || (template?.baseUrl ?? '');
    const apiKey = (current.aiApiKey ?? '').trim();
    const model = (current.aiModel ?? '').trim() || (template?.model ?? '');
    if (!baseUrl) {
      message.warning('请先填写接入地址');
      return;
    }
    if (!apiKey) {
      message.warning('请先填写 API Key');
      return;
    }
    setTesting(true);
    try {
      const result = await api.post<AiTestResult>('/settings/ai/test', {
        aiProvider: currentProvider,
        aiBaseUrl: baseUrl,
        aiApiKey: apiKey,
        aiModel: model,
      });
      if (result.ok) {
        message.success(`连接成功（${result.latencyMs ?? 0} ms）`);
      } else {
        message.error(`连接失败：${result.message}`);
      }
    } catch (e: unknown) {
      if (e instanceof ApiError) {
        message.error(`${e.code} ${e.message}`);
      }
    } finally {
      setTesting(false);
    }
  }

  if (settingsQuery.isLoading) {
    return <PageLoading />;
  }

  const activeHint = aiTemplateOf(provider ?? (form.getFieldValue('aiProvider') ?? 'deepseek'))?.hint;

  return (
    <div>
      <header className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <span className={styles.pageHeaderMeta}>偏好将即时写入本地配置</span>
        </div>
        <div className={styles.pageHeaderActions}>
          <button
            type="submit"
            form="settings-form"
            className={styles.btn}
            disabled={saveMutation.isPending}
          >
            <Save size={15} aria-hidden="true" />
            保存设置
          </button>
        </div>
      </header>

      <div className={styles.pageBody}>
        <Form
          id="settings-form"
          form={form}
          layout="vertical"
          onFinish={(v) => saveMutation.mutate(v)}
          className={styles.cardStack}
        >
          <div className={styles.card}>
            <p className={styles.sectionLabel}>AI 辅助</p>
            <Form.Item name="aiEnabled" label="启用 AI 环境规划与报错诊断" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="aiProvider" label="AI 供应商">
              <Select
                options={AI_PROVIDER_TEMPLATES.map((t) => ({ value: t.value, label: t.label }))}
                onChange={(next: string) => {
                  const prev = form.getFieldsValue();
                  const filled = applyProviderTemplate(
                    (prev.aiProvider as string | undefined) ?? 'deepseek',
                    { baseUrl: prev.aiBaseUrl, model: prev.aiModel },
                    next,
                  );
                  form.setFieldsValue({ aiBaseUrl: filled.baseUrl, aiModel: filled.model });
                }}
              />
            </Form.Item>
            <Form.Item name="aiBaseUrl" label="接入地址">
              <input className={styles.input} placeholder="https://api.deepseek.com" />
            </Form.Item>
            <Form.Item name="aiApiKey" label="API Key">
              <Input.Password placeholder="sk-..." autoComplete="new-password" />
            </Form.Item>
            <Form.Item name="aiModel" label="模型">
              <input className={styles.input} placeholder="deepseek-chat" />
            </Form.Item>
            <div className={styles.aiTestRow}>
              <button
                type="button"
                className={styles.btn}
                onClick={() => void testConnection()}
                disabled={testing}
              >
                {testing ? '测试中…' : '测试连接'}
              </button>
              {activeHint ? <span className={styles.aiHint}>{activeHint}</span> : null}
            </div>
          </div>

          <div className={styles.card}>
            <p className={styles.sectionLabel}>下载</p>
            <Form.Item name={['download', 'mirror']} label="镜像源">
              <input className={styles.input} placeholder="https://repo.huaweicloud.com" />
            </Form.Item>
            <Form.Item name={['download', 'timeoutMs']} label="超时（毫秒）">
              <InputNumber min={1000} step={1000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name={['download', 'maxRetry']} label="重试次数">
              <InputNumber min={0} max={10} style={{ width: '100%' }} />
            </Form.Item>
          </div>

          <div className={styles.card}>
            <p className={styles.sectionLabel}>日志</p>
            <Form.Item name="logLevel" label="级别">
              <Select options={LOG_LEVELS.map((l) => ({ value: l, label: l }))} />
            </Form.Item>
          </div>

          <div className={styles.card}>
            <p className={styles.sectionLabel}>数据</p>
            <div className={styles.kvItem}>
              <span className={styles.kvLabel}>数据目录</span>
              <span className={`${styles.kvValue} ${styles.mono}`}>~/.terrascout（卸载时默认保留）</span>
            </div>
          </div>
        </Form>

        <div className={styles.actionRow}>
          <button
            type="button"
            className={styles.btn}
            onClick={() => backupMutation.mutate()}
            disabled={backupMutation.isPending}
          >
            <Database size={14} aria-hidden="true" /> 备份数据库
          </button>
          <button
            type="button"
            className={styles.btn}
            onClick={() => diagMutation.mutate()}
            disabled={diagMutation.isPending}
          >
            <FileArchive size={14} aria-hidden="true" /> 导出诊断包
          </button>
        </div>
      </div>
    </div>
  );
}