/** 生成 UUID v4（幂等键 idempotencyKey）。 */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  // 降级：Math.random 组合（非安全场景仅用于展示键）
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/** 毫秒时间戳 → 本地可读时间。 */
export function formatTime(ts?: number): string {
  if (!ts) {
    return '-';
  }
  return new Date(ts).toLocaleString();
}

/** 字节 → 人类可读大小。 */
export function formatBytes(bytes?: number): string {
  if (bytes === undefined || bytes === null) {
    return '-';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}