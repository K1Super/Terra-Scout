/**
 * 错误码 → UI 映射。
 * 未收录的错误码回退到通用"服务异常"。
 */
export type ErrorTone = 'red' | 'yellow';
export type ErrorAction =
  | 're-select'
  | 'restart'
  | 'view-task'
  | 'help'
  | 'detail'
  | 'suggestion'
  | 'retry'
  | 'manual-clean'
  | 'disk-clean'
  | null;

export interface ErrorPresentation {
  code: number;
  title: string;
  tone: ErrorTone;
  action: ErrorAction;
  actionLabel?: string;
}

const ERROR_MAP: Record<number, Omit<ErrorPresentation, 'code'>> = {
  400001: { title: '项目路径为空或格式不正确', tone: 'red', action: 're-select', actionLabel: '重新选择' },
  401001: { title: '认证失败，请重启工具', tone: 'red', action: 'restart', actionLabel: '重启' },
  404001: { title: '项目路径不存在', tone: 'red', action: 're-select', actionLabel: '重新选择' },
  409001: { title: '该项目已有任务在执行', tone: 'yellow', action: 'view-task', actionLabel: '查看任务' },
  422001: { title: '无法识别项目类型', tone: 'red', action: 'help', actionLabel: '查看帮助' },
  422003: { title: '父 POM 未找到', tone: 'red', action: 'detail', actionLabel: '查看详情' },
  422007: { title: '版本已停止维护', tone: 'yellow', action: 'suggestion', actionLabel: '查看建议' },
  422009: { title: '文件校验失败', tone: 'red', action: 'retry', actionLabel: '重试' },
  422010: { title: '解压检测到安全风险', tone: 'red', action: 'detail', actionLabel: '查看详情' },
  422011: { title: '路径过长', tone: 'red', action: 'detail', actionLabel: '查看详情' },
  500003: { title: '任务异常中断', tone: 'red', action: 'retry', actionLabel: '重试' },
  500004: { title: '回滚失败', tone: 'red', action: 'manual-clean', actionLabel: '手动清理' },
  507001: { title: '磁盘空间不足', tone: 'red', action: 'disk-clean', actionLabel: '清理' },
  502001: { title: '下载源不可达', tone: 'red', action: 'retry', actionLabel: '重试' },
};

const FALLBACK: ErrorPresentation = {
  code: 0,
  title: '操作失败，请重试',
  tone: 'red',
  action: 'retry',
  actionLabel: '重试',
};

/** 根据错误码返回 UI 呈现信息（未收录回退通用）。 */
export function describeError(code: number): ErrorPresentation {
  const hit = ERROR_MAP[code];
  if (!hit) {
    return { ...FALLBACK, code };
  }
  return { ...hit, code };
}

/** 业务错误码（≥100000）与 HTTP 状态（<100000）区分：业务码返回 UI 映射，HTTP 归入网络错误。 */
export function isBusinessCode(code: number): boolean {
  return code >= 100000;
}

/** 401001 认证失败：需提示重启工具。 */
export function isAuthFailure(code: number): boolean {
  return code === 401001 || code === 401000;
}