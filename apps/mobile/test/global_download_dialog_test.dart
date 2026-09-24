import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';
import 'package:qingjing_wallpaper/downloads/global_download_dialog.dart';

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

void main() {
  testWidgets('全局下载弹窗展示进度、下载中禁止返回并在完成后自动关闭', (tester) async {
    final manager = DownloadManager(
      DeviceSessionManager(_UnusedTransport()),
      Uri.parse('https://example.test/api/v1'),
    );
    addTearDown(manager.dispose);
    manager.value = const DownloadState(
      wallpaperId: '1',
      deliveryPlatform: 'ANDROID',
      resourceType: 'VIDEO',
      requestId: 'request',
      afterRedemption: true,
      busy: true,
      status: 'downloading',
      received: 40,
      total: 100,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => FilledButton(
            onPressed: () => showDialog<void>(
              context: context,
              barrierDismissible: false,
              builder: (_) => GlobalDownloadDialog(manager: manager),
            ),
            child: const Text('开始'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('开始'));
    await tester.pumpAndSettle();

    expect(find.text('兑换成功'), findsOneWidget);
    expect(find.text('正在下载壁纸资源，请勿关闭或切换到别的 App'), findsOneWidget);
    expect(find.text('下载中 40%'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(find.byType(GlobalDownloadDialog), findsOneWidget);

    manager.value = const DownloadState(status: 'completed');
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.byType(GlobalDownloadDialog), findsNothing);
  });
}
