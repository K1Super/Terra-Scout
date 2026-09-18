import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventEmitter } from 'node:events';

// 使用 vi.hoisted 构造可注入的 spawn 替身（避免 hoisting 顺序问题）
const h = vi.hoisted(() => {
  const trail: Array<FakeChild> = [];
  const spawnMock = vi.fn((_cmd: string, _args: string[]) => {
    const fake = new FakeChild();
    trail.push(fake);
    return fake;
  });
  return { trail, spawnMock };
});

vi.mock('node:child_process', () => ({ spawn: h.spawnMock }));

import { JavaProcess, type JavaProcessOptions } from '../src/main/java-process';

class FakeChild extends EventEmitter {
  pid = 4242;
  stdout = new EventEmitter();
  stderr = new EventEmitter();
  killCalls = 0;
  kill = vi.fn(() => {
    this.killCalls += 1;
    return true;
  });
}

function mkOptions(overrides: Partial<JavaProcessOptions> = {}): JavaProcessOptions {
  return {
    javaBin: 'java',
    jarPath: '/jar/terrascout.jar',
    dataDir: '/data',
    token: 'bm9wZTp0b2tlbg==',
    ...overrides,
  };
}

function port(jp: JavaProcess): number {
  return jp.getPort();
}

describe('JavaProcess', () => {
  beforeEach(() => {
    h.trail.length = 0;
    vi.useRealTimers();
  });

  it('解析 READY 行并发出 ready 事件（含实际端口）', () => {
    const jp = new JavaProcess(mkOptions());
    const readySpy = vi.fn();
    jp.on('ready', readySpy);
    jp.start();
    const child = h.trail[0];
    child.stdout.emit('data', Buffer.from('\r\n'));
    child.stdout.emit('data', Buffer.from('READY 51235\n'));
    expect(readySpy).toHaveBeenCalledWith(51235);
    expect(port(jp)).toBe(51235);
  });

  it('启动参数含确定性 JVM 与 Spring 配置（内存/UTF-8/IPv4/banner）', () => {
    const jp = new JavaProcess(mkOptions());
    jp.start();
    const args: string[] = h.spawnMock.mock.calls[0][1];
    expect(args).toBeDefined();
    const joined = args.join(' ');
    expect(joined).toContain('-Djava.net.preferIPv4Stack=true');
    expect(joined).toContain('-Dfile.encoding=UTF-8');
    expect(joined).toContain('-Xms64m');
    expect(joined).toContain('-Xmx512m');
    expect(joined).toContain('--spring.main.banner-mode=off');
    expect(joined).toContain(`--terrascout.data-dir=/data`);
  });

  it('崩溃后自动重启（≤maxRestarts，间隔重启延迟）', () => {
    vi.useFakeTimers();
    const jp = new JavaProcess(mkOptions({ restartDelayMs: 10, maxRestarts: 3 }));
    const crashed = vi.fn();
    jp.on('crashed', crashed);
    jp.start();
    h.trail[0].emit('exit', 1, null);
    expect(jp.restartCount).toBe(1);
    vi.advanceTimersByTime(10);
    expect(h.trail.length).toBe(2);
  });

  it('超过 maxRestarts 后发出 giveUp，不再重启', () => {
    vi.useFakeTimers();
    const jp = new JavaProcess(mkOptions({ restartDelayMs: 10, maxRestarts: 1 }));
    const giveUp = vi.fn();
    jp.on('giveUp', giveUp);
    jp.start();
    h.trail[0].emit('exit', 1, null); // 第 1 次重启
    vi.advanceTimersByTime(10); // 触发 child1
    h.trail[1].emit('exit', 1, null); // 已达上限 → giveUp
    expect(giveUp).toHaveBeenCalledTimes(1);
    expect(h.trail.length).toBe(2);
  });

  it('isShuttingDown 时退出不触发崩溃重启，仅发出 exited', () => {
    const jp = new JavaProcess(mkOptions());
    const exited = vi.fn();
    const crashed = vi.fn();
    jp.on('exited', exited);
    jp.on('crashed', crashed);
    jp.start();
    // @ts-expect-error 测试访问私有字段模拟正在退出
    jp.shuttingDown = true;
    h.trail[0].emit('exit', 0, null);
    expect(exited).toHaveBeenCalledTimes(1);
    expect(crashed).not.toHaveBeenCalled();
    expect(h.trail.length).toBe(1);
  });

  it('READY 超时发出 startupFailed 并终止子进程', () => {
    vi.useFakeTimers();
    const jp = new JavaProcess(mkOptions({ readyTimeoutMs: 1000 }));
    const failed = vi.fn();
    jp.on('startupFailed', failed);
    jp.start();
    vi.advanceTimersByTime(1500);
    expect(failed).toHaveBeenCalledTimes(1);
    expect(h.trail[0].killCalls).toBeGreaterThan(0);
  });

  it('gracefulShutdown：POST shutdown（带 token）后等退出，超时 kill', async () => {
    const jp = new JavaProcess(mkOptions({ shutdownTimeoutMs: 500 }));
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue({ ok: true, status: 200 } as Response);
    jp.start();
    h.trail[0].stdout.emit('data', Buffer.from('READY 51236\n'));
    await jp.gracefulShutdown();
    expect(fetchSpy).toHaveBeenCalledWith(
      'http://127.0.0.1:51236/api/v1/shutdown',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(jp.isShuttingDown).toBe(true);
    fetchSpy.mockRestore();
  });
});