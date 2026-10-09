import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/config/app_branding.dart';
import 'package:qingjing_wallpaper/design_system/qj_components.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/detail/help_screen.dart';
import 'package:qingjing_wallpaper/support/online_support.dart';

void main() {
  for (final branding in [AppBranding.qingjing, AppBranding.jiyi]) {
    testWidgets('${branding.appName} 从微信卡片进入在线聊天并返回', (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        AppBrandingScope(
          branding: branding,
          child: MaterialApp(
            theme: QjTheme.light,
            home: const HelpScreen(customerService: true),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('联系客服'), findsOneWidget);
      expect(find.text('jykj992'), findsOneWidget);
      expect(find.byType(CustomerSupportPage), findsNothing);
      expect(
        tester.getTopLeft(find.text('联系在线客服')).dy,
        greaterThan(
          tester.getBottomLeft(find.byType(QjCustomerServiceCard)).dy,
        ),
      );
      await tester.ensureVisible(find.text('联系在线客服'));
      await tester.tap(find.text('联系在线客服'));
      await tester.pumpAndSettle();
      expect(find.byType(CustomerSupportPage), findsOneWidget);
      expect(find.text('在线客服'), findsOneWidget);
      expect(find.byType(QjCustomerServiceCard), findsNothing);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(QjCustomerServiceCard), findsOneWidget);
      expect(find.text('联系客服'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
