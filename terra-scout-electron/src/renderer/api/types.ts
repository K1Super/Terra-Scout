/**
 * 与 docs/api-spec.md 对齐的 TS 类型（D-002 / D-001：统一响应 code==HTTP×1000）。
 */

// ── 通用响应结构（rest-schema 3.2） ─────────────────────────────
export interface ApiEnvelope<T> {
  code: number;
  data?: T | null;
  message?: string;
  traceId?: string;
  timestamp?: number;
  /** 结构化明细（rest-schema ErrorResponse.details），含 hint 时前端附加展示。 */
  details?: Record<string, unknown>;
}

// ── 分页（3.6） ─────────────────────────────────────────────────
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

// ── 项目（3.4.1~3.4.4） ────────────────────────────────────────
export type ProjectType = 'MAVEN' | 'NPM' | 'MIXED' | 'GO' | 'PYTHON' | 'UNKNOWN';
export type OsType = 'WINDOWS' | 'LINUX' | 'MACOS';
export type Language = 'JAVA' | 'NODE' | 'GO' | 'PYTHON';

export interface LanguageConstraint {
  language: Language;
  constraint: string;
  sourceFile: string;
}

/** SDK 版本候选（R48：装配计划可选配版本，首项为推荐）。 */
export interface SdkVersionCandidate {
  version: string;
  lts: boolean;
  eol: boolean;
  sizeBytes: number | null;
  installed: boolean;
}

export interface SdkInstallPlan {
  language: Language;
  version: string;
  action: 'INSTALL' | 'REUSE';
  estimatedSizeBytes?: number | null;
  reason?: string;
  /** 可选版本候选表（R48），供详情页下拉选配；无候选（匹配失败被跳过）时不渲染选择器。 */
  candidates?: SdkVersionCandidate[];
}

export interface DependencyInstallPlan {
  ecosystem: string;
  isolation: string;
}

export interface VerifyCommandPlan {
  command: string;
  args: string[];
}

export interface AssemblyPlan {
  planId: string;
  sdkInstalls: SdkInstallPlan[];
  dependencyInstalls: DependencyInstallPlan[];
  verifyCommands: VerifyCommandPlan[];
}

export interface AnalyzeRequest {
  path: string;
  idempotencyKey?: string;
}

export interface AnalyzeResult {
  projectId: string;
  name: string;
  type: ProjectType;
  osType: OsType;
  constraints: LanguageConstraint[];
  plan: AssemblyPlan;
}

export interface ProjectListItem {
  projectId: string;
  name: string;
  projectRootPath: string;
  type: ProjectType;
  osType: OsType;
  lastTaskStatus: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface ProjectDetail extends ProjectListItem {
  constraints: LanguageConstraint[];
  /** 装配计划（analyze/详情可用）。 */
  plan?: AssemblyPlan;
}

// ── 任务（3.4.5~3.4.9） ────────────────────────────────────────
export type TaskStatus = 'QUEUED' | 'RUNNING' | 'PAUSED' | 'SUCCESS' | 'FAILED' | 'CANCELED' | 'SKIPPED';
export type StepStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

/** 用户 SDK 版本选配（R48）：{language, version} 覆盖自动匹配结果。 */
export interface VersionOverride {
  language: Language;
  version: string;
}

export interface ExecuteRequest {
  planId: string;
  confirm: boolean;
  idempotencyKey: string;
  /** 全量已选版本（R48）；缺省时后端按推荐版本自动匹配。 */
  versionOverrides?: VersionOverride[];
}

export interface ExecuteResult {
  taskId: string;
  status: TaskStatus;
}

export interface TaskListItem {
  taskId: string;
  type: string;
  status: TaskStatus;
  progress: number;
  errorCode: number | null;
  errorMsg: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface TaskStep {
  index: number;
  name: string;
  status: StepStatus;
}

export interface TaskDetail {
  taskId: string;
  type: string;
  status: TaskStatus;
  progress: number;
  steps: TaskStep[];
  errorCode: number | null;
  errorMsg: string | null;
  retryCount: number;
  maxRetry: number;
  createdAt: number;
  updatedAt: number;
}

export interface TaskLogs {
  taskId: string;
  stepIndex: number;
  lines: string[];
  truncated: boolean;
  stdoutFile: string;
  stderrFile: string;
}

export interface TaskActionRequest {
  taskId: string;
}

// ── SDK（3.4.10~3.4.12） ───────────────────────────────────────
export interface SdkVersion {
  language: Language;
  version: string;
  os: string;
  arch: string;
  downloadUrl: string;
  sha256: string;
  lts: boolean;
  eol: boolean;
  installed: boolean;
  /** 系统级已装（探测自 JAVA_HOME/PATH，非 Terra Scout 安装记录；无卸载语义）。 */
  systemInstalled?: boolean;
  recordId?: string;
  installedPath?: string;
  /** 当前/预定的物理落位路径（已装=实际路径，未装=安装目的地），保证空间去向透明。 */
  installPath?: string;
}

export interface SdkInstallRequest {
  language: Language;
  version: string;
  scope: 'PROJECT' | 'GLOBAL';
  projectId?: string;
  idempotencyKey: string;
  /** 可选（R45）：自定义安装根目录（绝对路径），自动落位 {所选目录}\{语言小写}\{版本}；缺省标准仓库。 */
  installDir?: string;
}

export interface SdkUninstallRequest {
  recordId: string;
  idempotencyKey: string;
}

/** 安装发起响应：异步任务返回 jobId（QUEUED），已装复用则同步返回 SUCCESS。 */
export interface SdkInstallResult {
  jobId?: string;
  status: 'QUEUED' | 'SUCCESS';
  /** 落地路径（QUEUED 时即确定，用户安装前即可知晓空间去向）。 */
  installPath?: string;
}

/** 安装进度快照（3.4.18 轮询）。 */
export interface SdkInstallProgress {
  jobId: string;
  language: Language;
  version: string;
  stage: string;
  message: string;
  percent: number;
  totalBytes: number;
  bytesDownloaded: number;
  finished: boolean;
  success: boolean;
  /** 已请求取消：终态为 CANCELLED，残留（.part/半解压目录）已即时回收。 */
  cancelled?: boolean;
  recordId?: string;
  installedPath?: string;
  installPath?: string;
  error?: string;
}

export interface MetadataReloadResult {
  loaded: boolean;
  sdkCount: number;
  updatedAt: string;
}

// ── 设置（3.4.15） ─────────────────────────────────────────────
export interface DownloadSettings {
  mirror: string;
  timeoutMs: number;
  maxRetry: number;
}

export interface Settings {
  download: DownloadSettings;
  commandTimeoutMs: number;
  logLevel: string;
  aiEnabled: boolean;
}

// ── 系统（3.4.16~3.4.17） ──────────────────────────────────────
export interface SystemInfo {
  version: string;
  javaVersion: string;
  dataDir: string;
  sdkRepoDir: string;
  dbSizeBytes: number;
}

export interface BackupResult {
  backupFile: string;
}

export interface DiagnosticResult {
  zipPath: string;
}

export interface Health {
  status: 'UP';
}

// ── 环境脚本（3.4.13） ─────────────────────────────────────────
export interface EnvScript {
  osType: OsType;
  script: string;
  scriptPath: string;
}