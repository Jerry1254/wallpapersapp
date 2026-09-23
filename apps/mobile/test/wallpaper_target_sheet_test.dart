import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/downloads/download_panel.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';

class _AcceptedPlayback extends AndroidWallpaperPlayback {
  const _AcceptedPlayback();

  @override
  Future<PlatformResult<void>> apply(
    String installedId,
    WallpaperEffect effect,
    WallpaperTarget target,
  ) async => const PlatformResult(
    OperationStatus.accepted,
    message: '系统已接受设置；位置不可读取，请到桌面或锁屏查看',
  );
}

void main() {
  testWidgets('系统已接受但位置不可读时显示完成状态', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: const Scaffold(
          body: WallpaperTargetSheet(
            installedId: 'installed-id',
            effect: WallpaperEffect.video,
            playback: _AcceptedPlayback(),
            placements: {'HOME', 'LOCK'},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('设置到哪里'), findsOneWidget);
    expect(find.text('确认设置'), findsOneWidget);
    await tester.tap(find.text('确认设置'));
    await tester.pumpAndSettle();
    expect(find.text('系统已接受设置'), findsOneWidget);
    expect(find.textContaining('请到桌面或锁屏查看'), findsOneWidget);
    expect(find.text('完成'), findsOneWidget);
    expect(find.text('重新尝试'), findsNothing);
  });
}
