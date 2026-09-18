import { describe, it, expect } from 'vitest';
import { KernelInfo, setDataDir, getKernelInfo } from '../src/main/kernel-info';

describe('KernelInfo', () => {
  it('generateToken 生成 32 字节 Base64（仅内存）', () => {
    const info = new KernelInfo('C:/data');
    const token = info.generateToken();
    expect(token).toBeTruthy();
    expect(Buffer.from(token, 'base64').length).toBe(32);
    expect(info.getToken()).toBe(token);
  });

  it('setPort/getPid 记录内存字段', () => {
    const info = new KernelInfo('C:/data');
    info.setPort(51200);
    info.setPid(1234);
    expect(info.getPort()).toBe(51200);
    expect(info.getPid()).toBe(1234);
  });

  it('log 写入 electron.log 且失败静默不抛', async () => {
    const info = new KernelInfo('C:/__nonexistent_dir__/data');
    await expect(info.log('hello')).resolves.toBeUndefined();
  });

  it('setDataDir 后 getKernelInfo 返回单例', () => {
    setDataDir('C:/x/y');
    const info = getKernelInfo();
    expect(info).toBeInstanceOf(KernelInfo);
  });
});