import type { ApiEnvelope } from './types';
import { describeError, isAuthFailure } from './errorMap';

/** API 业务/网络错误，携带错误码与可呈现文案。 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message?: string,
  ) {
    super(message ?? describeError(code).title);
    this.name = 'ApiError';
  }
}

/** 内核未就绪（Java 尚未输出 READY）。 */
export class KernelNotReadyError extends Error {
  constructor() {
    super('内核尚未就绪，请稍候');
    this.name = 'KernelNotReadyError';
  }
}

const TOKEN_HEADER = 'X-TerraScout-Token';
const OK_CODE = 200000;

/**
 * 纯函数：解析内核统一响应。可供 vitest 直接单测。
 * - HTTP 非 2xx：优先透传信封业务码（校验 code÷1000 == HTTP 状态），
 *   信封缺失或码状态不符时回退 status×1000（422 → 422000）
 * - HTTP 2xx 但 code ≠ 200000：抛业务错误
 * - 否则返回 data（可为 null）
 */
export function decodeEnvelope<T>(status: number, json: ApiEnvelope<T> | null, rawText = ''): T | null {
  if (status < 200 || status >= 300) {
    const envelopeCode = typeof json?.code === 'number' ? json.code : undefined;
    const httpCode =
      envelopeCode !== undefined && Math.floor(envelopeCode / 1000) === status
        ? envelopeCode
        : status * 1000;
    let message = json?.message || `HTTP ${status}: ${rawText.slice(0, 200)}`;
    const hint: unknown = json?.details?.hint;
    if (typeof hint === 'string' && hint) {
      message = `${message}\n${hint}`;
    }
    throw new ApiError(httpCode, message);
  }
  if (!json) {
    throw new ApiError(status * 1000, '响应为空');
  }
  if (json.code !== OK_CODE) {
    throw new ApiError(json.code, json.message || describeError(json.code).title);
  }
  return json.data === undefined ? null : json.data;
}

/** 由错误码判断是否应触发"强制重启提示"（401001）。 */
export function shouldForceRestart(error: unknown): boolean {
  return error instanceof ApiError && isAuthFailure(error.code);
}

/**
 * 内核 API 客户端：baseUrl/token 异步从 window.kernel 获取。
 * 渲染进程仅此通道与后端交互。
 */
export class KernelApiClient {
  private baseUrl: string | null = null;
  private token: string | null = null;
  private readonly readyListeners: Array<() => void> = [];

  /** 初始化：从 preload 读取 baseUrl 与 token，并订阅内核就绪事件。 */
  async init(): Promise<void> {
    if (!window.kernel) {
      throw new Error('window.kernel 不可用：preload 未注入');
    }
    const info = await window.kernel.getInfo();
    this.token = await window.kernel.getToken();
    this.baseUrl = info.apiBaseUrl;
    if (this.baseUrl) {
      this.emitReady();
    }
    window.kernel.onReady(({ port }) => {
      this.baseUrl = `http://127.0.0.1:${port}/api/v1`;
      this.emitReady();
    });
  }

  private emitReady(): void {
    if (this.baseUrl && this.token) {
      this.readyListeners.forEach((cb) => cb());
    }
  }

  /** 内核就绪后回调（用于桥接等待加载态）。 */
  onKernelReady(cb: () => void): () => void {
    if (this.baseUrl && this.token) {
      cb();
    }
    this.readyListeners.push(cb);
    return () => {
      const i = this.readyListeners.indexOf(cb);
      if (i >= 0) {
        this.readyListeners.splice(i, 1);
      }
    };
  }

  isReady(): boolean {
    return Boolean(this.baseUrl && this.token);
  }

  async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    if (!this.baseUrl || !this.token) {
      throw new KernelNotReadyError();
    }
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        [TOKEN_HEADER]: this.token,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const rawText = await res.text();
    let json: ApiEnvelope<T> | null = null;
    try {
      json = rawText ? (JSON.parse(rawText) as ApiEnvelope<T>) : null;
    } catch {
      json = null;
    }
    return decodeEnvelope<T>(res.status, json, rawText) as T;
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }
}

/** 渲染层全局单例。 */
export const api = new KernelApiClient();

/** 常用 401001 提示重启的引导按钮动作（页面层直接调用 window.kernel.restart）。 */
export function restartApp(): void {
  void window.kernel?.restart();
}