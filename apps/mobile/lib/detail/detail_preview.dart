import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../device/device_session.dart';
import '../downloads/download_manager.dart';

final detailPreviewRouteObserver = RouteObserver<PageRoute<dynamic>>();

class DetailPreviewController {
  ValueChanged<String>? _listener;
  String? _latest;

  void applyConfiguration(String value) {
    _latest = value;
    _listener?.call(value);
  }

  void _attach(ValueChanged<String> listener) {
    _listener = listener;
    final latest = _latest;
    if (latest != null) listener(latest);
  }

  void _detach(ValueChanged<String> listener) {
    if (identical(_listener, listener)) _listener = null;
  }
}

class DetailPreview extends StatefulWidget {
  const DetailPreview({
    super.key,
    required this.manager,
    required this.wallpaperId,
    required this.deliveryPlatform,
    required this.resourceType,
    required this.cover,
    this.active = true,
    this.preferPreview = false,
    this.fill = false,
    this.configuration,
    this.controller,
    this.onInstalled,
    this.onReady,
  });
  final DownloadManager manager;
  final String wallpaperId, deliveryPlatform, resourceType;
  final Widget cover;
  final bool active;
  final bool preferPreview, fill;
  final String? configuration;
  final DetailPreviewController? controller;
  final ValueChanged<String>? onInstalled;
  final ValueChanged<bool>? onReady;
  @override
  State<DetailPreview> createState() => _DetailPreviewState();
}

class _DetailPreviewState extends State<DetailPreview>
    with WidgetsBindingObserver, RouteAware {
  static const native = AndroidDetailPreview();
  String? installedId, requestId, error;
  bool restricted = true, ready = false, routeVisible = true;
  MethodChannel? channel;
  String? pendingConfiguration;
  String? applyingValue, appliedConfiguration;
  bool applyingConfiguration = false;
  late final ValueChanged<String> controllerListener = _queueConfiguration;
  PageRoute<dynamic>? route;
  bool get visible =>
      widget.active &&
      routeVisible &&
      (WidgetsBinding.instance.lifecycleState == null ||
          WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed);
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.manager.addListener(_downloadChanged);
    widget.controller?._attach(controllerListener);
    unawaited(_prepare());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final current = ModalRoute.of(context);
    if (current is PageRoute && current != route) {
      detailPreviewRouteObserver.unsubscribe(this);
      route = current;
      detailPreviewRouteObserver.subscribe(this, current);
    }
  }

  @override
  void didUpdateWidget(DetailPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.active != widget.active) _visibility();
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?._detach(controllerListener);
      widget.controller?._attach(controllerListener);
    }
    if (oldWidget.configuration != widget.configuration &&
        widget.configuration != null) {
      _queueConfiguration(widget.configuration!);
    }
  }

  @override
  void didPushNext() {
    routeVisible = false;
    _visibility();
  }

  @override
  void didPopNext() {
    routeVisible = true;
    _visibility();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) => _visibility();
  void _visibility() {
    if (!visible && widget.resourceType == 'LAYER_PARALLAX') {
      appliedConfiguration = null;
      pendingConfiguration ??=
          widget.configuration ?? widget.controller?._latest;
      if (ready && mounted) {
        setState(() => ready = false);
        widget.onReady?.call(false);
      }
    }
    unawaited(
      channel?.invokeMethod<void>('visible', visible).catchError((_) {}),
    );
  }

  void _downloadChanged() {
    final state = widget.manager.value;
    if (restricted &&
        state.status == 'completed' &&
        state.wallpaperId == widget.wallpaperId &&
        state.resourceType == widget.resourceType &&
        state.installedId != null) {
      _cancel();
      channel?.setMethodCallHandler(null);
      channel = null;
      appliedConfiguration = null;
      setState(() {
        installedId = state.installedId;
        restricted = false;
        ready = false;
        error = null;
      });
      widget.onInstalled?.call(state.installedId!);
    }
  }

  void _cancel() {
    final id = requestId;
    requestId = null;
    if (id != null) unawaited(native.cancel(id).catchError((_) {}));
  }

  Future<void> _prepare() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      setState(() => error = '当前平台的交互预览尚未开放');
      return;
    }
    final id = requestUuid();
    requestId = id;
    bool cancelled() => !mounted || requestId != id;
    try {
      final local = widget.preferPreview
          ? null
          : await widget.manager.current(
              widget.wallpaperId,
              widget.resourceType,
            );
      if (cancelled()) return;
      if (local != null) {
        setState(() {
          installedId = local;
          restricted = false;
        });
        widget.onInstalled?.call(local);
        return;
      }
      final binding = await widget.manager.sessions.ensureEncryptionKey();
      if (cancelled()) return;
      final descriptor = await widget.manager.sessions.authenticated(
        '/device/wallpapers/${widget.wallpaperId}/preview-tickets',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'deliveryPlatform': widget.deliveryPlatform,
          'resourceType': widget.resourceType,
        }),
      );
      if (cancelled()) return;
      if (descriptor['deliveryMode'] != 'APP_PREVIEW' ||
          descriptor['purpose'] != 'APP_PREVIEW' ||
          descriptor['durationSeconds'] != 120 ||
          descriptor['wallpaperId'] != widget.wallpaperId ||
          descriptor['downloadUrl'] != '/api/v1/preview/files' ||
          (descriptor['package'] as Map?)?['formatVersion'] != 3 ||
          (descriptor['package'] as Map?)?['encryptionKeySha256'] != binding ||
          (descriptor['resourceVersion'] as Map?)?['platform'] !=
              widget.deliveryPlatform ||
          (descriptor['resourceVersion'] as Map?)?['resourceType'] !=
              widget.resourceType) {
        throw const FormatException('Invalid preview descriptor');
      }
      final preview = await native.prepare(
        SecurePackageDownload(
          requestId: id,
          wallpaperId: widget.wallpaperId,
          resourceType: widget.resourceType,
          url: widget.manager.apiBase.resolve('/api/v1/preview/files'),
          descriptor: descriptor,
        ),
      );
      if (!cancelled()) {
        setState(() => installedId = preview);
        widget.onInstalled?.call(preview);
      }
    } catch (failure) {
      if (!cancelled()) {
        setState(
          () => error =
              failure is DeviceApiError &&
                  failure.code == 'PREVIEW_RESOURCE_NOT_READY'
              ? '此作品的预览资源暂时不可用'
              : '预览暂时不可用，请重试',
        );
      }
    } finally {
      if (requestId == id) requestId = null;
    }
  }

  Future<void> _created(int id) async {
    if (!mounted) return;
    final next = MethodChannel('qingjing/detail_preview/$id');
    channel = next;
    appliedConfiguration = null;
    next.setMethodCallHandler((call) async {
      if (!mounted || channel != next || call.method != 'status') return;
      setState(() {
        ready = call.arguments == 'ready' || call.arguments == 'touch';
        error = call.arguments == 'failed' ? '预览暂时不可用，请重试' : null;
      });
      widget.onReady?.call(ready);
      if (ready) unawaited(_flushConfiguration());
      if (ready && widget.configuration != null) {
        _queueConfiguration(widget.configuration!);
      }
    });
    _visibility();
    try {
      final state = await next.invokeMapMethod<String, dynamic>('state');
      if (mounted && channel == next && state?['rendering'] == true) {
        setState(() => ready = true);
        widget.onReady?.call(true);
        unawaited(_flushConfiguration());
        if (widget.configuration != null) {
          _queueConfiguration(widget.configuration!);
        }
      }
    } catch (_) {}
  }

  void _queueConfiguration(String value) {
    if (widget.resourceType != 'LAYER_PARALLAX') return;
    if (pendingConfiguration == value ||
        (pendingConfiguration == null &&
            (applyingValue == value || appliedConfiguration == value))) {
      return;
    }
    pendingConfiguration = value;
    unawaited(_flushConfiguration());
  }

  Future<void> _flushConfiguration() async {
    if (applyingConfiguration || !ready || channel == null) return;
    applyingConfiguration = true;
    try {
      while (mounted && ready && channel != null) {
        final value = pendingConfiguration;
        if (value == null) break;
        pendingConfiguration = null;
        applyingValue = value;
        final current = channel!;
        try {
          await current.invokeMethod<void>('configuration', {'config': value});
          if (channel == current) appliedConfiguration = value;
          if (mounted && channel == current && error == '参数无法应用，请复位后重试') {
            setState(() => error = null);
          }
        } catch (_) {
          if (mounted && channel == current) {
            setState(() => error = '参数无法应用，请复位后重试');
          }
        } finally {
          applyingValue = null;
        }
      }
    } finally {
      applyingConfiguration = false;
      if (mounted && ready && channel != null && pendingConfiguration != null) {
        unawaited(_flushConfiguration());
      }
    }
  }

  Widget _surface() => PlatformViewLink(
    key: ValueKey('$installedId-$restricted'),
    viewType: 'qingjing/detail_preview',
    surfaceFactory: (context, controller) => AndroidViewSurface(
      controller: controller as AndroidViewController,
      hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      gestureRecognizers: widget.resourceType == 'LAYER_PARALLAX'
          ? {
              Factory<OneSequenceGestureRecognizer>(
                () => EagerGestureRecognizer(),
              ),
            }
          : const <Factory<OneSequenceGestureRecognizer>>{},
    ),
    onCreatePlatformView: (params) {
      final controller = PlatformViewsService.initExpensiveAndroidView(
        id: params.id,
        viewType: 'qingjing/detail_preview',
        layoutDirection: TextDirection.ltr,
        creationParamsCodec: const StandardMessageCodec(),
        creationParams: {
          'installedId': installedId,
          'resourceType': widget.resourceType,
          'restricted': restricted,
          'visible': visible,
        },
        onFocus: () => params.onFocusChanged(true),
      );
      controller.addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
      controller.addOnPlatformViewCreatedListener(_created);
      unawaited(controller.create());
      return controller;
    },
  );
  @override
  void dispose() {
    _cancel();
    channel?.setMethodCallHandler(null);
    widget.controller?._detach(controllerListener);
    widget.manager.removeListener(_downloadChanged);
    detailPreviewRouteObserver.unsubscribe(this);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final content = Stack(
      fit: StackFit.expand,
      children: [
        widget.cover,
        if (installedId != null) _surface(),
        if (!ready && error == null)
          const Center(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Color(0x99000000),
                borderRadius: BorderRadius.all(Radius.circular(18)),
              ),
              child: Padding(
                padding: EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                child: Text('正在加载原图预览…', style: TextStyle(color: Colors.white)),
              ),
            ),
          ),
        if (error != null)
          Positioned(
            left: 12,
            right: 12,
            bottom: 12,
            child: Align(
              alignment: Alignment.bottomLeft,
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(error!),
                      TextButton(
                        onPressed: () {
                          _cancel();
                          channel?.setMethodCallHandler(null);
                          channel = null;
                          appliedConfiguration = null;
                          setState(() {
                            installedId = null;
                            error = null;
                            ready = false;
                          });
                          unawaited(_prepare());
                        },
                        child: const Text('重新加载预览'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
    if (widget.fill) return content;
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: AspectRatio(aspectRatio: 1 / 2, child: content),
    );
  }
}
