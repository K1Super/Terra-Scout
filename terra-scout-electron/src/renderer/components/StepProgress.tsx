import { Steps } from 'antd';
import type { TaskStep } from '../api/types';

/** 任务步骤进度（ui-pages 5.5）。 */
export default function StepProgress({ steps, current }: { steps: TaskStep[]; current?: number }): React.JSX.Element {
  const items = steps.map((s) => ({
    title: `${s.index}. ${s.name}`,
    status: stepToStatus(s.status),
  }));
  return (
    <Steps
      direction="vertical"
      size="small"
      current={current ?? 0}
      items={items}
      style={{ maxHeight: 420, overflowY: 'auto' }}
    />
  );
}

function stepToStatus(status: TaskStep['status']): 'wait' | 'process' | 'finish' | 'error' {
  switch (status) {
    case 'SUCCESS':
      return 'finish';
    case 'FAILED':
      return 'error';
    case 'RUNNING':
      return 'process';
    default:
      return 'wait';
  }
}