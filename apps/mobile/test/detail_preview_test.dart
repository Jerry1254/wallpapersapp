import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/detail/detail_preview.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';
import 'package:wallpaper_android/wallpaper_android.dart';

class _UnusedTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw UnimplementedError();
}

class _PreviewSessions extends DeviceSessionManager {
  _PreviewSessions(this.resourceType) : super(_UnusedTransport());
  final String resourceType;
  String get deliveryPlatform =>
      resourceType == 'STATIC_IMAGE' ? 'UNIVERSAL' : 'ANDROID';
  int requests = 0;
  bool unavailable = false;

  @override
  Future<String> ensureEncryptionKey() async => 'binding';

  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    requests++;
    expect(path, '/device/wallpapers/10/preview-tickets');
    expect(method, 'POST');
    expect(signed, isTrue);
    expect(jsonDecode(body!), {
      'deliveryPlatform': deliveryPlatform,
      'resourceType': resourceType,
    });
    if (unavailable) {
      throw const DeviceApiError(409, 'PREVIEW_PROCESSING');
    }
    return {
      'deliveryMode': 'APP_PREVIEW',
      'purpose': 'APP_PREVIEW',
      'durationSeconds': 120,
      'previewRevision': 7,
      'wallpaperId': '10',
      'downloadUrl': '/api/v1/preview/files',
      'package': {'formatVersion': 3, 'encryptionKeySha256': 'binding'},
      'resourceVersion': {
        'platform': deliveryPlatform,
        'resourceType': resourceType,
      },
    };
  }
}

class _FormalInstaller extends AndroidPackageInstaller {
  int lookups = 0;
  @override
  Future<String?> current(String wallpaperId, String resourceType) async {
    lookups++;
    return 'formal-installed';
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const native = MethodChannel('qingjing/wallpaper_android');
  const platformViews = MethodChannel('flutter/platform_views');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(native, null);
    messenger.setMockMethodCallHandler(platformViews, null);
  });

  for (final type in ['LAYER_PARALLAX', 'VIDEO', 'STATIC_IMAGE']) {
    for (final unavailable in [false, true]) {
      testWidgets(
        '$type 已下载仍使用预览，资源${unavailable ? '生成中不回退原版' : '就绪不切换原版'}',
        (tester) async {
          final sessions = _PreviewSessions(type)..unavailable = unavailable;
          final installer = _FormalInstaller();
          final manager = DownloadManager(
            sessions,
            Uri.parse('https://example.test/api/v1'),
            installer: installer,
            ios: false,
          );
          final previewId = '10-$type-7-${'a' * 64}';
          final handles = <String>[];
          final surfaces = <Map<Object?, Object?>>[];
          messenger.setMockMethodCallHandler(native, (call) async {
            expect(call.method, 'installDetailPreview');
            final arguments = Map<String, dynamic>.from(call.arguments as Map);
            expect(
              arguments['url'],
              'https://example.test/api/v1/preview/files',
            );
            expect((arguments['descriptor'] as Map)['purpose'], 'APP_PREVIEW');
            return {'previewId': previewId, 'resourceType': type};
          });
          messenger.setMockMethodCallHandler(platformViews, (call) async {
            if (call.method == 'create') {
              final arguments = call.arguments as Map;
              surfaces.add(
                const StandardMessageCodec().decodeMessage(
                      ByteData.sublistView(arguments['params'] as Uint8List),
                    )
                    as Map<Object?, Object?>,
              );
            }
            return null;
          });

          Widget preview() => MaterialApp(
            home: Scaffold(
              body: DetailPreview(
                manager: manager,
                wallpaperId: '10',
                deliveryPlatform: sessions.deliveryPlatform,
                resourceType: type,
                previewRevision: 7,
                cover: const ColoredBox(color: Colors.black),
                onInstalled: handles.add,
              ),
            ),
          );

          await tester.pumpWidget(preview());
          await tester.pumpAndSettle();
          expect(sessions.requests, 1);
          expect(installer.lookups, 0);
          expect(handles, unavailable ? isEmpty : [previewId]);
          expect(surfaces, unavailable ? isEmpty : hasLength(1));
          if (!unavailable) {
            expect(surfaces.single['installedId'], previewId);
            expect(surfaces.single['restricted'], isTrue);
          }

          manager.value = DownloadState(
            status: 'completed',
            wallpaperId: '10',
            deliveryPlatform: sessions.deliveryPlatform,
            resourceType: type,
            installedId: 'formal-installed',
          );
          await tester.pumpAndSettle();
          expect(handles, unavailable ? isEmpty : [previewId]);
          expect(surfaces, unavailable ? isEmpty : hasLength(1));
          if (unavailable) {
            expect(find.text('预览资源生成中，请稍后'), findsOneWidget);
          }
          expect(tester.takeException(), isNull);

          // Reopening the page after a formal download also stays on previews.
          await tester.pumpWidget(const SizedBox());
          await tester.pumpAndSettle();
          await tester.pumpWidget(preview());
          await tester.pumpAndSettle();
          expect(sessions.requests, 2);
          expect(installer.lookups, 0);
          expect(handles, unavailable ? isEmpty : [previewId, previewId]);
          await tester.pumpWidget(const SizedBox());
          await tester.pumpAndSettle();
          manager.dispose();
        },
        variant: TargetPlatformVariant.only(TargetPlatform.android),
      );
    }
  }
}
