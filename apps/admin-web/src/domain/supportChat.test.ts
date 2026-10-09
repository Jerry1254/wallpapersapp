import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/repositories/http/apiClient';
import type { SupportRepository } from '@/repositories/http/supportRepository';
import { createSupportChat, type SupportCacheData } from './supportChat';
import type { SupportConversation, SupportMessage, SupportSend } from './support';

const user = (id: string): SupportConversation => ({ id, name: `用户 ${id}`, online: true, hidden: false, unreadCount: 1, lastMessage: null });
const message = (body: SupportSend, cid = '1', id = '11'): SupportMessage => ({ ...body, id, conversationId: cid, sender: 'ADMIN', attachment: null, createdAt: new Date().toISOString() });
function setup() {
  let stored: SupportCacheData = { pending: [], drafts: {} };
  const cache = { load: () => structuredClone(stored), save: vi.fn((value: SupportCacheData) => { stored = JSON.parse(JSON.stringify(value)); }) };
  const repo: SupportRepository = {
    conversations: vi.fn(), conversation: vi.fn(), hide: vi.fn(), read: vi.fn().mockResolvedValue(undefined),
    messages: vi.fn().mockResolvedValue({ items: [], cursor: '0', hasMore: false }),
    byClient: vi.fn().mockRejectedValue(new ApiError(404, 'SUPPORT_MESSAGE_NOT_FOUND', '未找到')),
    send: vi.fn(async (cid, body) => message(body, cid))
  };
  const chat = createSupportChat(repo, cache); chat.restore();
  return { repo, cache, chat };
}
describe('客服可靠发送', () => {
  it('本地保存失败时不发送，也不清空草稿', async () => {
    const { chat, cache, repo } = setup(); chat.draft('1').text = '你好';
    cache.save.mockImplementation(() => { throw new Error('存储失败'); });
    await expect(chat.sendDraft('1')).rejects.toThrow(); expect(repo.send).not.toHaveBeenCalled();
    expect(chat.draft('1').text).toBe('你好');
  });
  it('超时保留原标识；重试先核对，服务端已保存时不重复发送', async () => {
    const { chat, repo } = setup(); chat.draft('1').text = '你好';
    vi.mocked(repo.send).mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '结果未确认'));
    await chat.sendDraft('1'); const pending = chat.state.pending[0]!;
    expect(pending.state).toBe('uncertain');
    vi.mocked(repo.byClient).mockResolvedValue(message(pending.request));
    await chat.retry(pending); expect(repo.send).toHaveBeenCalledTimes(1); expect(chat.state.pending).toHaveLength(0);
  });
  it('确认未保存后用同一个标识重发；不会把别的会话或用户消息当成成功', async () => {
    const { chat, repo } = setup(); chat.draft('1').text = '你好';
    vi.mocked(repo.send).mockRejectedValueOnce(new ApiError(400, 'SUPPORT_INVALID', '发送失败'));
    await chat.sendDraft('1'); const pending = chat.state.pending[0]!; const uuid = pending.request.clientId;
    vi.mocked(repo.messages).mockResolvedValue({ items: [{ ...message(pending.request), sender: 'CUSTOMER' }], cursor: '11', hasMore: false });
    await chat.select(user('1')); expect(chat.state.pending).toHaveLength(1);
    await chat.retry(pending); expect(vi.mocked(repo.send).mock.calls[1]?.[1].clientId).toBe(uuid);
    expect(chat.state.pending).toHaveLength(0);
  });
  it('切换用户后迟到的发送结果仍属于原会话，且不跳过未同步的消息', async () => {
    const { chat, repo } = setup(); await chat.select(user('1')); chat.draft('1').text = '你好';
    let complete!: (value: SupportMessage) => void;
    vi.mocked(repo.send).mockImplementation((_cid, body) => new Promise(resolve => { complete = () => resolve(message(body, '1', '99')); }));
    const sending = chat.sendDraft('1'); await chat.select(user('2')); complete({} as SupportMessage); await sending;
    expect(chat.state.selected?.id).toBe('2'); expect(chat.feed('2').messages).toHaveLength(0);
    expect(chat.feed('1').messages[0]?.id).toBe('99'); expect(chat.feed('1').since).toBe('0');
    await chat.sync('1'); expect(repo.messages).toHaveBeenLastCalledWith('1', { after: '0', limit: 100 });
    await chat.markRead('1', '99'); expect(repo.read).not.toHaveBeenCalled();
  });
  it('重启把发送中改为待核对，不自动重发', async () => {
    const { chat, cache, repo } = setup(); chat.draft('1').text = '你好';
    vi.mocked(repo.send).mockRejectedValueOnce(new Error('断网')); await chat.sendDraft('1');
    const other = createSupportChat(repo, cache); other.restore(); await other.recover();
    expect(other.state.pending[0]?.state).toBe('uncertain'); expect(repo.send).toHaveBeenCalledTimes(1);
  });
});
