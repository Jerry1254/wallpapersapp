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
import { ApiRequestError } from '../network/JsonHttp';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';
import { AppUpdateEvents, InstalledAppVersion } from '../update/AppUpdateEvents';
import { AppRelease, AppUpdatePolicy, AppUpdateService } from '../update/AppUpdateService';
import { CustomerSupport } from '../support/CustomerSupport';
import { DownloadedMovingPhoto, DownloadedStaticImage, HarmonyDelivery } from '../media/HarmonyDelivery';
import { SavedGalleryAsset } from '../media/SavedGalleryAsset';
import {
  POLICY_EFFECTIVE_DATE,
  POLICY_VERSION,
  PRIVACY_POLICY,
  PolicyDocument,
  PolicySection,
  USER_AGREEMENT
} from '../privacy/PolicyDocuments';

interface FailureWithMessage {
  message: string;
}

const LOG_DOMAIN: number = 0x514A;
const DELIVERY_PREFERENCES: string = 'qingjing_delivery_state';
const SAVED_MOVING_PHOTO_KEYS: string = 'saved_moving_photo_keys';
const DETAIL_PREVIEW_SECONDS: number = 2;
const PRIVACY_PREFERENCES: string = 'qingjing_privacy_state';
const ACCEPTED_POLICY_VERSION: string = 'accepted_policy_version';

@Entry
@Component
struct Index {
  @StorageLink('qingjing_foreground_revision') @Watch('onApplicationForeground') foregroundRevision: number = 0;
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
  @State customerDialogVisible: boolean = false;
  @State qrPreview: boolean = false;
  @State consentChecked: boolean = false;
  @State consentAccepted: boolean = false;
  @State consentBusy: boolean = false;
  @State consentError: string = '';
  @State policyDocumentKey: string = '';
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
  @State detailFullScreen: boolean = false;
  @State updateStartupChecked: boolean = false;
  @State updateVisible: boolean = false;
  @State updateMandatory: boolean = false;
  @State updateBusy: boolean = false;
  @State updateMessage: string = '';
  @State updateLatest: AppRelease | null = null;
  private context: common.UIAbilityContext | null = null;
  private api: CatalogApi | null = null;
  private videoController: VideoController = new VideoController();
  private detailVideoController: VideoController = new VideoController();
  private detailPreviewGeneration: number = 0;
  private pendingMovingPhoto: DownloadedMovingPhoto | null = null;
  private pendingDeliveryKey: string = '';
  private pendingStaticImage: DownloadedStaticImage | null = null;
  private savedGalleryAsset: SavedGalleryAsset | null = null;
  private openingSavedGalleryAsset: boolean = false;
  private initialLoadStarted: boolean = false;
  private pageVisible: boolean = false;
  private updates: AppUpdateService | null = null;
  private updateCheckTask: Promise<void> | null = null;
  private dismissedUpdateId: string = '';
  private serverRestrictionPending: boolean = false;

  aboutToAppear(): void {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      this.errorMessage = '无法初始化应用环境';
      return;
    }
    this.context = host as common.UIAbilityContext;
    this.loadPrivacyConsent();
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (this.consentAccepted) {
      this.initializeAfterConsent();
      if (this.updateStartupChecked) this.checkForUpdates();
    }
  }

  onPageHide(): void {
    this.pageVisible = false;
  }

  aboutToDisappear(): void {
    AppUpdateEvents.onRequired = null;
  }

  private onApplicationForeground(): void {
    if (this.consentAccepted && this.updates !== null) this.checkForUpdates();
    if (this.page === 'DETAIL' && this.selectedDetail !== null) {
      this.openDetail(this.selectedDetail as WallpaperSummary, this.selectedCapability);
    } else if (this.api !== null &&
      (this.page === 'HOME' || this.page === 'MINE' || this.page === 'SEARCH' || this.page === 'CATEGORY')) {
      this.retryCurrentPage();
    }
  }

  private async loadPrivacyConsent(): Promise<void> {
    if (this.context === null) return;
    try {
      const store: preferences.Preferences = await preferences.getPreferences(
        this.context as common.UIAbilityContext, PRIVACY_PREFERENCES);
      const acceptedVersion: string = String(await store.get(ACCEPTED_POLICY_VERSION, ''));
      this.consentAccepted = acceptedVersion === POLICY_VERSION;
      this.consentError = '';
    } catch (error) {
      this.consentAccepted = false;
      this.consentError = '无法读取本地同意记录，请重试';
    } finally {
      this.consentChecked = true;
    }
    if (this.consentAccepted) this.initializeAfterConsent();
  }

  private initializeAfterConsent(): void {
    if (this.context === null) return;
    if (this.updates === null) {
      this.updates = new AppUpdateService(this.context as common.UIAbilityContext,
        (policy: AppUpdatePolicy) => this.applyUpdatePolicy(policy));
      AppUpdateEvents.onRequired = () => this.requireUpdateFromServer();
      this.checkForUpdates();
      return;
    }
    this.initializeBusiness();
  }

  private initializeBusiness(): void {
    if (this.context === null || !this.updateStartupChecked || this.updateMandatory) return;
    if (this.api === null) {
      const sessions: HarmonyDeviceSession = new HarmonyDeviceSession(this.context as common.UIAbilityContext);
      this.api = new CatalogApi(sessions);
    }
    this.startInitialLoad();
  }

  private async checkForUpdates(): Promise<void> {
    if (this.updates === null || !this.consentAccepted) return;
    if (this.updateCheckTask !== null) return await (this.updateCheckTask as Promise<void>);
    const pending: Promise<void> = this.performUpdateCheck();
    this.updateCheckTask = pending;
    try {
      await pending;
    } finally {
      this.updateCheckTask = null;
    }
  }

  private async performUpdateCheck(): Promise<void> {
    this.updateBusy = true;
    this.updateMessage = '';
    try {
      await (this.updates as AppUpdateService).check();
    } catch (error) {
      if (this.updateMandatory) this.updateMessage = '暂时无法检查更新，请检查网络后重试';
      hilog.warn(LOG_DOMAIN, 'QingjingUpdate', 'Update check failed');
    } finally {
      this.updateBusy = false;
      this.updateStartupChecked = true;
      this.initializeBusiness();
    }
  }

  private applyUpdatePolicy(policy: AppUpdatePolicy): void {
    // Do not let an older in-flight check dismiss a gate established by a newer business rejection.
    if (this.serverRestrictionPending && !policy.mandatory) return;
    this.updateMandatory = policy.mandatory;
    this.updateLatest = policy.latestVersion;
    const latestId: string = policy.latestVersion?.id ?? '';
    this.updateVisible = policy.mandatory ||
      (policy.updateAvailable && latestId.length > 0 && this.dismissedUpdateId !== latestId);
    if (policy.mandatory) {
      this.detailFullScreen = false;
      try {
        if (this.detailPreviewPath.length > 0) this.detailVideoController.pause();
        if (this.page === 'TUTORIAL_PLAYER') this.videoController.pause();
      } catch (error) {
        hilog.warn(LOG_DOMAIN, 'QingjingUpdate', 'Unable to pause media during update gate');
      }
    }
  }

  private async requireUpdateFromServer(): Promise<void> {
    if (this.updates === null || this.serverRestrictionPending) return;
    this.serverRestrictionPending = true;
    this.updateMandatory = true;
    this.updateVisible = true;
    this.updateMessage = '';
    if (this.updateCheckTask !== null) await (this.updateCheckTask as Promise<void>);
    try {
      await (this.updates as AppUpdateService).confirmServerRestriction();
    } catch (error) {
      hilog.warn(LOG_DOMAIN, 'QingjingUpdate', 'Unable to save update restriction');
    }
    this.serverRestrictionPending = false;
    await this.checkForUpdates();
  }

  private dismissOptionalUpdate(): void {
    if (this.updateMandatory) return;
    this.dismissedUpdateId = this.updateLatest?.id ?? '';
    this.updateVisible = false;
    this.updateMessage = '';
  }

  private openUpdateStore(): void {
    if (this.updates === null || this.updateBusy) return;
    this.updateMessage = '';
    try {
      (this.updates as AppUpdateService).openStore(
        (message: string) => this.updateMessage = message,
        () => this.checkForUpdates());
    } catch (error) {
      this.updateMessage = '暂时无法打开应用市场，请稍后重试';
    }
  }

  private async acceptPrivacyPolicy(): Promise<void> {
    if (this.context === null || this.consentBusy) return;
    this.consentBusy = true;
    this.consentError = '';
    try {
      const store: preferences.Preferences = await preferences.getPreferences(
        this.context as common.UIAbilityContext, PRIVACY_PREFERENCES);
      await store.put(ACCEPTED_POLICY_VERSION, POLICY_VERSION);
      await store.flush();
      this.consentAccepted = true;
      this.initializeAfterConsent();
    } catch (error) {
      this.consentError = '暂时无法保存选择，请重试';
    } finally {
      this.consentBusy = false;
    }
  }

  private async rejectPrivacyPolicy(): Promise<void> {
    if (this.context === null || this.consentBusy) return;
    try {
      await (this.context as common.UIAbilityContext).terminateSelf();
    } catch (error) {
      this.consentError = '无法自动退出，请手动关闭应用';
    }
  }

  private startInitialLoad(): void {
    if (!this.pageVisible || this.initialLoadStarted || this.api === null ||
      !this.updateStartupChecked || this.updateMandatory) return;
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

  private async openDetail(item: WallpaperSummary, capabilityLabel: string = ''): Promise<void> {
    this.clearDetailPreview();
    this.detailFullScreen = false;
    this.selectedDetail = item;
    this.selectedCapability = item.capabilities.some((capability: WallpaperCapability): boolean =>
      capability.label === capabilityLabel) ? capabilityLabel : item.capabilities[0].label;
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

  onBackPress(): boolean {
    if (this.updateMandatory || (this.consentAccepted && !this.updateStartupChecked)) return true;
    if (this.updateVisible) {
      this.dismissOptionalUpdate();
      return true;
    }
    if (this.page === 'DETAIL' && this.detailFullScreen) {
      this.detailFullScreen = false;
      return true;
    }
    return false;
  }

  private goBack(): void {
    if (this.page === 'DETAIL' && this.detailFullScreen) {
      this.detailFullScreen = false;
      return;
    }
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

  private selectedCapabilityFor(item: WallpaperSummary): WallpaperCapability {
    const matched: WallpaperCapability | undefined = item.capabilities.find((capability: WallpaperCapability) =>
      capability.label === this.selectedCapability);
    return matched === undefined ? item.capabilities[0] : matched;
  }

  private selectDetailCapability(item: WallpaperSummary, capability: WallpaperCapability): void {
    this.selectedCapability = capability.label;
    this.statusMessage = '';
    this.prepareDetailPreview(item);
  }

  private async prepareDetailPreview(item: WallpaperSummary): Promise<void> {
    this.clearDetailPreview();
    if (!item.previewReady()) {
      this.detailPreviewError = item.previewUnavailableMessage();
      return;
    }
    const capability: WallpaperCapability = this.selectedCapabilityFor(item);
    if (capability.deliveryPlatform !== 'HARMONYOS' || capability.resourceType !== 'MOVING_PHOTO' ||
      this.api === null || this.context === null) return;
    const generation: number = this.detailPreviewGeneration;
    this.detailPreviewLoading = true;
    this.detailPreviewError = '';
    try {
      const descriptor = await (this.api as CatalogApi).previewTicket(item.id, capability);
      if (generation !== this.detailPreviewGeneration || this.page !== 'DETAIL') return;
      if (item.previewRevision > 0 && descriptor.previewRevision !== item.previewRevision) {
        throw new Error('预览资源已更新，请重新加载');
      }
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
        this.detailPreviewError = error instanceof ApiRequestError ? error.message : '动态效果暂时无法加载';
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
    if (this.detailSavedNowKey === key) return '已保存至相册，前往设置';
    if (this.savedDeliveryKeys.indexOf(key) >= 0) return '再次下载';
    return item.free || this.detailOwned ? '下载壁纸' : '兑换并下载';
  }

  private async primaryDetailAction(item: WallpaperSummary): Promise<void> {
    const key: string = this.deliveryKey(item, this.selectedCapabilityFor(item));
    if (this.detailSavedNowKey === key) {
      await this.openSavedGalleryAsset();
      return;
    }
    await this.detailAction(item);
  }

  private async openSavedGalleryAsset(): Promise<void> {
    if (this.context === null || this.openingSavedGalleryAsset) return;
    if (this.savedGalleryAsset === null) {
      this.toast('暂时无法打开这张图片，请手动进入图库查看');
      return;
    }
    this.openingSavedGalleryAsset = true;
    try {
      await (this.savedGalleryAsset as SavedGalleryAsset).open(this.context as common.UIAbilityContext);
    } catch (error) {
      hilog.warn(LOG_DOMAIN, 'Qingjing', 'Unable to open saved gallery asset: %{public}s', this.messageFor(error));
      this.toast('暂时无法打开这张图片，请手动进入图库查看');
    } finally {
      this.openingSavedGalleryAsset = false;
    }
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
        this.savedGalleryAsset = await HarmonyDelivery.saveStaticImage(this.context as common.UIAbilityContext,
          this.pendingStaticImage as DownloadedStaticImage);
        this.pendingStaticImage = null;
      } else {
        this.savedGalleryAsset = await HarmonyDelivery.saveDownloaded(this.context as common.UIAbilityContext,
          this.pendingMovingPhoto as DownloadedMovingPhoto);
        this.pendingMovingPhoto = null;
      }
      this.pendingDeliveryKey = '';
      await this.rememberSavedDelivery(savedKey);
      this.detailSavedNowKey = savedKey;
      this.deliveryVisible = false;
      this.statusMessage = '';
      this.toast('已保存至相册，前往设置');
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

  private detailCapabilityLabel(capability: WallpaperCapability): string {
    if (capability.resourceType === 'STATIC_IMAGE') return '静态壁纸';
    if (capability.resourceType === 'LAYER_PARALLAX') return '4D壁纸';
    return '动态壁纸';
  }

  @Builder
  private WallpaperCover(item: WallpaperSummary, width: number | string, height: number | string) {
    if (item.previewReady()) {
      Image(this.mediaUrl(item.coverUrl)).width(width).height(height).objectFit(ImageFit.Cover)
        .backgroundColor(T.surfaceMuted);
    } else {
      Column() {
        Text(item.previewUnavailableMessage()).fontSize(13).fontColor(T.mutedInk)
          .textAlign(TextAlign.Center);
      }
      .width(width).height(height).justifyContent(FlexAlign.Center).padding(12)
      .backgroundColor(T.surfaceMuted);
    }
  }

  @Builder
  private WallpaperCard(item: WallpaperSummary) {
    Column() {
      Stack({ alignContent: Alignment.TopStart }) {
        this.WallpaperCover(item, '100%', T.catalogMediaHeight);
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
        }, (item: WallpaperSummary) => item.id + ':' + item.previewRevision.toString() + ':' +
          item.previewGenerationStatus + ':' + item.coverUrl);
      }
      .columnsTemplate('1fr 1fr')
      .columnsGap(12)
      .rowsGap(20)
      .height(Math.ceil(this.wallpapers.length / 2) * 265 - 20);
    }
  }

  @Builder
  private HomePage() {
    Column() {
      Column() {
        this.BrandHeader();
        this.SearchBar();
      }
      .width('100%')
      .padding({ left: T.gutter, right: T.gutter });
      Scroll() {
        Column() {
        if (this.errorMessage.length > 0) {
          Column() { this.LoadingOrState(''); }
            .width('100%').margin({ top: 28, bottom: 24 });
        } else {
          Text('壁纸分类')
            .width('100%').fontSize(20).fontWeight(FontWeight.Bold).fontColor(T.ink)
            .margin({ top: 28, bottom: 12 });
          if (this.categories.length > 0) {
            Grid() {
                ForEach(this.categories, (category: WallpaperCategory) => {
                  GridItem() {
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
                      Text(category.name).width('100%').fontSize(13).fontColor(T.mutedInk)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.Center);
                    }
                    .width('100%');
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
            .columnsTemplate('1fr 1fr 1fr 1fr')
            .rowsGap(12)
            .width('100%')
            .height(Math.ceil(this.categories.length / 4) * 96);
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
          .width('100%').margin({ top: 16, bottom: 24 });
        }
        }
        .width('100%')
        .padding({ left: T.gutter, right: T.gutter });
      }
      .width('100%').layoutWeight(1).scrollBar(BarState.Off);
    }
    .width('100%').height('100%');
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
        Column() {
          Text('隐私政策与用户协议').width('100%').fontSize(16).fontWeight(FontWeight.Bold).fontColor(T.ink);
          Text('查看倾境壁纸的服务规则与信息处理说明')
            .width('100%').fontSize(12).fontColor(T.mutedInk).margin({ top: 5 });
          Row({ space: 8 }) {
            Button('隐私政策').layoutWeight(1).height(38).fontSize(13).fontWeight(FontWeight.Bold)
              .fontColor(T.ink).backgroundColor(T.surfaceMuted).borderRadius(19)
              .onClick(() => this.policyDocumentKey = 'PRIVACY');
            Button('用户协议').layoutWeight(1).height(38).fontSize(13).fontWeight(FontWeight.Bold)
              .fontColor(T.ink).backgroundColor(T.surfaceMuted).borderRadius(19)
              .onClick(() => this.policyDocumentKey = 'AGREEMENT');
          }
          .width('100%').margin({ top: 12 });
        }
        .width('100%').padding(18).backgroundColor(T.surface).border({ width: 1, color: T.outline })
        .borderRadius(T.cardRadius).margin({ top: 12 });
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
                Stack() {
                  this.WallpaperCover(item, 76, 96);
                }.width(76).height(96).borderRadius(16).clip(true);
                Column({ space: 8 }) {
                  Row({ space: 5 }) {
                    ForEach(this.thumbnailCapabilities(item), (capability: WallpaperCapability) => {
                      this.CapabilityBadge(this.thumbnailCapabilityLabel(capability));
                    }, (capability: WallpaperCapability) => capability.deliveryPlatform + ':' + capability.resourceType);
                  }
                  Text(item.title).width('100%').fontSize(16).fontWeight(FontWeight.Bold).fontColor(T.ink);
                }
                .height(96).justifyContent(FlexAlign.Start)
                .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 });
                Text('›').fontSize(28).fontColor(T.mutedInk);
              }
              .width('100%').padding(12).backgroundColor(T.surface).border({ width: 1, color: T.outline })
              .borderRadius(T.cardRadius).onClick(() => this.openDetail(item));
            }, (item: WallpaperSummary) => item.id + ':' + item.previewRevision.toString() + ':' +
              item.previewGenerationStatus + ':' + item.coverUrl);
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
      if (!this.detailFullScreen) {
        Stack({ alignContent: Alignment.Center }) {
          Text('壁纸详情').width('100%').fontSize(18).fontWeight(FontWeight.Bold)
            .fontColor(T.ink).textAlign(TextAlign.Center);
          Row() {
            Button() {
              Image($r('app.media.chevron_left')).width(20).height(20).objectFit(ImageFit.Contain);
            }
            .width(44).height(44).padding(0).backgroundColor(T.surface).borderRadius(22)
            .onClick(() => this.goBack());
            Blank();
            Button() {
              Row({ space: 5 }) {
                Image($r('app.media.circle_play')).width(17).height(17).objectFit(ImageFit.Contain);
                Text('设置教程').fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.accentStrong);
              }
            }
            .height(44).padding({ left: 8, right: 8 }).backgroundColor(T.surface)
            .onClick(() => this.openDetailTutorial());
          }
          .width('100%').height(T.topBarHeight);
        }
        .width('100%').height(T.topBarHeight);
        if (item.capabilities.length > 1) {
          Scroll() {
            Row({ space: 8 }) {
              ForEach(item.capabilities, (capability: WallpaperCapability) => {
                Button(this.detailCapabilityLabel(capability)).height(38).fontSize(13).fontWeight(FontWeight.Bold)
                  .fontColor(this.selectedCapability === capability.label ? T.inverseInk : T.ink)
                  .backgroundColor(this.selectedCapability === capability.label ? T.navigation : T.surface)
                  .border({ width: 1, color: T.outlineStrong }).borderRadius(19)
                  .onClick(() => this.selectDetailCapability(item, capability));
              }, (capability: WallpaperCapability) => capability.deliveryPlatform + capability.resourceType);
            }
            .width('100%').justifyContent(FlexAlign.Center);
          }
          .scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').height(40)
          .margin({ top: 8 });
        }
      }
        Stack({ alignContent: Alignment.Bottom }) {
          this.WallpaperCover(item, '100%', '100%');
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
          Row() {
            Text(this.selectedCapabilityFor(item).resourceType === 'LAYER_PARALLAX' ?
              '4D 景深' : this.detailCapabilityLabel(this.selectedCapabilityFor(item)))
              .fontSize(13).fontWeight(FontWeight.Bold).fontColor(T.inverseInk)
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .backgroundColor('#BB191817').border({ width: 1, color: '#66FFFFFF' }).borderRadius(20);
            Blank();
            Button() {
              Image(this.detailFullScreen ? $r('app.media.fullscreen_exit') : $r('app.media.fullscreen'))
                .width(24).height(24).objectFit(ImageFit.Contain);
            }
            .width(44).height(44).padding(0).backgroundColor('#BB191817')
            .border({ width: 1, color: '#66FFFFFF' }).borderRadius(16)
            .accessibilityText(this.detailFullScreen ? '退出全屏' : '全屏预览')
            .onClick(() => this.detailFullScreen = !this.detailFullScreen);
          }
          .width('100%').padding(12).position({ x: 0, y: 0 });
          Column() {
            if (this.detailPreviewError.length > 0) {
              Text(this.detailPreviewError).fontSize(13).fontColor(T.inverseInk).backgroundColor('#BB191817')
                .padding({ left: 12, right: 12, top: 8, bottom: 8 }).borderRadius(14).margin({ bottom: 10 });
              Button('重新加载预览').fontSize(13).fontColor(T.inverseInk).backgroundColor('#BB191817')
                .height(36).margin({ bottom: 10 }).onClick(() => this.openDetail(item));
            }
            if (this.statusMessage.length > 0) {
              Text(this.statusMessage).fontSize(13).fontColor(T.inverseInk).backgroundColor('#BB191817')
                .padding({ left: 12, right: 12, top: 8, bottom: 8 }).borderRadius(14).margin({ bottom: 10 });
            }
          }
          .width('100%').padding(12).align(Alignment.Bottom);
        }
        .width('100%').layoutWeight(1).backgroundColor('#000000')
        .borderRadius(this.detailFullScreen ? 0 : T.cardRadius).clip(true)
        .margin({ top: this.detailFullScreen ? 0 : 12 });
      if (!this.detailFullScreen) {
        Text(item.title).width('100%').fontSize(24).fontWeight(FontWeight.Bold).fontColor(T.ink)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.Start)
          .margin({ top: 16 });
        Button(this.detailActionLabel(item))
          .width('100%').height(T.primaryHeight).fontSize(17).fontWeight(FontWeight.Bold)
          .fontColor(T.ink).backgroundColor(T.accent).borderRadius(27)
          .margin({ top: 16, bottom: 16 })
          .onClick(() => this.primaryDetailAction(item));
      }
    }
    .width('100%').height('100%').alignItems(HorizontalAlign.Center)
    .backgroundColor(this.detailFullScreen ? '#000000' : T.surface)
    .padding({ left: this.detailFullScreen ? 0 : T.gutter, right: this.detailFullScreen ? 0 : T.gutter });
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
            Image($r('app.media.message_circle_more_dark')).width(22).height(22).objectFit(ImageFit.Contain);
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
      .margin({ top: 14 });
    }
    .width('88%').padding(20).backgroundColor(T.surface).borderRadius(T.cardRadius)
    .shadow({ radius: 34, color: '#2E191817', offsetY: 12 });
  }

  @Builder
  private PrivacyConsentGate() {
    Column() {
      Scroll() {
        Column() {
          Text('隐私政策与用户协议').width('100%').fontSize(23).fontWeight(FontWeight.Bolder).fontColor(T.ink);
          Text('欢迎使用倾境壁纸。请您在使用前阅读并同意以下协议。')
            .width('100%').fontSize(14).fontColor(T.ink).margin({ top: 14 });
          Text('为了识别当前安装、保护兑换权益并提供下载服务，您同意后 App 将生成匿名安装凭据，并处理平台类型、App 生成的公钥或凭据、兑换、下载及安全记录。我们不读取通讯录、短信、通话记录、位置、摄像头、麦克风或相册内容。')
            .width('100%').fontSize(12).fontColor(T.mutedInk).margin({ top: 12 });
          Text('请分别点击查看完整内容：').width('100%').fontSize(12).fontColor(T.mutedInk).margin({ top: 14 });
          Row({ space: 8 }) {
            Button('《隐私政策》').layoutWeight(1).height(40).fontSize(13).fontWeight(FontWeight.Bold)
              .fontColor(T.accentStrong).backgroundColor(T.accentSoft).borderRadius(20)
              .onClick(() => this.policyDocumentKey = 'PRIVACY');
            Button('《用户协议》').layoutWeight(1).height(40).fontSize(13).fontWeight(FontWeight.Bold)
              .fontColor(T.accentStrong).backgroundColor(T.accentSoft).borderRadius(20)
              .onClick(() => this.policyDocumentKey = 'AGREEMENT');
          }
          .width('100%').margin({ top: 8 });
          Text('点击“同意并继续”即表示您已阅读、理解并同意上述协议。')
            .width('100%').fontSize(12).fontColor(T.mutedInk).margin({ top: 14 });
          if (this.consentError.length > 0) {
            Text(this.consentError).width('100%').fontSize(12).fontColor('#C33A32').margin({ top: 10 });
          }
          Button(this.consentBusy ? '正在保存' : '同意并继续')
            .width('100%').height(48).fontSize(15).fontWeight(FontWeight.Bold)
            .fontColor(T.inverseInk).backgroundColor(T.navigation).borderRadius(24)
            .margin({ top: 20 }).enabled(!this.consentBusy)
            .onClick(() => this.acceptPrivacyPolicy());
          Button('不同意并退出')
            .width('100%').height(46).fontSize(14).fontWeight(FontWeight.Bold)
            .fontColor(T.ink).backgroundColor(T.surface)
            .border({ width: 1, color: T.outlineStrong }).borderRadius(23)
            .margin({ top: 10 }).enabled(!this.consentBusy)
            .onClick(() => this.rejectPrivacyPolicy());
        }
        .width('100%');
      }
      .width('100%').scrollBar(BarState.Off);
    }
    .width('88%').constraintSize({ maxHeight: '84%' }).padding(22)
    .backgroundColor(T.surface).borderRadius(T.cardRadius)
    .shadow({ radius: 34, color: '#2E191817', offsetY: 12 });
  }

  @Builder
  private PolicyOverlay(document: PolicyDocument) {
    Column() {
      Row() {
        Button() {
          Image($r('app.media.chevron_left')).width(20).height(20).objectFit(ImageFit.Contain);
        }
        .width(44).height(44).padding(0).backgroundColor(T.surface)
        .border({ width: 1, color: T.outline }).borderRadius(22)
        .onClick(() => this.policyDocumentKey = '');
        Text(document.title).fontSize(22).fontWeight(FontWeight.Bolder).fontColor(T.ink)
          .layoutWeight(1).margin({ left: 10 });
      }
      .width('100%');
      Scroll() {
        Column() {
          Text('生效日期：' + POLICY_EFFECTIVE_DATE).width('100%').fontSize(12).fontColor(T.mutedInk);
          Text(document.introduction).width('100%').fontSize(14).fontColor(T.ink).margin({ top: 14 });
          ForEach(document.sections, (section: PolicySection) => {
            Text(section.title).width('100%').fontSize(17).fontWeight(FontWeight.Bold)
              .fontColor(T.ink).margin({ top: 22 });
            Text(section.body).width('100%').fontSize(14).fontColor(T.ink).margin({ top: 8 });
          }, (section: PolicySection) => section.title);
        }
        .width('100%').padding({ bottom: 30 });
      }
      .width('100%').layoutWeight(1).scrollBar(BarState.Off).margin({ top: 16 });
    }
    .width('100%').height('100%').padding({ left: T.gutter, right: T.gutter })
    .backgroundColor(T.surface);
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
  private UpdateOverlay() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%').backgroundColor('#A6191817')
        .onClick(() => this.dismissOptionalUpdate());
      Column({ space: 16 }) {
        Text(this.updateMandatory ? '需要更新应用' : '发现新版本')
          .width('100%').fontSize(23).fontWeight(FontWeight.Bolder).fontColor(T.ink);
        Text(this.updateMandatory ? '当前版本已不再支持，请前往应用市场更新至最新版本。' :
          '新版本已可用，前往应用市场即可更新。')
          .width('100%').fontSize(14).fontColor(T.mutedInk);
        Text('当前版本 ' + InstalledAppVersion.info().versionName)
          .width('100%').fontSize(14).fontColor(T.mutedInk);
        if (this.updateLatest !== null) {
          Text('新版本 ' + (this.updateLatest as AppRelease).versionName)
            .width('100%').fontSize(14).fontWeight(FontWeight.Bold).fontColor(T.ink);
          if ((this.updateLatest as AppRelease).releaseNotes.length > 0) {
            Scroll() {
              Text((this.updateLatest as AppRelease).releaseNotes)
                .width('100%').fontSize(14).fontColor(T.mutedInk);
            }
            .width('100%').constraintSize({ maxHeight: 180 }).scrollBar(BarState.Auto);
          }
        }
        if (this.updateMessage.length > 0) {
          Text(this.updateMessage).width('100%').fontSize(13).fontColor('#C33A32');
        }
        Button('立即更新')
          .width('100%').height(48).fontSize(16).fontWeight(FontWeight.Bold)
          .fontColor(T.ink).backgroundColor(T.accent).borderRadius(24)
          .enabled(!this.updateBusy).onClick(() => this.openUpdateStore());
        Button(this.updateBusy ? '正在检查' : '重新检查')
          .width('100%').height(44).fontSize(14).fontWeight(FontWeight.Bold)
          .fontColor(T.ink).backgroundColor(T.surfaceMuted).borderRadius(22)
          .enabled(!this.updateBusy).onClick(() => this.checkForUpdates());
        if (!this.updateMandatory) {
          Button('暂不更新')
            .width('100%').height(42).fontSize(14).fontColor(T.mutedInk)
            .backgroundColor(Color.Transparent).onClick(() => this.dismissOptionalUpdate());
        }
      }
      .width('86%').constraintSize({ maxHeight: '88%' }).padding(24)
      .backgroundColor(T.surface).borderRadius(T.cardRadius)
      .onClick(() => {});
    }
    .width('100%').height('100%').hitTestBehavior(HitTestMode.Default);
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
      if (!this.consentChecked) {
        Column() {
          LoadingProgress().width(42).height(42).color(T.accentStrong);
        }
        .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        .backgroundColor(T.surface);
      } else if (!this.consentAccepted) {
        Column() {
          this.PrivacyConsentGate();
        }
        .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        .backgroundColor(T.surfaceMuted);
      } else if (!this.updateStartupChecked) {
        Column({ space: 14 }) {
          LoadingProgress().width(42).height(42).color(T.accentStrong);
          Text('正在检查应用版本').fontSize(14).fontColor(T.mutedInk);
        }
        .width('100%').height('100%').justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        .backgroundColor(T.surface);
      } else {
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
        .width('100%').height('100%').enabled(!this.updateVisible);
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
      if (this.policyDocumentKey === 'AGREEMENT') {
        this.PolicyOverlay(USER_AGREEMENT);
      }
      if (this.policyDocumentKey === 'PRIVACY') {
        this.PolicyOverlay(PRIVACY_POLICY);
      }
      if (this.consentAccepted && this.updateVisible) this.UpdateOverlay();
    }
    .width('100%').height('100%').backgroundColor(T.surface);
  }

}
