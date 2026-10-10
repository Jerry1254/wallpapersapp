import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/operations/device_activity.dart';

void main() {
  testWidgets(
    'reports foreground usage, pauses in background and stops on disposal',
    (tester) async {
      final sent = <Map<String, String>>[];
      final reporter = DeviceActivityReporter(
        (body) async {
          sent.add(body);
        },
        information: () async => {
          'manufacturer': 'test',
          'model': 'model',
          'osVersion': '6',
        },
      );
      reporter.start();
      await tester.pump();
      expect(sent, hasLength(1));
      expect(sent.single['model'], 'model');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump(const Duration(minutes: 2));
      expect(sent, hasLength(1));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(sent, hasLength(2));
      await tester.pump(const Duration(minutes: 1));
      expect(sent, hasLength(3));
      reporter.dispose();
      await tester.pump(const Duration(minutes: 2));
      expect(sent, hasLength(3));
    },
  );
  testWidgets(
    'retries failed reports and prevents concurrent lifecycle reports',
    (tester) async {
      int requests = 0;
      final pending = Completer<void>();
      final reporter = DeviceActivityReporter((body) async {
        requests++;
        if (requests == 1) throw Exception('network');
        await pending.future;
      }, information: () async => {});
      reporter.start();
      await tester.pump();
      expect(requests, 1);
      await tester.pump(const Duration(seconds: 30));
      expect(requests, 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(requests, 2);
      reporter.dispose();
      pending.complete();
      await tester.pump();
    },
  );
  testWidgets(
    'does not send metadata collected after the app moves to background',
    (tester) async {
      final metadata = Completer<Map<String, String>>();
      int requests = 0;
      final reporter = DeviceActivityReporter((body) async {
        requests++;
      }, information: () => metadata.future);
      reporter.start();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      metadata.complete({'model': 'test'});
      await tester.pump();
      expect(requests, 0);
      reporter.dispose();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    },
  );
}
