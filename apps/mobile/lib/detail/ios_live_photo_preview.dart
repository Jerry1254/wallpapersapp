import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

import '../device/device_session.dart';
import '../downloads/download_manager.dart';
import 'preview_availability.dart';

bool validIosLivePhotoPreviewDescriptor(
  Map<String, dynamic> descriptor, {
  required String wallpaperId,
  required String deliveryPlatform,
  required String resourceType,
  int previewRevision = 0,
}) {
  final version = descriptor['resourceVersion'];
  final video = descriptor['video'];
  return descriptor['deliveryMode'] == 'LIVE_PHOTO_PREVIEW' &&
      descriptor['purpose'] == 'APP_PREVIEW' &&
      descriptor['durationSeconds'] == 1 &&
      descriptor['wallpaperId'] == wallpaperId &&
      validPreviewRevision(
        descriptor['previewRevision'],
        expected: previewRevision,
      ) &&
      version is Map &&
      version['platform'] == deliveryPlatform &&
      version['resourceType'] == resourceType &&
      video is Map &&
      video['url'] == '/api/v1/preview/live-photo/video' &&
      video['mimeType'] == 'video/quicktime' &&
      video['sizeBytes'] is int &&
      (video['sizeBytes'] as int) > 0 &&
      video['sha256'] is String &&
      RegExp(r'^[a-f0-9]{64}$').hasMatch(video['sha256'] as String);
}

class IosLivePhotoPreviewView extends StatefulWidget {
  const IosLivePhotoPreviewView({
    super.key,
    required this.manager,
    required this.wallpaperId,
    required this.deliveryPlatform,
    required this.resourceType,
    required this.cover,
    this.nativePreview = const IosLivePhotoPreview(),
    this.fit = BoxFit.cover,
    this.previewRevision = 0,
    this.onReload,
  });

  final DownloadManager manager;
  final String wallpaperId, deliveryPlatform, resourceType;
  final Widget cover;
  final IosLivePhotoPreview nativePreview;
  final BoxFit fit;
  final int previewRevision;
  final VoidCallback? onReload;

  @override
  State<IosLivePhotoPreviewView> createState() =>
      _IosLivePhotoPreviewViewState();
}

class _IosLivePhotoPreviewViewState extends State<IosLivePhotoPreviewView>
    with WidgetsBindingObserver {
  String? requestId;
  VideoPlayerController? player;
  bool holding = false;
  bool failed = false;
  String? failureMessage;

  bool get ready => player?.value.isInitialized == true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_prepare());
  }

  Future<void> _prepare() async {
    final id = requestUuid();
    requestId = id;
    try {
      final descriptor = await widget.manager.sessions.authenticated(
        '/device/wallpapers/${widget.wallpaperId}/preview-tickets',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'deliveryPlatform': widget.deliveryPlatform,
          'resourceType': widget.resourceType,
        }),
      );
      if (!mounted || requestId != id) return;
      if (!validIosLivePhotoPreviewDescriptor(
        descriptor,
        wallpaperId: widget.wallpaperId,
        deliveryPlatform: widget.deliveryPlatform,
        resourceType: widget.resourceType,
        previewRevision: widget.previewRevision,
      )) {
        throw const FormatException('Invalid Live Photo preview descriptor');
      }
      final path = await widget.nativePreview.prepare(
        requestId: id,
        wallpaperId: widget.wallpaperId,
        apiOrigin: widget.manager.apiBase,
        descriptor: descriptor,
      );
      if (!mounted || requestId != id) {
        await widget.nativePreview.release(id);
        return;
      }
      final next = VideoPlayerController.file(File(path));
      try {
        await next.initialize();
        await next.setLooping(true);
        await next.setVolume(0);
      } catch (_) {
        await next.dispose();
        rethrow;
      }
      if (!mounted || requestId != id) {
        await next.dispose();
        await widget.nativePreview.release(id);
        return;
      }
      setState(() => player = next);
    } catch (failure) {
      if (mounted && requestId == id) {
        setState(() {
          failed = true;
          failureMessage = previewFailureMessage(failure);
        });
      }
    }
  }

  Future<void> _retry() async {
    final id = requestId;
    requestId = null;
    final current = player;
    player = null;
    if (id != null) {
      await widget.nativePreview.release(id).catchError((_) {});
    }
    await current?.dispose();
    if (!mounted) return;
    setState(() {
      failed = false;
      holding = false;
      failureMessage = null;
    });
    await _prepare();
  }

  Future<void> _start() async {
    final current = player;
    if (current == null || !current.value.isInitialized || holding) return;
    setState(() => holding = true);
    try {
      await current.seekTo(Duration.zero);
      if (mounted && holding && identical(player, current)) {
        await current.play();
      }
    } catch (_) {
      if (mounted && identical(player, current)) {
        setState(() {
          holding = false;
          failed = true;
          failureMessage = '动态预览暂不可用';
        });
      }
    }
  }

  void _stop() {
    final current = player;
    if (!holding && current?.value.isPlaying != true) return;
    if (mounted) setState(() => holding = false);
    if (current != null) {
      unawaited(current.pause().catchError((_) {}));
      unawaited(current.seekTo(Duration.zero).catchError((_) {}));
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _stop();
  }

  Widget _video(VideoPlayerController current) => FittedBox(
    fit: widget.fit,
    clipBehavior: Clip.hardEdge,
    child: SizedBox(
      width: current.value.aspectRatio,
      height: 1,
      child: VideoPlayer(current),
    ),
  );

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    final id = requestId;
    requestId = null;
    final current = player;
    player = null;
    unawaited(current?.dispose() ?? Future<void>.value());
    if (id != null) {
      unawaited(widget.nativePreview.release(id).catchError((_) {}));
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onLongPressStart: ready ? (_) => unawaited(_start()) : null,
    onLongPressEnd: ready ? (_) => _stop() : null,
    onLongPressCancel: ready ? _stop : null,
    child: Stack(
      fit: StackFit.expand,
      children: [
        widget.cover,
        if (holding && player != null) _video(player!),
        if (!ready && !failed)
          const Positioned(
            left: 0,
            right: 0,
            bottom: 14,
            child: Center(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Color(0xB3191817),
                  borderRadius: BorderRadius.all(Radius.circular(18)),
                ),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  child: Text(
                    '资源加载中，请稍后',
                    style: TextStyle(color: Colors.white),
                  ),
                ),
              ),
            ),
          ),
        if (ready && !holding)
          const Positioned(
            left: 0,
            right: 0,
            bottom: 14,
            child: Center(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Color(0xB3191817),
                  borderRadius: BorderRadius.all(Radius.circular(18)),
                ),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.touch_app_rounded,
                        color: Colors.white,
                        size: 18,
                      ),
                      SizedBox(width: 6),
                      Text('长按查看动态效果', style: TextStyle(color: Colors.white)),
                    ],
                  ),
                ),
              ),
            ),
          ),
        if (failed)
          Positioned(
            left: 12,
            right: 12,
            bottom: 12,
            child: Center(
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  color: Color(0xB3191817),
                  borderRadius: BorderRadius.all(Radius.circular(16)),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 7,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        failureMessage ?? '动态预览暂不可用',
                        style: const TextStyle(color: Colors.white),
                      ),
                      TextButton(
                        onPressed: widget.onReload ?? () => unawaited(_retry()),
                        child: const Text('重新加载预览'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    ),
  );
}
