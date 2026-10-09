import { reactive } from 'vue';
import { ApiError, readableApiError } from '@/repositories/http/apiClient';
import type { SupportRepository } from '@/repositories/http/supportRepository';
import { compareSupportIds, emptySupportDraft, pendingMatches, type Draft, type PendingSupportMessage, type SupportConversation, type SupportMessage, type SupportSend } from './support';

interface Feed { messages: SupportMessage[]; since: string; older: string; hasOlder: boolean; initialized: boolean; readThrough: string }
export interface SupportCacheData { pending: PendingSupportMessage[]; drafts: Record<string, Draft> }
export interface SupportCache { load(): SupportCacheData; save(data: SupportCacheData): void }
export class BrowserSupportCache implements SupportCache {
  private key = `qingjing-support-admin-v1:${location.origin}`;
  load(): SupportCacheData {
    const raw = localStorage.getItem(this.key); if (!raw) return { pending: [], drafts: {} };
    const value = JSON.parse(raw) as SupportCacheData;
    if (!Array.isArray(value.pending) || !value.drafts || typeof value.drafts !== 'object') throw new Error('本地发送记录无法读取，请保留浏览器数据并重试');
    return value;
  }
  save(data: SupportCacheData) { try { localStorage.setItem(this.key, JSON.stringify(data)); } catch { throw new Error('本地发送记录无法保存，请检查浏览器存储后重试'); } }
}
export function createSupportChat(repository: SupportRepository, cache: SupportCache) {
  const state = reactive({ selected: null as SupportConversation | null, feeds: {} as Record<string, Feed>, drafts: {} as Record<string, Draft>,
    pending: [] as PendingSupportMessage[], cacheError: '', error: '', loading: false });
  const busy = new Set<string>();
  const feed = (id: string): Feed => state.feeds[id] ??= { messages: [], since: '0', older: '0', hasOlder: false, initialized: false, readThrough: '0' };
  const snapshot = (): SupportCacheData => ({ pending: state.pending, drafts: state.drafts });
  const persist = () => { try { cache.save(snapshot()); state.cacheError = ''; } catch (error) { state.cacheError = readableApiError(error); } };
  function merge(id: string, messages: SupportMessage[]) {
    const current = feed(id); const map = new Map(current.messages.map(item => [item.id, item]));
    for (const message of messages) if (message.conversationId === id) map.set(message.id, message);
    current.messages = [...map.values()].sort((a, b) => compareSupportIds(a.id, b.id));
    const before = state.pending.length;
    state.pending = state.pending.filter(pending => !messages.some(message => pendingMatches(pending, message)));
    if (before !== state.pending.length) persist();
  }
  function draft(id: string): Draft { return state.drafts[id] ??= emptySupportDraft(); }
  function saveDraft() { persist(); }
  function restore() {
    try { const stored = cache.load(); state.drafts = stored.drafts; state.pending = stored.pending.map(item => ({ ...item, state: item.state === 'sending' ? 'uncertain' : item.state })); persist(); }
    catch (error) { state.cacheError = readableApiError(error, '本地发送记录无法读取'); }
  }
  async function select(conversation: SupportConversation) {
    state.selected = conversation; state.loading = true; state.error = ''; const id = conversation.id;
    try {
      if (!feed(id).initialized) {
        const page = await repository.messages(id); merge(id, page.items);
        const current = feed(id); current.since = page.items.at(-1)?.id || '0'; current.older = page.cursor;
        current.hasOlder = page.hasMore; current.initialized = true;
      } else await sync(id);
      await recover(id);
    } catch (error) { if (state.selected?.id === id) state.error = readableApiError(error, '聊天记录暂时无法加载'); }
    finally { if (state.selected?.id === id) state.loading = false; }
  }
  async function sync(id: string) {
    const current = feed(id); if (!current.initialized) return;
    for (let batch = 0; batch < 5; batch++) {
      const page = await repository.messages(id, { after: current.since, limit: 100 }); merge(id, page.items);
      if (compareSupportIds(page.cursor, current.since) > 0) current.since = page.cursor;
      if (!page.hasMore) break;
    }
  }
  async function older(id: string) {
    const current = feed(id); if (!current.hasOlder) return;
    const page = await repository.messages(id, { before: current.older }); merge(id, page.items);
    current.older = page.cursor; current.hasOlder = page.hasMore;
  }
  async function markRead(id: string, visibleId: string) {
    const current = feed(id); const position = compareSupportIds(visibleId, current.since) > 0 ? current.since : visibleId;
    if (position === '0' || compareSupportIds(position, current.readThrough) <= 0) return;
    await repository.read(id, position); current.readThrough = position;
  }
  async function recover(id?: string) {
    for (const pending of [...state.pending]) {
      if (pending.state !== 'uncertain' || (id && pending.conversationId !== id) || busy.has(pending.request.clientId)) continue;
      try { const message = await repository.byClient(pending.conversationId, pending.request.clientId); if (pendingMatches(pending, message)) merge(pending.conversationId, [message]); }
      catch (error) { if (!(error instanceof ApiError) || error.status !== 404) pending.error = readableApiError(error, '发送结果暂时无法确认'); }
    }
    persist();
  }
  async function attempt(pending: PendingSupportMessage, retry: boolean) {
    const key = pending.request.clientId; if (busy.has(key)) return; busy.add(key);
    pending.state = 'sending'; pending.error = ''; persist();
    try {
      if (retry) {
        try { const prior = await repository.byClient(pending.conversationId, key); if (!pendingMatches(pending, prior)) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', '此消息标识已用于不同内容'); merge(pending.conversationId, [prior]); return; }
        catch (error) { if (!(error instanceof ApiError) || error.status !== 404) throw error; }
      }
      const message = await repository.send(pending.conversationId, pending.request);
      if (!pendingMatches(pending, message)) throw new ApiError(0, 'INVALID_RESPONSE', '发送结果未确认，请重试');
      merge(pending.conversationId, [message]);
    } catch (error) {
      pending.state = error instanceof ApiError && error.status >= 400 && error.status < 500 ? 'failed' : 'uncertain';
      pending.error = readableApiError(error, '发送结果未确认，请核对或重试'); persist();
    } finally { busy.delete(key); }
  }
  async function sendDraft(id: string) {
    if (state.cacheError) throw new Error(state.cacheError);
    const current = draft(id); const text = current.text.trim();
    if (!current.attachment && (!text || text.length > 2000)) throw new Error('请输入消息，且不能超过 2000 字');
    const request: SupportSend = { clientId: crypto.randomUUID(), kind: current.attachment?.kind || 'TEXT', text: current.attachment ? null : text,
      attachmentId: current.attachment?.id || null, libraryItemId: current.attachment ? current.libraryItemId : null };
    const pending: PendingSupportMessage = { conversationId: id, request, attachment: current.attachment, state: 'sending', error: '', createdAt: new Date().toISOString() };
    const cleared = current.attachment ? { ...current, attachment: null, libraryItemId: null } : emptySupportDraft();
    const next = { pending: [...state.pending, pending], drafts: { ...state.drafts, [id]: cleared } };
    cache.save(next); state.pending = next.pending; state.drafts = next.drafts;
    // Send completion belongs to the captured conversation, even after switching users.
    await attempt(state.pending.find(item => item.request.clientId === request.clientId)!, false);
  }
  return { state, feed, draft, saveDraft, restore, select, sync, older, markRead, recover, sendDraft, retry: (pending: PendingSupportMessage) => attempt(pending, true) };
}
