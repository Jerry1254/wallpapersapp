import { http } from '@kit.NetworkKit';

export class ApiRequestError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export class JsonHttp {
  static async request(url: string, method: http.RequestMethod, body: Object | null = null,
    bearer: string = ''): Promise<Object> {
    const request: http.HttpRequest = http.createHttp();
    const headers: Record<string, string> = {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    };
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
      if (body !== null) options.extraData = JSON.stringify(body);
      const response: http.HttpResponse = await request.request(url, options);
      const text: string = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      const status: number = response.responseCode as number;
      if (status < 200 || status >= 300) {
        throw new ApiRequestError(status, JsonHttp.errorMessage(status));
      }
      return text.length === 0 ? new Object() : JSON.parse(text) as Object;
    } finally {
      request.destroy();
    }
  }

  private static errorMessage(status: number): string {
    if (status === 401 || status === 403) return '鸿蒙设备身份服务尚未开通';
    if (status === 404) return '内容不存在或已经下线';
    if (status === 429) return '操作太频繁，请稍后重试';
    return '暂时无法连接服务，请检查网络后重试';
  }
}
