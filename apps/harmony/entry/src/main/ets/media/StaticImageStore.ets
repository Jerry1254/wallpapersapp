import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';

export class StaticImageStore {
  static async save(context: common.UIAbilityContext, imagePath: string, title: string,
    extension: string): Promise<void> {
    const request = photoAccessHelper.MediaAssetChangeRequest.createAssetRequest(
      context,
      photoAccessHelper.PhotoType.IMAGE,
      extension,
      { title: title }
    );
    request.addResource(photoAccessHelper.ResourceType.IMAGE_RESOURCE, 'file://' + imagePath);
    await photoAccessHelper.getPhotoAccessHelper(context).applyChanges(request);
  }
}
