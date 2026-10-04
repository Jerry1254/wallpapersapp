import { common, wantConstant } from '@kit.AbilityKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { image } from '@kit.ImageKit';
import { sensor } from '@kit.SensorServiceKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import wallpaper from '@ohos.wallpaper';

// 只引用随普通 SDK 发布的 API。setVideo / setCustomWallpaper 和 WallpaperExtensionAbility
// 属于系统专用能力；不能把动态预览、静态截图或悬浮窗当成设置系统动态壁纸成功。
@Entry
@Component
struct Index {
  @State private tab: number = 0;
  @State private ready: boolean = false;
  @State private busy: boolean = false;
  @State private videoSource: string = '';
  @State private videoTitle: string = '内置循环测试视频';
  @State private savedVideoUri: string = '';
  @State private offsetX: number = 0;
  @State private offsetY: number = 0;
  @State private gravityRunning: boolean = false;
  @State private entries: string[] = ['等待手动测试。图库保存成功不代表系统壁纸设置成功。'];
  @StorageLink('labForeground') @Watch('foregroundChanged') private foreground: boolean = true;
  private controller: VideoController = new VideoController();
  private videoPath: string = '';
  private baselineY: number = 0;
  private calibrated: boolean = false;
  private gravityCallback: (event: sensor.GravityResponse) => void = (event: sensor.GravityResponse) => {
    if (!this.calibrated) {
      this.baselineY = event.y;
      this.calibrated = true;
    }
    const x = Math.max(-1, Math.min(1, event.x / 5));
    const y = Math.max(-1, Math.min(1, (event.y - this.baselineY) / 5));
    this.offsetX = this.offsetX * 0.78 + x * 0.22;
    this.offsetY = this.offsetY * 0.78 + y * 0.22;
  };

  aboutToAppear(): void { this.prepareSample(); }
  aboutToDisappear(): void { this.stopGravity(); }

  private context(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private note(message: string): void {
    const stamp = new Date().toLocaleTimeString();
    this.entries = [`${stamp} ${message}`, ...this.entries].slice(0, 18);
    hilog.info(0x514C, 'WallpaperLab', '%{public}s', message);
  }

  private error(operation: string, value: BusinessError): void {
    this.note(`${operation}失败：${value.code ?? '未知错误'} ${value.message ?? ''}`);
  }

  private async prepareSample(): Promise<void> {
    try {
      const bytes = await this.context().resourceManager.getRawFileContent('motion-test.mp4');
      this.videoPath = this.context().cacheDir + '/motion-test.mp4';
      const file = await fs.open(this.videoPath, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try { await fs.write(file.fd, bytes.buffer); } finally { await fs.close(file); }
      this.videoSource = fileUri.getUriFromPath(this.videoPath);
      this.ready = true;
      this.note(`系统：${deviceInfo.osFullName}；SDK：${deviceInfo.sdkApiVersion}；样本已就绪。`);
    } catch (error) { this.error('准备视频', error as BusinessError); }
  }

  private async chooseVideo(): Promise<void> {
    if (this.busy) { return; }
    this.busy = true;
    try {
      const options = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.VIDEO_TYPE;
      options.maxSelectNumber = 1;
      const selected = await new photoAccessHelper.PhotoViewPicker().select(options);
      if (selected.photoUris.length === 0) { return; }
      if (!/\.mp4(?:[?#]|$)/i.test(selected.photoUris[0])) {
        this.note('本轮只测试 MP4，请重新选择以 .mp4 结尾的视频。');
        return;
      }
      const source = await fs.open(selected.photoUris[0], fs.OpenMode.READ_ONLY);
      const path = this.context().cacheDir + `/selected-${Date.now()}.mp4`;
      try { await fs.copyFile(source.fd, path); } finally { await fs.close(source); }
      this.videoPath = path;
      this.videoSource = fileUri.getUriFromPath(path);
      this.videoTitle = '自己选择的视频（原文件，不转码）';
      this.savedVideoUri = '';
      this.note('已选取视频，未修改时长、帧率和画质。请使用 MP4 测试。');
    } catch (error) { this.error('选取视频', error as BusinessError); }
    finally { this.busy = false; }
  }

  private async saveVideo(): Promise<void> {
    if (this.busy || !this.ready) { return; }
    this.busy = true;
    const helper = photoAccessHelper.getPhotoAccessHelper(this.context());
    try {
      const request = photoAccessHelper.MediaAssetChangeRequest.createAssetRequest(
        this.context(), photoAccessHelper.PhotoType.VIDEO, 'mp4', { title: `壁纸实验视频_${Date.now()}` });
      request.addResource(photoAccessHelper.ResourceType.VIDEO_RESOURCE, fileUri.getUriFromPath(this.videoPath));
      // 由系统 SaveButton 本次点击授予保存权限，不申请全图库写入权限。
      await helper.applyChanges(request);
      this.savedVideoUri = request.getAsset().uri;
      this.note('视频已保存图库。下一步在图库检查是否有设置桌面／锁屏的入口，保存成功不代表可设置。');
    } catch (error) { this.error('保存视频', error as BusinessError); }
    finally {
      try { await helper.release(); } catch (error) { this.error('释放图库访问', error as BusinessError); }
      this.busy = false;
    }
  }

  private async openSavedVideo(): Promise<void> {
    try {
      await this.context().startAbility({
        action: 'ohos.want.action.viewData',
        uri: this.savedVideoUri,
        type: 'video/mp4',
        flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION
      });
      this.note('已请求系统打开视频，壁纸入口是否存在以系统页面为准。');
    } catch (error) {
      this.error('系统打开视频', error as BusinessError);
      this.note('可以手动回到图库，打开最新保存的视频继续检查。');
    }
  }

  private async setStatic(lockscreen: boolean): Promise<void> {
    if (this.busy) { return; }
    this.busy = true;
    let source: image.ImageSource | undefined = undefined;
    let pixels: image.PixelMap | undefined = undefined;
    try {
      const bytes = await this.context().resourceManager.getRawFileContent('static-test.png');
      source = image.createImageSource(bytes.buffer);
      pixels = await source.createPixelMap();
      // 旧版公开静态接口作为对照组；成功也只能说明静态壁纸可设置。
      await wallpaper.setWallpaper(pixels, lockscreen ? wallpaper.WallpaperType.WALLPAPER_LOCKSCREEN :
        wallpaper.WallpaperType.WALLPAPER_SYSTEM);
      this.note(`${lockscreen ? '锁屏' : '桌面'}静态接口返回成功，请离开 App 检查实际显示。`);
    } catch (error) { this.error('设置静态壁纸', error as BusinessError); }
    finally {
      if (pixels !== undefined) { await pixels.release(); }
      if (source !== undefined) { await source.release(); }
      this.busy = false;
    }
  }

  private foregroundChanged(): void {
    if (this.foreground && this.tab === 1) { this.startGravity(); }
    else {
      this.stopGravity();
      try { this.controller.pause(); } catch (error) { /* 视频尚未创建时无需暂停。 */ }
    }
  }

  private selectTab(value: number): void {
    this.stopGravity();
    this.tab = value;
    if (value === 1) { this.startGravity(); }
  }

  private startGravity(): void {
    if (this.gravityRunning || !this.foreground) { return; }
    try {
      this.calibrated = false;
      sensor.on(sensor.SensorId.GRAVITY, this.gravityCallback, { interval: 33333333 });
      this.gravityRunning = true;
      this.note('重力传感器已订阅，仅驱动当前 App 内的前后图层。');
    } catch (error) { this.error('重力传感器', error as BusinessError); }
  }

  private stopGravity(): void {
    if (!this.gravityRunning) { return; }
    try { sensor.off(sensor.SensorId.GRAVITY, this.gravityCallback); }
    catch (error) { this.error('关闭重力传感器', error as BusinessError); }
    this.gravityRunning = false;
  }

  @Builder private VideoPanel() {
    Text(this.videoTitle).fontSize(15).fontColor('#D9E2F0');
    if (this.ready) {
      Video({ src: this.videoSource, controller: this.controller })
        .width('100%').height(280).objectFit(ImageFit.Contain).muted(true).autoPlay(false).loop(true).controls(true)
        .onError(() => this.note('视频无法播放，请换用标准 MP4 视频。'));
    }
    Button('选择自己的 MP4').width('100%').enabled(!this.busy).onClick(() => this.chooseVideo());
    SaveButton({ text: SaveDescription.SAVE_TO_GALLERY, buttonType: ButtonType.Capsule })
      .width('100%').height(46).enabled(this.ready && !this.busy)
      .onClick((event: ClickEvent, result: SaveButtonOnClickResult) => {
        if (result === SaveButtonOnClickResult.SUCCESS) { this.saveVideo(); }
        else { this.note('未获得本次保存授权。'); }
      });
    if (this.savedVideoUri !== '') {
      Button('在系统中打开已保存视频').width('100%').onClick(() => this.openSavedVideo());
    }
    Text('验证步骤：保存 → 图库打开 → 查看“更多／设置为” → 分别检查桌面和锁屏。')
      .fontSize(14).fontColor('#F4CD70');
    Text('直接设置视频的 setVideo 是系统接口，本工具不调用。这里验证系统能否接收普通视频并让用户设置。')
      .fontSize(13).fontColor('#A7B6CC');
  }

  @Builder private GravityPanel() {
    Stack() {
      Column() {}.backgroundColor('#102743').width('120%').height('120%');
      Column() {}.backgroundColor('#E6C778').width(120).height(120).borderRadius(60)
        .translate({ x: 50 + this.offsetX * 12, y: -65 + this.offsetY * 12 });
      Column() {}.backgroundColor('#235E78').width(310).height(230).rotate({ angle: -24 })
        .translate({ x: -65 + this.offsetX * 24, y: 100 + this.offsetY * 24 });
      Column() {}.backgroundColor('#418F9E').width(300).height(210).rotate({ angle: 23 })
        .translate({ x: 85 + this.offsetX * 42, y: 145 + this.offsetY * 42 });
      Text('前景 · 4D').fontSize(30).fontWeight(FontWeight.Bold).fontColor('#FFFFFF')
        .translate({ x: this.offsetX * 60, y: 45 + this.offsetY * 60 });
    }.width('100%').height(300).clip(true).borderRadius(20);
    Text('倾斜手机，观察前后层位移').fontSize(16).fontColor('#F4CD70');
    Button('以当前姿势重新校准').width('100%').onClick(() => this.calibrated = false);
    Text('这里只验证 App 内的重力渲染。系统 WallpaperExtensionAbility 仅供系统应用使用，当前未找到普通 App 设置为桌面或锁屏的公开通道。')
      .fontSize(14).fontColor('#A7B6CC');
  }

  @Builder private StaticPanel() {
    Image($rawfile('static-test.png')).width('100%').height(280).objectFit(ImageFit.Contain);
    Text('静态对照组：点击后会替换相应的当前壁纸。').fontSize(14).fontColor('#F4CD70');
    Button('设置桌面静态壁纸').width('100%').enabled(!this.busy).onClick(() => this.setStatic(false));
    Button('设置锁屏静态壁纸').width('100%').enabled(!this.busy).onClick(() => this.setStatic(true));
    Text('该公开接口已废弃，用于记录这台设备是否仍支持。静态成功不代表视频或 4D 可设置。')
      .fontSize(13).fontColor('#A7B6CC');
  }

  build() {
    Scroll() {
      Column({ space: 16 }) {
        Text('鸿蒙壁纸实验室').fontSize(26).fontWeight(FontWeight.Bold).fontColor('#FFFFFF');
        Text('独立测试 · 公共接口 · 视频 / 重力 / 静态').fontSize(13).fontColor('#A7B6CC');
        Row({ space: 8 }) {
          ForEach(['视频', '4D 重力', '静态对照'], (title: string, index: number) => {
            Button(title).layoutWeight(1).fontSize(14)
              .backgroundColor(this.tab === index ? '#F4CD70' : '#24344D')
              .fontColor(this.tab === index ? '#101827' : '#FFFFFF')
              .onClick(() => this.selectTab(index));
          });
        }.width('100%');
        if (this.tab === 0) { this.VideoPanel(); }
        else if (this.tab === 1) { this.GravityPanel(); }
        else { this.StaticPanel(); }
        Divider().color('#34445C');
        Text('测试记录').width('100%').fontSize(18).fontColor('#FFFFFF');
        ForEach(this.entries, (line: string) => {
          Text(line).width('100%').fontSize(12).fontColor('#AFBCD0');
        }, (line: string) => line);
      }.width('100%').padding({ left: 22, right: 22, top: 28, bottom: 36 });
    }.width('100%').height('100%').backgroundColor('#101827');
  }
}
