import { describe, it, expect } from 'vitest';
import { decodeEnvelope, ApiError, shouldForceRestart } from '../src/renderer/api/client';

describe('ApiEnvelope 解码（D-002）', () => {
  it('200 + 200000 返回 data', () => {
    const data = decodeEnvelope<{ a: number }>(200, { code: 200000, data: { a: 1 } });
    expect(data).toEqual({ a: 1 });
  });

  it('HTTP 非 2xx 转为 HTTP×1000 错误码（401 → 401000 认证失败）', () => {
    expect(() => decodeEnvelope(401, { code: 401000, message: 'unauthorized' })).toThrow(ApiError);
    try {
      decodeEnvelope(401, { code: 401000, message: 'unauthorized' });
    } catch (e) {
      expect((e as ApiError).code).toBe(401000);
    }
    // 401 → 触发强制重启
    expect(shouldForceRestart(new ApiError(401000))).toBe(true);
  });

  it('HTTP 非 2xx 透传合法信封业务码（422001 不再折叠为 422000，裁决 R46）', () => {
    try {
      decodeEnvelope(422, {
        code: 422001,
        message: '项目类型无法识别',
        details: { hint: '请选择包含 pom.xml 的目录' },
      });
    } catch (e) {
      const err = e as ApiError;
      expect(err.code).toBe(422001);
      expect(err.message).toContain('pom.xml');
      expect(shouldForceRestart(err)).toBe(false);
    }
  });

  it('HTTP 401 信封 code 401001 → 透传 401001 并触发强制重启', () => {
    try {
      decodeEnvelope(401, { code: 401001, message: 'unauthorized' });
    } catch (e) {
      expect((e as ApiError).code).toBe(401001);
      expect(shouldForceRestart(e)).toBe(true);
    }
  });

  it('HTTP 非 2xx 信封缺失或码与状态不符 → 回退 HTTP×1000', () => {
    try {
      decodeEnvelope(422, null, 'oops');
    } catch (e) {
      expect((e as ApiError).code).toBe(422000);
    }
    try {
      decodeEnvelope(422, { code: 999999, message: 'x' });
    } catch (e) {
      expect((e as ApiError).code).toBe(422000);
    }
  });

  it('200 + 非 200000 抛业务错误', () => {
    try {
      decodeEnvelope(200, { code: 422003, message: '父 POM 未找到' });
    } catch (e) {
      expect((e as ApiError).code).toBe(422003);
    }
  });

  it('响应为空但 HTTP 200 抛错', () => {
    expect(() => decodeEnvelope(200, null, '')).toThrow(ApiError);
  });
});