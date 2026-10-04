import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/detail/preview_availability.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

void main() {
  test('processing and failed previews have retryable messages', () {
    expect(
      previewFailureMessage(const DeviceApiError(503, 'PREVIEW_PROCESSING')),
      '预览资源生成中，请稍后',
    );
    expect(
      previewFailureMessage(
        const DeviceApiError(503, 'PREVIEW_GENERATION_FAILED'),
      ),
      '预览资源生成失败，请稍后重试',
    );
  });

  test(
    'preview revisions accept legacy responses only without a known revision',
    () {
      expect(validPreviewRevision(null), isTrue);
      expect(validPreviewRevision(null, expected: 8), isFalse);
      expect(validPreviewRevision(7, expected: 8), isFalse);
      expect(validPreviewRevision(8, expected: 8), isTrue);
      expect(validPreviewRevision('8', expected: 8), isFalse);
      expect(validPreviewRevision(-1), isFalse);
    },
  );
}
