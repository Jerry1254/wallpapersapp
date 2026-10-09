import { common } from '@kit.AbilityKit';
import { fileIo, hash } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { DownloadDescriptor, DeliveryFile, PreviewDescriptor } from '../network/CatalogApi';
import { MovingPhotoStore } from './MovingPhotoStore';
import { StaticImageStore } from './StaticImageStore';
import { SavedGalleryAsset } from './SavedGalleryAsset';
import { InstalledAppVersion } from '../update/AppUpdateEvents';
import { SecurityEvents } from '../security/SecurityEvents';

export type DeliveryProgress = (received: number, total: number) => void;

export class DownloadedMovingPhoto {
  posterPath: string;
  videoPath: string;
  title: string;

  constructor(posterPath: string, videoPath: string, title: string) {
    this.posterPath = posterPath;
    this.videoPath = videoPath;
    this.title = title;
  }
}

export class DownloadedStaticImage {
  imagePath: string;
  title: string;
  extension: string;

  constructor(imagePath: string, title: string, extension: string) {
    this.imagePath = imagePath;
    this.title = title;
    this.extension = extension;
  }
}

export class HarmonyDelivery {
  private static readonly ORIGIN: string = 'https://wallpaper.biguo66.top';

  static async downloadMovingPhoto(context: common.UIAbilityContext, descriptor: DownloadDescriptor, title: string,
    onProgress: DeliveryProgress): Promise<DownloadedMovingPhoto> {
    if (descriptor.deliveryMode !== 'MOVING_PHOTO' || descriptor.ticket.length < 32 ||
      descriptor.poster === undefined || descriptor.video === undefined) {
      throw new Error('服务端没有返回可用的鸿蒙动态照片');
    }
    const poster: DeliveryFile = descriptor.poster as DeliveryFile;
    const video: DeliveryFile = descriptor.video as DeliveryFile;
    if (poster.mimeType !== 'image/jpeg' || video.mimeType !== 'video/mp4') {
      throw new Error('鸿蒙动态照片格式不正确');
    }
    const safeId: string = descriptor.wallpaperId.replace(/[^0-9A-Za-z_-]/g, '');
    const posterPath: string = context.cacheDir + '/moving-photo-' + safeId + '.jpg';
    const videoPath: string = context.cacheDir + '/moving-photo-' + safeId + '.mp4';
    const total: number = poster.sizeBytes + video.sizeBytes;
    try {
      await HarmonyDelivery.downloadPart(poster, descriptor.ticket, posterPath,
        (received: number) => onProgress(received, total));
      await HarmonyDelivery.downloadPart(video, descriptor.ticket, videoPath,
        (received: number) => onProgress(poster.sizeBytes + received, total));
      const safeTitle: string = title.replace(/[\\/:*?"'`<>|{}\[\]]/g, '').substring(0, 80) || '倾境动态壁纸';
      onProgress(total, total);
      return new DownloadedMovingPhoto(posterPath, videoPath, safeTitle);
    } catch (error) {
      await HarmonyDelivery.removeIfPresent(posterPath);
      await HarmonyDelivery.removeIfPresent(videoPath);
      if (error instanceof Error) throw error;
      throw new Error('鸿蒙动态壁纸下载失败');
    }
  }

  static async downloadStaticImage(context: common.UIAbilityContext, descriptor: DownloadDescriptor, title: string,
    onProgress: DeliveryProgress): Promise<DownloadedStaticImage> {
    if (descriptor.deliveryMode !== 'STATIC_IMAGE' || descriptor.ticket.length < 32 || descriptor.image === undefined) {
      throw new Error('服务端没有返回可用的静态原图');
    }
    const image: DeliveryFile = descriptor.image as DeliveryFile;
    const extension: string = HarmonyDelivery.imageExtension(image.mimeType);
    const safeId: string = descriptor.wallpaperId.replace(/[^0-9A-Za-z_-]/g, '');
    const imagePath: string = context.cacheDir + '/static-wallpaper-' + safeId + '.' + extension;
    try {
      await HarmonyDelivery.downloadPart(image, descriptor.ticket, imagePath,
        (received: number) => onProgress(received, image.sizeBytes));
      const safeTitle: string = title.replace(/[\\/:*?"'`<>|{}\[\]]/g, '').substring(0, 80) || '倾境静态壁纸';
      onProgress(image.sizeBytes, image.sizeBytes);
      return new DownloadedStaticImage(imagePath, safeTitle, extension);
    } catch (error) {
      await HarmonyDelivery.removeIfPresent(imagePath);
      if (error instanceof Error) throw error;
      throw new Error('静态壁纸下载失败');
    }
  }

  static async downloadMovingPhotoPreview(context: common.UIAbilityContext, descriptor: PreviewDescriptor,
    wallpaperId: string): Promise<string> {
    if (descriptor.deliveryMode !== 'MOVING_PHOTO_PREVIEW' || descriptor.purpose !== 'APP_PREVIEW' ||
      descriptor.wallpaperId !== wallpaperId || descriptor.durationSeconds < 1 || descriptor.durationSeconds > 2 ||
      descriptor.ticket.length < 32 || descriptor.video === undefined) {
      throw new Error('服务端没有返回可用的鸿蒙动态预览');
    }
    const video: DeliveryFile = descriptor.video as DeliveryFile;
    if (video.mimeType !== 'video/mp4' || video.url !== '/api/v1/preview/moving-photo/video') {
      throw new Error('鸿蒙动态预览格式不正确');
    }
    const safeId: string = wallpaperId.replace(/[^0-9A-Za-z_-]/g, '');
    const videoPath: string = context.cacheDir + '/moving-photo-preview-' + safeId + '-' + Date.now().toString() + '.mp4';
    try {
      await HarmonyDelivery.downloadPart(video, descriptor.ticket, videoPath, (_received: number) => {});
      return videoPath;
    } catch (error) {
      await HarmonyDelivery.removeIfPresent(videoPath);
      if (error instanceof Error) throw error;
      throw new Error('鸿蒙动态预览加载失败');
    }
  }

  static async discardPreview(path: string): Promise<void> {
    if (path.length > 0) await HarmonyDelivery.removeIfPresent(path);
  }

  static async saveDownloaded(context: common.UIAbilityContext,
    movingPhoto: DownloadedMovingPhoto): Promise<SavedGalleryAsset> {
    try {
      return await MovingPhotoStore.save(context, movingPhoto.posterPath, movingPhoto.videoPath, movingPhoto.title);
    } finally {
      await HarmonyDelivery.discard(movingPhoto);
    }
  }

  static async discard(movingPhoto: DownloadedMovingPhoto): Promise<void> {
    await HarmonyDelivery.removeIfPresent(movingPhoto.posterPath);
    await HarmonyDelivery.removeIfPresent(movingPhoto.videoPath);
  }

  static async saveStaticImage(context: common.UIAbilityContext,
    image: DownloadedStaticImage): Promise<SavedGalleryAsset> {
    try {
      return await StaticImageStore.save(context, image.imagePath, image.title, image.extension);
    } finally {
      await HarmonyDelivery.discardStaticImage(image);
    }
  }

  static async discardStaticImage(image: DownloadedStaticImage): Promise<void> {
    await HarmonyDelivery.removeIfPresent(image.imagePath);
  }

  private static async downloadPart(file: DeliveryFile, ticket: string, destination: string,
    onProgress: (received: number) => void): Promise<void> {
    SecurityEvents.requireAllowed(HarmonyDelivery.absoluteUrl(file.url));
    const request: http.HttpRequest = http.createHttp();
    const output: fileIo.File = await fileIo.open(destination,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    let received: number = 0;
    let writeFailure: string = '';
    request.on('dataReceive', (data: ArrayBuffer) => {
      if (writeFailure.length > 0) return;
      try {
        received += fileIo.writeSync(output.fd, data);
        onProgress(received);
      } catch (error) {
        writeFailure = '写入下载文件失败';
      }
    });
    try {
      const headers: Record<string, string> = InstalledAppVersion.headers();
      headers['Accept'] = file.mimeType;
      headers['Authorization'] = 'Bearer ' + ticket;
      const status: number = await request.requestInStream(HarmonyDelivery.absoluteUrl(file.url), {
        method: http.RequestMethod.GET,
        header: headers,
        connectTimeout: 15000,
        readTimeout: 120000,
        usingCache: false
      });
      if (status !== 200) throw new Error(status === 401 ? '下载凭证已过期，请重试' : '壁纸资源下载失败');
      if (writeFailure.length > 0) throw new Error(writeFailure);
    } finally {
      request.destroy();
      await fileIo.close(output);
    }
    if (received !== file.sizeBytes) throw new Error('壁纸资源下载不完整，请重试');
    const digest: string = await hash.hash(destination, 'sha256');
    if (digest.toLowerCase() !== file.sha256.toLowerCase()) throw new Error('壁纸资源校验失败，请重试');
  }

  private static absoluteUrl(path: string): string {
    if (path.startsWith('https://')) return path;
    return path.startsWith('/') ? HarmonyDelivery.ORIGIN + path : HarmonyDelivery.ORIGIN + '/' + path;
  }

  private static imageExtension(mimeType: string): string {
    if (mimeType === 'image/jpeg') return 'jpg';
    if (mimeType === 'image/png') return 'png';
    if (mimeType === 'image/webp') return 'webp';
    throw new Error('静态原图格式不正确');
  }

  private static async removeIfPresent(path: string): Promise<void> {
    try {
      await fileIo.unlink(path);
    } catch (error) {
    }
  }
}
