import { describe, expect, it, vi } from 'vitest';
import type { RedemptionResult } from '@/domain/device';
import { ApiClientError } from '@/repositories/http/apiClient';
import { memoryStorage } from '@/test/storage';
import { RedemptionCoordinator, RedemptionUncertain, pendingRedemptionStorageKey } from './redemptionCoordinator';

const code = 'ABCDEFGHJKLMNPQRSTUV';
const finalResult = (key: string, result: RedemptionResult['result'] = 'GRANTED'): RedemptionResult => ({
  idempotencyKey: key, result, quotaDelta: result === 'GRANTED' ? 1 : 0, createdAt: '2026-09-12T10:00:00Z'
});

describe('redemption uncertainty and recovery', () => {
  it('does not dispatch an incomplete code', async () => {
    const repository = { redeem: vi.fn(), redemptionResult: vi.fn() };
    const coordinator = new RedemptionCoordinator(memoryStorage(), repository);
    await expect(coordinator.redeem('1', 'INVALID')).rejects.toThrow('20 位');
    expect(repository.redeem).not.toHaveBeenCalled();
  });

  it('keeps the original key across a lost response and reload, storing no plaintext code', async () => {
    const storage = memoryStorage();
    const repository = { redeem: vi.fn().mockRejectedValue(new ApiClientError(0, 'NETWORK_ERROR', 'lost')), redemptionResult: vi.fn() };
    const coordinator = new RedemptionCoordinator(storage, repository);
    await expect(coordinator.redeem('1', code)).rejects.toBeInstanceOf(RedemptionUncertain);
    const pending = coordinator.pending!;
    expect(storage.getItem(pendingRedemptionStorageKey)).not.toContain(code);
    expect(storage.getItem(pendingRedemptionStorageKey)).not.toContain('"code"');
    repository.redemptionResult.mockResolvedValue(finalResult(pending.key));
    const reloaded = new RedemptionCoordinator(storage, repository);
    expect((await reloaded.confirm()).result).toBe('GRANTED');
    expect(repository.redemptionResult).toHaveBeenCalledWith(pending.key);
    expect(reloaded.pending).toBeUndefined();
    expect(repository.redeem).toHaveBeenCalledTimes(1);
  });

  it('blocks a changed code or wallpaper, and retries only the same normalized body/key', async () => {
    const repository = { redeem: vi.fn().mockRejectedValue(new ApiClientError(503, 'UNAVAILABLE', 'down')), redemptionResult: vi.fn() };
    const coordinator = new RedemptionCoordinator(memoryStorage(), repository);
    await expect(coordinator.redeem('1', code)).rejects.toBeInstanceOf(RedemptionUncertain);
    const pending = coordinator.pending!;
    await expect(coordinator.redeem('1', '23456789234567892345')).rejects.toBeInstanceOf(RedemptionUncertain);
    await expect(coordinator.redeem('2', code)).rejects.toBeInstanceOf(RedemptionUncertain);
    repository.redeem.mockImplementation(async (_body, key) => finalResult(key));
    await coordinator.redeem('1', 'abcde-fghjk lmnpq-rstuv');
    expect(repository.redeem).toHaveBeenCalledTimes(2);
    expect(repository.redeem.mock.calls[0]).toEqual(repository.redeem.mock.calls[1]);
    expect(repository.redeem.mock.calls[1]![1]).toBe(pending.key);
  });

  it('keeps processing and not-found confirmations pending', async () => {
    const repository = { redeem: vi.fn(async (_body, key) => ({ status: 'PROCESSING' as const, idempotencyKey: key })), redemptionResult: vi.fn() };
    const coordinator = new RedemptionCoordinator(memoryStorage(), repository);
    await expect(coordinator.redeem('1', code)).rejects.toBeInstanceOf(RedemptionUncertain);
    const key = coordinator.pending!.key;
    repository.redemptionResult.mockRejectedValue(new ApiClientError(404, 'REDEMPTION_REQUEST_NOT_FOUND', 'missing'));
    await expect(coordinator.confirm()).rejects.toThrow('原兑换码');
    expect(coordinator.pending!.key).toBe(key);
  });

  it('clears definite business rejection and pre-transaction validation errors', async () => {
    const repository = { redeem: vi.fn(async (_body, key) => finalResult(key, 'CODE_EXHAUSTED')), redemptionResult: vi.fn() };
    const coordinator = new RedemptionCoordinator(memoryStorage(), repository);
    expect((await coordinator.redeem('1', code)).result).toBe('CODE_EXHAUSTED');
    expect(coordinator.pending).toBeUndefined();
    repository.redeem.mockRejectedValue(new ApiClientError(400, 'VALIDATION_FAILED', 'invalid'));
    await expect(coordinator.redeem('1', code)).rejects.toMatchObject({ status: 400 });
    expect(coordinator.pending).toBeUndefined();
  });
});
