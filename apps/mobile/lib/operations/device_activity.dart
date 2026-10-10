import 'dart:async';
import 'dart:io';
import 'dart:convert';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../device/device_session.dart';

Future<Map<String, String>> nativeDeviceInformation() async {
  try {
    if (Platform.isIOS) {
      return await const MethodChannel(
            'qingjing/wallpaper_ios',
          ).invokeMapMethod<String, String>('deviceInformation') ??
          {};
    }
    final info = await const AndroidWallpaperPlayback().capabilities();
    return {
      if (info.manufacturer != null) 'manufacturer': info.manufacturer!,
      if (info.model != null) 'model': info.model!,
      if (info.osVersion != null) 'osVersion': info.osVersion!,
    };
  } catch (_) {
    return {}; // Usage still records when an older native bridge has no metadata.
  }
}

/// Created only after privacy, security and mandatory-update gates have passed.
class DeviceActivityReporter with WidgetsBindingObserver {
  DeviceActivityReporter(
    this.send, {
    this.information = nativeDeviceInformation,
  });
  factory DeviceActivityReporter.forSession(DeviceSessionManager sessions) =>
      DeviceActivityReporter((body) async {
        await sessions.authenticated(
          '/device/activity',
          method: 'POST',
          signed: true,
          body: jsonEncode(body),
        );
      });
  final Future<void> Function(Map<String, String>) send;
  final Future<Map<String, String>> Function() information;
  Timer? _timer;
  bool _disposed = false, _pending = false, _active = true;
  Map<String, String>? _information;

  void start() {
    _active =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    unawaited(report());
  }

  Future<void> report() async {
    if (_disposed || !_active || _pending) return;
    _timer?.cancel();
    _pending = true;
    bool success = false;
    try {
      _information ??= await information();
      if (_disposed || !_active) return;
      await send(_information!);
      success = true;
    } catch (_) {
      // Retry silently; a failed statistics request must never prevent ordinary use.
    } finally {
      _pending = false;
      if (!_disposed && _active) {
        _timer = Timer(
          success ? const Duration(minutes: 1) : const Duration(seconds: 30),
          report,
        );
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _active = state == AppLifecycleState.resumed;
    _timer?.cancel();
    if (_active) unawaited(report());
  }

  void dispose() {
    _disposed = true;
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
  }
}
