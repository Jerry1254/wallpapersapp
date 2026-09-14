import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

/// Separate localDebug entry, installed with adb install -r, never test teardown.
/// Fixture installation proves native interoperability, not business entitlement.
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kDebugMode || appFlavor != 'local') {
    throw StateError('Native probe requires localDebug');
  }
  runApp(const MaterialApp(home: NativeProbe()));
}

class NativeProbe extends StatefulWidget {
  const NativeProbe({super.key});
  @override
  State<NativeProbe> createState() => _NativeProbeState();
}

class _NativeProbeState extends State<NativeProbe> {
  static const channel = MethodChannel('qingjing/wallpaper_android');
  static const origin = 'http://127.0.0.1:8082';
  final installer = AndroidPackageInstaller();
  final playback = const AndroidWallpaperPlayback();
  final ids = <String, String>{};
  String output = 'WP-A07 原生夹具测试，非正式业务下载';
  bool busy = false;

  Future<void> action(String name, Future<Object?> Function() run) async {
    setState(() => busy = true);
    debugPrint('A07_PROBE_BEGIN $name');
    try {
      final value = await run();
      final text = '$name ${jsonEncode(value)}';
      debugPrint('A07_PROBE_RESULT $text');
      if (mounted) setState(() => output = text);
    } catch (error) {
      // Do not log HTTP descriptors, tickets, public-key requests or content keys.
      final text = '$name failed ${error.runtimeType}';
      debugPrint('A07_PROBE_ERROR $text');
      if (mounted) setState(() => output = text);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<Object?> state() async =>
      channel.invokeMapMethod<String, dynamic>('debugPlaybackState');

  Future<Object?> install() async {
    const identity = AndroidDeviceIdentity();
    await identity.installation();
    final key = await identity.encryptionPublicKey();
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      for (final type in ['STATIC_IMAGE', 'VIDEO', 'LAYER_PARALLAX']) {
        final request = await client.postUrl(Uri.parse('$origin/fixture'));
        request.headers.contentType = ContentType.json;
        final body = utf8.encode(
          jsonEncode({
            'resourceType': type,
            'mode': 'valid',
            'publicKeyPem': key['publicKeyPem'],
          }),
        );
        request.contentLength = body.length;
        request.add(body);
        final response = await request.close().timeout(
          const Duration(seconds: 15),
        );
        if (response.statusCode != 200) throw StateError('Fixture unavailable');
        final descriptor =
            jsonDecode(await utf8.decoder.bind(response).join())
                as Map<String, dynamic>;
        final id = requestUuid();
        installer.prepare(
          SecurePackageDownload(
            requestId: id,
            wallpaperId: '101',
            resourceType: type,
            url: Uri.parse('$origin/api/v1/delivery/files'),
            descriptor: descriptor,
          ),
        );
        final result = await installer.install(id);
        if (result.status != OperationStatus.completed ||
            result.value == null) {
          throw StateError('Install rejected');
        }
        ids[type] = result.value!;
      }
      return ids;
    } finally {
      client.close(force: true);
    }
  }

  Future<String> id(String type) async =>
      ids[type] ??
      await installer.current('101', type) ??
      (throw StateError('Install fixture first'));

  Future<Object?> preview(WallpaperEffect effect) async {
    final result = await playback.open(
      await id(AndroidWallpaperPlayback.resourceType(effect)),
      effect,
    );
    return {'status': result.status.name, 'message': result.message};
  }

  Future<Object?> apply(WallpaperEffect effect, WallpaperTarget target) async {
    final before = await state();
    final result = await playback.apply(
      await id(AndroidWallpaperPlayback.resourceType(effect)),
      effect,
      target,
    );
    return {
      'status': result.status.name,
      'message': result.message,
      'before': before,
      'after': await state(),
    };
  }

  @override
  Widget build(BuildContext context) {
    final commands = <String, Future<Object?> Function()>{
      '查询手机能力': () =>
          channel.invokeMapMethod<String, dynamic>('playbackCapabilities'),
      '安装三类夹具': install,
      '静态预览': () => preview(WallpaperEffect.staticImage),
      '静态桌面': () => apply(WallpaperEffect.staticImage, WallpaperTarget.home),
      '静态锁屏': () => apply(WallpaperEffect.staticImage, WallpaperTarget.lock),
      '静态两者': () => apply(WallpaperEffect.staticImage, WallpaperTarget.both),
      '视频系统设置': () => apply(WallpaperEffect.video, WallpaperTarget.home),
      '视频独立预览': () => preview(WallpaperEffect.video),
      '查询播放状态': state,
      '清理未使用缓存': () async => {
        'removed': await installer.clearUnused(),
        'video': await installer.current('101', 'VIDEO'),
      },
    };
    return Scaffold(
      appBar: AppBar(title: const Text('WP-A07 真机原生测试')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            for (final entry in commands.entries)
              FilledButton(
                onPressed: busy ? null : () => action(entry.key, entry.value),
                child: Text(entry.key),
              ),
            if (busy) const LinearProgressIndicator(),
            Text(output),
          ],
        ),
      ),
    );
  }
}
