import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { SavedGalleryAsset } from './SavedGalleryAsset';

export class StaticImageStore {
  static async save(context: common.UIAbilityContext, imagePath: string, title: string,
    extension: string): Promise<SavedGalleryAsset> {
    const request = photoAccessHelper.MediaAssetChangeRequest.createAssetRequest(
      context,
      photoAccessHelper.PhotoType.IMAGE,
      extension,
      { title: title }
    );
    request.addResource(photoAccessHelper.ResourceType.IMAGE_RESOURCE, 'file://' + imagePath);
    await photoAccessHelper.getPhotoAccessHelper(context).applyChanges(request);
    const mimeType: string = extension === 'jpg' ? 'image/jpeg' : 'image/' + extension;
    return new SavedGalleryAsset(request.getAsset().uri, mimeType);
  }
}
