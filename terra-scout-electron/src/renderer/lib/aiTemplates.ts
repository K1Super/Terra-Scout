/**
 * AI 供应商标准配置模板。
 *
 * 每套模板由默认接入地址与推荐模型构成，接入地址与模型均可在设置页覆盖为
 * 自定义值；模板仅用于初始填充与空值回落。新增供应商只需在此扩展常量表。
 */

export interface AiProviderTemplate {
  value: string;
  label: string;
  baseUrl: string;
  model: string;
  hint: string;
}

export const AI_PROVIDER_TEMPLATES: readonly AiProviderTemplate[] = [
  {
    value: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    hint: 'DeepSeek 官方 API，兼容 OpenAI 格式',
  },
  {
    value: 'glm',
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    model: 'glm-4-flash',
    hint: '智谱开放平台，兼容 OpenAI 格式',
  },
  {
    value: 'openai-compatible',
    label: 'OpenAI 兼容',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini',
    hint: '任意 OpenAI 兼容网关（也适用于本地网关）',
  },
];

export const AI_PROVIDERS = AI_PROVIDER_TEMPLATES.map((t) => t.value);

export function aiTemplateOf(provider: string): AiProviderTemplate | undefined {
  return AI_PROVIDER_TEMPLATES.find((t) => t.value === provider);
}

/**
 * 供应商切换时的模板填充规则：仅当当前接入地址为空、或仍等于旧供应商模板的
 * 默认地址时才覆盖为新模板，避免覆盖用户已自定义的地址。
 */
export function applyProviderTemplate(
  prevProvider: string,
  prev: { baseUrl?: string; model?: string },
  nextProvider: string,
): { baseUrl: string; model: string } {
  const next = aiTemplateOf(nextProvider);
  if (!next) {
    return { baseUrl: prev.baseUrl ?? '', model: prev.model ?? '' };
  }
  const prevTemplate = aiTemplateOf(prevProvider);
  const adoptTemplateUrl = !prev.baseUrl || prev.baseUrl.trim() === prevTemplate?.baseUrl;
  return {
    baseUrl: adoptTemplateUrl ? next.baseUrl : (prev.baseUrl ?? '').trim(),
    model: prev.model && prev.model.trim() ? prev.model.trim() : next.model,
  };
}