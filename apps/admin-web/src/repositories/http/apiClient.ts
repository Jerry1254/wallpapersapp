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

  const response = await fetch(`${apiBaseUrl}/api/v1${path}`, {
    ...options,
    body,
    headers,
    credentials: 'include'
  });
  if (!response.ok) {
    return throwResponseError(response);
  }

  const data = response.status === 204 ? undefined as T : await response.json() as T;
  return { data, etag: response.headers.get('ETag') };
};

export const apiDownload = async (
  path: string,
  options: RequestInit = {}
): Promise<{ data: Blob; filename: string }> => {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'text/csv');
  const response = await fetch(`${apiBaseUrl}/api/v1${path}`, {
    ...options,
    headers,
    credentials: 'include'
  });
  if (!response.ok) return throwResponseError(response);
  const disposition = response.headers.get('Content-Disposition') || '';
  const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || 'codes.csv';
  return { data: await response.blob(), filename };
};

export const readableApiError = (cause: unknown, fallback = '操作失败') => {
  if (!(cause instanceof ApiError)) return cause instanceof Error ? cause.message : fallback;
  const detail = cause.details[0];
  if (cause.code === 'VERSION_CONFLICT') return '数据已被更新，请刷新后重试';
  if (cause.code === 'SESSION_EXPIRED' || cause.code === 'UNAUTHORIZED') return '登录已过期，请重新登录';
  if (cause.code === 'ASSET_NOT_READY') return '资源尚未校验完成';
  return detail?.field ? `${detail.field}：${detail.reason}` : (detail?.reason || cause.message || fallback);
};
