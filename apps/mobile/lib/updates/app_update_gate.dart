import 'dart:async';

import 'package:flutter/material.dart';

import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import 'app_updates.dart';

/// Place inside the accepted-privacy builder so no update request precedes consent.
/// The business subtree is created once, after the first permitted decision, and
/// is retained when a later mandatory update blocks the app.
class AppUpdateBootstrap extends StatefulWidget {
  const AppUpdateBootstrap({
    super.key,
    required this.controller,
    required this.builder,
  });

  final AppUpdateController controller;
  final WidgetBuilder builder;

  @override
  State<AppUpdateBootstrap> createState() => _AppUpdateBootstrapState();
}

class _AppUpdateBootstrapState extends State<AppUpdateBootstrap> {
  Widget? _business;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_changed);
    _startAfterFrame();
  }

  void _startAfterFrame() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(widget.controller.start());
    });
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void didUpdateWidget(AppUpdateBootstrap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller == widget.controller) return;
    oldWidget.controller.removeListener(_changed);
    widget.controller.addListener(_changed);
    _business = null;
    _startAfterFrame();
  }

  @override
  void dispose() {
    widget.controller.removeListener(_changed);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_business != null) return _business!;
    if (!widget.controller.ready || widget.controller.required) {
      return Scaffold(
        body: SafeArea(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (!widget.controller.ready)
                  const CircularProgressIndicator(color: T.colorAccentStrong),
                if (!widget.controller.ready) const SizedBox(height: T.space4),
                Text(
                  widget.controller.required ? '请更新应用后继续使用' : '正在检查应用版本',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ),
          ),
        ),
      );
    }
    return _business = widget.builder(context);
  }
}

/// MaterialApp.builder wraps the entire Navigator with this widget. The visual
/// barrier therefore stays above any subsequently pushed page or download dialog.
/// A separate transparent route supplies Navigator's hardware-back/pop guard.
class AppUpdateOverlay extends StatefulWidget {
  const AppUpdateOverlay({
    super.key,
    required this.controller,
    required this.navigatorKey,
    required this.child,
  });

  final AppUpdateController controller;
  final GlobalKey<NavigatorState> navigatorKey;
  final Widget child;

  @override
  State<AppUpdateOverlay> createState() => _AppUpdateOverlayState();
}

class _AppUpdateOverlayState extends State<AppUpdateOverlay>
    with WidgetsBindingObserver {
  RawDialogRoute<void>? _guard;
  bool _syncQueued = false;

  bool get _visible => widget.controller.visible || widget.controller.updating;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_changed);
    WidgetsBinding.instance.addObserver(this);
    _scheduleRouteSync();
  }

  @override
  void didUpdateWidget(AppUpdateOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller ||
        oldWidget.navigatorKey != widget.navigatorKey) {
      oldWidget.controller.removeListener(_changed);
      widget.controller.addListener(_changed);
      _removeGuard();
    }
    _scheduleRouteSync();
  }

  void _changed() {
    if (!mounted) return;
    setState(() {});
    _scheduleRouteSync();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Checking/ready become true only after the accepted-privacy bootstrap starts.
    if (state == AppLifecycleState.resumed &&
        (widget.controller.ready || widget.controller.checking)) {
      unawaited(widget.controller.check());
    }
  }

  void _scheduleRouteSync() {
    if (_syncQueued) return;
    _syncQueued = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _syncQueued = false;
      if (mounted) _synchronizeRoute();
    });
  }

  void _synchronizeRoute() {
    if (!_visible) {
      _removeGuard();
      return;
    }
    if (_guard != null) return;
    final navigator = widget.navigatorKey.currentState;
    if (navigator == null) return;
    final controller = widget.controller;
    final route = RawDialogRoute<void>(
      settings: const RouteSettings(name: '/app-update-guard'),
      barrierDismissible: false,
      barrierColor: Colors.transparent,
      transitionDuration: Duration.zero,
      pageBuilder: (context, animation, secondaryAnimation) => AnimatedBuilder(
        animation: controller,
        builder: (context, _) => PopScope<void>(
          canPop: !controller.required && !controller.updating,
          onPopInvokedWithResult: (didPop, _) {
            if (didPop && !controller.required && !controller.updating) {
              controller.dismiss();
            }
          },
          child: const SizedBox.shrink(),
        ),
      ),
    );
    _guard = route;
    unawaited(
      navigator.push<void>(route).whenComplete(() {
        if (identical(_guard, route)) _guard = null;
        if (mounted && _visible) _scheduleRouteSync();
      }),
    );
  }

  void _removeGuard() {
    final route = _guard;
    _guard = null;
    final navigator = route?.navigator;
    if (route != null &&
        navigator != null &&
        navigator.mounted &&
        route.isActive) {
      navigator.removeRoute(route);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_changed);
    WidgetsBinding.instance.removeObserver(this);
    // Defer removal until Navigator is out of its current transition/tree teardown.
    final route = _guard;
    _guard = null;
    scheduleMicrotask(() {
      final navigator = route?.navigator;
      if (route != null &&
          navigator != null &&
          navigator.mounted &&
          route.isActive) {
        navigator.removeRoute(route);
      }
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      ExcludeFocus(
        excluding: _visible,
        child: ExcludeSemantics(
          excluding: _visible,
          child: AbsorbPointer(absorbing: _visible, child: widget.child),
        ),
      ),
      if (_visible)
        Positioned.fill(
          child: Stack(
            fit: StackFit.expand,
            children: [
              ModalBarrier(
                color: T.colorScrim,
                dismissible:
                    !widget.controller.required && !widget.controller.updating,
                onDismiss: widget.controller.dismiss,
                semanticsLabel: widget.controller.required
                    ? '需要更新应用'
                    : '关闭更新提醒',
              ),
              SafeArea(
                child: FocusScope(
                  autofocus: true,
                  child: _UpdateDialog(controller: widget.controller),
                ),
              ),
            ],
          ),
        ),
    ],
  );
}

class _UpdateDialog extends StatelessWidget {
  const _UpdateDialog({required this.controller});
  final AppUpdateController controller;

  @override
  Widget build(BuildContext context) {
    final busy = controller.checking || controller.updating;
    final android = controller.version?.platform == 'android';
    final release = controller.policy?.latest;
    final progress = controller.progress;
    final message = controller.message;
    return Dialog(
      key: const ValueKey('app-update-dialog'),
      backgroundColor: T.colorSurface,
      surfaceTintColor: Colors.transparent,
      insetPadding: const EdgeInsets.all(T.space5),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(T.radiusCard),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 390),
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(T.space5),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                controller.required ? '需要更新应用' : '发现新版本',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: T.space3),
              Text(
                controller.required
                    ? '当前版本已不再支持，请更新后继续使用。'
                    : '新版本已可用，您可以立即更新或稍后再说。',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: T.space3),
              if (controller.version != null) ...[
                Text(
                  '当前版本 ${controller.version!.name}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: T.space2),
              ],
              Text(
                android
                    ? release != null
                          ? '更新至 ${release.name}'
                          : '正在获取最新版本，请重新检查'
                    : '更新至应用商店最新版本',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              if (release != null && release.notes.trim().isNotEmpty) ...[
                const SizedBox(height: T.space3),
                Text(
                  release.notes,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
              if (controller.updating) ...[
                const SizedBox(height: T.space4),
                LinearProgressIndicator(
                  value: progress,
                  color: T.colorAccentStrong,
                ),
                const SizedBox(height: T.space2),
                Text(
                  progress == null
                      ? '正在准备更新'
                      : '正在下载 ${(progress * 100).round()}%',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
              if (message != null && message.isNotEmpty) ...[
                const SizedBox(height: T.space3),
                Text(message, style: Theme.of(context).textTheme.bodySmall),
              ],
              const SizedBox(height: T.space5),
              QjPrimaryAction(
                label: controller.updating ? '正在更新' : '立即更新',
                accent: true,
                onPressed: busy || release == null
                    ? null
                    : () => unawaited(controller.update()),
              ),
              const SizedBox(height: T.space2),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: busy ? null : () => unawaited(controller.check()),
                  child: Text(controller.checking ? '正在检查' : '重新检查'),
                ),
              ),
              if (!controller.required)
                SizedBox(
                  width: double.infinity,
                  child: TextButton(
                    onPressed: controller.updating ? null : controller.dismiss,
                    child: const Text('稍后再说'),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
