import type { RedemptionResponse, RedemptionResult } from '@/domain/device';
import { ApiClientError } from '@/repositories/http/apiClient';
import { deviceRepository } from '@/repositories/http/deviceRepository';
import { sha256Hex } from '@/repositories/http/h5DeviceProvider';

export const pendingRedemptionStorageKey = 'qj-pending-v1';
export interface PendingRedemption { key: string; wallpaperId: string; bodyHash: string }
type Repository = Pick<typeof deviceRepository, 'redeem' | 'redemptionResult'>;
export class RedemptionUncertain extends Error {}

export class RedemptionCoordinator {
  private busy = false;
  constructor(private readonly storage: Storage, private readonly repository: Repository = deviceRepository) {}

  get pending(): PendingRedemption | undefined {
    const raw = this.storage.getItem(pendingRedemptionStorageKey);
    if (!raw) return undefined;
    const value = JSON.parse(raw) as PendingRedemption;
    if (typeof value.key !== 'string' || typeof value.wallpaperId !== 'string' || !/^[a-f0-9]{64}$/.test(value.bodyHash)) {
      throw new Error('上次兑换记录不可用，请联系客服确认结果');
    }
    return value;
  }

  private finish(response: RedemptionResponse): RedemptionResult {
    const pending = this.pending;
    if (!pending || response.idempotencyKey !== pending.key) throw new RedemptionUncertain('兑换结果尚未确认，请稍后再次确认');
    if ('status' in response) throw new RedemptionUncertain('服务正在处理兑换，请稍后确认结果');
    // Never erase a newer intent created in another tab.
    this.storage.removeItem(pendingRedemptionStorageKey);
    return response;
  }

  async confirm(): Promise<RedemptionResult> {
    const pending = this.pending;
    if (!pending) throw new Error('没有待确认的兑换');
    try { return this.finish(await this.repository.redemptionResult(pending.key)); }
    catch (error) {
      if (error instanceof RedemptionUncertain) throw error;
      if (error instanceof ApiClientError && error.code === 'REDEMPTION_REQUEST_NOT_FOUND') {
        throw new RedemptionUncertain('暂未找到兑换结果。可再次确认，或输入原兑换码重试本次兑换');
      }
      throw new RedemptionUncertain('暂时无法确认兑换结果，请恢复网络后再次确认');
    }
  }

  async redeem(wallpaperId: string, suppliedCode: string): Promise<RedemptionResult> {
    if (this.busy) throw new Error('正在处理本次兑换');
    this.busy = true;
    try {
      const code = suppliedCode.replace(/[-\s]/g, '').toUpperCase();
      if (!/^[A-Z0-9]{20}$/.test(code)) throw new Error('请输入完整的 20 位兑换码');
      const body = JSON.stringify({ wallpaperId, code });
      const bodyHash = await sha256Hex(body);
      let pending = this.pending;
      if (pending && (pending.wallpaperId !== wallpaperId || pending.bodyHash !== bodyHash)) {
        throw new RedemptionUncertain('上次兑换尚未确认。请先确认结果，重试时需使用原兑换码');
      }
      if (!pending) {
        pending = { key: crypto.randomUUID(), wallpaperId, bodyHash };
        // Persist BEFORE dispatch, but never persist the plaintext code or body.
        this.storage.setItem(pendingRedemptionStorageKey, JSON.stringify(pending));
      }
      try { return this.finish(await this.repository.redeem(body, pending.key)); }
      catch (error) {
        if (error instanceof RedemptionUncertain) throw error;
        // Errors before the transaction are definite. Conflict/timeout/5xx remain pending.
        if (error instanceof ApiClientError && [400, 401, 403, 404, 429].includes(error.status)) {
          if (this.pending?.key === pending.key) this.storage.removeItem(pendingRedemptionStorageKey);
          throw error;
        }
        throw new RedemptionUncertain('兑换结果尚未确认，请恢复网络后确认结果');
      }
    } finally { this.busy = false; }
  }
}

export const redemptionResultMessage = (result: RedemptionResult) => {
  if (result.result === 'CODE_NOT_FOUND') return '兑换码无效或已停用，请检查后重试';
  if (result.result === 'CODE_EXHAUSTED') return '兑换额度已用完，请联系客服';
  if (result.result === 'WALLPAPER_UNAVAILABLE') return '这张壁纸暂时无法兑换';
  if (result.result === 'ALREADY_OWNED') return '当前浏览器已获得这张壁纸，本次未消耗额度';
  if (result.result === 'GRANTED') return '兑换成功，已绑定当前浏览器';
  return '兑换未完成，请稍后重试';
};
