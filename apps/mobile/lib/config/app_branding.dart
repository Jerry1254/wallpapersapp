import 'package:flutter/widgets.dart';

class AppBranding {
  const AppBranding._(this.appName, this.shortName, this.serviceName);

  static const qingjing = AppBranding._('倾境动态壁纸', '倾境', '倾境壁纸');
  static const jiyi = AppBranding._('吉意壁纸', '吉意', '吉意壁纸');

  static AppBranding forFlavor(String flavor) =>
      flavor == 'offline' ? jiyi : qingjing;

  final String appName;
  final String shortName;
  final String serviceName;

  String productText(String text) => this == qingjing
      ? text
      : text
            .replaceAll(qingjing.appName, appName)
            .replaceAll(qingjing.serviceName, serviceName)
            .replaceAll(qingjing.shortName, shortName);
}

class AppBrandingScope extends InheritedWidget {
  const AppBrandingScope({
    super.key,
    required this.branding,
    required super.child,
  });

  final AppBranding branding;

  static AppBranding of(BuildContext context) =>
      context
          .dependOnInheritedWidgetOfExactType<AppBrandingScope>()
          ?.branding ??
      AppBranding.qingjing;

  @override
  bool updateShouldNotify(AppBrandingScope oldWidget) =>
      branding != oldWidget.branding;
}
