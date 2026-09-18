import type { ThemeConfig } from 'antd';

/**
 * antd 全局主题（单色编辑部风格）。
 *
 * 为什么集中在这里：antd 组件（Menu 选中条、链接、message/Modal 主按钮、
 * focus 环等）默认是蓝色系，这是页面顶部出现"Java 原生颜色条"的根因。
 * 必须在 ConfigProvider 一处收口，把 antd 全组件拉进锌灰阶，
 * 而不是在每个组件上零散覆盖。
 *
 * 所有 hex 均取自 styles/tokens.css 的锌灰阶，右侧注释标注对应变量。
 */
export const theme: ThemeConfig = {
  token: {
    // 主色 = gray-800：单色体系里"主操作色"就是最深的可用灰
    colorPrimary: '#27272a',
    // 链接色同主色，消灭 antd 默认蓝链接
    colorLink: '#27272a', // = --gray-800
    colorLinkHover: '#18181b', // = --gray-900
    colorLinkActive: '#18181b', // = --gray-900
    // 文本
    colorText: '#27272a', // = --gray-800
    colorTextSecondary: '#52525b', // = --gray-600
    colorTextTertiary: '#71717a', // = --gray-500
    // 边框与背景
    colorBorder: '#d4d4d8', // = --gray-300
    colorBorderSecondary: '#e8e8ea', // = --gray-200
    colorBgContainer: '#ffffff', // 卡片/输入白底
    colorBgLayout: '#fafafa', // = --gray-50 浅灰画布
    // 语义色收敛为低饱和档（仅状态点/危险按钮场景）
    colorSuccess: '#3f6212',
    colorWarning: '#b45309',
    colorError: '#991b1b',
    // focus 环跟随主色变灰，消灭蓝色聚焦环
    colorInfo: '#27272a',
    // 全局圆角与字体
    borderRadius: 10,
    fontFamily: 'inherit', // 继承 body 的 --font-ui 栈
  },
  components: {
    Menu: {
      // 导航整体透明底（融入侧栏灰底，不出现白色块）
      itemBg: 'transparent',
      // 选中项：中灰胶囊底（比悬浮深一档）+ 深灰文字，层级清晰但不跳
      itemSelectedBg: '#e4e4e7', // = --gray-200
      itemSelectedColor: '#18181b', // = --gray-900
      // 悬浮：淡灰胶囊底随悬停即时显现（而非点击时才四处），克制不抢选中
      itemHoverBg: '#f4f4f5', // = --gray-100
      itemHoverColor: '#18181b', // = --gray-900
      // 按下：与悬浮同色，点击瞬间不再跳出更深的"突兀灰框"
      itemActiveBg: '#f4f4f5', // = --gray-100
      itemColor: '#71717a', // = --gray-500 默认导航文字
      itemHeight: 38,
      itemBorderRadius: 8,
      itemMarginInline: 4,
      itemMarginBlock: 2,
      // 关键：消灭 inline 模式选中项右侧的彩色指示条（默认 3px 蓝条）
      activeBarBorderWidth: 0,
      activeBarHeight: 0,
    },
    Button: {
      borderRadius: 8,
      // 全站按钮统一白底发丝线（组件级覆盖见 styles/global.css），
      // antd 层只收敛圆角并去除默认投影
      primaryShadow: 'none',
      dangerShadow: 'none',
      defaultShadow: 'none',
    },
    Modal: {
      // 弹窗圆角与全局一致，去掉默认投影里的蓝调（用纯灰阴影）
      borderRadius: 14,
    },
    Message: {
      // message 提示条收敛为灰阶
      colorSuccess: '#3f6212',
      colorError: '#991b1b',
      colorWarning: '#b45309',
      colorInfo: '#27272a',
    },
    Tooltip: {
      colorBgSpotlight: '#27272a', // = --gray-800 深灰气泡
    },
    Steps: {
      // 步骤条进行中颜色走灰（全局 token 已覆盖，这里显式声明避免遗漏）
      colorPrimary: '#27272a',
    },
  },
};
