import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { theme } from './theme';
import { Root } from './App';
import { api } from './api/client';

// TanStack Query 全局客户端（分工：只承载服务端数据缓存，见 store/kernel.ts 契约）
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 5_000,
      refetchOnWindowFocus: false,
    },
  },
});

// 初始化内核 API 客户端（读 token / 端口，订阅内核就绪）。
// 为什么 catch 空实现：初始化失败不阻塞挂载——主界面必须立刻渲染，
// 内核状态由右下角浮动胶囊（KernelStatus）感知，不再用顶部横幅占位。
api.init().catch(() => {
  /* 内核异常交由 KernelStatus 胶囊呈现 */
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('root element missing');
}

// 标准入口结构：ConfigProvider 全局主题（灰阶收口）→ QueryClientProvider → Root
createRoot(container).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={theme}>
      <QueryClientProvider client={queryClient}>
        <Root />
      </QueryClientProvider>
    </ConfigProvider>
  </React.StrictMode>,
);
