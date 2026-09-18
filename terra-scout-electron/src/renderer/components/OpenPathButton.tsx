import React from 'react';
import { FolderOpen } from 'lucide-react';
import { message } from 'antd';

/** 快捷打开按钮（R51）：经内核 ipc 用系统资源管理器打开目标目录/文件。 */
export interface OpenPathButtonProps {
  /** 目标绝对路径；为空时不渲染（等价于 SDK 行内原有条件渲染）。 */
  path?: string;
  /** 按钮文案，默认「打开」。 */
  label?: string;
  /** 图标尺寸 px，默认 14（与 SDK 行/设置页动作按钮一致）。 */
  iconSize?: number;
  /** 复用调用方按钮样式类（如页面 module 的 btn，保持全站灰阶风格统一）。 */
  className?: string;
}

/**
 * 封装自 SDK 已安装行的「打开」交互（R45 引入的 sdk:open-path），
 * 供关于页存储区等场景复用同一视觉与失败提示，避免重复实现。
 */
export function OpenPathButton({
  path,
  label = '打开',
  iconSize = 14,
  className,
}: OpenPathButtonProps): React.JSX.Element | null {
  if (!path) {
    return null;
  }
  const onOpen = async (): Promise<void> => {
    const res = await window.kernel.openPath(path);
    if (!res.ok) {
      message.error(res.error ?? '打开失败');
    }
  };
  return (
    <button
      type="button"
      className={className}
      onClick={() => void onOpen()}
      aria-label={`打开 ${path}`}
    >
      <FolderOpen size={iconSize} aria-hidden="true" /> {label}
    </button>
  );
}

export default OpenPathButton;