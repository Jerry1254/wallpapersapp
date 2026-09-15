import dayjs from 'dayjs';

import type {
  AdminDashboard,
  CodeBatchDetail,
  CodeBatchSummary,
  CreateCodeBatchResponse,
  DeviceDetail,
  DevicePlatform,
  DeviceStatus,
  DeviceSummary,
  ApiPlatform,
  Category,
  PageMetadata,
  Platform,
  ParallaxPackageFile,
  PublishStatus,
  RedemptionCode,
  RedemptionCodeStatus,
  RedemptionDetail,
  RedemptionResult,
  RedemptionSummary,
  ResourceFile,
  ResourceType,
  ResourceVersionStatus,
  Wallpaper,
  WallpaperKind,
  WallpaperResources,
  WallpaperTutorial,
  WallpaperTutorialKey,
  WallpaperVariant
} from '@/domain/admin';
import { ApiError, apiDownload, apiRequest, apiResourceUrl } from '@/repositories/http/apiClient';

type AssetPurpose = 'CATEGORY_ICON' | 'WALLPAPER_COVER' | 'BACKGROUND' | 'FOREGROUND'
  | 'PARALLAX_CONFIG' | 'VIDEO' | 'LIVE_PHOTO_IMAGE' | 'LIVE_PHOTO_VIDEO'
  | 'STATIC_IMAGE' | 'THEME_PACKAGE' | 'TUTORIAL_VIDEO';
type AssetRole = 'BACKGROUND' | 'FOREGROUND' | 'PARALLAX_CONFIG' | 'VIDEO'
  | 'LIVE_PHOTO_IMAGE' | 'LIVE_PHOTO_VIDEO' | 'STATIC_IMAGE' | 'THEME_PACKAGE';

interface ApiAsset {
  id: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  previewUrl?: string | null;
  validationStatus: string;
}

interface ApiWallpaperTutorial {
  key: WallpaperTutorialKey;
  title: string;
  platform: ApiPlatform;
  wallpaperKind: 'PARALLAX_4D' | 'DYNAMIC' | 'STATIC';
  enabled: boolean;
  sortOrder: number;
  video: ApiAsset | null;
  updatedAt?: string | null;
  version: number;
}

interface ApiCategory {
  id: string;
  parentId: string | null;
  name: string;
  slug: string;
  icon: ApiAsset | null;
  sortOrder: number;
  wallpaperCount: number;
  children: ApiCategory[];
  version: number;
}

interface ApiCategoryList { items: ApiCategory[] }

interface ApiCategorySummary {
  id: string;
  name: string;
  slug: string;
}

interface ApiResourceBinding {
  id: string;
  role: AssetRole;
  ordinal: number;
  asset: ApiAsset;
}

interface ApiResourceVersion {
  id: string;
  versionNo: number;
  status: ResourceVersionStatus;
  bindings: ApiResourceBinding[];
  sourcePackage?: ApiParallaxPackage | null;
}

interface ApiParallaxPackage {
  id: string;
  originalFilename: string;
  mimeType: 'application/zip';
  sizeBytes: number;
  sha256: string;
  validationStatus: 'READY';
  cover: ApiAsset;
  canvas: { width: number; height: number };
  layers: Array<{
    index: number;
    originalFilename: string;
    role: 'BACKGROUND' | 'FOREGROUND';
    ordinal: number;
    depth: number;
    scale: number;
    opacity: number;
    blendMode: 'normal' | 'screen' | 'add';
  }>;
}

interface ApiWallpaperVariant {
  id: string;
  platform: ApiPlatform;
  resourceType: ResourceType;
  resourceVersions: ApiResourceVersion[];
  version: number;
}

interface ApiWallpaperSummary {
  id: string;
  title: string;
  slug: string;
  kind: 'PARALLAX_4D' | 'DYNAMIC' | 'STATIC';
  rootCategory: ApiCategorySummary;
  childCategory: ApiCategorySummary | null;
  cover: ApiAsset;
  sortOrder: number;
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE' | 'ARCHIVED';
  featuredRank?: number | null;
  updatedAt: string;
  version: number;
}

interface ApiWallpaperDetail extends ApiWallpaperSummary {
  copyrightNote: string;
  variants: ApiWallpaperVariant[];
}

interface ApiWallpaperPage {
  items: ApiWallpaperSummary[];
  page: { page: number; pageSize: number; totalItems: number; totalPages: number };
}

interface ApiSession {
  admin: { id: string; username: string };
  csrfToken: string;
  expiresAt: string;
}

interface ApiPage<T> {
  items: T[];
  page: PageMetadata;
}

interface VariantSpec {
  platform: ApiPlatform;
  resourceType: ResourceType;
  bindings: { role: AssetRole; purpose: AssetPurpose; resource: ResourceFile | undefined }[];
  parallaxPackage?: ParallaxPackageFile;
}

const kindToApi: Record<WallpaperKind, ApiWallpaperSummary['kind']> = {
  four_d: 'PARALLAX_4D',
  dynamic: 'DYNAMIC',
  static: 'STATIC'
};
const kindFromApi: Record<ApiWallpaperSummary['kind'], WallpaperKind> = {
  PARALLAX_4D: 'four_d',
  DYNAMIC: 'dynamic',
  STATIC: 'static'
};
const statusFromApi: Record<ApiWallpaperSummary['status'], PublishStatus> = {
  DRAFT: 'draft',
  PUBLISHED: 'published',
  OFFLINE: 'offline',
  ARCHIVED: 'archived'
};

const formatDate = (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm');
const ifMatch = (version: number) => `"${version}"`;
const jsonBody = (value: unknown) => JSON.stringify(value);
const queryString = (input: Record<string, string | number | undefined | null>) => {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(input)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `?${query}` : '';
};
const toResource = (asset: ApiAsset): ResourceFile => ({
  name: asset.originalFilename,
  size: asset.sizeBytes,
  mime: asset.mimeType,
  url: apiResourceUrl(asset.previewUrl),
  assetId: asset.id
});

const toParallaxPackage = (value: ApiParallaxPackage): ParallaxPackageFile => ({
  name: value.originalFilename,
  size: value.sizeBytes,
  mime: value.mimeType,
  packageId: value.id,
  coverAssetId: value.cover.id,
  coverUrl: apiResourceUrl(value.cover.previewUrl),
  layerCount: value.layers.length,
  canvasWidth: value.canvas.width,
  canvasHeight: value.canvas.height
});

const tutorialFromApi = (value: ApiWallpaperTutorial): WallpaperTutorial => ({
  key: value.key,
  title: value.title,
  platform: value.platform,
  wallpaperKind: value.wallpaperKind,
  enabled: value.enabled,
  sortOrder: value.sortOrder,
  video: value.video ? toResource(value.video) : undefined,
  updatedAt: value.updatedAt ? formatDate(value.updatedAt) : null,
  version: value.version
});

const categoryFromApi = (value: ApiCategory): Category => ({
  id: value.id,
  name: value.name,
  slug: value.slug,
  parentId: value.parentId ?? null,
  iconUrl: apiResourceUrl(value.icon?.previewUrl),
  icon: value.icon ? toResource(value.icon) : undefined,
  sort: value.sortOrder,
  wallpaperCount: value.wallpaperCount,
  version: value.version
});

const flattenCategories = (items: ApiCategory[]): Category[] => items.flatMap((item) => [
  categoryFromApi(item),
  ...flattenCategories(item.children || [])
]);

const preferredVersion = (variant: ApiWallpaperVariant) => (
  variant.resourceVersions.find((item) => item.status === 'READY')
  || variant.resourceVersions.find((item) => item.status === 'PUBLISHED')
  || variant.resourceVersions[0]
);

const platformsFromVariants = (kind: WallpaperKind, variants: ApiWallpaperVariant[]): Platform[] => {
  if (kind === 'static' || variants.some((item) => item.platform === 'UNIVERSAL')) return ['android', 'ios', 'harmony'];
  const result: Platform[] = [];
  if (variants.some((item) => item.platform === 'ANDROID')) result.push('android');
  if (variants.some((item) => item.platform === 'IOS')) result.push('ios');
  if (variants.some((item) => item.platform === 'HARMONYOS')) result.push('harmony');
  return result;
};

const resourcesFromApi = (value: ApiWallpaperDetail): WallpaperResources => {
  const resources: WallpaperResources = { cover: toResource(value.cover) };
  for (const variant of value.variants) {
    const version = preferredVersion(variant);
    if (!version) continue;
    if (version.sourcePackage) resources.parallaxPackage = toParallaxPackage(version.sourcePackage);
    for (const binding of version.bindings) {
      const resource = toResource(binding.asset);
      if (binding.role === 'VIDEO' && variant.platform === 'ANDROID') resources.androidVideo = resource;
      if (binding.role === 'LIVE_PHOTO_VIDEO') resources.iosMov = resource;
      if (binding.role === 'LIVE_PHOTO_IMAGE') resources.iosPhoto = resource;
      if (binding.role === 'STATIC_IMAGE') resources.staticImage = resource;
      if (binding.role === 'THEME_PACKAGE') resources.harmonyPackage = resource;
    }
  }
  return resources;
};

const wallpaperFromApi = (value: ApiWallpaperDetail): Wallpaper => {
  const kind = kindFromApi[value.kind];
  return {
    id: value.id,
    title: value.title,
    slug: value.slug,
    categoryId: value.rootCategory.id,
    subcategoryId: value.childCategory?.id || '',
    kind,
    platforms: platformsFromVariants(kind, value.variants),
    status: statusFromApi[value.status],
    sort: value.sortOrder,
    featuredRank: value.featuredRank ?? null,
    coverUrl: apiResourceUrl(value.cover.previewUrl),
    resources: resourcesFromApi(value),
    copyrightNote: value.copyrightNote,
    updatedAt: formatDate(value.updatedAt),
    version: value.version,
    variants: value.variants.map((item) => ({
      id: item.id,
      platform: item.platform,
      resourceType: item.resourceType,
      resourceVersions: item.resourceVersions.map((version) => ({
        id: version.id,
        versionNo: version.versionNo,
        status: version.status
      })),
      version: item.version
    }))
  };
};

const variantSpecs = (value: Wallpaper): VariantSpec[] => {
  if (value.kind === 'four_d') {
    return [{
      platform: 'ANDROID',
      resourceType: 'LAYER_PARALLAX',
      bindings: [],
      parallaxPackage: value.resources.parallaxPackage
    }];
  }
  if (value.kind === 'static') {
    return [{
      platform: 'UNIVERSAL',
      resourceType: 'STATIC_IMAGE',
      bindings: [{ role: 'STATIC_IMAGE', purpose: 'STATIC_IMAGE', resource: value.resources.staticImage }]
    }];
  }
  const result: VariantSpec[] = [];
  if (value.platforms.includes('android')) result.push({
    platform: 'ANDROID', resourceType: 'VIDEO',
    bindings: [{ role: 'VIDEO', purpose: 'VIDEO', resource: value.resources.androidVideo }]
  });
  if (value.platforms.includes('ios')) result.push({
    platform: 'IOS', resourceType: 'LIVE_PHOTO', bindings: [
      { role: 'LIVE_PHOTO_IMAGE', purpose: 'LIVE_PHOTO_IMAGE', resource: value.resources.iosPhoto },
      { role: 'LIVE_PHOTO_VIDEO', purpose: 'LIVE_PHOTO_VIDEO', resource: value.resources.iosMov }
    ]
  });
  if (value.platforms.includes('harmony')) result.push({
    platform: 'HARMONYOS', resourceType: 'THEME_PACKAGE',
    bindings: [{ role: 'THEME_PACKAGE', purpose: 'THEME_PACKAGE', resource: value.resources.harmonyPackage }]
  });
  return result;
};

const uploadAsset = async (resource: ResourceFile, purpose: AssetPurpose) => {
  if (resource.assetId) return resource.assetId;
  if (!resource.nativeFile) throw new ApiError(422, 'ASSET_NOT_READY', `缺少资源：${resource.name}`);
  const form = new FormData();
  form.set('purpose', purpose);
  form.set('file', resource.nativeFile, resource.name);
  const { data } = await apiRequest<ApiAsset>('/admin/assets', { method: 'POST', body: form, csrf: true });
  if (data.validationStatus !== 'READY') throw new ApiError(422, 'ASSET_NOT_READY', `${resource.name} 尚未通过校验`);
  resource.assetId = data.id;
  resource.url = apiResourceUrl(data.previewUrl);
  return data.id;
};

const prepareParallaxPackage = async (resource: ParallaxPackageFile) => {
  if (resource.packageId && resource.coverAssetId) {
    return { packageId: resource.packageId, coverAssetId: resource.coverAssetId };
  }
  if (!resource.nativeFile) throw new ApiError(422, 'PARALLAX_PACKAGE_REQUIRED', '缺少 4D 固定资源包');
  const form = new FormData();
  form.set('file', resource.nativeFile, resource.name);
  const { data } = await apiRequest<ApiParallaxPackage>('/admin/parallax-packages', {
    method: 'POST',
    body: form,
    csrf: true
  });
  if (data.validationStatus !== 'READY') {
    throw new ApiError(422, 'PARALLAX_PACKAGE_INVALID', `${resource.name} 尚未通过解析校验`);
  }
  const parsed = toParallaxPackage(data);
  Object.assign(resource, parsed, { nativeFile: resource.nativeFile });
  return { packageId: data.id, coverAssetId: data.cover.id };
};

const fetchWallpaper = async (id: string) => (
  await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${id}`)
).data;

const eligibleVersion = (variant: ApiWallpaperVariant) => (
  variant.resourceVersions.find((item) => item.status === 'READY')
  || variant.resourceVersions.find((item) => item.status === 'PUBLISHED')
);

const listAllSummaries = async () => {
  const first = (await apiRequest<ApiWallpaperPage>('/admin/wallpapers?page=1&pageSize=100')).data;
  if (first.page.totalPages <= 1) return first.items;
  const pages = await Promise.all(Array.from({ length: first.page.totalPages - 1 }, (_, index) => (
    apiRequest<ApiWallpaperPage>(`/admin/wallpapers?page=${index + 2}&pageSize=100`)
  )));
  return [first.items, ...pages.map((item) => item.data.items)].flat();
};

export class WallpaperSaveError extends Error {
  constructor(public readonly originalError: unknown, public readonly wallpaper: Wallpaper) {
    super('壁纸已保存部分步骤，请修正资源后继续保存');
    this.name = 'WallpaperSaveError';
  }
}

export const adminRepository = {
  async login(username: string, password: string) {
    return (await apiRequest<ApiSession>('/admin/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Request-Id': crypto.randomUUID() },
      body: jsonBody({ username, password })
    })).data;
  },

  async currentSession() {
    return (await apiRequest<ApiSession>('/admin/sessions')).data;
  },

  async logout() {
    await apiRequest<void>('/admin/sessions', { method: 'DELETE', csrf: true });
  },

  async dashboard(): Promise<{ summary: AdminDashboard; wallpapers: Wallpaper[] }> {
    const [summary, wallpapers] = await Promise.all([
      apiRequest<AdminDashboard>('/admin/dashboard'),
      this.wallpapers()
    ]);
    return { summary: summary.data, wallpapers };
  },

  async categories() {
    const { data } = await apiRequest<ApiCategoryList>('/admin/categories');
    return flattenCategories(data.items);
  },

  async tutorials() {
    const { data } = await apiRequest<{ items: ApiWallpaperTutorial[] }>('/admin/wallpaper-tutorials');
    return data.items.map(tutorialFromApi).sort((left, right) => left.sortOrder - right.sortOrder);
  },

  async saveTutorial(input: WallpaperTutorial) {
    if (!input.video) throw new ApiError(422, 'ASSET_NOT_READY', '请上传教程 MP4');
    const videoAssetId = await uploadAsset(input.video, 'TUTORIAL_VIDEO');
    const { data } = await apiRequest<ApiWallpaperTutorial>(`/admin/wallpaper-tutorials/${input.key}`, {
      method: 'PUT',
      headers: { 'If-Match': ifMatch(input.version) },
      body: jsonBody({
        videoAssetId,
        enabled: input.enabled,
        sortOrder: input.sortOrder
      }),
      csrf: true
    });
    return tutorialFromApi(data);
  },

  async saveCategory(input: Category) {
    const iconAssetId = input.parentId === null && input.icon
      ? await uploadAsset(input.icon, 'CATEGORY_ICON')
      : null;
    const payload = jsonBody({
      parentId: input.parentId,
      name: input.name.trim(),
      slug: input.slug.trim(),
      iconAssetId,
      sortOrder: input.sort
    });
    const response = input.id
      ? await apiRequest<ApiCategory>(`/admin/categories/${input.id}`, {
          method: 'PATCH', headers: { 'If-Match': ifMatch(input.version) }, body: payload, csrf: true
        })
      : await apiRequest<ApiCategory>('/admin/categories', { method: 'POST', body: payload, csrf: true });
    return categoryFromApi(response.data);
  },

  async wallpapers() {
    const summaries = await listAllSummaries();
    const details = await Promise.all(summaries.map((item) => fetchWallpaper(item.id)));
    return details.map(wallpaperFromApi);
  },

  async saveWallpaper(input: Wallpaper, publish: boolean) {
    let coverAssetId: string;
    if (input.kind === 'four_d') {
      const sourcePackage = input.resources.parallaxPackage;
      if (sourcePackage) {
        coverAssetId = (await prepareParallaxPackage(sourcePackage)).coverAssetId;
      } else if (input.id && input.resources.cover?.assetId && input.variants.some((variant) =>
        variant.resourceType === 'LAYER_PARALLAX' && variant.resourceVersions.some((version) =>
          ['READY', 'PUBLISHED'].includes(version.status)))) {
        coverAssetId = input.resources.cover.assetId;
      } else {
        throw new ApiError(422, 'PARALLAX_PACKAGE_REQUIRED', '请上传 4D 固定资源包');
      }
    } else {
      const cover = input.resources.cover;
      if (!cover) throw new ApiError(422, 'ASSET_NOT_READY', '请上传列表封面');
      coverAssetId = await uploadAsset(cover, 'WALLPAPER_COVER');
    }
    const payload = jsonBody({
      title: input.title.trim(),
      slug: input.slug.trim(),
      kind: kindToApi[input.kind],
      rootCategoryId: input.categoryId,
      childCategoryId: input.subcategoryId || null,
      coverAssetId,
      featuredRank: input.featuredRank ?? null,
      sortOrder: input.sort,
      copyrightNote: input.copyrightNote.trim()
    });
    let detail: ApiWallpaperDetail | undefined;
    try {
      detail = input.id
        ? (await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${input.id}`, {
            method: 'PATCH', headers: { 'If-Match': ifMatch(input.version) }, body: payload, csrf: true
          })).data
        : (await apiRequest<ApiWallpaperDetail>('/admin/wallpapers', { method: 'POST', body: payload, csrf: true })).data;

      const selectedVersions: string[] = [];
      for (const spec of variantSpecs(input)) {
        let variant = detail.variants.find((item) => item.platform === spec.platform && item.resourceType === spec.resourceType);
        if (!variant) {
          await apiRequest<ApiWallpaperVariant>(`/admin/wallpapers/${detail.id}/variants`, {
            method: 'POST',
            headers: { 'If-Match': ifMatch(detail.version) },
            body: jsonBody({ platform: spec.platform, resourceType: spec.resourceType, minimumOsVersion: null, capabilityRequirements: [] }),
            csrf: true
          });
          detail = await fetchWallpaper(detail.id);
          variant = detail.variants.find((item) => item.platform === spec.platform && item.resourceType === spec.resourceType);
        }
        if (!variant) throw new ApiError(500, 'INTERNAL_ERROR', '资源变体创建后未返回');

        let version = eligibleVersion(variant);
        const hasNewFiles = spec.parallaxPackage
          ? version?.sourcePackage?.id !== spec.parallaxPackage.packageId
          : spec.bindings.some((item) => item.resource?.nativeFile);
        if (!version || hasNewFiles) {
          const versionNo = Math.max(0, ...variant.resourceVersions.map((item) => item.versionNo)) + 1;
          if (spec.resourceType === 'LAYER_PARALLAX' && !spec.parallaxPackage) {
            throw new ApiError(422, 'PARALLAX_PACKAGE_REQUIRED', '请上传 4D 固定资源包');
          }
          if (spec.parallaxPackage) {
            if (!spec.parallaxPackage.packageId) {
              throw new ApiError(422, 'PARALLAX_PACKAGE_REQUIRED', '4D 固定资源包尚未完成服务端解析');
            }
            version = (await apiRequest<ApiResourceVersion>(`/admin/variants/${variant.id}/parallax-resource-versions`, {
              method: 'POST',
              body: jsonBody({ versionNo, sourcePackageId: spec.parallaxPackage.packageId }),
              csrf: true
            })).data;
          } else {
            const bindings = [];
            for (const binding of spec.bindings) {
              if (!binding.resource) throw new ApiError(422, 'ASSET_NOT_READY', `缺少 ${binding.role} 资源`);
              bindings.push({
                assetId: await uploadAsset(binding.resource, binding.purpose),
                role: binding.role,
                ordinal: 0
              });
            }
            version = (await apiRequest<ApiResourceVersion>(`/admin/variants/${variant.id}/resource-versions`, {
              method: 'POST', body: jsonBody({ versionNo, manifestSha256: null, bindings }), csrf: true
            })).data;
          }
        }
        if (publish && version.status === 'READY' &&
            (variant.platform === 'ANDROID' || variant.platform === 'UNIVERSAL') &&
            ['STATIC_IMAGE', 'VIDEO', 'LAYER_PARALLAX'].includes(variant.resourceType)) {
          await apiRequest<ApiResourceVersion>(`/admin/resource-versions/${version.id}/secure-package`, { method: 'POST', csrf: true });
        }
        selectedVersions.push(version.id);
      }

      if (publish) {
        detail = (await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${detail.id}/publish`, {
          method: 'POST',
          headers: { 'If-Match': ifMatch(detail.version) },
          body: jsonBody({ resourceVersionIds: selectedVersions }),
          csrf: true
        })).data;
      } else {
        detail = await fetchWallpaper(detail.id);
      }
      return wallpaperFromApi(detail);
    } catch (cause) {
      // 上传/版本/发布是多个 API 步骤。保留已创建的 ID 与最新锁版本，
      // 让资源失败后的重试继续编辑同一作品，避免重复 Slug 或旧版本冲突。
      if (!detail) throw cause;
      const latest = await fetchWallpaper(detail.id).catch(() => detail!);
      const persisted = wallpaperFromApi(latest);
      throw new WallpaperSaveError(cause, {
        ...input,
        id: persisted.id,
        version: persisted.version,
        status: persisted.status,
        variants: persisted.variants,
        resources: { ...persisted.resources, ...input.resources }
      });
    }
  },

  async publishWallpaper(id: string) {
    const detail = await fetchWallpaper(id);
    for (const variant of detail.variants) {
      const version = eligibleVersion(variant);
      if (version?.status === 'READY' && (variant.platform === 'ANDROID' || variant.platform === 'UNIVERSAL') &&
          ['STATIC_IMAGE', 'VIDEO', 'LAYER_PARALLAX'].includes(variant.resourceType)) {
        await apiRequest<ApiResourceVersion>(`/admin/resource-versions/${version.id}/secure-package`, { method: 'POST', csrf: true });
      }
    }
    const resourceVersionIds = detail.variants.map(eligibleVersion).filter(Boolean).map((item) => item!.id);
    if (!resourceVersionIds.length) throw new ApiError(422, 'RESOURCE_VERSION_NOT_READY', '这张壁纸还没有可发布的资源版本');
    const published = (await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${id}/publish`, {
      method: 'POST', headers: { 'If-Match': ifMatch(detail.version) }, body: jsonBody({ resourceVersionIds }), csrf: true
    })).data;
    return wallpaperFromApi(published);
  },

  async offlineWallpaper(id: string) {
    const detail = await fetchWallpaper(id);
    const offline = (await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${id}/offline`, {
      method: 'POST',
      headers: { 'If-Match': ifMatch(detail.version) },
      body: jsonBody({ reason: '管理员在管理后台执行下架' }),
      csrf: true
    })).data;
    return wallpaperFromApi(offline);
  },

  async deleteWallpaper(value: Wallpaper) {
    const hasHistory = value.variants.some((variant) => variant.resourceVersions.length > 0);
    if (value.status === 'offline' || hasHistory) {
      await apiRequest<ApiWallpaperDetail>(`/admin/wallpapers/${value.id}/archive`, {
        method: 'POST',
        headers: { 'If-Match': ifMatch(value.version) },
        body: jsonBody({ reason: '管理员在管理后台执行归档' }),
        csrf: true
      });
      return;
    }
    await apiRequest<void>(`/admin/wallpapers/${value.id}`, {
      method: 'DELETE', headers: { 'If-Match': ifMatch(value.version) }, csrf: true
    });
  },

  async codeBatches(input: { page?: number; pageSize?: number; q?: string } = {}) {
    return (await apiRequest<ApiPage<CodeBatchSummary>>(`/admin/code-batches${queryString({
      page: input.page || 1,
      pageSize: input.pageSize || 20,
      q: input.q?.trim()
    })}`)).data;
  },

  async codeBatch(id: string) {
    return (await apiRequest<CodeBatchDetail>(`/admin/code-batches/${id}`)).data;
  },

  async createCodeBatch(
    input: { name: string; generatedCount: number; quotaPerCode: number },
    idempotencyKey: string
  ) {
    return (await apiRequest<CreateCodeBatchResponse>('/admin/code-batches', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: jsonBody(input),
      csrf: true
    })).data;
  },

  async downloadCodeBatch(id: string, deliveryTicket: string) {
    return apiDownload(`/admin/code-batches/${id}/delivery`, {
      headers: { 'X-Delivery-Ticket': deliveryTicket }
    });
  },

  async confirmCodeBatchDelivery(id: string) {
    await apiRequest<void>(`/admin/code-batches/${id}/delivery-confirmation`, {
      method: 'POST',
      csrf: true
    });
  },

  async redemptionCodes(
    id: string,
    input: { page?: number; pageSize?: number; status?: RedemptionCodeStatus | ''; suffix?: string } = {}
  ) {
    return (await apiRequest<ApiPage<RedemptionCode>>(`/admin/code-batches/${id}/codes${queryString({
      page: input.page || 1,
      pageSize: input.pageSize || 20,
      status: input.status,
      suffix: input.suffix?.trim()
    })}`)).data;
  },

  async redemptions(input: {
    page?: number;
    pageSize?: number;
    codeSuffix?: string;
    wallpaperId?: string;
    deviceId?: string;
    result?: RedemptionResult | '';
    createdFrom?: string;
    createdTo?: string;
  } = {}) {
    return (await apiRequest<ApiPage<RedemptionSummary>>(`/admin/redemptions${queryString({
      page: input.page || 1,
      pageSize: input.pageSize || 20,
      codeSuffix: input.codeSuffix?.trim(),
      wallpaperId: input.wallpaperId?.trim(),
      deviceId: input.deviceId?.trim(),
      result: input.result,
      createdFrom: input.createdFrom,
      createdTo: input.createdTo
    })}`)).data;
  },

  async redemption(id: string) {
    return (await apiRequest<RedemptionDetail>(`/admin/redemptions/${id}`)).data;
  },

  async devices(input: {
    page?: number;
    pageSize?: number;
    platform?: DevicePlatform | '';
    status?: DeviceStatus | '';
  } = {}) {
    return (await apiRequest<ApiPage<DeviceSummary>>(`/admin/devices${queryString({
      page: input.page || 1,
      pageSize: input.pageSize || 20,
      platform: input.platform,
      status: input.status
    })}`)).data;
  },

  async device(id: string) {
    return (await apiRequest<DeviceDetail>(`/admin/devices/${id}`)).data;
  }
};

export type AdminSession = ApiSession;
