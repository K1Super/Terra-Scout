import { useNavigate } from 'react-router-dom';
import { FolderOpen, Play } from 'lucide-react';
import type { ProjectListItem } from '../api/types';
import { formatTime } from '../lib/id';
import styles from './project-card.module.css';

interface ProjectCardProps {
  project: ProjectListItem;
  onDelete: (id: string) => void;
}

/**
 * 首页项目卡片。
 * 语言：白纸面卡片 + 发丝线；hover 时边框加深一档并上浮 1px
 * （180ms 复合曲线）——克制的存在感，不用大面积阴影与彩色。
 * 装配状态用"点 + 文字"的灰阶胶囊表达。
 */
export default function ProjectCard({ project, onDelete }: ProjectCardProps): React.JSX.Element {
  const navigate = useNavigate();
  const installed = project.lastTaskStatus === 'SUCCESS';
  return (
    <div className={styles.card}>
      <div className={styles.cardHeader}>
        <span className={styles.cardTitle}>
          <FolderOpen size={15} className={styles.titleIcon} aria-hidden="true" />
          {project.name}
        </span>
        <span className={`${styles.pill} ${installed ? styles.pillOk : ''}`} role="status">
          <span className={styles.pillDot} aria-hidden="true" />
          {installed ? '已装配' : '未装配'}
        </span>
      </div>
      <p className={styles.path}>{project.projectRootPath}</p>
      <p className={styles.meta}>
        {project.type} · 更新于 {formatTime(project.updatedAt)}
      </p>
      <div className={styles.actions}>
        <button
          type="button"
          className={styles.btn}
          onClick={() => navigate(`/project/${project.projectId}`)}
        >
          查看
        </button>
        <button
          type="button"
          className={styles.btn}
          onClick={() => navigate(`/project/${project.projectId}`)}
          aria-label={`${installed ? '重新装配' : '装配'} ${project.name}`}
        >
          <Play size={14} aria-hidden="true" />
          {installed ? '重新装配' : '装配'}
        </button>
        <button
          type="button"
          className={styles.btnDelete}
          onClick={() => onDelete(project.projectId)}
          aria-label={`删除项目 ${project.name}`}
        >
          删除
        </button>
      </div>
    </div>
  );
}
