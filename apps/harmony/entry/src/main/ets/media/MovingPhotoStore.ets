import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';

export class MovingPhotoStore {
  static async save(context: common.UIAbilityContext, posterPath: string, videoPath: string,
    title: string): Promise<void> {
    const request = photoAccessHelper.MediaAssetChangeRequest.createAssetRequest(
      context,
      photoAccessHelper.PhotoType.IMAGE,
      'jpg',
      {
        title: title,
        subtype: photoAccessHelper.PhotoSubtype.MOVING_PHOTO
      }
    );
    request.addResource(photoAccessHelper.ResourceType.IMAGE_RESOURCE, 'file://' + posterPath);
    request.addResource(photoAccessHelper.ResourceType.VIDEO_RESOURCE, 'file://' + videoPath);
    await photoAccessHelper.getPhotoAccessHelper(context).applyChanges(request);
  }
}
