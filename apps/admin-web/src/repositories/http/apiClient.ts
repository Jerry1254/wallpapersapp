interface ErrorDetail {
  field?: string;
  reason: string;
}

interface ErrorEnvelope {
  error?: {
    code?: string;
    message?: string;
    requestId?: string;
    details?: ErrorDetail[];
  };
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
    public readonly details: ErrorDetail[] = []
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface ApiResponse<T> {
  data: T;
  etag: string | null;
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
let csrfToken = '';
let unauthorizedHandler: (() => void) | undefined;

export const setCsrfToken = (value: string) => { csrfToken = value; };
export const setUnauthorizedHandler = (handler: () => void) => { unauthorizedHandler = handler; };
export const apiResourceUrl = (path?: string | null) => path ? `${apiBaseUrl}${path}` : '';

const isBodyInit = (value: unknown): value is BodyInit => (
  typeof value === 'string'
  || value instanceof FormData
  || value instanceof Blob
  || value instanceof URLSearchParams
  || value instanceof ArrayBuffer
  || ArrayBuffer.isView(value)
);

const throwResponseError = async (response: Response): Promise<never> => {
  let envelope: ErrorEnvelope = {};
  try {
    envelope = await response.json() as ErrorEnvelope;
  } catch {
    // 保留统一的 HTTP 回退错误。
  }
  if (response.status === 401) {
    unauthorizedHandler?.();
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('qingjing:session-expired'));
  }
  throw new ApiError(
    response.status,
    envelope.error?.code || `HTTP_${response.status}`,
    envelope.error?.message || `请求失败（HTTP ${response.status}）`,
    envelope.error?.requestId,
    envelope.error?.details || []
  );
};

// 包括响应体读取，避免服务无响应时一直保持“保存中”。上传给予更长窗口。
const executeRequest = async <T>(path: string, options: RequestInit, read: (response: Response) => Promise<T>): Promise<T> => {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (options.signal?.aborted) abort();
  options.signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; abort(); }, options.body instanceof FormData ? 60_000 : 15_000);
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1${path}`, { ...options, signal: controller.signal, credentials: 'include', cache: 'no-store' });
    if (!response.ok) return await throwResponseError(response);
    return await read(response);
  } catch (cause) {
    if (cause instanceof ApiError) throw cause;
    if (timedOut) throw new ApiError(0, 'REQUEST_TIMEOUT', '请求超时，请先刷新确认操作结果后再重试');
    if (options.signal?.aborted) throw new ApiError(0, 'REQUEST_CANCELLED', '请求已取消');
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查网络后重试；提交过的操作请先刷新确认结果');
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abort);
  }
};

export const apiRequest = async <T>(
  path: string,
  options: RequestInit & { csrf?: boolean } = {}
): Promise<ApiResponse<T>> => {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  let body = options.body;
  if (typeof body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (body !== undefined && body !== null && !isBodyInit(body)) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(body);
  }
  if (options.csrf) {
    if (!csrfToken) throw new ApiError(401, 'SESSION_EXPIRED', '管理员会话已失效，请重新登录');
    headers.set('X-CSRF-Token', csrfToken);
    headers.set('X-Request-Id', crypto.randomUUID());
  }

  return executeRequest(path, {
    ...options,
    body,
    headers,
    credentials: 'include'
  }, async (response) => {
    const data = response.status === 204 ? undefined as T : await response.json() as T;
    return { data, etag: response.headers.get('ETag') };
  });
};

export const apiDownload = async (
  path: string,
  options: RequestInit = {}
): Promise<{ data: Blob; filename: string }> => {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'text/csv');
  return executeRequest(path, {
    ...options,
    headers,
    credentials: 'include'
  }, async (response) => {
    const disposition = response.headers.get('Content-Disposition') || '';
    const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || 'codes.csv';
    return { data: await response.blob(), filename };
  });
};

const errorLabels: Record<string, string> = {
  VERSION_CONFLICT: '数据已被更新，请刷新后重试',
  SESSION_EXPIRED: '登录已过期，请重新登录', UNAUTHORIZED: '登录已过期，请重新登录',
  CSRF_INVALID: '登录状态已变化，请重新登录后再操作',
  DUPLICATE_SLUG: '此 Slug 已被使用，请换一个', DUPLICATE_CATEGORY_NAME: '同级分类名称已存在，请换一个名称',
  DUPLICATE_VARIANT: '此平台的资源组合已存在，请刷新后编辑',
  ASSET_NOT_READY: '资源尚未就绪，请补齐资源并检查校验结果',
  RESOURCE_VERSION_NOT_READY: '尚无可发布的资源版本，请补齐资源并检查校验结果',
  ASSET_VALIDATION_FAILED: '资源校验失败，请检查文件内容、尺寸、透明通道或 4D 配置版本',
  PARALLAX_PACKAGE_REQUIRED: '请上传固定格式的 4D ZIP 资源包',
  PARALLAX_PACKAGE_INVALID: '4D ZIP 解析失败，请检查固定目录、图层编号和 config.json',
  PARALLAX_PACKAGE_IN_USE: '4D ZIP 已绑定资源版本，不能删除',
  PAYLOAD_TOO_LARGE: '文件超过上传大小限制，请选择更小的文件',
  UNSUPPORTED_MEDIA_TYPE: '文件格式不支持或与实际内容不符，请按上传说明选择文件',
  DOMAIN_RULE_VIOLATION: '内容不符合发布规则，请检查分类、平台和资源组合',
  RESOURCE_IN_USE: '内容已有引用，请先移除引用；有交付历史的壁纸请下架或归档',
  STATE_CONFLICT: '当前状态无法执行此操作，请刷新并检查内容状态',
  WALLPAPER_NOT_FOUND: '壁纸已不存在，请刷新列表', CATEGORY_NOT_FOUND: '分类已不存在，请刷新列表',
  ASSET_NOT_FOUND: '资源已不存在，请重新上传', RESOURCE_NOT_FOUND: '资源版本已不存在，请刷新',
  TUTORIAL_NOT_FOUND: '教程配置不存在，请刷新',
  TUTORIAL_VIDEO_REQUIRED: '启用教程前请上传 MP4 视频',
  TUTORIAL_ASSET_IN_USE: '教程视频仍在使用，暂时不能清理',
  DEVICE_NOT_FOUND: '设备已不存在，请刷新列表', REDEMPTION_NOT_FOUND: '兑换记录已不存在，请刷新列表',
  CODE_BATCH_NOT_FOUND: '兑换码批次已不存在，请刷新列表',
  DELIVERY_EXPIRED: '明文交付已确认或过期，无法再次下载；只能查看掩码和额度',
  DELIVERY_TICKET_INVALID: '交付票据无效或已过期，请检查本次交付状态',
  IDEMPOTENCY_KEY_REUSED: '本次请求内容已变化，请关闭后重新填写',
  CODE_GENERATION_CONFLICT: '兑换码生成未完成，请稍后重试', RATE_LIMITED: '操作过于频繁，请稍后再试',
  MALFORMED_REQUEST: '请求格式不正确，请刷新页面后重试', INTERNAL_ERROR: '服务暂时不可用，请稍后重试',
  HTTP_500: '服务暂时不可用，请稍后重试', HTTP_502: '无法连接服务，请稍后重试', HTTP_503: '服务暂时不可用，请稍后重试',
  REQUEST_TIMEOUT: '请求超时，请先刷新确认操作结果后再重试',
  REQUEST_CANCELLED: '请求已取消', NETWORK_ERROR: '无法连接服务，请检查网络后重试；提交过的操作请先刷新确认结果'
};
const fieldLabels: Record<string, string> = {
  name: '名称', title: '壁纸名称', slug: 'Slug', parentId: '所属节点', rootCategoryId: '一级分类', childCategoryId: '二级分类',
  sortOrder: '排序值', featuredRank: '精选排序', copyrightNote: '版权说明', videoAssetId: '教程视频', sourcePackageId: '4D 固定资源包', generatedCount: '生成数量', quotaPerCode: '每码设备数',
  suffix: '末位码', wallpaperId: '壁纸 ID', deviceId: '设备 ID', codeSuffix: '兑换码末位', page: '页码', pageSize: '每页数量'
};

export const readableApiError = (cause: unknown, fallback = '操作失败') => {
  if (!(cause instanceof ApiError)) return cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message) ? cause.message : fallback;
  if (cause.code === 'VALIDATION_FAILED') {
    const field = cause.details[0]?.field;
    return field && fieldLabels[field] ? `${fieldLabels[field]}不符合要求，请检查填写内容` : '填写内容不符合要求，请检查格式与取值范围';
  }
  // Repository 的本地校验可直接提供具体中文提示。
  if (/[\u4e00-\u9fff]/.test(cause.message) && !cause.code.startsWith('HTTP_')) return cause.message;
  return errorLabels[cause.code] || fallback;
};
