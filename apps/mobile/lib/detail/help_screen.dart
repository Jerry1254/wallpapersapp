import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import '../catalog/catalog.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../support/customer_service.dart';

class HelpScreen extends StatelessWidget {
  const HelpScreen({super.key, this.customerService = false, this.repository});
  final bool customerService;
  final CatalogRepository? repository;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(T.space5, 0, T.space5, T.space6),
            children: [
              QjPageHeader(
                title: customerService ? '微信客服' : '壁纸设置教程',
                serviceAction: !customerService,
                onAction: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const HelpScreen(customerService: true),
                  ),
                ),
              ),
              const SizedBox(height: T.space6),
              if (customerService) ...[
                QjCustomerServiceCard(
                  onCopy: () => copyCustomerWechat(context),
                  onPreview: () => previewCustomerQr(context),
                  onSave: () => saveCustomerQr(context),
                ),
              ] else
                _TutorialCatalog(repository: repository),
            ],
          ),
        ),
      ),
    ),
  );
}

class _TutorialCatalog extends StatefulWidget {
  const _TutorialCatalog({required this.repository});
  final CatalogRepository? repository;
  @override
  State<_TutorialCatalog> createState() => _TutorialCatalogState();
}

class _TutorialCatalogState extends State<_TutorialCatalog> {
  late Future<List<WallpaperTutorial>> future = _load();

  Future<List<WallpaperTutorial>> _load() =>
      widget.repository?.tutorials() ?? Future.error('教程接口尚未配置');

  String icon(String key) => switch (key) {
    'ANDROID_PARALLAX_4D' => 'layers-3',
    'ANDROID_DYNAMIC' => 'play-square',
    'STATIC' => 'smartphone',
    'HARMONYOS_DYNAMIC' => 'panels-top-left',
    'IOS_DYNAMIC' => 'smartphone',
    _ => 'circle-play',
  };

  void retry() => setState(() => future = _load());

  @override
  Widget build(BuildContext context) => FutureBuilder<List<WallpaperTutorial>>(
    future: future,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return const Padding(
          padding: EdgeInsets.symmetric(vertical: T.space8),
          child: Center(child: CircularProgressIndicator()),
        );
      }
      if (snapshot.hasError) {
        return QjStatePanel(
          kind: QjStateKind.error,
          description: snapshot.error is ApiFailure
              ? (snapshot.error! as ApiFailure).message
              : '设置教程暂时无法加载',
          onPressed: retry,
        );
      }
      final tutorials = snapshot.requireData;
      if (tutorials.isEmpty) {
        return QjStatePanel(description: '设置教程暂时还没有发布', onPressed: retry);
      }
      return Column(
        children: [
          for (final (index, tutorial) in tutorials.indexed)
            Padding(
              padding: const EdgeInsets.only(bottom: T.space3),
              child: Semantics(
                button: true,
                label: '播放${tutorial.title}',
                child: InkWell(
                  onTap: () => showSettingTutorial(
                    context,
                    widget.repository!,
                    tutorial,
                  ),
                  borderRadius: BorderRadius.circular(T.radiusCard),
                  child: Container(
                    constraints: const BoxConstraints(minHeight: 88),
                    padding: const EdgeInsets.all(T.space4),
                    decoration: BoxDecoration(
                      color: T.colorSurface,
                      border: Border.all(color: T.colorOutline),
                      borderRadius: BorderRadius.circular(T.radiusCard),
                      boxShadow: const [T.shadowSoft],
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 50,
                          height: 50,
                          decoration: BoxDecoration(
                            color: T.colorAccent,
                            borderRadius: BorderRadius.circular(17),
                          ),
                          child: Center(
                            child: QjIcon(icon(tutorial.key), size: 24),
                          ),
                        ),
                        const SizedBox(width: T.space4),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                tutorial.title,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              const SizedBox(height: 5),
                              Text(
                                '点击播放',
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                            ],
                          ),
                        ),
                        Text(
                          '${index + 1}'.padLeft(2, '0'),
                          style: QjTheme.type(
                            20,
                            FontWeight.w800,
                            T.lineHeightSection,
                            T.colorOutlineStrong,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      );
    },
  );
}

Future<void> showSettingTutorial(
  BuildContext context,
  CatalogRepository repository,
  WallpaperTutorial tutorial,
) async {
  late final Uri source;
  try {
    source = repository.media(tutorial.videoPath);
  } catch (_) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('教程播放地址无效，请稍后重试')));
    return;
  }
  await showGeneralDialog<void>(
    context: context,
    barrierDismissible: false,
    transitionDuration: const Duration(milliseconds: 180),
    pageBuilder: (_, _, _) =>
        SettingTutorialScreen(title: tutorial.title, source: source),
  );
}

class SettingTutorialScreen extends StatefulWidget {
  const SettingTutorialScreen({
    super.key,
    required this.title,
    required this.source,
  });
  final String title;
  final Uri source;
  @override
  State<SettingTutorialScreen> createState() => _SettingTutorialScreenState();
}

class _SettingTutorialScreenState extends State<SettingTutorialScreen> {
  late final VideoPlayerController controller;
  bool ready = false, immersive = false;
  String? failure;
  @override
  void initState() {
    super.initState();
    controller = VideoPlayerController.networkUrl(widget.source)
      ..addListener(_changed);
    controller
        .initialize()
        .then((_) {
          if (mounted) setState(() => ready = true);
        })
        .catchError((_) {
          if (mounted) setState(() => failure = '教程视频暂时无法播放，请稍后重试');
        });
  }

  void _changed() {
    if (mounted && ready) setState(() {});
  }

  Future<void> toggleFullscreen() async {
    immersive = !immersive;
    await SystemChrome.setEnabledSystemUIMode(
      immersive ? SystemUiMode.immersiveSticky : SystemUiMode.edgeToEdge,
    );
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    controller.removeListener(_changed);
    controller.dispose();
    if (immersive) SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  String time(Duration value) {
    final minutes = value.inMinutes.toString().padLeft(2, '0');
    final seconds = (value.inSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  void toggle() {
    controller.value.isPlaying ? controller.pause() : controller.play();
  }

  @override
  Widget build(BuildContext context) {
    final value = controller.value;
    return Scaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            T.space4,
            T.space4,
            T.space4,
            T.space5,
          ),
          child: Column(
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.title,
                          style: QjTheme.type(
                            18,
                            FontWeight.w700,
                            1.35,
                            T.colorInverseInk,
                          ),
                        ),
                        Text(
                          '跟随视频完成壁纸设置',
                          style: QjTheme.type(
                            12,
                            FontWeight.w400,
                            T.lineHeightCaption,
                            Colors.white60,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭教程',
                    onPressed: () => Navigator.pop(context),
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.white12,
                    ),
                    icon: const QjIcon('x', size: 24, color: T.colorInverseInk),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Expanded(
                child: Center(
                  child: AspectRatio(
                    aspectRatio: 9 / 16,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(T.radiusCard),
                      child: ColoredBox(
                        color: T.colorNavigation,
                        child: failure != null
                            ? Center(
                                child: Padding(
                                  padding: const EdgeInsets.all(T.space5),
                                  child: Text(
                                    failure!,
                                    textAlign: TextAlign.center,
                                    style: QjTheme.type(
                                      13,
                                      FontWeight.w500,
                                      T.lineHeightBody,
                                      T.colorInverseInk,
                                    ),
                                  ),
                                ),
                              )
                            : ready
                            ? Stack(
                                fit: StackFit.expand,
                                children: [
                                  VideoPlayer(controller),
                                  if (!value.isPlaying)
                                    Center(
                                      child: IconButton(
                                        tooltip: '播放教程',
                                        onPressed: toggle,
                                        style: IconButton.styleFrom(
                                          backgroundColor: T.colorAccent,
                                          minimumSize: const Size(64, 64),
                                        ),
                                        icon: const QjIcon('play', size: 30),
                                      ),
                                    ),
                                ],
                              )
                            : const Center(child: CircularProgressIndicator()),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  IconButton(
                    tooltip: value.isPlaying ? '暂停教程' : '播放教程',
                    onPressed: ready ? toggle : null,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.white12,
                    ),
                    icon: QjIcon(
                      value.isPlaying ? 'pause' : 'play',
                      size: 21,
                      color: T.colorInverseInk,
                    ),
                  ),
                  Expanded(
                    child: Slider(
                      value: ready
                          ? value.position.inMilliseconds.toDouble().clamp(
                              0,
                              value.duration.inMilliseconds.toDouble(),
                            )
                          : 0,
                      max: ready && value.duration.inMilliseconds > 0
                          ? value.duration.inMilliseconds.toDouble()
                          : 1,
                      activeColor: T.colorAccent,
                      onChanged: ready
                          ? (next) => controller.seekTo(
                              Duration(milliseconds: next.round()),
                            )
                          : null,
                    ),
                  ),
                  Text(
                    '${time(value.position)} / ${time(value.duration)}',
                    style: QjTheme.type(
                      12,
                      FontWeight.w400,
                      T.lineHeightCaption,
                      Colors.white70,
                    ),
                  ),
                  IconButton(
                    tooltip: immersive ? '退出全屏' : '全屏播放',
                    onPressed: toggleFullscreen,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.white12,
                    ),
                    icon: const QjIcon('maximize-2', color: T.colorInverseInk),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
