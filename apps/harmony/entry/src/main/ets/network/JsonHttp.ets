import { http } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const LOG_DOMAIN: number = 0x514A;

export class ApiRequestError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

interface ErrorDetail {
  code?: string;
}

interface ErrorEnvelope {
  error?: ErrorDetail;
}

export class JsonHttp {
  static async request(url: string, method: http.RequestMethod, body: Object | string | null = null,
    bearer: string = '', extraHeaders: Record<string, string> = {}, acceptedStatuses: number[] = []): Promise<Object> {
    const request: http.HttpRequest = http.createHttp();
    const headers: Record<string, string> = {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    };
    Object.keys(extraHeaders).forEach((key: string) => headers[key] = extraHeaders[key]);
    if (bearer.length > 0) headers['Authorization'] = 'Bearer ' + bearer;
    try {
      const options: http.HttpRequestOptions = {
        method: method,
        header: headers,
        connectTimeout: 15000,
        readTimeout: 15000,
        usingCache: false,
        expectDataType: http.HttpDataType.STRING
      };
      if (body !== null) options.extraData = typeof body === 'string' ? body : JSON.stringify(body);
      const response: http.HttpResponse = await request.request(url, options);
      const text: string = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      const status: number = response.responseCode as number;
      if ((status < 200 || status >= 300) && acceptedStatuses.indexOf(status) < 0) {
        const code: string = JsonHttp.errorCode(text);
        hilog.error(LOG_DOMAIN, 'QingjingApi', 'HTTP %{public}d %{public}s %{public}s', status, code, text);
        throw new ApiRequestError(status, code, JsonHttp.errorMessage(status, code));
      }
      return text.length === 0 ? new Object() : JSON.parse(text) as Object;
    } finally {
      request.destroy();
    }
  }

  private static errorCode(text: string): string {
    try {
      const body: ErrorEnvelope = JSON.parse(text) as ErrorEnvelope;
      return body.error?.code ?? '';
    } catch (error) {
      return '';
    }
  }

  private static errorMessage(status: number, code: string): string {
    const suffix: string = code.length > 0 ? '（' + code + '）' : '';
    if (status === 401 || status === 403) return '鸿蒙设备身份验证失败' + suffix;
    if (status === 404) return '内容不存在或已经下线';
    if (status === 429) return '操作太频繁，请稍后重试';
    return '暂时无法连接服务，请检查网络后重试' + suffix;
  }
}
