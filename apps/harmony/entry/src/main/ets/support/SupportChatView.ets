import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { QjTokens as T } from '../design/Tokens';
import { SupportAttachment, SupportClient, SupportMessage, SupportPending } from './SupportClient';

@Component
export struct SupportChatView {
  onBack: () => void = () => {};
  onBackup: () => void = () => {};
  @StorageLink('qingjing_app_active') @Watch('applicationChanged') active: boolean = true;
  @State messages: SupportMessage[] = [];
  @State pending: SupportPending[] = [];
  @State text: string = '';
  @State selected: SupportAttachment | null = null;
  @State uploadPath: string = '';
  @State loading: boolean = true;
  @State sending: boolean = false;
  @State uploading: boolean = false;
  @State error: string = '';
  @State storageError: string = '';
  @State older: boolean = false;
  @State urls: Map<string, string> = new Map<string, string>();
  @State previewKind: string = '';
  @State previewId: string = '';
  @State previewUrl: string = '';
  private client: SupportClient | null = null;
  private timer: number = -1;
  private alive: boolean = true;
  private initialized: boolean = false;
  private running: boolean = false;
  private delay: number = 2000;
  private heartbeat: number = 0;
  private scroller: Scroller = new Scroller();
  private bottom: boolean = true;
  private mediaLoading: Set<string> = new Set<string>();
  aboutToAppear(): void {
    this.alive = true;
    this.client = new SupportClient(this.getUIContext().getHostContext() as common.UIAbilityContext);
    this.client.changed = () => this.refresh(); this.poll();
  }
  aboutToDisappear(): void {
    this.alive = false; clearTimeout(this.timer);
    if (this.client !== null) { this.client.changed = () => {}; this.client.presence(false).catch(() => {}); }
  }
  private applicationChanged(): void {
    clearTimeout(this.timer); if (this.active) { this.delay = 2000; this.poll(); } else this.client?.presence(false).catch(() => {});
  }
  private async poll(): Promise<void> {
    clearTimeout(this.timer); if (!this.alive || !this.active || this.running || this.client === null) return; this.running = true;
    try {
      if (!this.initialized) { await this.client.initialize(); this.initialized = true; } else await this.client.sync();
      if (Date.now() - this.heartbeat >= 20000) { await this.client.presence(true); this.heartbeat = Date.now(); }
      if (this.alive) { this.loading = false; this.error = ''; this.refresh(); this.markRead(); } this.delay = 2000;
    } catch (error) { if (this.alive) { this.loading = false; this.error = error instanceof Error ? error.message : '网络暂时不可用'; } this.delay = Math.min(this.delay * 2, 30000); }
    finally { this.running = false; if (this.alive && this.active) this.timer = setTimeout(() => this.poll(), this.delay); }
  }
  private refresh(): void {
    if (!this.alive || this.client === null) return;
    this.messages = this.client.messages.slice(); this.pending = this.client.pending.slice(); this.text = this.client.text;
    this.selected = this.client.attachment; this.uploadPath = this.client.uploadPath; this.older = this.client.hasOlder; this.storageError = this.client.storageError;
    this.messages.forEach((message: SupportMessage) => { if (message.attachment !== null) this.loadMedia(message.attachment.id); });
    this.pending.forEach((message: SupportPending) => { if (message.attachment !== null) this.loadMedia(message.attachment.id); });
    if (this.bottom) setTimeout(() => { if (this.alive) { this.scroller.scrollEdge(Edge.Bottom); this.markRead(); } }, 30);
  }
  private markRead(): void {
    if (!this.bottom || !this.active || this.messages.length === 0 || this.client === null) return;
    this.client.readVisible(this.messages[this.messages.length - 1].id).catch(() => {});
  }
  private async loadMedia(id: string, refresh: boolean = false): Promise<void> {
    if (this.client === null || this.mediaLoading.has(id) || (!refresh && this.urls.has(id))) return; this.mediaLoading.add(id);
    try { const url: string = await this.client.media(id, refresh); if (this.alive) { const next: Map<string, string> = new Map<string, string>(this.urls); next.set(id, url); this.urls = next; } }
    catch (error) { if (this.alive) { const next: Map<string, string> = new Map<string, string>(this.urls); next.set(id, ''); this.urls = next; } }
    finally { this.mediaLoading.delete(id); }
  }
  private async send(): Promise<void> {
    if (this.sending || this.uploading || this.client === null) return; this.sending = true;
    try { this.bottom = true; await this.client.sendDraft(); this.refresh(); } catch (error) { this.error = error instanceof Error ? error.message : '发送失败，请重试'; }
    finally { if (this.alive) this.sending = false; }
  }
  private async pickImage(): Promise<void> {
    if (this.uploading || this.client === null) return; this.uploading = true;
    try {
      const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
      const result: photoAccessHelper.PhotoSelectResult = await picker.select({ MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE, maxSelectNumber: 1 });
      if (this.alive && result.photoUris.length > 0) await this.client.chooseImage(result.photoUris[0]);
    } catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : '图片选择或上传失败，可重试'; }
    finally { if (this.alive) { this.uploading = false; this.refresh(); } }
  }
  private async retryUpload(): Promise<void> {
    if (this.uploading || this.client === null) return; this.uploading = true;
    try { await this.client.uploadImage(); this.error = ''; } catch (error) { this.error = error instanceof Error ? error.message : '图片上传失败'; }
    finally { if (this.alive) { this.uploading = false; this.refresh(); } }
  }
  private async openMedia(attachment: SupportAttachment): Promise<void> {
    if (this.client === null) return; this.previewKind = attachment.kind; this.previewId = attachment.id; this.previewUrl = '';
    try { const url: string = await this.client.media(attachment.id, true); if (this.alive && this.previewId === attachment.id) this.previewUrl = url; }
    catch (error) { this.error = '媒体加载失败，请关闭后重试'; }
  }
  private time(value: string): string { const date: Date = new Date(value); return (date.getMonth() + 1) + '/' + date.getDate() + ' ' + String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0'); }
  @Builder
  private Attachment(attachment: SupportAttachment) {
    if (attachment.kind === 'VIDEO') Button('▶ 播放客服视频').fontSize(14).fontColor(T.ink).backgroundColor(T.accentSoft).onClick(() => this.openMedia(attachment));
    else if ((this.urls.get(attachment.id) ?? '').length > 0) Image(this.urls.get(attachment.id)).width(210).height(160).objectFit(ImageFit.Contain).borderRadius(10).onClick(() => this.openMedia(attachment)).onError(() => { const next: Map<string, string> = new Map<string, string>(this.urls); next.set(attachment.id, ''); this.urls = next; });
    else Button('图片加载中 / 重试').fontSize(12).fontColor(T.mutedInk).backgroundColor(T.surfaceMuted).onClick(() => this.loadMedia(attachment.id, true));
  }
  @Builder
  private Message(message: SupportMessage) {
    Column({ space: 5 }) {
      Text(this.time(message.createdAt)).fontSize(10).fontColor(T.mutedInk);
      Column() { if (message.attachment !== null) this.Attachment(message.attachment as SupportAttachment); else Text(message.text ?? '').fontSize(15).fontColor(T.ink).lineHeight(22).copyOption(CopyOptions.InApp); }
        .padding(12).backgroundColor(message.sender === 'CUSTOMER' ? T.accentSoft : T.surface).borderRadius(13).constraintSize({ maxWidth: '84%' }).border({ width: 1, color: T.outline });
      if (message.sender === 'CUSTOMER') Text('已发送').fontSize(10).fontColor('#548268');
    }.width('100%').alignItems(message.sender === 'CUSTOMER' ? HorizontalAlign.End : HorizontalAlign.Start).margin({ bottom: 18 });
  }
  @Builder
  private Pending(item: SupportPending) {
    Column({ space: 5 }) {
      Text(this.time(item.createdAt)).fontSize(10).fontColor(T.mutedInk);
      Column() { if (item.attachment !== null) this.Attachment(item.attachment as SupportAttachment); else Text(item.request.text ?? '').fontSize(15).fontColor(T.ink).lineHeight(22).copyOption(CopyOptions.InApp); }.padding(12).backgroundColor(T.accentSoft).borderRadius(13).constraintSize({ maxWidth: '84%' });
      Row({ space: 10 }) {
        Text(item.state === 'sending' ? '发送中…' : item.state === 'failed' ? '发送失败' : '发送结果未确认').fontSize(11).fontColor(item.state === 'sending' ? T.mutedInk : '#BB513F');
        if (item.state !== 'sending') Button('核对并重试').fontSize(11).fontColor(T.ink).backgroundColor(Color.Transparent).height(30).onClick(() => this.client?.attempt(item, true));
      }
      if (item.error.length > 0) Text(item.error).fontSize(11).fontColor('#BB513F').constraintSize({ maxWidth: '84%' });
    }.width('100%').alignItems(HorizontalAlign.End).margin({ bottom: 18 });
  }
  build() {
    Stack() {
      Column() {
        Row() { Button('‹').width(42).height(42).fontSize(30).fontColor(T.ink).backgroundColor(Color.Transparent).onClick(this.onBack); Text('在线客服').fontSize(21).fontWeight(FontWeight.Bold).fontColor(T.ink).layoutWeight(1); Button('微信客服').fontSize(12).fontColor(T.mutedInk).backgroundColor(Color.Transparent).onClick(this.onBackup); }.width('100%').padding({ left: 8, right: 8, top: 8, bottom: 8 }).backgroundColor(T.surface);
        if (this.error.length > 0 || this.storageError.length > 0) Row() { Text(this.storageError.length > 0 ? this.storageError : this.error + '，恢复后继续同步').layoutWeight(1).fontSize(12).fontColor('#BB513F'); Button('重连').fontSize(12).fontColor(T.ink).backgroundColor(Color.Transparent).onClick(() => this.poll()); }.width('100%').padding({ left: 16, right: 8 }).backgroundColor(T.accentSoft);
        if (this.loading) Column() { LoadingProgress().width(32).height(32); }.layoutWeight(1).justifyContent(FlexAlign.Center);
        else Scroll(this.scroller) {
          Column() {
            if (this.older) Button('加载更早消息').fontSize(12).fontColor(T.mutedInk).backgroundColor(Color.Transparent).onClick(() => { this.bottom = false; this.client?.loadOlder().catch(() => this.error = '历史记录加载失败，请重试'); });
            if (this.messages.length === 0 && this.pending.length === 0) Text('你好，请描述遇到的问题，我们会在这里回复你。').fontSize(13).fontColor(T.mutedInk).textAlign(TextAlign.Center).padding(26);
            ForEach(this.messages, (message: SupportMessage) => this.Message(message), (message: SupportMessage) => message.id);
            ForEach(this.pending, (item: SupportPending) => this.Pending(item), (item: SupportPending) => item.request.clientId + item.state + item.error);
          }.width('100%').padding(18);
        }.layoutWeight(1).width('100%').onScroll((x: number, y: number) => { if (y < 0) this.bottom = false; }).onScrollEdge((edge: Edge) => { if (edge === Edge.Bottom) { this.bottom = true; this.markRead(); } });
        Column() {
          Row() { Button(this.uploading ? '上传中…' : '＋ 图片').fontSize(13).fontColor(T.ink).backgroundColor(Color.Transparent).enabled(!this.uploading && !this.sending).onClick(() => this.pickImage()); Blank(); Text('发送状态可核对').fontSize(10).fontColor(T.mutedInk); }.width('100%');
          if (this.selected !== null || this.uploadPath.length > 0) Row() {
            Text(this.uploading ? '图片上传中…' : this.uploadPath.length > 0 ? '图片上传失败，可重试' : this.selected?.filename ?? '图片').fontSize(12).fontColor(T.ink).layoutWeight(1).maxLines(1);
            if (this.uploadPath.length > 0) Button('重试上传').fontSize(12).fontColor(T.ink).backgroundColor(Color.Transparent).enabled(!this.uploading).onClick(() => this.retryUpload());
            Button('取消').fontSize(12).fontColor(T.mutedInk).backgroundColor(Color.Transparent).enabled(!this.uploading).onClick(() => { try { this.client?.cancelImage(); } catch (error) { this.error = '无法保存草稿，请重试'; } });
          }.width('100%').padding({ left: 10 }).backgroundColor(T.accentSoft).borderRadius(8);
          Row({ space: 10 }) {
            TextArea({ text: this.text, placeholder: '输入消息…' }).layoutWeight(1).height(72).maxLength(2000).fontSize(15).backgroundColor(T.surfaceMuted).borderRadius(12).enabled(!this.sending).onChange((value: string) => this.client?.saveDraft(value));
            Button(this.sending ? '发送中' : '发送').fontSize(14).fontWeight(FontWeight.Bold).height(42).fontColor(T.ink).backgroundColor(T.accent).borderRadius(12).enabled(!this.sending && !this.uploading && this.uploadPath.length === 0 && this.storageError.length === 0).onClick(() => this.send());
          }.width('100%');
          if (this.selected !== null && this.text.length > 0) Text('本次发送图片，文字会保留').fontSize(11).fontColor(T.mutedInk).margin({ top: 5 });
        }.width('100%').padding({ left: 12, right: 12, bottom: 16 }).backgroundColor(T.surface);
      }.width('100%').height('100%').backgroundColor(T.background);
      if (this.previewKind.length > 0) Column() {
        Row() { Text(this.previewKind === 'VIDEO' ? '客服视频' : '图片预览').fontSize(16).fontColor(Color.White).layoutWeight(1); Button('关闭').fontColor(Color.White).backgroundColor(Color.Transparent).onClick(() => { this.previewKind = ''; this.previewUrl = ''; }); }.width('100%').padding(16);
        if (this.previewUrl.length === 0) LoadingProgress().width(36).height(36).layoutWeight(1);
        else if (this.previewKind === 'VIDEO') Video({ src: this.previewUrl }).width('100%').layoutWeight(1).controls(true).autoPlay(false).objectFit(ImageFit.Contain).onError(() => this.error = '视频加载失败，请关闭后重试');
        else Image(this.previewUrl).width('100%').layoutWeight(1).objectFit(ImageFit.Contain);
      }.width('100%').height('100%').backgroundColor(Color.Black);
    }.width('100%').height('100%');
  }
}
