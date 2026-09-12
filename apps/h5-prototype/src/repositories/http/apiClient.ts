interface ErrorEnvelope {
  error?: {
    code?: string;
    message?: string;
    requestId?: string;
  };
}

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message);
    this.name = 'ApiClientError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

export interface ApiRequestOptions {
  method?: 'GET' | 'POST';
  body?: string;
  headers?: Record<string, string>;
  acceptedStatuses?: number[];
}

export const apiRequest = async <T>(path: string, options: ApiRequestOptions = {}): Promise<T> => {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 15000);
  try {
    const response = await fetch(`/api/v1${path}`, {
      method: options.method ?? 'GET',
      body: options.body,
      headers: { Accept: 'application/json', ...options.headers },
      signal: timeout.signal
    });
    if (response.ok || options.acceptedStatuses?.includes(response.status)) return await response.json() as T;

    let envelope: ErrorEnvelope = {};
    try {
      envelope = await response.json() as ErrorEnvelope;
    } catch {
      // The status still carries enough information for a stable client error.
    }
    throw new ApiClientError(
      response.status,
      envelope.error?.code ?? 'REQUEST_FAILED',
      envelope.error?.message ?? '服务暂时不可用，请稍后重试',
      envelope.error?.requestId
    );
  } catch (error) {
    if (error instanceof ApiClientError) throw error;
    throw new ApiClientError(0, 'NETWORK_ERROR', '暂时无法连接服务，请检查网络后重试');
  } finally {
    clearTimeout(timer);
  }
};

export const catalogErrorMessage = (error: unknown) => {
  if (error instanceof ApiClientError) {
    if (error.status === 404) return '内容不存在或已经下线';
    if (error.status === 400) return '查询参数无效，请调整后重试';
    if (error.status >= 500) return '服务暂时不可用，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
};
