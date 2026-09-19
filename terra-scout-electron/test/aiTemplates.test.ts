import { describe, expect, it } from 'vitest';
import {
  AI_PROVIDER_TEMPLATES,
  AI_PROVIDERS,
  aiTemplateOf,
  applyProviderTemplate,
} from '../src/renderer/lib/aiTemplates';

describe('aiTemplates 供应商模板', () => {
  it('内置 DeepSeek / 智谱 GLM / OpenAI 兼容三套标准模板', () => {
    expect(AI_PROVIDERS).toEqual(['deepseek', 'glm', 'openai-compatible']);
    const values = AI_PROVIDER_TEMPLATES.map((t) => t.value);
    expect(new Set(values).size).toBe(3);
    expect(new Set(AI_PROVIDER_TEMPLATES.map((t) => t.baseUrl)).size).toBe(3);
  });

  it('aiTemplateOf 命中与未命中', () => {
    expect(aiTemplateOf('deepseek')?.baseUrl).toBe('https://api.deepseek.com');
    expect(aiTemplateOf('glm')?.model).toBe('glm-4-flash');
    expect(aiTemplateOf('nope')).toBeUndefined();
  });
});

describe('applyProviderTemplate 模板填充规则', () => {
  it('接入地址为空时采用新供应商模板', () => {
    const next = applyProviderTemplate('deepseek', { baseUrl: '' }, 'glm');
    expect(next.baseUrl).toBe('https://open.bigmodel.cn/api/paas/v4');
  });

  it('仍为旧模板默认地址时覆盖为新模板', () => {
    const next = applyProviderTemplate('deepseek', { baseUrl: 'https://api.deepseek.com' }, 'glm');
    expect(next.baseUrl).toBe('https://open.bigmodel.cn/api/paas/v4');
  });

  it('用户自定义地址不被覆盖', () => {
    const custom = 'http://127.0.0.1:11434/v1';
    const next = applyProviderTemplate('deepseek', { baseUrl: custom, model: 'local-model' }, 'glm');
    expect(next.baseUrl).toBe(custom);
    expect(next.model).toBe('local-model');
  });

  it('模型为空时回落新模板默认模型', () => {
    const next = applyProviderTemplate('deepseek', { baseUrl: '', model: '  ' }, 'openai-compatible');
    expect(next.model).toBe('gpt-4o-mini');
  });

  it('目标供应商未知时原样保留', () => {
    const next = applyProviderTemplate('deepseek', { baseUrl: 'https://api.deepseek.com', model: 'm' }, 'unknown');
    expect(next).toEqual({ baseUrl: 'https://api.deepseek.com', model: 'm' });
  });
});