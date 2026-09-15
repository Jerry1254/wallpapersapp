import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../support/customer_service.dart';

class HelpScreen extends StatelessWidget {
  const HelpScreen({super.key, this.customerService = false});
  final bool customerService;

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
                title: customerService ? '微信客服' : '壁纸设计教程',
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
                const SizedBox(height: T.space4),
                QjSettingTutorialCard(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => const SettingTutorialScreen(),
                    ),
                  ),
                ),
              ] else
                const _TutorialLessons(),
            ],
          ),
        ),
      ),
    ),
  );
}

class _TutorialLessons extends StatelessWidget {
  const _TutorialLessons();
  static const lessons = [
    (
      '静态壁纸',
      '准备适合手机竖屏显示的完整画面。',
      'smartphone',
      ['推荐比例 1:2', '主体避开顶部时间和底部手势区域', '导出 JPG、PNG 或 WebP'],
    ),
    (
      'Android 4D 分层',
      '把前景与背景拆开，给手机姿态变化留出移动范围。',
      'layers-3',
      ['至少包含背景层和透明前景层', '背景四周需要补全安全区域', '前景边缘使用透明 PNG'],
    ),
    (
      '动态效果素材',
      '使用短时、无声、可循环的演示素材。',
      'play-square',
      ['建议 6–12 秒', '首尾画面衔接自然', '避免快速闪烁和强烈位移'],
    ),
    (
      '提交给客服',
      '整理原图、分层文件和效果说明后发送。',
      'send',
      ['文件名写明壁纸名称', '说明希望支持的平台', '保留原始设计文件'],
    ),
    (
      '内容授权',
      '只提交自己拥有使用权的图片和角色素材。',
      'shield-check',
      ['不得上传盗版影视或动漫素材', '人物照片需要获得肖像授权', '保留素材来源和授权记录'],
    ),
  ];
  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final (index, item) in lessons.indexed)
        Padding(
          padding: const EdgeInsets.only(bottom: T.space4),
          child: Container(
            padding: const EdgeInsets.all(T.space5),
            decoration: BoxDecoration(
              color: T.colorSurface,
              border: Border.all(color: T.colorOutline),
              borderRadius: BorderRadius.circular(T.radiusCard),
              boxShadow: const [T.shadowSoft],
            ),
            child: Stack(
              children: [
                Positioned(
                  right: 0,
                  top: 0,
                  child: Text(
                    '${index + 1}'.padLeft(2, '0'),
                    style: QjTheme.type(
                      22,
                      FontWeight.w800,
                      T.lineHeightSection,
                      T.colorOutlineStrong,
                    ),
                  ),
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 50,
                      height: 50,
                      decoration: BoxDecoration(
                        color: T.colorAccent,
                        borderRadius: BorderRadius.circular(17),
                      ),
                      child: Center(child: QjIcon(item.$3, size: 24)),
                    ),
                    const SizedBox(width: T.space4),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: const EdgeInsets.only(right: 36),
                            child: Text(
                              item.$1,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ),
                          const SizedBox(height: 5),
                          Text(
                            item.$2,
                            style: Theme.of(
                              context,
                            ).textTheme.bodySmall?.copyWith(fontSize: 13),
                          ),
                          const SizedBox(height: T.space3),
                          for (final point in item.$4)
                            Padding(
                              padding: const EdgeInsets.only(bottom: 4),
                              child: Text(
                                '• $point',
                                style: QjTheme.type(
                                  13,
                                  FontWeight.w400,
                                  1.8,
                                  T.colorInkSoft,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
    ],
  );
}

class SettingTutorialScreen extends StatefulWidget {
  const SettingTutorialScreen({super.key});
  @override
  State<SettingTutorialScreen> createState() => _SettingTutorialScreenState();
}

class _SettingTutorialScreenState extends State<SettingTutorialScreen> {
  late final VideoPlayerController controller;
  bool ready = false, immersive = false;
  @override
  void initState() {
    super.initState();
    controller = VideoPlayerController.asset(
      'assets/ui-reference/setting-tutorial.mp4',
    )..addListener(_changed);
    controller.initialize().then((_) {
      if (mounted) setState(() => ready = true);
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
                          '设置教程',
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
                        child: ready
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
