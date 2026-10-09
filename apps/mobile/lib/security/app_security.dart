import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../device/device_session.dart';
import '../design_system/qj_theme.dart';
import 'security_network.dart';

abstract interface class SecurityLocalStore {
  Future<bool> blocked(String scope);
  Future<void> save(String scope, bool blocked);
}

class NativeSecurityStore implements SecurityLocalStore {
  const NativeSecurityStore();
  static const channel = MethodChannel('qingjing/security');
  @override
  Future<bool> blocked(String scope) async =>
      await channel.invokeMethod<bool>('cachedBlock', scope) ?? false;
  @override
  Future<void> save(String scope, bool blocked) => channel.invokeMethod<void>(
    'saveBlock',
    {'scope': scope, 'blocked': blocked},
  );
}

typedef EnvironmentChecks =
    Future<Map<String, String>> Function(List<String> checks);
Future<Map<String, String>> nativeEnvironmentChecks(List<String> checks) async {
  try {
    final values = await NativeSecurityStore.channel
        .invokeMapMethod<String, String>('checkEnvironment', checks);
    return {
      for (final check in checks)
        check: const {'NORMAL', 'RISK', 'UNKNOWN'}.contains(values?[check])
            ? values![check]!
            : 'UNKNOWN',
    };
  } catch (_) {
    return {for (final check in checks) check: 'UNKNOWN'};
  }
}

class AppSecurityController extends ChangeNotifier with WidgetsBindingObserver {
  AppSecurityController(
    this.sessions,
    this.scope, {
    this.store = const NativeSecurityStore(),
    this.checks = nativeEnvironmentChecks,
  });
  final DeviceSessionManager sessions;
  final String scope;
  final SecurityLocalStore store;
  final EnvironmentChecks checks;
  bool ready = false,
      blocked = false,
      checking = false,
      started = false,
      active = true,
      alive = true;
  String error = '';
  Timer? _timer;
  Future<void>? _pending;
  int _restrictionEpoch = 0;
  Future<void> start() async {
    if (started) return check();
    started = true;
    SecurityNetwork.onBlocked = markBlocked;
    WidgetsBinding.instance.addObserver(this);
    try {
      blocked = await store.blocked(scope);
      SecurityNetwork.blocked = blocked;
    } catch (_) {}
    if (!alive) return;
    notifyListeners();
    await check();
  }

  void markBlocked() {
    if (!alive) return;
    _restrictionEpoch++;
    blocked = true;
    SecurityNetwork.blocked = true;
    error = '';
    _timer?.cancel();
    unawaited(_save(true));
    notifyListeners();
  }

  Future<void> _save(bool value) async {
    try {
      await store.save(scope, value);
    } catch (_) {}
  }

  Future<void> check() {
    if (!alive) return Future.value();
    if (_pending != null) return _pending!;
    final task = _check();
    _pending = task;
    return task.whenComplete(() {
      if (identical(_pending, task)) _pending = null;
    });
  }

  Future<void> _check() async {
    _timer?.cancel();
    checking = true;
    error = '';
    notifyListeners();
    final epoch = _restrictionEpoch;
    try {
      var state = await sessions.authenticated('/device/security/state');
      if (state['allowed'] is! bool || state['checks'] is! List) {
        throw const FormatException('Invalid security state');
      }
      if (state['allowed'] == true) {
        final requested = (state['checks'] as List).cast<String>();
        if (requested.isNotEmpty) {
          state = await sessions.authenticated(
            '/device/security/reports',
            method: 'POST',
            signed: true,
            body: jsonEncode({'signals': await checks(requested)}),
          );
          if (state['allowed'] is! bool) {
            throw const FormatException('Invalid security report');
          }
        }
      }
      if (!alive || epoch != _restrictionEpoch) {
        return;
      }
      blocked = state['allowed'] != true;
      SecurityNetwork.blocked = blocked;
      ready = !blocked;
      await _save(blocked);
    } catch (_) {
      if (alive) error = '网络异常，请稍后重试';
    } finally {
      if (alive) {
        checking = false;
        notifyListeners();
        // Blocked devices only probe on refresh/foreground, never resume business polling.
        if (active && !blocked && ready) {
          _timer = Timer(const Duration(seconds: 30), check);
        }
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    _timer?.cancel();
    if (active && started) unawaited(check());
  }

  @override
  void dispose() {
    alive = false;
    _timer?.cancel();
    if (started) WidgetsBinding.instance.removeObserver(this);
    if (SecurityNetwork.onBlocked == markBlocked) {
      SecurityNetwork.onBlocked = null;
      SecurityNetwork.blocked = false;
    }
    super.dispose();
  }
}

class AppSecurityBootstrap extends StatefulWidget {
  const AppSecurityBootstrap({
    super.key,
    required this.controller,
    required this.builder,
  });
  final AppSecurityController controller;
  final WidgetBuilder builder;
  @override
  State<AppSecurityBootstrap> createState() => _AppSecurityBootstrapState();
}

class _AppSecurityBootstrapState extends State<AppSecurityBootstrap> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(widget.controller.start());
    });
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (_, _) => widget.controller.ready
        ? widget.builder(context)
        : SecurityNetworkPage(controller: widget.controller),
  );
}

class AppSecurityOverlay extends StatelessWidget {
  const AppSecurityOverlay({
    super.key,
    required this.controller,
    required this.child,
  });
  final AppSecurityController controller;
  final Widget child;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (_, _) => controller.blocked
        ? SecurityNetworkPage(controller: controller)
        : child,
  );
}

class SecurityNetworkPage extends StatelessWidget {
  const SecurityNetworkPage({super.key, required this.controller});
  final AppSecurityController controller;
  @override
  Widget build(BuildContext context) {
    if (!controller.ready && !controller.blocked && controller.error.isEmpty) {
      return const Scaffold(
        body: SafeArea(child: Center(child: CircularProgressIndicator())),
      );
    }
    return PopScope(
      canPop: false,
      child: Scaffold(
        body: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(T.space6),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.wifi_off_rounded,
                    size: 58,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(height: T.space5),
                  Text(
                    '网络异常',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: T.space3),
                  const Text('暂时无法连接，请稍后重试'),
                  const SizedBox(height: T.space6),
                  FilledButton(
                    onPressed: controller.checking ? null : controller.check,
                    child: Text(controller.checking ? '正在刷新…' : '刷新'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
