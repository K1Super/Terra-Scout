/** 任务进度条（transform: scaleX，禁止动画 width）。 */
interface TaskProgressProps {
  percent: number;
  label?: string;
}

export default function TaskProgress({ percent, label }: TaskProgressProps): React.JSX.Element {
  const clamped = Math.max(0, Math.min(1, percent));
  const scaled = (clamped * 100).toFixed(0);
  return (
    <div
      role="progressbar"
      aria-valuenow={Number(scaled)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={label ?? '任务进度'}
    >
      <div className="progress-bar">
        <div
          className="progress-bar__fill"
          style={{ transform: `scaleX(${clamped})` }}
        />
      </div>
      <div className="sr-only">{scaled}%</div>
    </div>
  );
}