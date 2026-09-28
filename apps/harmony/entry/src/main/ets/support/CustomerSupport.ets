import { pasteboard } from '@kit.BasicServicesKit';

export class CustomerSupport {
  static readonly WECHAT_ID: string = 'qingjing_service';

  static async copyWechat(): Promise<void> {
    const data: pasteboard.PasteData = pasteboard.createData(
      pasteboard.MIMETYPE_TEXT_PLAIN, CustomerSupport.WECHAT_ID);
    await pasteboard.getSystemPasteboard().setData(data);
  }
}
