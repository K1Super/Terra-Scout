import { randomBytes } from 'node:crypto';
import { appendFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';

/**
 * 内核信息管理（D-016 token 合规）：
 * - Token：crypto.randomBytes(32).toString('base64') 仅内存，随进程退出失效；
 *   经 --terrascout.token= 传给 Java，渲染进程经 contextBridge 仅拼 X-TerraScout-Token 头。
 * - 端口 / PID 记录在内存，不落盘（process-management 5.6）。
 * - Electron 日志写入 {data-dir}/logs/electron.log（process-management 5.8，按天+大小滚动由外层/内核负责）。
 */
export class KernelInfo {
  private readonly logFile: string;
  private port = 0;
  private pid?: number;
  private memoryToken = '';

  constructor(private readonly dataDir: string) {
    this.logFile = join(dataDir, 'logs', 'electron.log');
  }

  /** 生成仅内存的随机 Token（32 字节 Base64），并保存供 IPC 单通道取用。 */
  generateToken(): string {
    this.memoryToken = randomBytes(32).toString('base64');
    return this.memoryToken;
  }

  setToken(token: string): void {
    this.memoryToken = token;
  }

  getToken(): string | null {
    return this.memoryToken || null;
  }

  setPort(port: number): void {
    this.port = port;
  }

  getPort(): number {
    return this.port;
  }

  setPid(pid: number): void {
    this.pid = pid;
  }

  getPid(): number | undefined {
    return this.pid;
  }

  /** 同步写日志（失败静默，避免主进程崩溃）。 */
  async log(message: string): Promise<void> {
    const line = `[${new Date().toISOString()}] ${message}\n`;
    try {
      const dir = this.logFile.slice(0, this.logFile.lastIndexOf('\\'));
      await mkdir(dir, { recursive: true });
      await appendFile(this.logFile, line, { encoding: 'utf8' });
    } catch {
      // 日志写入失败不阻断主流程
    }
  }
}

/** 单例：data-dir 在 index.ts 启动时确定，无法在此初始化，setInstance 后使用。 */
let instance: KernelInfo | null = null;

export function setDataDir(dataDir: string): void {
  instance = new KernelInfo(dataDir);
}

export function getKernelInfo(): KernelInfo {
  if (!instance) {
    throw new Error('KernelInfo not initialized: setDataDir must be called first');
  }
  return instance;
}