import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/updates/app_updates.dart';

AppRelease release(
  int code, {
  String? name,
  String? url,
  String? hash,
  int? size,
}) => AppRelease({
  'id': '$code',
  'versionName': name ?? '1.0.$code',
  'versionCode': code,
  'releaseNotes': '修复已知问题',
  'storeUrl': 'https://apps.apple.com/cn/app/id123456',
  'downloadUrl': url,
  'sha256': hash,
  'fileSize': size,
});

class FakePlatform implements AppUpdatePlatform {
  InstalledAppVersion current = const InstalledAppVersion(
    'android',
    '1.0.1',
    1,
  );
  String? saved;
  String? path;
  int installs = 0, storeOpens = 0;
  @override
  Future<InstalledAppVersion> installed() async => current;
  @override
  Future<String?> cached(String scope) async => saved;
  @override
  Future<void> save(String scope, String? value) async {
    saved = value;
  }

  @override
  Future<String> downloadPath(String key) async => path!;
  @override
  Future<String> install(String path, AppRelease release) async {
    installs++;
    return 'installerOpened';
  }

  @override
  Future<void> openStore(String url) async {
    storeOpens++;
  }
}

class FakeApi implements AppUpdateApi {
  int calls = 0;
  AppUpdatePolicy result = const AppUpdatePolicy(
    mandatory: false,
    available: false,
  );
  Future<AppUpdatePolicy> Function()? operation;
  @override
  Future<AppUpdatePolicy> check(InstalledAppVersion version) async {
    calls++;
    return operation == null ? result : await operation!();
  }
}

void main() {
  test('iOS按数字营销版本比较而不是构建号或字典序', () {
    expect(compareMarketingVersions('1.9', '1.10.0'), -1);
    expect(compareMarketingVersions('1.0', '1.0.0'), 0);
    expect(
      const InstalledAppVersion(
        'ios',
        '1.0.1',
        2,
      ).isBefore(release(10022, name: '1.0.0')),
      false,
    );
  });
  test('普通更新可忽略；有效强制不能忽略；最高普通版本仍作为安装目标', () async {
    final platform = FakePlatform(), api = FakeApi();
    api.result = AppUpdatePolicy(
      mandatory: false,
      available: true,
      latest: release(4),
    );
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    await controller.start();
    expect(controller.visible, true);
    controller.dismiss();
    expect(controller.visible, false);
    await controller.check();
    expect(controller.visible, false);
    api.result = AppUpdatePolicy(
      mandatory: true,
      available: true,
      minimum: release(3),
      latest: release(4),
    );
    await controller.check();
    controller.dismiss();
    expect(controller.required, true);
    expect(controller.visible, true);
    expect(controller.policy!.latest!.code, 4);
  });
  test('首次检查失败放行；确认强制后断网重启仍拦截；联网撤销后清缓存', () async {
    final platform = FakePlatform(), api = FakeApi();
    api.operation = () async => throw const SocketException('offline');
    final first = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    await first.start();
    expect(first.ready, true);
    expect(first.required, false);
    first.dispose();
    platform.saved = jsonEncode(
      AppUpdatePolicy(
        mandatory: true,
        available: true,
        minimum: release(3),
        latest: release(4),
      ).toJson(),
    );
    final again = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(again.dispose);
    await again.start();
    expect(again.required, true);
    expect(platform.saved, isNotNull);
    api.operation = null;
    api.result = AppUpdatePolicy(
      mandatory: false,
      available: true,
      latest: release(4),
    );
    await again.check();
    expect(again.required, false);
    expect(platform.saved, isNull);
  });
  test('真实安装版达到缓存门槛后离线不继续强制', () async {
    final platform = FakePlatform()
      ..current = const InstalledAppVersion('android', '1.0.3', 3)
      ..saved = jsonEncode(
        AppUpdatePolicy(
          mandatory: true,
          available: true,
          minimum: release(3),
          latest: release(4),
        ).toJson(),
      );
    final api = FakeApi()
      ..operation = () async => throw const SocketException('offline');
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    await controller.start();
    expect(controller.required, false);
  });
  test('启动和前台并发检查合并为同一请求', () async {
    final api = FakeApi(), platform = FakePlatform();
    final pending = Completer<AppUpdatePolicy>();
    api.operation = () => pending.future;
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    final first = controller.start(), second = controller.check();
    await Future<void>.delayed(Duration.zero);
    expect(api.calls, 1);
    pending.complete(const AppUpdatePolicy(mandatory: false, available: false));
    await Future.wait([first, second]);
  });
  test('新的业务426不会被正在进行的旧检查结果解除', () async {
    final api = FakeApi(), platform = FakePlatform();
    final stale = Completer<AppUpdatePolicy>(),
        fresh = Completer<AppUpdatePolicy>();
    api.operation = () => api.calls == 1 ? stale.future : fresh.future;
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    final first = controller.start();
    await Future<void>.delayed(Duration.zero);
    controller.requireFromServer();
    stale.complete(const AppUpdatePolicy(mandatory: false, available: false));
    await first;
    await Future<void>.delayed(Duration.zero);
    expect(controller.required, true);
    expect(api.calls, 2);
    fresh.complete(
      AppUpdatePolicy(
        mandatory: true,
        available: true,
        minimum: release(3),
        latest: release(4),
      ),
    );
    await controller.check();
    expect(controller.required, true);
  });
  test('商店按钮仅打开商店，不解除强制门禁', () async {
    final platform = FakePlatform()
      ..current = const InstalledAppVersion('ios', '1.0.1', 1);
    final api = FakeApi()
      ..result = AppUpdatePolicy(
        mandatory: true,
        available: true,
        minimum: release(3),
        latest: release(4),
      );
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    await controller.start();
    await controller.update();
    expect(platform.storeOpens, 1);
    expect(controller.required, true);
  });

  test('新426不能沿用已满足的旧门槛而让离线重启绕过', () async {
    final platform = FakePlatform()
      ..current = const InstalledAppVersion('android', '1.0.3', 3);
    final api = FakeApi()
      ..result = AppUpdatePolicy(
        mandatory: false,
        available: true,
        minimum: release(3),
        latest: release(4),
      );
    final controller = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    await controller.start();
    api.operation = () async => throw const SocketException('offline');
    controller.requireFromServer();
    await controller.check();
    expect(controller.policy!.minimum, isNull);
    expect(controller.policy!.latest, isNull);
    controller.dispose();
    final restarted = AppUpdateController(
      Uri.parse('https://example.com/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(restarted.dispose);
    await restarted.start();
    expect(restarted.required, true);
  });
  test('安卓断点续传后校验完整哈希才交给安装器，安装按钮不解除门禁', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final directory = await Directory.systemTemp.createTemp(
      'qingjing-update-test',
    );
    final bytes = utf8.encode('immutable-signed-apk-fixture');
    final requests = <String?>[];
    server.listen((request) async {
      requests.add(request.headers.value('Range'));
      request.response.statusCode = 206;
      request.response.headers.set(
        'Content-Range',
        'bytes 5-${bytes.length - 1}/${bytes.length}',
      );
      request.response.add(bytes.sublist(5));
      await request.response.close();
    });
    addTearDown(() async {
      await server.close(force: true);
      await directory.delete(recursive: true);
    });
    final platform = FakePlatform()..path = '${directory.path}/package.apk';
    await File('${platform.path}.part').writeAsBytes(bytes.sublist(0, 5));
    final api = FakeApi()
      ..result = AppUpdatePolicy(
        mandatory: true,
        available: true,
        minimum: release(3),
        latest: release(
          4,
          url: '/api/v1/app-updates/packages/4',
          hash: sha256.convert(bytes).toString(),
          size: bytes.length,
        ),
      );
    final controller = AppUpdateController(
      Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    await controller.start();
    await controller.update();
    expect(requests, ['bytes=5-']);
    expect(await File(platform.path!).readAsBytes(), bytes);
    expect(platform.installs, 1);
    expect(controller.required, true);
  });
  test('坏哈希不会调安装器，强制继续生效并删除错误缓存', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final directory = await Directory.systemTemp.createTemp(
      'qingjing-update-test',
    );
    final bytes = utf8.encode('tampered-content');
    server.listen((request) async {
      request.response.add(bytes);
      await request.response.close();
    });
    addTearDown(() async {
      await server.close(force: true);
      await directory.delete(recursive: true);
    });
    final platform = FakePlatform()..path = '${directory.path}/package.apk';
    final api = FakeApi()
      ..result = AppUpdatePolicy(
        mandatory: true,
        available: true,
        minimum: release(3),
        latest: release(
          4,
          url: '/api/v1/app-updates/packages/4',
          hash: '0' * 64,
          size: bytes.length,
        ),
      );
    final controller = AppUpdateController(
      Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      platform: platform,
      api: api,
    );
    addTearDown(controller.dispose);
    await controller.start();
    await controller.update();
    expect(platform.installs, 0);
    expect(controller.required, true);
    expect(await File('${platform.path}.part').exists(), false);
  });
}
