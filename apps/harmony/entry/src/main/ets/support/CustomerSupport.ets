import { common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';

export class CustomerSupport {
  static readonly WECHAT_ID: string = 'qingjing_service';

  static async copyWechat(): Promise<void> {
    const data: pasteboard.PasteData = pasteboard.createData(
      pasteboard.MIMETYPE_TEXT_PLAIN, CustomerSupport.WECHAT_ID);
    await pasteboard.getSystemPasteboard().setData(data);
  }

  static async saveQr(context: common.UIAbilityContext): Promise<void> {
    const bytes: Uint8Array = await context.resourceManager.getRawFileContent('customer-service-qr.png');
    const request: photoAccessHelper.MediaAssetChangeRequest =
      photoAccessHelper.MediaAssetChangeRequest.createAssetRequest(
        context,
        photoAccessHelper.PhotoType.IMAGE,
        'png',
        { title: '倾境壁纸客服二维码' }
      );
    request.addResource(photoAccessHelper.ResourceType.IMAGE_RESOURCE, bytes.buffer as ArrayBuffer);
    await photoAccessHelper.getPhotoAccessHelper(context).applyChanges(request);
  }
}
