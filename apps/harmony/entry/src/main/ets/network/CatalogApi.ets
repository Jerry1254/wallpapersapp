import { http } from '@kit.NetworkKit';
import {
  ApiCategoryPayload,
  ApiTutorialPayload,
  ApiWallpaperPayload,
  HarmonyCatalogProjection,
  WallpaperCapability,
  WallpaperCategory,
  WallpaperSummary,
  WallpaperTutorial
} from '../model/CatalogModels';
import { HarmonyDeviceSession } from './HarmonyDeviceSession';
import { ApiRequestError, JsonHttp } from './JsonHttp';
import { util } from '@kit.ArkTS';

interface SupportUnreadResponse {
  unreadCount: number;
}

interface CategoryListResponse {
  items: ApiCategoryPayload[];
}

interface WallpaperPageResponse {
  items: ApiWallpaperPayload[];
}

interface TutorialListResponse {
  items: ApiTutorialPayload[];
}

interface EntitlementPayload {
  wallpaper: ApiWallpaperPayload;
}

interface EntitlementPageResponse {
  items: EntitlementPayload[];
}

interface RedemptionResponse {
  status?: string;
  idempotencyKey: string;
  result?: string;
}

interface RedemptionRequest {
  wallpaperId: string;
  code: string;
}

interface DownloadTicketRequest {
  deliveryPlatform: string;
  resourceType: string;
}

interface PreviewTicketRequest {
  deliveryPlatform: string;
  resourceType: string;
}

export interface DeliveryFile {
  url: string;
  sha256: string;
  sizeBytes: number;
  mimeType: string;
}

export interface DownloadDescriptor {
  deliveryMode: string;
  wallpaperId: string;
  ticket: string;
  poster?: DeliveryFile;
  video?: DeliveryFile;
  image?: DeliveryFile;
}

export interface PreviewDescriptor {
  deliveryMode: string;
  purpose: string;
  durationSeconds: number;
  wallpaperId: string;
  previewRevision?: number;
  ticket: string;
  video?: DeliveryFile;
}

export class CatalogApi {
  static readonly BASE_URL: string = 'https://wallpaper.biguo66.top/api/v1';
  private sessions: HarmonyDeviceSession;

  constructor(sessions: HarmonyDeviceSession) {
    this.sessions = sessions;
  }

  async supportUnread(): Promise<number> {
    const response: SupportUnreadResponse = await this.authenticatedGet('/device/support/conversation') as SupportUnreadResponse;
    return response.unreadCount;
  }

  async categories(): Promise<WallpaperCategory[]> {
    const response: CategoryListResponse = await this.authenticatedGet('/public/categories') as CategoryListResponse;
    const result: WallpaperCategory[] = [];
    response.items.forEach((item: ApiCategoryPayload) => result.push(WallpaperCategory.fromApi(item)));
    return result;
  }

  async wallpapers(filter: string, query: string = '', categoryId: string = ''): Promise<WallpaperSummary[]> {
    const parameters: string[] = ['page=1', 'pageSize=100'];
    if (query.length > 0) parameters.push('q=' + encodeURIComponent(query));
    if (categoryId.length > 0) parameters.push('rootCategoryId=' + encodeURIComponent(categoryId));
    if (filter === HarmonyCatalogProjection.FILTER_FREE) parameters.push('accessType=FREE');
    if (filter === HarmonyCatalogProjection.FILTER_MOVING_PHOTO) {
      parameters.push('deliveryPlatform=HARMONYOS');
      parameters.push('resourceType=MOVING_PHOTO');
    } else if (filter === HarmonyCatalogProjection.FILTER_STATIC) {
      parameters.push('deliveryPlatform=UNIVERSAL');
      parameters.push('resourceType=STATIC_IMAGE');
    } else if (query.length === 0 && categoryId.length === 0) {
      parameters.push('view=FEATURED');
    }
    const response: WallpaperPageResponse = await this.authenticatedGet(
      '/public/wallpapers?' + parameters.join('&')) as WallpaperPageResponse;
    const source: WallpaperSummary[] = [];
    response.items.forEach((item: ApiWallpaperPayload) => source.push(WallpaperSummary.fromApi(item)));
    return HarmonyCatalogProjection.projectCatalog(source);
  }

  async wallpaper(id: string): Promise<WallpaperSummary> {
    const response: ApiWallpaperPayload = await this.authenticatedGet(
      '/public/wallpapers/' + encodeURIComponent(id)) as ApiWallpaperPayload;
    const projected: WallpaperSummary | null = HarmonyCatalogProjection.projectWallpaper(
      WallpaperSummary.fromApi(response));
    if (projected === null) {
      throw new ApiRequestError(404, 'WALLPAPER_NOT_AVAILABLE_FOR_PLATFORM', '该壁纸没有鸿蒙可用资源');
    }
    return projected as WallpaperSummary;
  }

  async tutorials(): Promise<WallpaperTutorial[]> {
    const response: TutorialListResponse = await JsonHttp.request(
      CatalogApi.BASE_URL + '/public/wallpaper-tutorials', http.RequestMethod.GET) as TutorialListResponse;
    const result: WallpaperTutorial[] = [];
    response.items.forEach((item: ApiTutorialPayload) => {
      if (item.key === 'HARMONYOS_DYNAMIC' || item.key === 'STATIC') {
        result.push(WallpaperTutorial.fromApi(item));
      }
    });
    result.sort((left: WallpaperTutorial, right: WallpaperTutorial) => left.sortOrder - right.sortOrder);
    return result;
  }

  async entitlements(): Promise<WallpaperSummary[]> {
    const response: EntitlementPageResponse = await this.authenticatedGet(
      '/device/me/entitlements?page=1&pageSize=100') as EntitlementPageResponse;
    const source: WallpaperSummary[] = [];
    response.items.forEach((item: EntitlementPayload) => source.push(WallpaperSummary.fromApi(item.wallpaper)));
    return HarmonyCatalogProjection.projectCatalog(source);
  }

  async owns(wallpaperId: string): Promise<boolean> {
    const items: WallpaperSummary[] = await this.entitlements();
    return items.some((item: WallpaperSummary) => item.id === wallpaperId);
  }

  async redeem(wallpaperId: string, suppliedCode: string): Promise<string> {
    const code: string = suppliedCode.replace(/[-\s]/g, '').toUpperCase();
    if (!/^[A-Z0-9]{20}$/.test(code)) throw new Error('请输入 20 位兑换码');
    const key: string = util.generateRandomUUID();
    const request: RedemptionRequest = { wallpaperId: wallpaperId, code: code };
    const headers: Record<string, string> = {};
    headers['Idempotency-Key'] = key;
    let response: RedemptionResponse = await this.sessions.signedPost('/device/redemptions',
      request, headers, [422]) as RedemptionResponse;
    for (let attempt: number = 0; response.status === 'PROCESSING' && attempt < 4; attempt++) {
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 350));
      response = await this.authenticatedGet('/device/redemptions/' + encodeURIComponent(key)) as RedemptionResponse;
    }
    if (response.status === 'PROCESSING') throw new Error('兑换正在处理中，请稍后重试');
    if (response.idempotencyKey !== key) throw new Error('兑换结果校验失败，请重试');
    switch (response.result) {
      case 'GRANTED': return '兑换成功';
      case 'ALREADY_OWNED': return '已拥有这张壁纸';
      case 'CODE_NOT_FOUND': throw new Error('兑换码无效');
      case 'CODE_EXHAUSTED': throw new Error('兑换码额度已用尽');
      case 'WALLPAPER_UNAVAILABLE': throw new Error('这张壁纸暂不可兑换');
      case 'WALLPAPER_FREE': return '这张壁纸已经免费';
      default: throw new Error('兑换未成功，请联系客服');
    }
  }

  async downloadTicket(wallpaperId: string, capability: WallpaperCapability): Promise<DownloadDescriptor> {
    const request: DownloadTicketRequest = {
      deliveryPlatform: capability.deliveryPlatform,
      resourceType: capability.resourceType
    };
    return await this.sessions.signedPost('/device/wallpapers/' + encodeURIComponent(wallpaperId) +
      '/download-tickets', request) as DownloadDescriptor;
  }

  async previewTicket(wallpaperId: string, capability: WallpaperCapability): Promise<PreviewDescriptor> {
    const request: PreviewTicketRequest = {
      deliveryPlatform: capability.deliveryPlatform,
      resourceType: capability.resourceType
    };
    return await this.sessions.signedPost('/device/wallpapers/' + encodeURIComponent(wallpaperId) +
      '/preview-tickets', request) as PreviewDescriptor;
  }

  mediaUrl(path: string): string {
    if (path.startsWith('https://')) return path;
    if (path.startsWith('/')) return 'https://wallpaper.biguo66.top' + path;
    return CatalogApi.BASE_URL + '/' + path;
  }

  private async authenticatedGet(path: string): Promise<Object> {
    let token: string = await this.sessions.accessToken();
    try {
      return await JsonHttp.request(CatalogApi.BASE_URL + path, http.RequestMethod.GET, null, token);
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 401) {
        this.sessions.invalidate();
        token = await this.sessions.accessToken();
        return await JsonHttp.request(CatalogApi.BASE_URL + path, http.RequestMethod.GET, null, token);
      }
      if (error instanceof Error) throw error;
      throw new Error('暂时无法连接服务，请稍后重试');
    }
  }
}
