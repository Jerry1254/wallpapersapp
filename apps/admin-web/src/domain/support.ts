export type SupportKind = 'TEXT' | 'IMAGE' | 'VIDEO';
export type LibraryKind = 'IMAGE' | 'VIDEO' | 'PHRASE';
export interface SupportAttachment { id: string; kind: 'IMAGE' | 'VIDEO'; filename: string; mimeType: string; sizeBytes: number; width: number | null; height: number | null; durationMs: number | null }
export interface SupportMessage { id: string; conversationId: string; clientId: string; sender: 'CUSTOMER' | 'ADMIN'; kind: SupportKind; text: string | null; attachment: SupportAttachment | null; createdAt: string }
export interface SupportConversation { id: string; name: string; online: boolean; hidden: boolean; unreadCount: number; lastMessage: SupportMessage | null }
export interface SupportSend { clientId: string; kind: SupportKind; text: string | null; attachmentId: string | null; libraryItemId: string | null }
export interface SupportMessagePage { items: SupportMessage[]; cursor: string; hasMore: boolean }
export interface SupportLibraryItem { id: string; kind: LibraryKind; title: string; note: string; text: string | null; attachment: SupportAttachment | null; version: number; updatedAt: string }
export interface SupportLibraryWrite { kind: LibraryKind; title: string; note: string; text: string | null; attachmentId: string | null; version?: number }
export interface Page<T> { items: T[]; page: number; pageSize: number; total: number }
export interface Draft { text: string; attachment: SupportAttachment | null; libraryItemId: string | null }
export interface PendingSupportMessage { conversationId: string; request: SupportSend; attachment: SupportAttachment | null; state: 'sending' | 'failed' | 'uncertain'; error: string; createdAt: string }
export const compareSupportIds = (a: string, b: string) => BigInt(a) === BigInt(b) ? 0 : BigInt(a) > BigInt(b) ? 1 : -1;
export const supportPreview = (message: SupportMessage | null) => message?.kind === 'IMAGE' ? '[图片]' : message?.kind === 'VIDEO' ? '[视频]' : message?.text || '暂无消息';
export const emptySupportDraft = (): Draft => ({ text: '', attachment: null, libraryItemId: null });
export const pendingMatches = (pending: PendingSupportMessage, message: SupportMessage) => message.sender === 'ADMIN'
  && message.conversationId === pending.conversationId && message.clientId === pending.request.clientId
  && message.kind === pending.request.kind && (message.text || '') === (pending.request.text || '')
  && (message.attachment?.id || null) === pending.request.attachmentId;
