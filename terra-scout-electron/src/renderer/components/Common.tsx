import React from 'react';
import styles from './common.module.css';

/**
 * 骨架屏（加载态，只动画 opacity）。
 *
 * 为什么删掉 PageTitle：单色编辑部风格下，导航词不允许在页面里
 * 以大标题形式重复；各页改用「语境化页头」（统计、动作按钮），
 * 页头结构由 pages.module.css 的 pageHeader 系列承载。
 */
export function PageLoading(): React.JSX.Element {
  return (
    <div className={styles.skeletonStack} data-testid="page-loading" aria-busy="true" aria-label="加载中">
      <div className={`${styles.skeleton} ${styles.line}`} />
      <div className={`${styles.skeleton} ${styles.card}`} />
      <div className={`${styles.skeleton} ${styles.card}`} />
    </div>
  );
}
