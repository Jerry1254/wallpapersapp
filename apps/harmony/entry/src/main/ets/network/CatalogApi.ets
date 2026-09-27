import { http } from '@kit.NetworkKit';
import {
  ApiCategoryPayload,
  ApiTutorialPayload,
  ApiWallpaperPayload,
  HarmonyCatalogProjection,
  WallpaperCategory,
  WallpaperSummary,
  WallpaperTutorial
} from '../model/CatalogModels';
import { HarmonyDeviceSession } from './HarmonyDeviceSession';
import { ApiRequestError, JsonHttp } from './JsonHttp';

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

export class CatalogApi {
  static readonly BASE_URL: string = 'https://wallpaper.biguo66.top/api/v1';
  private sessions: HarmonyDeviceSession;

  constructor(sessions: HarmonyDeviceSession) {
    this.sessions = sessions;
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
    if (projected === null) throw new ApiRequestError(404, '该壁纸没有鸿蒙可用资源');
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
