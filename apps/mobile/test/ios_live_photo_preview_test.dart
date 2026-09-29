import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/detail/ios_live_photo_preview.dart';

void main() {
  Map<String, dynamic> descriptor() => {
    'deliveryMode': 'LIVE_PHOTO_PREVIEW',
    'purpose': 'APP_PREVIEW',
    'durationSeconds': 1,
    'wallpaperId': '7',
    'resourceVersion': {'platform': 'IOS', 'resourceType': 'LIVE_PHOTO'},
    'video': {
      'url': '/api/v1/preview/live-photo/video',
      'mimeType': 'video/quicktime',
      'sizeBytes': 1024,
      'sha256': 'a' * 64,
    },
  };

  test('iOS dynamic preview accepts only the exact ticket-bound video', () {
    expect(
      validIosLivePhotoPreviewDescriptor(
        descriptor(),
        wallpaperId: '7',
        deliveryPlatform: 'IOS',
        resourceType: 'LIVE_PHOTO',
      ),
      isTrue,
    );
    for (final mutation in <void Function(Map<String, dynamic>)>[
      (value) => value['durationSeconds'] = 2,
      (value) => value['wallpaperId'] = '8',
      (value) =>
          (value['video'] as Map)['url'] = '/api/v1/delivery/live-photo/video',
      (value) => (value['video'] as Map)['sha256'] = 'invalid',
    ]) {
      final value = descriptor();
      mutation(value);
      expect(
        validIosLivePhotoPreviewDescriptor(
          value,
          wallpaperId: '7',
          deliveryPlatform: 'IOS',
          resourceType: 'LIVE_PHOTO',
        ),
        isFalse,
      );
    }
  });
}
