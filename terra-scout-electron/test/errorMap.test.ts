import { describe, it, expect } from 'vitest';
import { describeError, isBusinessCode, isAuthFailure } from '../src/renderer/api/errorMap';

describe('errorMap (ui-pages 5.8)', () => {
  it('已收录错误码返回对应文案与颜色', () => {
    const p = describeError(422003);
    expect(p.title).toBe('父 POM 未找到');
    expect(p.tone).toBe('red');
    expect(p.action).toBe('detail');
  });

  it('401001 判定为认证失败需重启', () => {
    expect(isAuthFailure(401001)).toBe(true);
    expect(isAuthFailure(401000)).toBe(true);
    expect(isAuthFailure(400001)).toBe(false);
  });

  it('未收录错误码回退通用文案，但仍保留 code', () => {
    const p = describeError(601999);
    expect(p.code).toBe(601999);
    expect(p.title).toBe('操作失败，请重试');
    expect(p.action).toBe('retry');
  });

  it('业务码（≥100000）与 HTTP 码区分', () => {
    expect(isBusinessCode(200000)).toBe(true);
    expect(isBusinessCode(401000)).toBe(true);
    expect(isBusinessCode(401)).toBe(false);
  });
});