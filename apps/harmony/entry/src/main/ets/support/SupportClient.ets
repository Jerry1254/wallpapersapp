import { SecurityEvents } from '../security/SecurityEvents';
import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';
import { ApiRequestError, JsonHttp } from '../network/JsonHttp';
import { CatalogApi } from '../network/CatalogApi';
import { InstalledAppVersion } from '../update/AppUpdateEvents';
interface SupportErrorDetail { code?: string; }
interface SupportErrorEnvelope { error?: SupportErrorDetail; }

export interface SupportAttachment {
  id: string; kind: string; filename: string; mimeType: string; sizeBytes: number;
  width: number | null; height: number | null; durationMs: number | null;
}
export interface SupportMessage {
  id: string; conversationId: string; clientId: string; sender: string; kind: string; text: string | null;
  attachment: SupportAttachment | null; createdAt: string;
}
export interface SupportSend { clientId: string; kind: string; text: string | null; attachmentId: string | null; libraryItemId: string | null; }
export interface SupportPending { conversationId: string; request: SupportSend; attachment: SupportAttachment | null; createdAt: string; state: string; error: string; }
interface SupportPage { items: SupportMessage[]; cursor: string; hasMore: boolean; }
interface SupportConversation { id: string; }
interface SupportPresence { active: boolean; }
interface SupportRead { messageId: string; }
interface SupportAccess { url: string; expiresAt: string; }
interface SupportCache { text: string; attachment: SupportAttachment | null; uploadPath: string; pending: SupportPending[]; }
export function compareSupportIds(a: string, b: string): number { return a.length === b.length ? (a === b ? 0 : a > b ? 1 : -1) : a.length > b.length ? 1 : -1; }
export class SupportClient {
  cid: string = ''; messages: SupportMessage[] = []; pending: SupportPending[] = [];
  text: string = ''; attachment: SupportAttachment | null = null; uploadPath: string = '';
  since: string = '0'; older: string = '0'; readThrough: string = '0'; hasOlder: boolean = false;
  private unreadableCache: boolean = false;
  storageError: string = ''; changed: () => void = () => {};
  private context: common.UIAbilityContext;
  private sessions: HarmonyDeviceSession;
  private cachePath: string = '';
  private sending: Set<string> = new Set<string>();
  private mediaCache: Map<string, SupportAccess> = new Map<string, SupportAccess>();
  private reading: boolean = false;
  constructor(context: common.UIAbilityContext) { this.context = context; this.sessions = new HarmonyDeviceSession(context); }
  async initialize(): Promise<void> {
    await this.sessions.accessToken();
    const identity: string = await this.sessions.supportCacheIdentity();
    this.cachePath = this.context.filesDir + '/customer-support-' + identity + '.json';
    try {
      if (fs.accessSync(this.cachePath)) {
        const saved: SupportCache = JSON.parse(fs.readTextSync(this.cachePath)) as SupportCache;
        this.text = saved.text; this.attachment = saved.attachment; this.uploadPath = saved.uploadPath; this.pending = saved.pending;
        this.pending.forEach((item: SupportPending) => { if (item.state === 'sending') item.state = 'uncertain'; });
      }
      this.persist();
    } catch (error) { this.unreadableCache = true; this.storageError = '本机发送记录无法读取，请保留应用数据后重试'; }
    const conversation: SupportConversation = await this.get('/device/support/conversation') as SupportConversation;
    this.cid = conversation.id;
    const page: SupportPage = await this.get('/device/support/messages?limit=50') as SupportPage;
    this.merge(page.items); this.since = page.items.length > 0 ? page.items[page.items.length - 1].id : '0';
    this.older = page.cursor; this.hasOlder = page.hasMore; this.changed(); await this.recover();
  }
  persist(): void {
    if (this.unreadableCache) throw new Error(this.storageError);
    if (this.cachePath.length === 0) throw new Error('本机发送记录尚未就绪');
    const cache: SupportCache = { text: this.text, attachment: this.attachment, uploadPath: this.uploadPath, pending: this.pending };
    const temp: string = this.cachePath + '.next';
    try {
      const file: fs.File = fs.openSync(temp, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try { fs.writeSync(file.fd, JSON.stringify(cache)); fs.fsyncSync(file.fd); } finally { fs.closeSync(file); }
      fs.renameSync(temp, this.cachePath); this.storageError = '';
    } catch (error) { this.storageError = '本机发送记录保存失败，请检查存储空间后重试'; throw new Error(this.storageError); }
  }
  saveDraft(text: string): void { this.text = text; try { this.persist(); } catch (error) {} this.changed(); }
  async get(path: string): Promise<Object> {
    for (let attempt: number = 0; attempt < 2; attempt++) {
      const token: string = await this.sessions.accessToken();
      try { return await JsonHttp.request(CatalogApi.BASE_URL + path, http.RequestMethod.GET, null, token); }
      catch (error) { if (error instanceof ApiRequestError && error.status === 401 && attempt === 0) { this.sessions.invalidate(); continue; } if (error instanceof Error) throw error; throw new Error('网络连接失败'); }
    }
    throw new Error('设备验证失败，请重新连接');
  }
  async presence(active: boolean): Promise<void> { const body: SupportPresence = { active: active }; await this.sessions.signedPost('/device/support/presence', body); }
  private matches(item: SupportPending, message: SupportMessage): boolean {
    return message.sender === 'CUSTOMER' && message.conversationId === item.conversationId && message.clientId === item.request.clientId &&
      message.kind === item.request.kind && (message.text ?? '') === (item.request.text ?? '') && (message.attachment?.id ?? null) === item.request.attachmentId;
  }
  private merge(messages: SupportMessage[]): void {
    const items: Map<string, SupportMessage> = new Map<string, SupportMessage>();
    this.messages.forEach((item: SupportMessage) => items.set(item.id, item));
    messages.forEach((item: SupportMessage) => { if (item.conversationId === this.cid) items.set(item.id, item); });
    this.messages = Array.from(items.values()).sort((a: SupportMessage, b: SupportMessage) => compareSupportIds(a.id, b.id));
    const previous: number = this.pending.length;
    this.pending = this.pending.filter((item: SupportPending) => !messages.some((message: SupportMessage) => this.matches(item, message)));
    if (previous !== this.pending.length) { try { this.persist(); } catch (error) {} }
    this.changed();
  }
  async sync(): Promise<void> {
    for (let batch: number = 0; batch < 5; batch++) {
      const page: SupportPage = await this.get('/device/support/messages?after=' + this.since + '&limit=100') as SupportPage;
      this.merge(page.items); if (compareSupportIds(page.cursor, this.since) > 0) this.since = page.cursor;
      if (!page.hasMore) break;
    }
    await this.recover();
  }
  async loadOlder(): Promise<void> {
    if (!this.hasOlder) return;
    const page: SupportPage = await this.get('/device/support/messages?before=' + this.older + '&limit=50') as SupportPage;
    this.merge(page.items); this.older = page.cursor; this.hasOlder = page.hasMore; this.changed();
  }
  async readVisible(messageId: string): Promise<void> {
    if (this.reading) return;
    const position: string = compareSupportIds(messageId, this.since) > 0 ? this.since : messageId;
    if (position === '0' || compareSupportIds(position, this.readThrough) <= 0) return;
    const body: SupportRead = { messageId: position }; this.reading = true;
    try { await this.sessions.signedPost('/device/support/read', body); this.readThrough = position; } finally { this.reading = false; }
  }
  async recover(): Promise<void> {
    for (const item of this.pending.slice()) {
      if (item.state !== 'uncertain' || item.conversationId !== this.cid || this.sending.has(item.request.clientId)) continue;
      try { const message: SupportMessage = await this.get('/device/support/messages/by-client/' + item.request.clientId) as SupportMessage; if (this.matches(item, message)) this.merge([message]); }
      catch (error) {}
    }
  }
  async sendDraft(): Promise<void> {
    if (this.storageError.length > 0) throw new Error(this.storageError);
    const text: string = this.text.trim();
    if (this.attachment === null && (text.length === 0 || text.length > 2000)) throw new Error('请输入消息，不能超过 2000 字');
    const body: SupportSend = { clientId: util.generateRandomUUID(), kind: this.attachment === null ? 'TEXT' : 'IMAGE', text: this.attachment === null ? text : null,
      attachmentId: this.attachment?.id ?? null, libraryItemId: null };
    const pending: SupportPending = { conversationId: this.cid, request: body, attachment: this.attachment, state: 'sending', error: '', createdAt: new Date().toISOString() };
    const oldText: string = this.text; const oldAttachment: SupportAttachment | null = this.attachment;
    this.pending.push(pending); if (this.attachment === null) this.text = ''; this.attachment = null;
    try { this.persist(); } catch (error) { this.pending.pop(); this.text = oldText; this.attachment = oldAttachment; if (error instanceof Error) throw error; throw new Error('本机保存失败'); }
    this.changed(); await this.attempt(pending, false);
  }
  async attempt(item: SupportPending, retry: boolean): Promise<void> {
    const key: string = item.request.clientId; if (this.sending.has(key)) return; this.sending.add(key);
    item.state = 'sending'; item.error = ''; this.changed();
    try {
      this.persist();
      if (retry) {
        try { const prior: SupportMessage = await this.get('/device/support/messages/by-client/' + key) as SupportMessage;
          if (!this.matches(item, prior)) throw new Error('消息标识已用于不同内容'); this.merge([prior]); return;
        } catch (error) { if (!(error instanceof ApiRequestError && error.status === 404)) { if (error instanceof Error) throw error; throw new Error('无法核对消息，请重试'); } }
      }
      const message: SupportMessage = await this.sessions.signedPost('/device/support/messages', item.request) as SupportMessage;
      if (!this.matches(item, message)) throw new Error('发送结果未确认，请核对或重试');
      this.merge([message]);
    } catch (error) {
      item.state = error instanceof ApiRequestError && error.status >= 400 && error.status < 500 ? 'failed' : 'uncertain';
      item.error = error instanceof Error ? error.message : '网络连接失败，请核对或重试'; try { this.persist(); } catch (saveError) {} this.changed();
    } finally { this.sending.delete(key); }
  }
  async media(id: string, refresh: boolean = false): Promise<string> {
    const cached: SupportAccess | undefined = this.mediaCache.get(id);
    if (!refresh && cached !== undefined && Date.parse(cached.expiresAt) > Date.now() + 30000) return CatalogApi.BASE_URL.replace('/api/v1', '') + cached.url;
    const result: SupportAccess = await this.get('/device/support/attachments/' + id + '/access') as SupportAccess;
    if (!new RegExp('^/api/v1/support/files/' + id + '\\?ticket=[A-Za-z0-9_-]{43}$').test(result.url)) throw new Error('媒体地址无效，请重试');
    this.mediaCache.set(id, result); return CatalogApi.BASE_URL.replace('/api/v1', '') + result.url;
  }
  async chooseImage(uri: string): Promise<void> {
    const selected: fs.File = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    const path: string = this.context.filesDir + '/support-upload-' + util.generateRandomUUID() + '.jpg';
    try { const size: number = fs.statSync(selected.fd).size; if (size <= 0 || size > 10 * 1024 * 1024) throw new Error('图片不能为空，且不能超过 10 MB'); fs.copyFileSync(selected.fd, path); }
    finally { fs.closeSync(selected); }
    this.uploadPath = path; this.attachment = null; this.persist(); this.changed(); await this.uploadImage();
  }
  async uploadImage(): Promise<void> {
    SecurityEvents.requireAllowed(CatalogApi.BASE_URL + '/device/support/attachments');
    const request: http.HttpRequest = http.createHttp();
    try {
      const token: string = await this.sessions.accessToken();
      const headers: Record<string, string> = InstalledAppVersion.headers();
      headers['Authorization'] = 'Bearer ' + token; headers['Content-Type'] = 'multipart/form-data';
      const response: http.HttpResponse = await request.request(CatalogApi.BASE_URL + '/device/support/attachments', {
        method: http.RequestMethod.POST, header: headers,
        multiFormDataList: [{ name: 'file', contentType: 'application/octet-stream', remoteFileName: 'support-image', filePath: this.uploadPath }],
        connectTimeout: 15000, readTimeout: 60000, expectDataType: http.HttpDataType.STRING, usingCache: false
      });
      if (response.responseCode !== 201) {
        const errorBody: SupportErrorEnvelope = JSON.parse(response.result as string) as SupportErrorEnvelope;
        if (errorBody.error?.code === 'ACCESS_UNAVAILABLE') {
          SecurityEvents.notifyBlocked();
          throw new Error('网络异常，请稍后重试');
        }
        throw new Error('图片上传失败，请检查格式或网络后重试');
      }
      this.attachment = JSON.parse(response.result as string) as SupportAttachment;
      const old: string = this.uploadPath; this.uploadPath = ''; this.persist(); try { fs.unlinkSync(old); } catch (error) {} this.changed();
    } finally { request.destroy(); }
  }
  cancelImage(): void { const path: string = this.uploadPath; this.uploadPath = ''; this.attachment = null; this.persist(); if (path.length > 0) { try { fs.unlinkSync(path); } catch (error) {} } this.changed(); }
}
