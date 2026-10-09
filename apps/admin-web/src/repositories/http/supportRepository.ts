import { apiRequest, apiResourceUrl, ApiError } from './apiClient';
import type { LibraryKind, Page, SupportAttachment, SupportConversation, SupportLibraryItem, SupportLibraryWrite, SupportMessage, SupportMessagePage, SupportSend } from '@/domain/support';

export interface SupportRepository {
  conversations(search?: string, unread?: boolean, page?: number): Promise<Page<SupportConversation>>;
  conversation(id: string): Promise<SupportConversation>;
  messages(id: string, query?: { after?: string; before?: string; limit?: number }): Promise<SupportMessagePage>;
  byClient(id: string, clientId: string): Promise<SupportMessage>;
  send(id: string, data: SupportSend): Promise<SupportMessage>;
  read(id: string, messageId: string): Promise<void>;
  hide(id: string): Promise<void>;
}
const root = '/admin/support';
const conversation = (id: string) => `${root}/conversations/${encodeURIComponent(id)}`;
const queryString = (query: Record<string, string | number | boolean | undefined>) => new URLSearchParams(Object.entries(query)
  .filter(([, value]) => value !== undefined).map(([key, value]) => [key, String(value)])).toString();
const read = async <T>(path: string) => (await apiRequest<T>(path)).data;
const write = async <T>(path: string, method: string, body?: unknown) => (await apiRequest<T>(path, {
  method, ...(body === undefined ? {} : { body: JSON.stringify(body) }), csrf: true
})).data;
const mediaCache = new Map<string, { url: string; expiresAt: number }>();
const mediaRequests = new Map<string, Promise<string>>();
export const supportRepository: SupportRepository = {
  conversations: (search = '', unread = false, page = 1) => read(`${root}/conversations?${queryString({ search, unread, page, pageSize: 50 })}`),
  conversation: id => read(conversation(id)),
  messages: (id, query = {}) => read(`${conversation(id)}/messages?${queryString(query)}`),
  byClient: (id, clientId) => read(`${conversation(id)}/messages/by-client/${encodeURIComponent(clientId)}`),
  send: (id, body) => write(`${conversation(id)}/messages`, 'POST', body),
  read: async (id, messageId) => { await write(`${conversation(id)}/read`, 'POST', { messageId }); },
  hide: async id => { await write(conversation(id), 'DELETE'); }
};
export const supportLibraryRepository = {
  list: (kind: LibraryKind, search = '', page = 1) => read<Page<SupportLibraryItem>>(`${root}/library?${queryString({ kind, search, page, pageSize: 50 })}`),
  create: (body: SupportLibraryWrite) => write<SupportLibraryItem>(`${root}/library`, 'POST', body),
  update: (id: string, body: SupportLibraryWrite) => write<SupportLibraryItem>(`${root}/library/${encodeURIComponent(id)}`, 'PUT', body),
  delete: (item: SupportLibraryItem) => write<void>(`${root}/library/${encodeURIComponent(item.id)}?version=${item.version}`, 'DELETE'),
  upload: async (file: File, kind: 'IMAGE' | 'VIDEO') => {
    if (file.size > (kind === 'IMAGE' ? 10 : 100) * 1024 * 1024 || file.size === 0) throw new Error(kind === 'IMAGE' ? '图片不能超过 10 MB，且不能为空' : '视频不能超过 100 MB，且不能为空');
    const form = new FormData(); form.set('file', file); form.set('kind', kind);
    return (await apiRequest<SupportAttachment>(`${root}/attachments`, { method: 'POST', body: form, csrf: true })).data;
  },
  media: (id: string, refresh = false): Promise<string> => {
    const cached = mediaCache.get(id);
    if (!refresh && cached && cached.expiresAt > Date.now() + 30_000) return Promise.resolve(cached.url);
    const pending = mediaRequests.get(id); if (pending) return pending;
    const request = read<{ url: string; expiresAt: string }>(`${root}/attachments/${encodeURIComponent(id)}/access`).then(data => {
      if (!new RegExp(`^/api/v1/support/files/${id}\\?ticket=[A-Za-z0-9_-]{43}$`).test(data.url)) throw new ApiError(0, 'INVALID_RESPONSE', '媒体地址无效，请重试');
      const value = { url: apiResourceUrl(data.url), expiresAt: Date.parse(data.expiresAt) }; mediaCache.set(id, value); return value.url;
    }).finally(() => mediaRequests.delete(id));
    mediaRequests.set(id, request); return request;
  }
};
