import '../device/device_session.dart';

String previewFailureMessage(Object failure) {
  if (failure is DeviceApiError) {
    return switch (failure.code) {
      'PREVIEW_PROCESSING' => '预览资源生成中，请稍后',
      'PREVIEW_GENERATION_FAILED' => '预览资源生成失败，请稍后重试',
      'PREVIEW_RESOURCE_NOT_READY' => '此作品的预览资源暂时不可用',
      _ => '预览暂时不可用，请重试',
    };
  }
  return '预览暂时不可用，请重试';
}

bool validPreviewRevision(dynamic value, {int expected = 0}) => value == null
    ? expected == 0
    : value is int && value >= 0 && (expected == 0 || value == expected);
