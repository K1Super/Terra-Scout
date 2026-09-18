import { spawn, type ChildProcessByStdio } from 'node:child_process';
import type { Readable } from 'node:stream';
import { EventEmitter } from 'node:events';
import { join } from 'node:path';

export interface JavaProcessOptions {
  /** Java 可执行文件路径（打包后指向 jre/bin/java.exe，dev 可为 'java'）。 */
  javaBin: string;
  /** terrascout jar 绝对路径。 */
  jarPath: string;
  /** 数据目录（--terrascout.data-dir）。 */
  dataDir: string;
  /** 仅内存 token。 */
  token: string;
  /** 等待 READY 超时（ms），默认 20000。Windows 上系统防御软件实时扫描
   *  53MB fat jar 会使 Spring 装配波动（实测 8~12s），10s 阈值在慢机上有误报风险。 */
  readyTimeoutMs?: number;
  /** 崩溃自动重启最大次数，默认 3（process-management 5.4）。 */
  maxRestarts?: number;
  /** 重启间隔（ms），默认 2000。 */
  restartDelayMs?: number;
  /** 优雅退出后等待进程退出的超时（ms），默认 5000（process-management 5.5）。 */
  shutdownTimeoutMs?: number;
}

export interface JavaProcessEvents {
  ready: (port: number) => void;
  /** stdout 每行日志（READY 行不重复发出）。 */
  log: (line: string, stream: 'stdout' | 'stderr') => void;
  /** 崩溃退出（非主动关闭）。 */
  crashed: (code: number | null, signal: NodeJS.Signals | null) => void;
  /** 重启次数超出上限，需用户手动重启。 */
  giveUp: () => void;
  /** 进程正常退出（主动/优雅）。 */
  exited: (code: number | null) => void;
  /** 启动失败（READY 超时）。 */
  startupFailed: (error: string) => void;
}

/**
 * Java 内核进程管理（process-management 5.2~5.7）：
 * - spawn java -jar terrascout.jar --server.address=127.0.0.1 --server.port=0 --terrascout.token=<b64> --terrascout.data-dir=...
 * - 解析 stdout "READY <actualPort>" 得到实际端口
 * - 崩溃自动重启 ≤3 次，每次间隔 2s；isShuttingDown 时跳过重启
 * - 优雅退出：POST /api/v1/shutdown → 等待退出 ≤5s → 超时 kill
 */
export class JavaProcess extends EventEmitter {
  private child?: ChildProcessByStdio<null, Readable, Readable>;
  private port = 0;
  private restartAttempts = 0;
  private shuttingDown = false;
  private voluntarilyStopped = false;
  private readyTimer?: NodeJS.Timeout;
  private restartTimer?: NodeJS.Timeout;

  constructor(private readonly options: JavaProcessOptions) {
    super();
  }

  getPort(): number {
    return this.port;
  }

  getPid(): number | undefined {
    return this.child?.pid;
  }

  get restartCount(): number {
    return this.restartAttempts;
  }

  get isShuttingDown(): boolean {
    return this.shuttingDown;
  }

  /**
   * 生成 Java 启动参数（process-management 5.3 契约）。
   * 强制 IPv4 栈：Windows 默认 IPv6 优先，部分官方源（python.org→Fastly 等）的
   * AAAA 路由不可达会导致抓取连接超时；IPv4 实测稳定（见 docs 决策裁决）。
   * 堆内存显式化 + UTF-8 显式化：避免默认按物理内存 1/4 估算的漂移与 Windows
   * 默认代码页（GBK）对路径/响应的不确定编码（启动加速收益弱，本组参数为确定性）。
   */
  private buildArgs(): string[] {
    return [
      '-Djava.net.preferIPv4Stack=true',
      '-Dfile.encoding=UTF-8',
      '-Xms64m',
      '-Xmx512m',
      '-jar',
      this.options.jarPath,
      '--server.address=127.0.0.1',
      '--server.port=0',
      `--terrascout.token=${this.options.token}`,
      `--terrascout.data-dir=${this.options.dataDir}`,
      '--spring.main.banner-mode=off',
    ];
  }

  start(): void {
    if (this.shuttingDown || this.voluntarilyStopped) {
      return;
    }
    const child = spawn(this.options.javaBin, this.buildArgs(), {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.child = child;

    const readyTimeout = this.options.readyTimeoutMs ?? 20000;
    this.readyTimer = setTimeout(() => {
      if (!this.port && this.child) {
        this.emit('startupFailed', `Java 未在 ${readyTimeout}ms 内输出 READY`);
        this.child.kill();
      }
    }, readyTimeout);

    child.stdout.on('data', (buf: Buffer) => this.handleOutput(buf, 'stdout'));
    child.stderr.on('data', (buf: Buffer) => this.handleOutput(buf, 'stderr'));

    child.on('exit', (code, signal) => this.handleExit(code, signal));
    child.on('error', (err) => {
      this.emit('startupFailed', `无法启动 Java: ${err.message}`);
    });
  }

  private handleOutput(buf: Buffer, stream: 'stdout' | 'stderr'): void {
    buf.toString('utf8').split(/\r?\n/).forEach((line) => {
      if (line.length === 0) {
        return;
      }
      if (stream === 'stdout') {
        const match = line.match(/^READY\s+(\d+)$/);
        if (match) {
          this.onReady(parseInt(match[1], 10));
          return;
        }
      }
      this.emit('log', line, stream);
    });
  }

  private onReady(port: number): void {
    if (this.readyTimer) {
      clearTimeout(this.readyTimer);
      this.readyTimer = undefined;
    }
    this.port = port;
    this.restartAttempts = 0;
    this.emit('ready', port);
  }

  private handleExit(code: number | null, signal: NodeJS.Signals | null): void {
    if (this.readyTimer) {
      clearTimeout(this.readyTimer);
      this.readyTimer = undefined;
    }
    if (this.shuttingDown || this.voluntarilyStopped) {
      this.emit('exited', code);
      return;
    }
    // 崩溃：自动重启 ≤3 次，间隔 2s
    if (this.restartAttempts >= this.maxRestarts) {
      this.emit('giveUp');
      return;
    }
    this.emit('crashed', code, signal);
    this.restartAttempts += 1;
    const delay = this.options.restartDelayMs ?? 2000;
    this.restartTimer = setTimeout(() => this.start(), delay);
  }

  private get maxRestarts(): number {
    return this.options.maxRestarts ?? 3;
  }

  /** 主动停止：不触发自动重启。 */
  stop(): void {
    this.voluntarilyStopped = true;
    if (this.child) {
      this.child.kill();
    }
  }

  /**
   * 优雅退出（process-management 5.5）：
   * 置 isShuttingDown→POST /api/v1/shutdown（带 token）→等待退出 ≤5s→超时强制 kill。
   * 返回 Promise 在进程最终退出时 resolve。
   */
  async gracefulShutdown(): Promise<void> {
    if (!this.child || this.port === 0) {
      return;
    }
    this.shuttingDown = true;
    try {
      const res = await fetch(`http://127.0.0.1:${this.port}/api/v1/shutdown`, {
        method: 'POST',
        headers: { 'X-TerraScout-Token': this.options.token },
      });
      if (!res.ok) {
        this.emit('log', `shutdown HTTP ${res.status}`, 'stderr');
      }
    } catch {
      // 内核已断，直接走超时/退出路径
    }
    const timeout = this.options.shutdownTimeoutMs ?? 5000;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        if (this.child) {
          this.child.kill();
        }
        resolve();
      }, timeout);
      this.once('exited', () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  /** 关闭未启动的定时器（JVM 资源释放，供生命周期收尾）。 */
  cleanup(): void {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = undefined;
    }
  }
}

/**
 * 校验资源路径（打包后 jar 位于 resources/，dev 指向后端 target）：
 * - dev: TERRA_SCOUT_JAR 提供的绝对 jar 路径
 * - prod: join(process.resourcesPath, 'terrascout.jar')
 */
export function resolveJarPath(explicit?: string): string {
  if (explicit) {
    return explicit;
  }
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return join((process as unknown as { resourcesPath?: string }).resourcesPath ?? '', 'terrascout.jar');
}