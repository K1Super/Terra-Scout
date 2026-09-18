import { lazy, Suspense, useEffect } from 'react';
import { HashRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Menu } from 'antd';
import {
  LayoutDashboard,
  ServerCog,
  Layers,
  Settings as SettingsIcon,
  Info,
} from 'lucide-react';
import { useKernelStore } from './store/kernel';
import { api } from './api/client';
import KernelStatus from './components/KernelStatus';
import styles from './app.module.css';

// 路由级懒加载（spec §9.6.3）
const HomePage = lazy(() => import('./pages/Home'));
const ProjectDetailPage = lazy(() => import('./pages/ProjectDetail'));
const TasksPage = lazy(() => import('./pages/Tasks'));
const TaskDetailPage = lazy(() => import('./pages/TaskDetail'));
const SdksPage = lazy(() => import('./pages/Sdks'));
const SettingsPage = lazy(() => import('./pages/Settings'));
const AboutPage = lazy(() => import('./pages/About'));

const NAV = [
  { key: '/', label: '首页', icon: <LayoutDashboard size={16} /> },
  { key: '/tasks', label: '任务中心', icon: <ServerCog size={16} /> },
  { key: '/sdks', label: 'SDK 管理', icon: <Layers size={16} /> },
  { key: '/settings', label: '设置', icon: <SettingsIcon size={16} /> },
  { key: '/about', label: '关于', icon: <Info size={16} /> },
];

/**
 * 布局壳：左侧 220px 导航 + 右侧灰画布内容区。
 *
 * 为什么没有顶部横幅：内核状态条曾占据顶部大片空间、在启动期造成
 * 正文空白等待。现改为右下角浮动胶囊（KernelStatus），
 * 主界面启动期间立刻渲染，布局完全不感知内核状态。
 */
export default function App(): React.JSX.Element {
  const setReady = useKernelStore((s) => s.setReady);
  const setError = useKernelStore((s) => s.setError);
  const clearEvent = useKernelStore((s) => s.clearEvent);
  const navigate = useNavigate();
  const { pathname } = useLocation();

  useEffect(() => {
    // 订阅内核生命周期事件并写入 zustand（仅客户端进程状态），
    // KernelStatus 胶囊只读该 store，两者分工不交叉
    const offReady = api.onKernelReady(() => {
      setReady();
      clearEvent();
    });
    const offEvent = window.kernel?.onEvent((e) => {
      if (e.type === 'crashed') {
        setError('crashed');
      } else if (e.type === 'giveup') {
        setError('giveup');
      } else if (e.type === 'startup-failed') {
        setError('startup-failed', e.error);
      }
    });
    return () => {
      offReady();
      offEvent?.();
    };
  }, [setReady, setError, clearEvent]);

  return (
    <div className={styles.shell}>
      <aside className={styles.sider}>
        {/* 应用标识区：主标 13px/600 + 副标 10px 浅灰小字，编辑部刊头式克制 */}
        <div className={styles.brand}>
          <div className={styles.brandName}>Terra Scout</div>
          <div className={styles.brandSub}>环境装配工作台</div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[pathname]}
          className={styles.menu}
          items={NAV.map((n) => ({
            key: n.key,
            icon: n.icon,
            label: n.label,
          }))}
          onClick={({ key }) => navigate(key)}
        />
        <footer className={styles.siderFoot}>
          <span>Terra Scout 0.1.0</span>
          <button
            type="button"
            className={styles.diagLink}
            onClick={() => navigate('/about')}
          >
            诊断
          </button>
        </footer>
      </aside>

      <main className={styles.main}>
        <div className={styles.content}>
          <Suspense fallback={<div className={styles.loading}>加载中…</div>}>
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/project/:id" element={<ProjectDetailPage />} />
              <Route path="/tasks" element={<TasksPage />} />
              <Route path="/task/:id" element={<TaskDetailPage />} />
              <Route path="/sdks" element={<SdksPage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/about" element={<AboutPage />} />
            </Routes>
          </Suspense>
        </div>
      </main>

      {/* 内核状态右下角浮动胶囊：脱离文档流，不占任何布局空间 */}
      <KernelStatus />
    </div>
  );
}

// 供 index.tsx 挂载（HashRouter 外层包装）
export function Root(): React.JSX.Element {
  return (
    <HashRouter>
      <App />
    </HashRouter>
  );
}
