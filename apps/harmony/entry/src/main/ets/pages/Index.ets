import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { QjTokens as T } from '../design/Tokens';
import {
  HarmonyCatalogProjection,
  WallpaperCapability,
  WallpaperCategory,
  WallpaperSummary,
  WallpaperTutorial
} from '../model/CatalogModels';
import { CatalogApi } from '../network/CatalogApi';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';
import { CustomerSupport } from '../support/CustomerSupport';
import { DownloadedMovingPhoto, DownloadedStaticImage, HarmonyDelivery } from '../media/HarmonyDelivery';

interface FailureWithMessage {
  message: string;
}

const LOG_DOMAIN: number = 0x514A;
const DELIVERY_PREFERENCES: string = 'qingjing_delivery_state';
const SAVED_MOVING_PHOTO_KEYS: string = 'saved_moving_photo_keys';
const DETAIL_PREVIEW_SECONDS: number = 2;

@Entry
@Component
struct Index {
  @State activeTab: number = 0;
  @State page: string = 'HOME';
  @State returnPage: string = 'HOME';
  @State selectedFilter: string = HarmonyCatalogProjection.FILTER_FEATURED;
  @State categories: WallpaperCategory[] = [];
  @State wallpapers: WallpaperSummary[] = [];
  @State entitlements: WallpaperSummary[] = [];
  @State tutorials: WallpaperTutorial[] = [];
  @State selectedDetail: WallpaperSummary | null = null;
  @State selectedCapability: string = '';
  @State selectedTutorial: WallpaperTutorial | null = null;
  @State currentTitle: string = '';
  @State currentQuery: string = '';
  @State currentCategoryId: string = '';
  @State searchText: string = '';
  @State loading: boolean = false;
  @State errorMessage: string = '';
  @State statusMessage: string = '';
  @State qrPreview: boolean = false;
  @State customerDialogVisible: boolean = false;
  @State detailOwned: boolean = false;
  @State redemptionVisible: boolean = false;
  @State redemptionCode: string = '';
  @State redemptionMessage: string = '';
  @State redemptionBusy: boolean = false;
  @State deliveryVisible: boolean = false;
  @State deliveryMessage: string = '';
  @State deliveryProgress: number = 0;
  @State deliveryReady: boolean = false;
  @State deliveryJustRedeemed: boolean = false;
  @State savedDeliveryKeys: string[] = [];
  @State detailSavedNowKey: string = '';
  @State detailPreviewPath: string = '';
  @State detailPreviewLoading: boolean = false;
  @State detailPreviewEnded: boolean = false;
  @State detailPreviewError: string = '';
  private context: common.UIAbilityContext | null = null;
  private api: CatalogApi | null = null;
  private videoController: VideoController = new VideoController();
  private detailVideoController: VideoController = new VideoController();
  private detailPreviewGeneration: number = 0;
  private pendingMovingPhoto: DownloadedMovingPhoto | null = null;
  private pendingDeliveryKey: string = '';
  private pendingStaticImage: DownloadedStaticImage | null = null;
  private initialLoadStarted: boolean = false;
  private pageVisible: boolean = false;

  aboutToAppear(): void {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      this.errorMessage = '无法初始化应用环境';
      return;
    }
    this.context = host as common.UIAbilityContext;
    const sessions: HarmonyDeviceSession = new HarmonyDeviceSession(this.context as common.UIAbilityContext);
    this.api = new CatalogApi(sessions);
    this.startInitialLoad();
  }

  onPageShow(): void {
    this.pageVisible = true;
    this.startInitialLoad();
  }

  private startInitialLoad(): void {
    if (!this.pageVisible || this.initialLoadStarted || this.api === null) return;
    this.initialLoadStarted = true;
    this.loadSavedDeliveryKeys();
    this.loadHome();
  }

  private async loadHome(): Promise<void> {
    if (this.api === null) return;
    this.loading = true;
    this.errorMessage = '';
    try {
      const items: WallpaperSummary[] = await (this.api as CatalogApi).wallpapers(this.selectedFilter);
      this.wallpapers = items.slice();
    } catch (error) {
      this.errorMessage = this.messageFor(error);
      hilog.error(LOG_DOMAIN, 'QingjingApi', 'Home load failed: %{public}s', this.errorMessage);
      this.wallpapers = [];
    } finally {
      this.loading = false;
    }
    if (this.categories.length > 0) return;
    try {
      const items: WallpaperCategory[] = await (this.api as CatalogApi).categories();
      this.categories = items.slice();
    } catch (error) {
      hilog.error(LOG_DOMAIN, 'QingjingApi', 'Category load failed: %{public}s', this.messageFor(error));
    }
  }

  private async loadWallpapers(filter: string, query: string = '', categoryId: string = ''): Promise<void> {
    if (this.api === null) return;
    this.loading = true;
    this.errorMessage = '';
    try {
      this.wallpapers = await (this.api as CatalogApi).wallpapers(filter, query, categoryId);
    } catch (error) {
      this.errorMessage = this.messageFor(error);
      this.wallpapers = [];
    } finally {
      this.loading = false;
    }
  }

  private async loadEntitlements(): Promise<void> {
    if (this.api === null) return;
    this.loading = true;
    this.errorMessage = '';
    try {
      this.entitlements = await (this.api as CatalogApi).entitlements();
    } catch (error) {
      this.errorMessage = this.messageFor(error);
      this.entitlements = [];
    } finally {
      this.loading = false;
    }
  }

  private async loadTutorials(): Promise<void> {
    if (this.api === null) return;
    this.loading = true;
    this.errorMessage = '';
    try {
      this.tutorials = await (this.api as CatalogApi).tutorials();
    } catch (error) {
      this.errorMessage = this.messageFor(error);
      this.tutorials = [];
    } finally {
      this.loading = false;
    }
  }

  private async openDetail(item: WallpaperSummary): Promise<void> {
    this.clearDetailPreview();
    this.detailSavedNowKey = '';
    this.selectedDetail = item;
    this.selectedCapability = item.capabilities[0].label;
    this.detailOwned = item.free;
    this.page = 'DETAIL';
    this.statusMessage = '';
    if (this.api === null) return;
    try {
      const detail: WallpaperSummary = await (this.api as CatalogApi).wallpaper(item.id);
      if (this.page !== 'DETAIL' || this.selectedDetail === null ||
        (this.selectedDetail as WallpaperSummary).id !== item.id) return;
      this.selectedDetail = detail;
      this.selectedCapability = detail.capabilities[0].label;
      const preview: Promise<void> = this.prepareDetailPreview(detail);
      this.detailOwned = detail.free || await (this.api as CatalogApi).owns(detail.id);
      await preview;
    } catch (error) {
      this.statusMessage = this.messageFor(error);
    }
  }

  private async openTutorials(fromPage: string): Promise<void> {
    this.returnPage = fromPage;
    this.page = 'TUTORIALS';
    this.selectedTutorial = null;
    await this.loadTutorials();
  }

  private async openDetailTutorial(): Promise<void> {
    if (this.selectedDetail === null) return;
    const selected: WallpaperCapability = this.selectedCapabilityFor(this.selectedDetail as WallpaperSummary);
    const expectedKey: string = selected.resourceType === 'MOVING_PHOTO' ? 'HARMONYOS_DYNAMIC' : 'STATIC';
    if (this.tutorials.length === 0) await this.loadTutorials();
    const tutorial: WallpaperTutorial | undefined = this.tutorials.find(
      (item: WallpaperTutorial) => item.key === expectedKey);
    if (tutorial === undefined) {
      this.toast('对应的设置教程暂未发布');
      return;
    }
    this.returnPage = 'DETAIL';
    this.selectedTutorial = tutorial;
    if (this.detailPreviewPath.length > 0) {
      this.detailVideoController.stop();
      this.detailPreviewEnded = true;
    }
    this.page = 'TUTORIAL_PLAYER';
  }

  private openCustomer(fromPage: string): void {
    this.returnPage = fromPage;
    this.page = 'CUSTOMER';
    this.qrPreview = false;
  }

  private goBack(): void {
    if (this.page === 'DETAIL') {
      this.clearDetailPreview();
      this.page = this.activeTab === 0 ? 'HOME' : 'MINE';
      this.selectedDetail = null;
      return;
    }
    this.page = this.returnPage;
    this.selectedTutorial = null;
  }

  private async copyWechat(): Promise<void> {
    try {
      await CustomerSupport.copyWechat();
      this.toast('客服微信已复制');
    } catch (error) {
      this.toast('复制失败，请手动复制微信号');
    }
  }

  private async saveQr(): Promise<void> {
    if (this.context === null) return;
    try {
      await CustomerSupport.saveQr(this.context as common.UIAbilityContext);
      this.toast('二维码已保存，请检查图库');
    } catch (error) {
      this.toast('保存二维码失败，请重试');
    }
  }

  private selectedCapabilityFor(item: WallpaperSummary): WallpaperCapability {
    const matched: WallpaperCapability | undefined = item.capabilities.find((capability: WallpaperCapability) =>
      capability.label === this.selectedCapability);
    return matched === undefined ? item.capabilities[0] : matched;
  }

  private selectDetailCapability(item: WallpaperSummary, capability: WallpaperCapability): void {
    this.selectedCapability = capability.label;
    this.statusMessage = '';
    this.detailSavedNowKey = '';
    this.prepareDetailPreview(item);
  }

  private async prepareDetailPreview(item: WallpaperSummary): Promise<void> {
    this.clearDetailPreview();
    const capability: WallpaperCapability = this.selectedCapabilityFor(item);
    if (capability.deliveryPlatform !== 'HARMONYOS' || capability.resourceType !== 'MOVING_PHOTO' ||
      this.api === null || this.context === null) return;
    const generation: number = this.detailPreviewGeneration;
    this.detailPreviewLoading = true;
    this.detailPreviewError = '';
    try {
      const descriptor = await (this.api as CatalogApi).previewTicket(item.id, capability);
      const path: string = await HarmonyDelivery.downloadMovingPhotoPreview(
        this.context as common.UIAbilityContext, descriptor, item.id);
      if (generation !== this.detailPreviewGeneration || this.page !== 'DETAIL') {
        await HarmonyDelivery.discardPreview(path);
        return;
      }
      this.detailPreviewPath = path;
      this.detailPreviewLoading = false;
      this.detailPreviewEnded = false;
    } catch (error) {
      if (generation === this.detailPreviewGeneration) {
        this.detailPreviewLoading = false;
        this.detailPreviewError = '动态效果暂时无法加载';
      }
    }
  }

  private replayDetailPreview(): void {
    if (this.detailPreviewPath.length === 0) return;
    this.detailPreviewEnded = false;
    this.detailPreviewError = '';
    this.detailVideoController.pause();
    this.detailVideoController.setCurrentTime(0);
    this.detailVideoController.start();
  }

  private finishDetailPreview(): void {
    if (this.detailPreviewPath.length === 0 || this.detailPreviewEnded) return;
    this.detailVideoController.pause();
    this.detailPreviewEnded = true;
  }

  private scheduleDetailPreviewFinish(): void {
    const generation: number = this.detailPreviewGeneration;
    const path: string = this.detailPreviewPath;
    if (path.length === 0) return;
    setTimeout(() => {
      if (generation === this.detailPreviewGeneration && path === this.detailPreviewPath) {
        this.finishDetailPreview();
      }
    }, DETAIL_PREVIEW_SECONDS * 1000);
  }

  private clearDetailPreview(): void {
    this.detailPreviewGeneration += 1;
    this.detailVideoController.stop();
    const previous: string = this.detailPreviewPath;
    this.detailPreviewPath = '';
    this.detailPreviewLoading = false;
    this.detailPreviewEnded = false;
    this.detailPreviewError = '';
    if (previous.length > 0) HarmonyDelivery.discardPreview(previous);
  }

  private deliveryKey(item: WallpaperSummary, capability: WallpaperCapability): string {
    return item.id + ':' + capability.deliveryPlatform + ':' + capability.resourceType;
  }

  private detailActionLabel(item: WallpaperSummary): string {
    const capability: WallpaperCapability = this.selectedCapabilityFor(item);
    const key: string = this.deliveryKey(item, capability);
    if (this.detailSavedNowKey === key) return '已保存到相册，请设置';
    if (this.savedDeliveryKeys.indexOf(key) >= 0) return '再次下载';
    return item.free || this.detailOwned ? '下载壁纸' : '兑换并下载';
  }

  private async primaryDetailAction(item: WallpaperSummary): Promise<void> {
    const key: string = this.deliveryKey(item, this.selectedCapabilityFor(item));
    if (this.detailSavedNowKey === key) {
      await this.openDetailTutorial();
      return;
    }
    await this.detailAction(item);
  }

  private async loadSavedDeliveryKeys(): Promise<void> {
    if (this.context === null) return;
    try {
      const store: preferences.Preferences = await preferences.getPreferences(
        this.context as common.UIAbilityContext, DELIVERY_PREFERENCES);
      const raw: string = String(await store.get(SAVED_MOVING_PHOTO_KEYS, ''));
      this.savedDeliveryKeys = raw.length > 0 ? raw.split('|') : [];
    } catch (error) {
      this.savedDeliveryKeys = [];
    }
  }

  private async rememberSavedDelivery(key: string): Promise<void> {
    if (key.length === 0 || this.savedDeliveryKeys.indexOf(key) >= 0) return;
    const updated: string[] = [];
    this.savedDeliveryKeys.forEach((item: string) => updated.push(item));
    updated.push(key);
    this.savedDeliveryKeys = updated;
    if (this.context === null) return;
    try {
      const store: preferences.Preferences = await preferences.getPreferences(
        this.context as common.UIAbilityContext, DELIVERY_PREFERENCES);
      await store.put(SAVED_MOVING_PHOTO_KEYS, updated.join('|'));
      await store.flush();
    } catch (error) {
    }
  }

  private messageFor(error: Object): string {
    if (error instanceof Error && error.message.length > 0) return error.message;
    const failure: FailureWithMessage = error as FailureWithMessage;
    if (failure.message !== undefined && failure.message.length > 0) return failure.message;
    return '暂时无法加载，请稍后重试';
  }

  private retryCurrentPage(): void {
    if (this.page === 'MINE') {
      this.loadEntitlements();
    } else if (this.page === 'TUTORIALS') {
      this.loadTutorials();
    } else if (this.page === 'SEARCH' || this.page === 'CATEGORY') {
      this.loadWallpapers(HarmonyCatalogProjection.FILTER_FEATURED, this.currentQuery, this.currentCategoryId);
    } else {
      this.loadHome();
    }
  }

  private toast(message: string): void {
    this.getUIContext().getPromptAction().showToast({ message: message });
  }

  private mediaUrl(path: string): string {
    return this.api === null ? path : (this.api as CatalogApi).mediaUrl(path);
  }

  private openRedemption(): void {
    this.redemptionCode = '';
    this.redemptionMessage = '';
    this.redemptionVisible = true;
  }

  private async detailAction(item: WallpaperSummary): Promise<void> {
    if (this.deliveryVisible || this.redemptionBusy) return;
    if (!item.free && !this.detailOwned) {
      this.openRedemption();
      return;
    }
    await this.downloadSelected(item, false);
  }

  private async redeemAndDownload(): Promise<void> {
    if (this.api === null || this.selectedDetail === null || this.redemptionBusy) return;
    this.redemptionBusy = true;
    this.redemptionMessage = '';
    try {
      const result: string = await (this.api as CatalogApi).redeem(
        (this.selectedDetail as WallpaperSummary).id, this.redemptionCode);
      this.detailOwned = true;
      this.redemptionVisible = false;
      this.toast(result);
      await this.downloadSelected(this.selectedDetail as WallpaperSummary, true);
    } catch (error) {
      this.redemptionMessage = this.messageFor(error);
    } finally {
      this.redemptionBusy = false;
    }
  }

  private async downloadSelected(item: WallpaperSummary, justRedeemed: boolean): Promise<void> {
    if (this.api === null || this.context === null) return;
    const capability: WallpaperCapability = this.selectedCapabilityFor(item);
    this.deliveryVisible = true;
    this.deliveryReady = false;
    this.deliveryJustRedeemed = justRedeemed;
    this.deliveryMessage = '正在准备壁纸资源';
    this.deliveryProgress = 0;
    this.statusMessage = '';
    this.pendingDeliveryKey = this.deliveryKey(item, capability);
    try {
      const descriptor = await (this.api as CatalogApi).downloadTicket(item.id, capability);
      this.deliveryMessage = '正在下载壁纸资源，请勿关闭或切换到别的 App';
      const updateProgress = (received: number, total: number) => {
        this.deliveryProgress = total > 0 ? Math.min(100, Math.floor(received * 100 / total)) : 0;
      };
      if (capability.resourceType === 'STATIC_IMAGE') {
        this.pendingStaticImage = await HarmonyDelivery.downloadStaticImage(
          this.context as common.UIAbilityContext, descriptor, item.title, updateProgress);
      } else {
        this.pendingMovingPhoto = await HarmonyDelivery.downloadMovingPhoto(
          this.context as common.UIAbilityContext, descriptor, item.title, updateProgress);
      }
      this.deliveryReady = true;
      this.deliveryMessage = '资源已下载并校验，请点击保存到图库';
    } catch (error) {
      this.pendingDeliveryKey = '';
      this.deliveryVisible = false;
      this.deliveryReady = false;
      this.statusMessage = this.messageFor(error);
      this.toast(this.statusMessage);
    }
  }

  private async saveDownloadedWallpaper(): Promise<void> {
    if (this.context === null || (this.pendingMovingPhoto === null && this.pendingStaticImage === null)) return;
    const savedKey: string = this.pendingDeliveryKey;
    this.deliveryReady = false;
    this.deliveryMessage = this.pendingStaticImage === null ? '正在保存鸿蒙动态照片' : '正在保存静态原图';
    try {
      if (this.pendingStaticImage !== null) {
        await HarmonyDelivery.saveStaticImage(this.context as common.UIAbilityContext,
          this.pendingStaticImage as DownloadedStaticImage);
        this.pendingStaticImage = null;
      } else {
        await HarmonyDelivery.saveDownloaded(this.context as common.UIAbilityContext,
          this.pendingMovingPhoto as DownloadedMovingPhoto);
        this.pendingMovingPhoto = null;
      }
      this.pendingDeliveryKey = '';
      await this.rememberSavedDelivery(savedKey);
      this.detailSavedNowKey = savedKey;
      this.deliveryVisible = false;
      this.statusMessage = '';
      this.toast('已保存到相册，请设置');
    } catch (error) {
      this.pendingMovingPhoto = null;
      this.pendingStaticImage = null;
      this.pendingDeliveryKey = '';
      this.deliveryVisible = false;
      this.statusMessage = this.messageFor(error);
      this.toast(this.statusMessage);
    }
  }

  private async cancelDownloadedWallpaper(): Promise<void> {
    if (this.pendingMovingPhoto !== null) {
      await HarmonyDelivery.discard(this.pendingMovingPhoto as DownloadedMovingPhoto);
      this.pendingMovingPhoto = null;
    }
    if (this.pendingStaticImage !== null) {
      await HarmonyDelivery.discardStaticImage(this.pendingStaticImage as DownloadedStaticImage);
      this.pendingStaticImage = null;
    }
    this.pendingDeliveryKey = '';
    this.deliveryVisible = false;
    this.deliveryReady = false;
  }

  @Builder
  private BrandHeader(title: string = '倾境') {
    Row() {
      Text(title)
        .fontSize(28)
        .fontWeight(FontWeight.Bolder)
        .fontColor(T.ink)
        .layoutWeight(1);
      Button() {
        Row({ space: 7 }) {
          Image($r('app.media.message_circle_more')).width(20).height(20).objectFit(ImageFit.Contain);
          Text('客服').fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.inverseInk);
        }
      }
        .height(44)
        .padding({ left: 16, right: 16 })
        .backgroundColor(T.navigation)
        .borderRadius(22)
        .onClick(() => this.openCustomer(this.page));
    }
    .width('100%')
    .height(T.topBarHeight)
    .margin({ bottom: 18 });
  }

  @Builder
  private PageHeader(title: string, showService: boolean = false) {
    Row() {
      Button() {
        Image($r('app.media.chevron_left')).width(20).height(20).objectFit(ImageFit.Contain);
      }
        .width(44)
        .height(44)
        .padding(0)
        .backgroundColor(T.surface)
        .border({ width: 1, color: T.outline })
        .borderRadius(22)
        .onClick(() => this.goBack());
      Text(title)
        .fontSize(22)
        .fontWeight(FontWeight.Bolder)
        .fontColor(T.ink)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .margin({ left: 8 });
      if (showService) {
        Button() {
          Row({ space: 6 }) {
            Image($r('app.media.message_circle_more')).width(18).height(18).objectFit(ImageFit.Contain);
            Text('客服').fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.inverseInk);
          }
        }
          .height(44)
          .padding({ left: 14, right: 14 })
          .backgroundColor(T.navigation)
          .borderRadius(22)
          .onClick(() => this.openCustomer(this.page));
      }
    }
    .width('100%')
    .height(T.topBarHeight);
  }

  @Builder
  private SearchBar() {
    Row() {
      Text('⌕')
        .fontSize(22)
        .fontColor(T.mutedInk);
      TextInput({ text: this.searchText, placeholder: '搜索壁纸名称' })
        .fontSize(14)
        .height(40)
        .backgroundColor(Color.Transparent)
        .layoutWeight(1)
        .onChange((value: string) => this.searchText = value);
      Button('搜索')
        .height(40)
        .padding({ left: 17, right: 17 })
        .fontSize(14)
        .fontWeight(FontWeight.Bold)
        .fontColor(T.inverseInk)
        .backgroundColor(T.accentStrong)
        .borderRadius(T.controlRadius)
        .onClick(() => {
          const query: string = this.searchText.trim();
          if (query.length === 0) return;
          this.currentTitle = '“' + query + '”';
          this.currentQuery = query;
          this.currentCategoryId = '';
          this.page = 'SEARCH';
          this.loadWallpapers(HarmonyCatalogProjection.FILTER_FEATURED, query);
        });
    }
    .width('100%')
    .height(50)
    .padding({ left: 15, right: 5 })
    .backgroundColor(T.surface)
    .border({ width: 1, color: T.outline })
    .borderRadius(18)
    .shadow({ radius: 24, color: '#1231281F', offsetY: 8 });
  }

  @Builder
  private FilterChip(label: string) {
    Button(label)
      .height(38)
      .padding({ left: 16, right: 16 })
      .fontSize(13)
      .fontWeight(FontWeight.Bold)
      .fontColor(this.selectedFilter === label ? T.inverseInk : T.ink)
      .backgroundColor(this.selectedFilter === label ? T.navigation : T.surface)
      .border({ width: 1, color: this.selectedFilter === label ? T.navigation : T.outlineStrong })
      .borderRadius(19)
      .margin({ right: 8 })
      .onClick(() => {
        this.selectedFilter = label;
        this.loadWallpapers(label);
      });
  }

  @Builder
  private CapabilityBadge(label: string, accent: boolean = false) {
    Text(label)
      .fontSize(10)
      .fontWeight(FontWeight.Bold)
      .fontColor(accent ? T.ink : T.inverseInk)
      .backgroundColor(accent ? T.accent : '#99191817')
      .padding({ left: 8, right: 8, top: 5, bottom: 5 })
      .borderRadius(12);
  }

  private thumbnailCapabilities(item: WallpaperSummary): WallpaperCapability[] {
    return item.capabilities.filter((capability: WallpaperCapability) => capability.resourceType !== 'STATIC_IMAGE');
  }

  private thumbnailCapabilityLabel(capability: WallpaperCapability): string {
    if (capability.resourceType === 'LAYER_PARALLAX') return '4D壁纸';
    return '动态壁纸';
  }

  @Builder
  private WallpaperCard(item: WallpaperSummary) {
    Column() {
      Stack({ alignContent: Alignment.TopStart }) {
        Image(this.mediaUrl(item.coverUrl))
          .width('100%')
          .height(T.catalogMediaHeight)
          .objectFit(ImageFit.Cover)
          .backgroundColor(T.surfaceMuted);
        Row({ space: 5 }) {
          ForEach(this.thumbnailCapabilities(item), (capability: WallpaperCapability) => {
            this.CapabilityBadge(this.thumbnailCapabilityLabel(capability));
          }, (capability: WallpaperCapability) => capability.deliveryPlatform + capability.resourceType);
          if (item.free) this.CapabilityBadge('免费', true);
        }
        .padding(10);
      }
      .width('100%')
      .height(T.catalogMediaHeight)
      .borderRadius(T.mediaRadius)
      .clip(true)
      .shadow({ radius: 42, color: '#1A31281F', offsetY: 16 });
      Text(item.title)
        .width('100%')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor(T.ink)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 12 });
    }
    .width('100%')
    .onClick(() => this.openDetail(item));
  }

  @Builder
  private LoadingOrState(emptyText: string) {
    if (this.loading) {
      Column({ space: 12 }) {
        LoadingProgress().width(36).height(36).color(T.accentStrong);
        Text('正在加载').fontSize(13).fontColor(T.mutedInk);
      }
      .width('100%')
      .padding({ top: 70, bottom: 70 });
    } else if (this.errorMessage.length > 0) {
      Column({ space: 12 }) {
        Text('暂时无法加载').fontSize(18).fontWeight(FontWeight.Bold).fontColor(T.ink);
        Text(this.errorMessage)
          .width('100%').fontSize(13).fontColor(T.mutedInk).textAlign(TextAlign.Center);
        Button('重新加载')
          .height(44)
          .fontWeight(FontWeight.Bold)
          .fontColor(T.inverseInk)
          .backgroundColor(T.navigation)
          .borderRadius(22)
          .onClick(() => this.retryCurrentPage());
      }
      .width('100%')
      .padding({ left: 24, right: 24, top: 54, bottom: 54 })
      .backgroundColor(T.surfaceMuted)
      .borderRadius(T.cardRadius);
    } else {
      Column({ space: 8 }) {
        Text(emptyText).fontSize(18).fontWeight(FontWeight.Bold).fontColor(T.ink);
        Text('下拉刷新或稍后再试').fontSize(13).fontColor(T.mutedInk);
      }
      .width('100%')
      .padding({ top: 60, bottom: 60 })
      .backgroundColor(T.surfaceMuted)
      .borderRadius(T.cardRadius);
    }
  }

  @Builder
  private CatalogWallpaperGrid(emptyText: string) {
    if (this.wallpapers.length === 0) {
      this.LoadingOrState(emptyText);
    } else {
      Grid() {
        ForEach(this.wallpapers, (item: WallpaperSummary) => {
          GridItem() { this.WallpaperCard(item); }
        }, (item: WallpaperSummary) => item.id);
      }
      .columnsTemplate('1fr 1fr')
      .columnsGap(12)
      .rowsGap(20)
      .height(Math.ceil(this.wallpapers.length / 2) * 250);
    }
  }

  @Builder
  private HomePage() {
    Scroll() {
      Column() {
        this.BrandHeader();
        this.SearchBar();
        if (this.errorMessage.length > 0) {
          Column() { this.LoadingOrState(''); }
            .width('100%').margin({ top: 28, bottom: 110 });
        } else {
          Text('壁纸分类')
            .width('100%').fontSize(20).fontWeight(FontWeight.Bold).fontColor(T.ink)
            .margin({ top: 28, bottom: 12 });
          if (this.categories.length > 0) {
            Scroll() {
              Row({ space: 18 }) {
                ForEach(this.categories, (category: WallpaperCategory) => {
                  Column({ space: 8 }) {
                    if (category.iconUrl.length > 0) {
                      Image(this.mediaUrl(category.iconUrl))
                        .width(T.categoryIcon).height(T.categoryIcon).objectFit(ImageFit.Cover).borderRadius(18);
                    } else {
                      Text(category.name.substring(0, 1))
                        .width(T.categoryIcon).height(T.categoryIcon).fontSize(22).fontWeight(FontWeight.Bold)
                        .fontColor(T.ink).textAlign(TextAlign.Center)
                        .backgroundColor(T.surfaceMuted).borderRadius(18);
                    }
                    Text(category.name).fontSize(13).fontColor(T.mutedInk);
                  }
                  .onClick(() => {
                    this.currentTitle = category.name;
                    this.currentQuery = '';
                    this.currentCategoryId = category.id;
                    this.page = 'CATEGORY';
                    this.loadWallpapers(HarmonyCatalogProjection.FILTER_FEATURED, '', category.id);
                  });
                }, (category: WallpaperCategory) => category.id);
              }
              .width('100%')
              .justifyContent(FlexAlign.Start);
            }
            .scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%');
          }
          Text(this.selectedFilter === HarmonyCatalogProjection.FILTER_FEATURED ? '精选壁纸' : this.selectedFilter)
            .width('100%').fontSize(20).fontWeight(FontWeight.Bold).fontColor(T.ink)
            .margin({ top: 28, bottom: 12 });
          Scroll() {
            Row() {
              this.FilterChip(HarmonyCatalogProjection.FILTER_FEATURED);
              this.FilterChip(HarmonyCatalogProjection.FILTER_FREE);
              this.FilterChip(HarmonyCatalogProjection.FILTER_MOVING_PHOTO);
              this.FilterChip(HarmonyCatalogProjection.FILTER_STATIC);
            }
          }
          .scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%');
          Column() {
            this.CatalogWallpaperGrid('暂时没有符合条件的已发布壁纸');
          }
          .width('100%').margin({ top: 16, bottom: 110 });
        }
      }
      .width('100%').constraintSize({ minHeight: '100%' })
      .padding({ left: T.gutter, right: T.gutter });
    }
    .width('100%').height('100%').scrollBar(BarState.Off);
  }

  @Builder
  private ResultPage() {
    Scroll() {
      Column() {
        this.PageHeader(this.currentTitle, true);
        this.SearchBar();
        Column() {
          this.CatalogWallpaperGrid(
            this.page === 'SEARCH' ? '没有找到相关壁纸，换个关键词试试' : '这个分类正在补充壁纸');
        }
        .width('100%').margin({ top: 24, bottom: 40 });
      }
      .width('100%').constraintSize({ minHeight: '100%' })
      .padding({ left: T.gutter, right: T.gutter });
    }
    .width('100%').height('100%').scrollBar(BarState.Off);
  }

  @Builder
  private MinePage() {
    Scroll() {
      Column() {
        this.BrandHeader('我的');
        Row() {
          Column() {
            Text('壁纸设置教程').width('100%').fontSize(17).fontWeight(FontWeight.Bold).fontColor(T.ink);
            Text('了解鸿蒙动态照片和静态壁纸的设置方式')
              .width('100%').fontSize(13).fontColor(T.mutedInk).margin({ top: 5 });
          }.layoutWeight(1);
          Text('›').fontSize(30).fontColor(T.mutedInk);
        }
        .width('100%').padding(18).backgroundColor(T.surface).border({ width: 1, color: T.outline })
        .borderRadius(T.cardRadius).shadow({ radius: 24, color: '#1231281F', offsetY: 8 })
        .onClick(() => this.openTutorials('MINE'));
        Row() {
          Text('已获得壁纸').fontSize(20).fontWeight(FontWeight.Bold).fontColor(T.ink).layoutWeight(1);
          Button('刷新权益').height(40).fontSize(13).backgroundColor(T.surface)
            .fontColor(T.ink).border({ width: 1, color: T.outlineStrong }).borderRadius(20)
            .onClick(() => this.loadEntitlements());
        }
        .width('100%').margin({ top: 28, bottom: 12 });
        if (this.entitlements.length === 0) {
          this.LoadingOrState('兑换壁纸后会显示在这里');
        } else {
          Column({ space: 12 }) {
            ForEach(this.entitlements, (item: WallpaperSummary) => {
              Row() {
                Image(this.mediaUrl(item.coverUrl)).width(76).height(96).objectFit(ImageFit.Cover).borderRadius(16);
                Column({ space: 8 }) {
                  Row({ space: 5 }) {
                    ForEach(this.thumbnailCapabilities(item), (capability: WallpaperCapability) => {
                      this.CapabilityBadge(this.thumbnailCapabilityLabel(capability));
                    });
                  }
                  Text(item.title).width('100%').fontSize(16).fontWeight(FontWeight.Bold).fontColor(T.ink);
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 });
                Text('›').fontSize(28).fontColor(T.mutedInk);
              }
              .width('100%').padding(12).backgroundColor(T.surface).border({ width: 1, color: T.outline })
              .borderRadius(T.cardRadius).onClick(() => this.openDetail(item));
            }, (item: WallpaperSummary) => item.id);
          }
        }
      }
      .width('100%').constraintSize({ minHeight: '100%' })
      .padding({ left: T.gutter, right: T.gutter, bottom: 18 });
    }
    .width('100%').height('100%').scrollBar(BarState.Off);
  }

  @Builder
  private DetailPage(item: WallpaperSummary) {
    Column() {
      Row() {
        Button() {
          Image($r('app.media.chevron_left')).width(20).height(20).objectFit(ImageFit.Contain);
        }
          .width(44).height(44).padding(0).backgroundColor(T.surface)
          .border({ width: 1, color: T.outline }).borderRadius(22)
          .onClick(() => this.goBack());
        Text(item.title).fontSize(22).fontWeight(FontWeight.Bolder).fontColor(T.ink)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1).margin({ left: 8 });
        Button() {
          Row({ space: 8 }) {
            Image($r('app.media.circle_play')).width(17).height(17).objectFit(ImageFit.Contain);
            Text('观看设置教程').fontSize(12).fontWeight(FontWeight.Bold).fontColor(T.accentStrong);
          }
        }
          .width(142).height(44).padding({ left: 13, right: 13 })
          .fontColor(T.accentStrong).backgroundColor(T.accentSoft)
          .border({ width: 1, color: T.accent }).borderRadius(22)
          .onClick(() => this.openDetailTutorial());
      }
      .width('100%').height(T.topBarHeight);
      if (item.capabilities.length > 1) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(item.capabilities, (capability: WallpaperCapability) => {
              Button(capability.label).height(38).fontSize(13).fontWeight(FontWeight.Bold)
                .fontColor(this.selectedCapability === capability.label ? T.inverseInk : T.ink)
                .backgroundColor(this.selectedCapability === capability.label ? T.navigation : T.surface)
                .border({ width: 1, color: T.outlineStrong }).borderRadius(19)
                .onClick(() => {
                  this.selectDetailCapability(item, capability);
                });
            }, (capability: WallpaperCapability) => capability.deliveryPlatform + capability.resourceType);
          }
        }
        .scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
        .margin({ top: 12, bottom: 12 });
      }
      Stack({ alignContent: Alignment.Bottom }) {
        Image(this.mediaUrl(item.coverUrl)).width('100%').height('100%').objectFit(ImageFit.Cover)
          .backgroundColor(T.surfaceMuted);
        if (this.detailPreviewPath.length > 0) {
          Video({ src: 'file://' + this.detailPreviewPath, controller: this.detailVideoController })
            .width('100%').height('100%').objectFit(ImageFit.Cover).controls(false)
            .autoPlay(!this.detailPreviewEnded).loop(false).muted(true)
            .onStart(() => {
              this.detailPreviewEnded = false;
              this.scheduleDetailPreviewFinish();
            })
            .onUpdate((info: PlaybackInfo) => {
              if (info.time >= DETAIL_PREVIEW_SECONDS) {
                this.finishDetailPreview();
              }
            })
            .onFinish(() => {
              this.finishDetailPreview();
            })
            .onError(() => {
              this.detailPreviewError = '动态效果暂时无法播放';
            });
        }
        Stack({ alignContent: Alignment.Center }) {
          if (this.detailPreviewLoading) {
            Row({ space: 8 }) {
              LoadingProgress().width(20).height(20).color(T.inverseInk);
              Text('正在加载动态效果').fontSize(13).fontColor(T.inverseInk);
            }
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .backgroundColor('#BB191817').borderRadius(18);
          }
          if (this.detailPreviewEnded) {
            Button() {
              Row({ space: 8 }) {
                Image($r('app.media.replay_round')).width(22).height(22).objectFit(ImageFit.Contain);
                Text('重新播放').fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.inverseInk);
              }
            }
            .height(46).padding({ left: 18, right: 18 }).backgroundColor('#CC191817').borderRadius(23)
            .onClick(() => this.replayDetailPreview());
          }
        }
        .width('100%').height('100%');
        Column() {
          if (this.detailPreviewError.length > 0) {
            Text(this.detailPreviewError).fontSize(13).fontColor(T.inverseInk).backgroundColor('#BB191817')
              .padding({ left: 12, right: 12, top: 8, bottom: 8 }).borderRadius(14).margin({ bottom: 10 });
          }
          if (this.statusMessage.length > 0) {
            Text(this.statusMessage).fontSize(13).fontColor(T.inverseInk).backgroundColor('#BB191817')
              .padding({ left: 12, right: 12, top: 8, bottom: 8 }).borderRadius(14).margin({ bottom: 10 });
          }
          Button(this.detailActionLabel(item))
            .width('100%').height(T.primaryHeight).fontSize(17).fontWeight(FontWeight.Bold)
            .fontColor(T.ink).backgroundColor(T.accent).borderRadius(27)
            .onClick(() => this.primaryDetailAction(item));
        }
        .width('100%').padding(16).align(Alignment.Bottom);
      }
      .width('100%').layoutWeight(1).borderRadius(T.cardRadius).clip(true).margin({ bottom: 20 });
    }
    .width('100%').height('100%').padding({ left: T.gutter, right: T.gutter });
  }

  @Builder
  private TutorialsPage() {
    Scroll() {
      Column() {
        this.PageHeader('壁纸设置教程', true);
        if (this.loading || this.errorMessage.length > 0 || this.tutorials.length === 0) {
          this.LoadingOrState('设置教程暂时还没有发布');
        } else {
          Column({ space: 12 }) {
            ForEach(this.tutorials, (tutorial: WallpaperTutorial, index: number) => {
              Row() {
                Text('▶').width(50).height(50).fontSize(20).fontColor(T.ink).textAlign(TextAlign.Center)
                  .backgroundColor(T.accent).borderRadius(17);
                Column({ space: 5 }) {
                  Text(tutorial.title).width('100%').fontSize(16).fontWeight(FontWeight.Bold).fontColor(T.ink);
                  Text('点击播放').width('100%').fontSize(12).fontColor(T.mutedInk);
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 16 });
                Text((index + 1).toString().padStart(2, '0')).fontSize(20).fontWeight(FontWeight.Bold)
                  .fontColor(T.outlineStrong);
              }
              .width('100%').padding(16).backgroundColor(T.surface).border({ width: 1, color: T.outline })
              .borderRadius(T.cardRadius).shadow({ radius: 24, color: '#1231281F', offsetY: 8 })
              .onClick(() => {
                this.selectedTutorial = tutorial;
                this.page = 'TUTORIAL_PLAYER';
              });
            }, (tutorial: WallpaperTutorial) => tutorial.key);
          }
        }
      }
      .width('100%').constraintSize({ minHeight: '100%' })
      .padding({ left: T.gutter, right: T.gutter, bottom: 30 });
    }
    .width('100%').height('100%').scrollBar(BarState.Off);
  }

  @Builder
  private TutorialPlayerPage(tutorial: WallpaperTutorial) {
    Column() {
      this.PageHeader(tutorial.title, false);
      Video({ src: this.mediaUrl(tutorial.videoUrl), controller: this.videoController })
        .width('100%').layoutWeight(1).objectFit(ImageFit.Contain).controls(true).autoPlay(true)
        .backgroundColor(Color.Black).borderRadius(T.cardRadius);
      Text('视频播放完成后，按教程在图库中设置对应壁纸')
        .width('100%').fontSize(13).fontColor(T.mutedInk).margin({ top: 16, bottom: 20 });
    }
    .width('100%').height('100%').padding({ left: T.gutter, right: T.gutter });
  }

  @Builder
  private CustomerPage() {
    Scroll() {
      Column() {
        this.PageHeader('微信客服', false);
        Column() {
          Row() {
            Text('✦').width(44).height(44).fontSize(20).textAlign(TextAlign.Center)
              .backgroundColor(T.accentSoft).fontColor(T.accentStrong).borderRadius(15);
            Column({ space: 3 }) {
              Text('微信客服').width('100%').fontSize(17).fontWeight(FontWeight.Bold).fontColor(T.ink);
              Text('添加客服获取兑换码或处理设备恢复').width('100%').fontSize(12).fontColor(T.mutedInk);
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 });
          }
          .width('100%');
          Image($r('app.media.customer_service_qr'))
            .width(164).height(164).objectFit(ImageFit.Contain).margin({ top: 24 })
            .onClick(() => this.qrPreview = true);
          Text('点击二维码可放大查看').fontSize(12).fontColor(T.mutedInk).margin({ top: 8 });
          Row() {
            SaveButton({ icon: SaveIconStyle.LINES, text: SaveDescription.SAVE_IMAGE,
              buttonType: ButtonType.Capsule })
              .height(44).fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.ink)
              .backgroundColor(T.surface).borderWidth(1).borderColor(T.outlineStrong)
              .onClick((event: ClickEvent, result: SaveButtonOnClickResult) => {
                if (result === SaveButtonOnClickResult.SUCCESS) {
                  this.saveQr();
                } else {
                  this.toast('需要允许保存图片才能保存二维码');
                }
              });
          }
          .margin({ top: 12 });
          Row() {
            Column({ space: 2 }) {
              Text('微信号').width('100%').fontSize(10).fontWeight(FontWeight.Bold).fontColor(T.mutedInk);
              Text(CustomerSupport.WECHAT_ID).width('100%').fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.ink);
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1);
            Button('复制').height(38).fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.inverseInk)
              .backgroundColor(T.navigation).borderRadius(19).onClick(() => this.copyWechat());
          }
          .width('100%').height(62).padding({ left: 16, right: 10 }).backgroundColor(T.surfaceMuted)
          .borderRadius(T.controlRadius).margin({ top: 24 });
          Row({ space: 7 }) {
            Image($r('app.media.clock')).width(17).height(17).objectFit(ImageFit.Contain);
            Text('服务时间 09:00-21:00').fontSize(12).fontColor(T.mutedInk);
          }
          .margin({ top: 14 });
        }
        .width('100%').padding(20).backgroundColor(T.surface).border({ width: 1, color: T.outline })
        .borderRadius(T.cardRadius).margin({ top: 20 });
      }
      .width('100%').constraintSize({ minHeight: '100%' })
      .padding({ left: T.gutter, right: T.gutter, bottom: 30 });
    }
    .width('100%').height('100%').scrollBar(BarState.Off);
  }

  @Builder
  private CustomerDialogOverlay() {
    Column() {
      Row() {
        Text('联系客服').fontSize(22).fontWeight(FontWeight.Bolder).fontColor(T.ink).layoutWeight(1);
        Button('关闭').height(38).fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.ink)
          .backgroundColor(T.surfaceMuted).borderRadius(19)
          .onClick(() => this.customerDialogVisible = false);
      }
      .width('100%').margin({ bottom: 16 });
      Scroll() {
        Column() {
          Row() {
            Image($r('app.media.message_circle_more_dark')).width(22).height(22).objectFit(ImageFit.Contain);
            Column({ space: 3 }) {
              Text('微信客服').width('100%').fontSize(17).fontWeight(FontWeight.Bold).fontColor(T.ink);
              Text('添加客服获取兑换码或处理设备恢复').width('100%').fontSize(12).fontColor(T.mutedInk);
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 });
          }
          .width('100%');
          Image($r('app.media.customer_service_qr'))
            .width(164).height(164).objectFit(ImageFit.Contain).margin({ top: 20 })
            .onClick(() => this.qrPreview = true);
          Text('点击二维码可放大查看').fontSize(12).fontColor(T.mutedInk).margin({ top: 8 });
          Row() {
            SaveButton({ icon: SaveIconStyle.LINES, text: SaveDescription.SAVE_IMAGE,
              buttonType: ButtonType.Capsule })
              .height(44).fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.ink)
              .backgroundColor(T.surface).borderWidth(1).borderColor(T.outlineStrong)
              .onClick((event: ClickEvent, result: SaveButtonOnClickResult) => {
                if (result === SaveButtonOnClickResult.SUCCESS) {
                  this.saveQr();
                } else {
                  this.toast('需要允许保存图片才能保存二维码');
                }
              });
          }
          .margin({ top: 12 });
          Row() {
            Column({ space: 2 }) {
              Text('微信号').width('100%').fontSize(10).fontWeight(FontWeight.Bold).fontColor(T.mutedInk);
              Text(CustomerSupport.WECHAT_ID).width('100%').fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.ink);
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1);
            Button('复制').height(38).fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.inverseInk)
              .backgroundColor(T.navigation).borderRadius(19).onClick(() => this.copyWechat());
          }
          .width('100%').height(62).padding({ left: 16, right: 10 }).backgroundColor(T.surfaceMuted)
          .borderRadius(T.controlRadius).margin({ top: 20 });
          Row({ space: 7 }) {
            Image($r('app.media.clock')).width(17).height(17).objectFit(ImageFit.Contain);
            Text('服务时间 09:00-21:00').fontSize(12).fontColor(T.mutedInk);
          }
          .margin({ top: 14, bottom: 4 });
        }
        .width('100%');
      }
      .width('100%').layoutWeight(1).scrollBar(BarState.Off);
    }
    .width('88%').height('76%').padding(20).backgroundColor(T.surface).borderRadius(T.cardRadius)
    .shadow({ radius: 34, color: '#2E191817', offsetY: 12 });
  }

  @Builder
  private RedemptionOverlay() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column()
        .width('100%').height('100%').backgroundColor('#88191817')
        .onClick(() => {
          if (!this.redemptionBusy) this.redemptionVisible = false;
        });
      Column() {
        Row() {}
          .width(38).height(5).backgroundColor(T.outlineStrong).borderRadius(3)
          .margin({ bottom: 20 });
        Row() {
          Stack({ alignContent: Alignment.Center }) {
            Image($r('app.media.key_round')).width(22).height(22).objectFit(ImageFit.Contain);
          }
          .width(44).height(44).backgroundColor(T.accentSoft).borderRadius(15);
          Column({ space: 3 }) {
            Text('兑换这张壁纸').width('100%').fontSize(22).fontWeight(FontWeight.Bolder).fontColor(T.ink);
            Text('输入客服发送的兑换码').width('100%').fontSize(13).fontColor(T.mutedInk);
          }
          .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 });
        }
        .width('100%').margin({ bottom: 20 });
        Text('兑换码').width('100%').fontSize(12).fontWeight(FontWeight.Bold).fontColor(T.mutedInk)
          .margin({ bottom: 7 });
        TextInput({ text: this.redemptionCode, placeholder: '请输入兑换码' })
          .width('100%').height(52).fontSize(16).backgroundColor(T.surfaceMuted)
          .border({ width: 1, color: T.outlineStrong }).borderRadius(T.controlRadius)
          .maxLength(32).onChange((value: string) => this.redemptionCode = value);
        if (this.redemptionMessage.length > 0) {
          Text(this.redemptionMessage).width('100%').fontSize(13).fontColor('#C33A32').margin({ top: 9 });
        }
        Button(this.redemptionBusy ? '正在兑换' : '验证并兑换')
          .width('100%').height(48).fontSize(15).fontWeight(FontWeight.Bold)
          .fontColor(T.inverseInk).backgroundColor(T.navigation).borderRadius(24)
          .margin({ top: 18 }).enabled(!this.redemptionBusy)
          .onClick(() => this.redeemAndDownload());
        Button() {
          Row({ space: 7 }) {
            Image($r('app.media.message_circle_more_dark')).width(18).height(18).objectFit(ImageFit.Contain);
            Text('联系客服').fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.ink);
          }
        }
        .width('100%').height(44).backgroundColor(T.surface)
        .border({ width: 1, color: T.outlineStrong }).borderRadius(22)
        .margin({ top: 12 }).enabled(!this.redemptionBusy)
        .onClick(() => this.customerDialogVisible = true);
      }
      .width('100%').padding({ left: 22, right: 22, top: 14, bottom: 28 })
      .backgroundColor(T.surface).borderRadius({ topLeft: 28, topRight: 28 });
    }
    .width('100%').height('100%');
  }

  @Builder
  private DeliveryOverlay() {
    Column({ space: 16 }) {
      if (this.deliveryReady) {
        Text('✓').width(48).height(48).fontSize(26).fontWeight(FontWeight.Bold).fontColor(T.ink)
          .textAlign(TextAlign.Center).backgroundColor(T.accent).borderRadius(24);
      } else {
        LoadingProgress().width(46).height(46).color(T.accentStrong);
      }
      Text(this.deliveryReady ? '下载完成' : (this.deliveryJustRedeemed ? '兑换成功' : '正在下载壁纸'))
        .fontSize(22).fontWeight(FontWeight.Bolder).fontColor(T.ink);
      Text(this.deliveryMessage).width('100%').fontSize(13).fontColor(T.mutedInk).textAlign(TextAlign.Center);
      if (this.deliveryReady) {
        Row({ space: 10 }) {
          Button('暂不保存').width('48%').height(46).fontSize(14).fontWeight(FontWeight.Bold)
            .fontColor(T.ink).backgroundColor(T.surface)
            .border({ width: 1, color: T.outlineStrong }).borderRadius(23)
            .onClick(() => this.cancelDownloadedWallpaper());
          SaveButton({ text: SaveDescription.SAVE_IMAGE, buttonType: ButtonType.Capsule })
            .width('48%').height(46).fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.ink)
            .backgroundColor(T.accent).borderRadius(23)
            .onClick((event: ClickEvent, result: SaveButtonOnClickResult) => {
              if (result === SaveButtonOnClickResult.SUCCESS) {
                this.saveDownloadedWallpaper();
              } else {
                this.toast('需要允许保存图片才能保存到图库');
              }
            });
        }
        .width('100%');
      } else {
        Progress({ value: this.deliveryProgress, total: 100, type: ProgressType.Linear })
          .width('100%').height(8).color(T.accentStrong).backgroundColor(T.surfaceMuted).borderRadius(4);
        Text(this.deliveryProgress.toString() + '%').fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.ink);
      }
    }
    .width('82%').padding(24).backgroundColor(T.surface).borderRadius(T.cardRadius)
    .shadow({ radius: 34, color: '#2E191817', offsetY: 12 });
  }

  @Builder
  private BottomNavigation() {
    Row() {
      Button('首页').layoutWeight(1).height(52).fontWeight(FontWeight.Bold)
        .fontColor(this.activeTab === 0 ? T.inverseInk : T.ink)
        .backgroundColor(this.activeTab === 0 ? T.navigation : Color.Transparent).borderRadius(26)
        .onClick(() => {
          this.activeTab = 0;
          this.page = 'HOME';
          this.loadHome();
        });
      Button('我的').layoutWeight(1).height(52).fontWeight(FontWeight.Bold)
        .fontColor(this.activeTab === 1 ? T.inverseInk : T.ink)
        .backgroundColor(this.activeTab === 1 ? T.navigation : Color.Transparent).borderRadius(26)
        .onClick(() => {
          this.activeTab = 1;
          this.page = 'MINE';
          this.loadEntitlements();
        });
    }
    .width('100%').height(T.bottomNavigationHeight).padding(7).backgroundColor('#F5FFFFFF')
    .border({ width: 1, color: T.outline }).borderRadius(33)
    .shadow({ radius: 34, color: '#2E191817', offsetY: 12 });
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column() {
        Stack({ alignContent: Alignment.TopStart }) {
          if (this.page === 'HOME') this.HomePage();
          if (this.page === 'MINE') this.MinePage();
          if (this.page === 'CATEGORY' || this.page === 'SEARCH') this.ResultPage();
          if (this.page === 'DETAIL' && this.selectedDetail !== null) {
            this.DetailPage(this.selectedDetail as WallpaperSummary);
          }
          if (this.page === 'TUTORIALS') this.TutorialsPage();
          if (this.page === 'TUTORIAL_PLAYER' && this.selectedTutorial !== null) {
            this.TutorialPlayerPage(this.selectedTutorial as WallpaperTutorial);
          }
          if (this.page === 'CUSTOMER') this.CustomerPage();
        }
        .width('100%').layoutWeight(1);
        if (this.page === 'HOME' || this.page === 'MINE') {
          Row() { this.BottomNavigation(); }
            .width('100%').padding({ left: T.gutter, right: T.gutter, bottom: 12 });
        }
      }
      .width('100%').height('100%');
      if (this.redemptionVisible) this.RedemptionOverlay();
      if (this.customerDialogVisible) {
        Column() { this.CustomerDialogOverlay(); }
          .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
          .backgroundColor('#99191817');
      }
      if (this.qrPreview) {
        Column() {
          Button('关闭').height(44).fontColor(T.inverseInk).backgroundColor('#99191817').borderRadius(22)
            .onClick(() => this.qrPreview = false);
          Image($r('app.media.customer_service_qr')).width(320).height(320).objectFit(ImageFit.Contain)
            .margin({ top: 20 });
        }
        .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        .backgroundColor('#EE191817').onClick(() => this.qrPreview = false);
      }
      if (this.deliveryVisible) {
        Column() { this.DeliveryOverlay(); }
          .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
          .backgroundColor('#99191817');
      }
    }
    .width('100%').height('100%').backgroundColor(T.surface);
  }
}
