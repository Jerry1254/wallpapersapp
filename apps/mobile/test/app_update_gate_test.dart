import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/updates/app_update_gate.dart';
import 'package:qingjing_wallpaper/updates/app_updates.dart';

const _allowed = AppUpdatePolicy(mandatory: false, available: false);

AppRelease _release(int number) => AppRelease({
  'id': 'release-$number',
  'versionName': '$number.0.0',
  'versionCode': number,
  'releaseNotes': '修复已知问题',
  'storeUrl': 'https://apps.apple.com/cn/app/id123456789',
});

AppUpdatePolicy _mandatory() => AppUpdatePolicy(
  mandatory: true,
  available: true,
  minimum: _release(3),
  latest: _release(4),
);

class _Platform implements AppUpdatePlatform {
  int storeOpens = 0;
  Future<void> storeResult = Future.value();
  @override
  Future<InstalledAppVersion> installed() async =>
      const InstalledAppVersion('ios', '1.0.0', 1);
  @override
  Future<String?> cached(String scope) async => null;
  @override
  Future<void> save(String scope, String? value) async {}
  @override
  Future<String> downloadPath(String key) => throw UnimplementedError();
  @override
  Future<String> install(String path, AppRelease release) =>
      throw UnimplementedError();
  @override
  Future<void> openStore(String url) async {
    storeOpens++;
    await storeResult;
  }
}

class _Api implements AppUpdateApi {
  Future<AppUpdatePolicy> response = Future.value(_allowed);
  int checks = 0;
  @override
  Future<AppUpdatePolicy> check(InstalledAppVersion version) {
    checks++;
    return response;
  }
}

Widget _app(
  AppUpdateController controller,
  GlobalKey<NavigatorState> navigator,
  WidgetBuilder business,
) => MaterialApp(
  navigatorKey: navigator,
  theme: QjTheme.light,
  builder: (context, child) => AppUpdateOverlay(
    controller: controller,
    navigatorKey: navigator,
    child: child!,
  ),
  home: AppUpdateBootstrap(controller: controller, builder: business),
);

class _Business extends StatefulWidget {
  const _Business({required this.onStart, required this.onDispose});
  final VoidCallback onStart, onDispose;
  @override
  State<_Business> createState() => _BusinessState();
}

class _BusinessState extends State<_Business> {
  @override
  void initState() {
    super.initState();
    widget.onStart();
  }

  @override
  void dispose() {
    widget.onDispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => const Scaffold(body: Text('业务首页'));
}

void main() {
  testWidgets('强制检查及更新执行中不允许再次操作或返回绕过', (tester) async {
    final platform = _Platform();
    final api = _Api()..response = Future.value(_mandatory());
    final controller = AppUpdateController(
      Uri.parse('https://example.test/api/v1'),
      platform: platform,
      api: api,
    );
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      _app(controller, navigator, (_) => const Scaffold(body: Text('业务首页'))),
    );
    await tester.pumpAndSettle();
    final checkResult = Completer<AppUpdatePolicy>();
    api.response = checkResult.future;
    final checking = controller.check();
    await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(
      tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed,
      isNull,
    );
    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(controller.required, isTrue);
    checkResult.complete(_mandatory());
    await checking;
    await tester.pumpAndSettle();

    final storeResult = Completer<void>();
    platform.storeResult = storeResult.future;
    await tester.tap(find.text('立即更新'));
    await tester.pump();
    expect(controller.updating, isTrue);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(
      tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed,
      isNull,
    );
    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(find.text('需要更新应用'), findsOneWidget);
    storeResult.complete();
    await tester.pumpAndSettle();
    expect(controller.required, isTrue);
    expect(platform.storeOpens, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    controller.dispose();
    expect(tester.takeException(), isNull);
  });

  testWidgets('首次检查结束前及强制更新期间不初始化业务，撤销后才初始化', (tester) async {
    final result = Completer<AppUpdatePolicy>();
    final api = _Api()..response = result.future;
    final controller = AppUpdateController(
      Uri.parse('https://example.test/api/v1'),
      platform: _Platform(),
      api: api,
    );
    final navigator = GlobalKey<NavigatorState>();
    var starts = 0;
    await tester.pumpWidget(
      _app(controller, navigator, (_) {
        starts++;
        return const Scaffold(body: Text('业务首页'));
      }),
    );
    await tester.pump();
    expect(starts, 0);
    expect(api.checks, 1);

    result.complete(_mandatory());
    await tester.pumpAndSettle();
    expect(starts, 0);
    expect(find.text('需要更新应用'), findsOneWidget);
    expect(find.text('稍后再说'), findsNothing);
    await tester.binding.handlePopRoute();
    await tester.tapAt(const Offset(5, 5));
    await tester.pumpAndSettle();
    expect(find.text('需要更新应用'), findsOneWidget);
    expect(starts, 0);

    api.response = Future.value(_allowed);
    await controller.check();
    await tester.pumpAndSettle();
    expect(starts, 1);
    expect(find.text('需要更新应用'), findsNothing);
    expect(find.text('业务首页'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    controller.dispose();
    expect(tester.takeException(), isNull);
  });

  testWidgets('强制遮罩覆盖详情及后续弹窗，更新按钮可用且不销毁已有业务', (tester) async {
    final platform = _Platform();
    final api = _Api();
    final controller = AppUpdateController(
      Uri.parse('https://example.test/api/v1'),
      platform: platform,
      api: api,
    );
    final navigator = GlobalKey<NavigatorState>();
    var starts = 0, disposals = 0, detailActions = 0, dialogActions = 0;
    await tester.pumpWidget(
      _app(
        controller,
        navigator,
        (_) => _Business(onStart: () => starts++, onDispose: () => disposals++),
      ),
    );
    await tester.pumpAndSettle();
    unawaited(
      navigator.currentState!.push(
        MaterialPageRoute<void>(
          builder: (_) => Scaffold(
            body: TextButton(
              onPressed: () => detailActions++,
              child: const Text('详情操作'),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    api.response = Future.value(_mandatory());
    await controller.check();
    await tester.pumpAndSettle();
    expect(starts, 1);
    expect(disposals, 0);
    expect(find.text('详情操作').hitTestable(), findsNothing);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('需要更新应用'), findsOneWidget);

    unawaited(
      showDialog<void>(
        context: navigator.currentState!.context,
        builder: (_) => AlertDialog(
          content: TextButton(
            onPressed: () => dialogActions++,
            child: const Text('后续下载弹窗操作'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('后续下载弹窗操作').hitTestable(), findsNothing);
    expect(find.text('立即更新').hitTestable(), findsOneWidget);
    await tester.tap(find.text('立即更新'));
    await tester.pumpAndSettle();
    expect(platform.storeOpens, 1);
    expect(controller.required, isTrue);
    expect(detailActions, 0);
    expect(dialogActions, 0);

    // Even an imperative pop of the guard cannot remove the independent forced overlay.
    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('需要更新应用'), findsOneWidget);
    expect(disposals, 0);
    api.response = Future.value(_allowed);
    await controller.check();
    await tester.pumpAndSettle();
    expect(find.text('需要更新应用'), findsNothing);
    expect(find.text('详情操作').hitTestable(), findsOneWidget);
    expect(starts, 1);
    expect(disposals, 0);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    controller.dispose();
    expect(disposals, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('普通更新返回可关闭，同一版本复查不会重复弹出', (tester) async {
    final optional = AppUpdatePolicy(
      mandatory: false,
      available: true,
      latest: _release(4),
    );
    final api = _Api()..response = Future.value(optional);
    final controller = AppUpdateController(
      Uri.parse('https://example.test/api/v1'),
      platform: _Platform(),
      api: api,
    );
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      _app(controller, navigator, (_) => const Scaffold(body: Text('业务首页'))),
    );
    await tester.pumpAndSettle();
    expect(find.text('发现新版本'), findsOneWidget);
    expect(find.text('稍后再说'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('发现新版本'), findsNothing);
    await controller.check();
    await tester.pumpAndSettle();
    expect(find.text('发现新版本'), findsNothing);
    expect(find.text('业务首页'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    controller.dispose();
    expect(tester.takeException(), isNull);
  });
}
