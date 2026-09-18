import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// 渲染层构建：产物进入 dist/renderer（asar 内），base './' 保证 file:// 协议可加载
export default defineConfig({
  root: resolve(__dirname, 'src/renderer'),
  plugins: [react()],
  base: './',
  build: {
    outDir: resolve(__dirname, 'dist/renderer'),
    emptyOutDir: true,
  },
});