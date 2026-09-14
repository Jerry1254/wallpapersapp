import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/design_system/qj_components.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/design_system/ui_spec_screen.dart';

void main() {
  Future<void> open(WidgetTester tester, double width, double scale) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = Size(width, 820);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: const Scaffold(body: UiSpecScreen()),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final (width, scale) in [(375.0, 1.0), (390.0, 1.3), (430.0, 1.0)]) {
    testWidgets('规范页 ${width.toInt()} 宽度 / $scale 字体可完整滚动', (tester) async {
      await open(tester, width, scale);
      expect(tester.takeException(), isNull);
      await tester.ensureVisible(find.text('使用边界'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const ValueKey('specimen-tab-1')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('规范面板可查看错误、成功和设置状态并返回原位置', (tester) async {
    await open(tester, 375, 1.3);
    final redeem = find.widgetWithText(QjPrimaryAction, '兑换码面板');
    await tester.ensureVisible(redeem);
    await tester.tap(redeem);
    await tester.pumpAndSettle();
    expect(find.text('组件演示'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'demo');
    await tester.tap(find.widgetWithText(QjFilterChip, '失败'));
    await tester.pumpAndSettle();
    expect(find.text('兑换码无效或额度已用完'), findsOneWidget);
    await tester.tap(find.byTooltip('关闭组件演示'));
    await tester.pumpAndSettle();
    final setting = find.widgetWithText(QjPrimaryAction, 'Android · 设置位置');
    await tester.ensureVisible(setting);
    await tester.tap(setting);
    await tester.pumpAndSettle();
    await tester.tap(find.text('锁屏壁纸'));
    await tester.pump();
    await tester.tap(find.widgetWithText(QjPrimaryAction, '确认设置'));
    await tester.pumpAndSettle();
    expect(find.text('壁纸设置成功'), findsOneWidget);
    await tester.tap(find.widgetWithText(QjPrimaryAction, '完成'));
    await tester.pumpAndSettle();
    expect(find.text('组件演示'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
