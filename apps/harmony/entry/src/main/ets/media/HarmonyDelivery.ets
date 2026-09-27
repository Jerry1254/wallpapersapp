import { common } from '@kit.AbilityKit';
import { fileIo, hash } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { DownloadDescriptor, DeliveryFile } from '../network/CatalogApi';
import { MovingPhotoStore } from './MovingPhotoStore';

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

  static async saveDownloaded(context: common.UIAbilityContext, movingPhoto: DownloadedMovingPhoto): Promise<void> {
    try {
      await MovingPhotoStore.save(context, movingPhoto.posterPath, movingPhoto.videoPath, movingPhoto.title);
    } finally {
      await HarmonyDelivery.discard(movingPhoto);
    }
  }

  static async discard(movingPhoto: DownloadedMovingPhoto): Promise<void> {
    await HarmonyDelivery.removeIfPresent(movingPhoto.posterPath);
    await HarmonyDelivery.removeIfPresent(movingPhoto.videoPath);
  }

  private static async downloadPart(file: DeliveryFile, ticket: string, destination: string,
    onProgress: (received: number) => void): Promise<void> {
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
      const status: number = await request.requestInStream(HarmonyDelivery.absoluteUrl(file.url), {
        method: http.RequestMethod.GET,
        header: {
          'Accept': file.mimeType,
          'Authorization': 'Bearer ' + ticket
        },
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

  private static async removeIfPresent(path: string): Promise<void> {
    try {
      await fileIo.unlink(path);
    } catch (error) {
    }
  }
}
