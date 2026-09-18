import { defineConfig } from 'vitest/config';

// electron 独立质量门禁：npm ci && npm run build && npm test
// 进程/工具层单元测试（java-process / kernel-info / errorMap / client 解码等）
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    globals: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
    },
  },
});