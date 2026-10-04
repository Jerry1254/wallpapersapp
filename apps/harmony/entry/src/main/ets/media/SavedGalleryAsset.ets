import { common, wantConstant } from '@kit.AbilityKit';

export class SavedGalleryAsset {
  readonly uri: string;
  readonly mimeType: string;

  constructor(uri: string, mimeType: string) {
    this.uri = uri;
    this.mimeType = mimeType;
  }

  async open(context: common.UIAbilityContext): Promise<void> {
    if (this.uri.length === 0) throw new Error('没有获得已保存图片的图库地址');
    await context.startAbility({
      bundleName: 'com.huawei.hmos.photos',
      abilityName: 'com.huawei.hmos.photos.MainAbility',
      action: 'ohos.want.action.viewData',
      uri: this.uri,
      type: this.mimeType,
      parameters: { uri: this.uri },
      flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION
    });
  }
}
