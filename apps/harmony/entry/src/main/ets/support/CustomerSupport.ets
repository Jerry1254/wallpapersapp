import { pasteboard } from '@kit.BasicServicesKit';

export class CustomerSupport {
  static readonly EMAIL: string = 'qingjingwallpaper@126.com';

  static async copyEmail(): Promise<void> {
    const data: pasteboard.PasteData = pasteboard.createData(
      pasteboard.MIMETYPE_TEXT_PLAIN, CustomerSupport.EMAIL);
    await pasteboard.getSystemPasteboard().setData(data);
  }
}
