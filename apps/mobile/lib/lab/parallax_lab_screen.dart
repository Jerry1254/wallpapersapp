import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import '../detail/detail_preview.dart';
import '../downloads/download_manager.dart';
import 'parallax_lab.dart';

class ParallaxLabScreen extends StatefulWidget {
  const ParallaxLabScreen({
    super.key,
    required this.repository,
    required this.downloads,
    required this.id,
    required this.apiBase,
  });
  final CatalogRepository repository;
  final DownloadManager downloads;
  final String id;
  final Uri apiBase;
  @override
  State<ParallaxLabScreen> createState() => _ParallaxLabScreenState();
}

class _ParallaxLabScreenState extends State<ParallaxLabScreen> {
  late final Future<Wallpaper> wallpaper = widget.repository.detail(widget.id);
  late final ParallaxLabAdminClient admin = ParallaxLabAdminClient(
    widget.apiBase,
  );
  final nativeDraft = ParallaxLabNative();
  ParallaxLabConfig? baseline, config;
  String? baseResourceVersionId, loadError;
  int versionNo = 0, selected = 0;
  bool panel = false, hidden = false, ready = false, saving = false;
  bool get dirty =>
      config != null &&
      baseline != null &&
      config!.encoded != baseline!.encoded;

  @override
  void initState() {
    super.initState();
    unawaited(
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky),
    );
  }

  @override
  void dispose() {
    unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge));
    super.dispose();
  }

  Future<void> _installed(String previewId) async {
    if (config != null) return;
    try {
      final info = await const AndroidDetailPreview().configuration(previewId);
      final base = info['resourceVersionId'] as String;
      var next = ParallaxLabConfig.decode(info['config'] as String);
      final draft = await nativeDraft.readDraft(widget.id);
      if (draft?['baseResourceVersionId'] == base &&
          draft?['config'] is String) {
        next = ParallaxLabConfig.decode(draft!['config'] as String);
      }
      if (!mounted) return;
      setState(() {
        baseResourceVersionId = base;
        versionNo = (info['versionNo'] as num).toInt();
        baseline = ParallaxLabConfig.decode(info['config'] as String);
        config = next;
        loadError = null;
      });
    } catch (_) {
      if (mounted) setState(() => loadError = '当前 4D 配置无法读取');
    }
  }

  void _change(void Function(ParallaxLabConfig value) edit) {
    final current = config;
    if (current == null) return;
    edit(current);
    setState(() {});
    final base = baseResourceVersionId;
    if (base != null) {
      unawaited(nativeDraft.writeDraft(widget.id, base, current.encoded));
    }
  }

  void _layer(String key, dynamic value) => _change((item) {
    item.layers[selected][key] = value;
  });
  void _motion(String key, dynamic value) => _change((item) {
    item.motion[key] = value;
  });

  Future<bool> _login() async {
    final account = TextEditingController(), password = TextEditingController();
    String? error;
    final result = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: const Text('管理员登录'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('管理员登录后才能整体保存配置。'),
              const SizedBox(height: 16),
              TextField(
                controller: account,
                decoration: const InputDecoration(labelText: '账号'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: password,
                obscureText: true,
                decoration: const InputDecoration(labelText: '密码'),
              ),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    error!,
                    style: const TextStyle(color: Colors.red),
                  ),
                ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () async {
                try {
                  await admin.login(account.text.trim(), password.text);
                  if (dialogContext.mounted) Navigator.pop(dialogContext, true);
                } on ParallaxLabApiError catch (failure) {
                  update(
                    () => error = failure.status == 401
                        ? '账号或密码错误'
                        : failure.message,
                  );
                }
              },
              child: const Text('登录'),
            ),
          ],
        ),
      ),
    );
    account.dispose();
    password.dispose();
    return result == true;
  }

  Future<bool> _save({bool confirmed = false}) async {
    final current = config, base = baseResourceVersionId;
    if (!dirty || current == null || base == null || saving) return false;
    if (!confirmed) {
      final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('保存全部参数？'),
          content: const Text('场景参数和所有图层参数将整体保存为一个新版本。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('确认保存'),
            ),
          ],
        ),
      );
      if (accepted != true || !mounted) return false;
    }
    if (!admin.authenticated && !await _login()) return false;
    setState(() => saving = true);
    try {
      Map<String, dynamic> result;
      try {
        result = await admin.save(widget.id, base, current.value);
      } on ParallaxLabApiError catch (failure) {
        if (failure.status != 401 || !mounted || !await _login()) rethrow;
        result = await admin.save(widget.id, base, current.value);
      }
      if (!mounted) return false;
      setState(() {
        baseResourceVersionId = result['resourceVersionId'] as String;
        versionNo = (result['versionNo'] as num).toInt();
        baseline = current.copy();
      });
      await nativeDraft.clearDraft(widget.id);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('全部参数已保存为版本 $versionNo')));
      }
      return true;
    } on ParallaxLabApiError catch (failure) {
      if (!mounted) return false;
      final message = failure.code == 'VERSION_CONFLICT'
          ? '配置已被其他设备更新，请返回后重新进入'
          : failure.message;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
      return false;
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Future<void> _back() async {
    if (!dirty) {
      if (mounted) Navigator.pop(context);
      return;
    }
    final action = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('保存本次修改？'),
        content: const Text('当前修改尚未保存。你可以整体保存后返回，或者放弃修改直接返回。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'continue'),
            child: const Text('继续调整'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'discard'),
            child: const Text('不保存并返回'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'save'),
            child: const Text('保存并返回'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    if (action == 'discard') {
      await nativeDraft.clearDraft(widget.id);
      if (mounted) Navigator.pop(context);
    } else if (action == 'save' && await _save(confirmed: true) && mounted) {
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) unawaited(_back());
    },
    child: Scaffold(
      backgroundColor: Colors.black,
      body: FutureBuilder<Wallpaper>(
        future: wallpaper,
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text(
                      '无法加载该壁纸',
                      style: TextStyle(color: Colors.white, fontSize: 18),
                    ),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('返回'),
                    ),
                  ],
                ),
              ),
            );
          }
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final item = snapshot.requireData;
          return Stack(
            fit: StackFit.expand,
            children: [
              DetailPreview(
                manager: widget.downloads,
                wallpaperId: item.id,
                deliveryPlatform: 'ANDROID',
                resourceType: 'LAYER_PARALLAX',
                cover: CatalogImage(
                  repository: widget.repository,
                  path: item.cover,
                ),
                preferPreview: true,
                fill: true,
                configuration: config?.encoded,
                onInstalled: _installed,
                onReady: (value) => setState(() => ready = value),
              ),
              if (!hidden) ...[
                const Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  height: 150,
                  child: _TopScrim(),
                ),
                SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 10, 14, 0),
                    child: Align(
                      alignment: Alignment.topCenter,
                      child: Row(
                        children: [
                          _CircleButton(icon: Icons.chevron_left, onTap: _back),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  item.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 20,
                                    fontWeight: FontWeight.w800,
                                  ),
                                ),
                                Text(
                                  '${ready ? '● 姿态响应正常' : '○ 正在加载真机效果'}${versionNo > 0 ? ' · 配置 v$versionNo' : ''}',
                                  style: const TextStyle(
                                    color: Colors.white70,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          TextButton(
                            onPressed: dirty && !saving ? _save : null,
                            style: TextButton.styleFrom(
                              backgroundColor: dirty
                                  ? const Color(0xffffb22f)
                                  : Colors.black38,
                              foregroundColor: Colors.black,
                              disabledForegroundColor: Colors.white70,
                            ),
                            child: Text(
                              saving
                                  ? '保存中'
                                  : dirty
                                  ? '保存'
                                  : '已保存',
                            ),
                          ),
                          const SizedBox(width: 8),
                          _CircleButton(
                            icon: Icons.visibility_off_outlined,
                            onTap: () => setState(() {
                              hidden = true;
                              panel = false;
                            }),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                if (!panel)
                  SafeArea(
                    child: Align(
                      alignment: Alignment.bottomCenter,
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: FilledButton.icon(
                          onPressed: config == null
                              ? null
                              : () => setState(() => panel = true),
                          icon: const Icon(Icons.tune),
                          label: Text(
                            config == null ? loadError ?? '正在读取参数' : '调整参数',
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
              if (hidden)
                Center(
                  child: FilledButton.tonalIcon(
                    onPressed: () => setState(() => hidden = false),
                    icon: const Icon(Icons.visibility),
                    label: const Text('显示控件'),
                  ),
                ),
              if (!hidden && panel) ...[
                Positioned.fill(
                  bottom: MediaQuery.sizeOf(context).height * .72,
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => setState(() => panel = false),
                  ),
                ),
                Align(
                  alignment: Alignment.bottomCenter,
                  child: FractionallySizedBox(
                    heightFactor: .72,
                    widthFactor: 1,
                    child: _panel(item),
                  ),
                ),
              ],
            ],
          );
        },
      ),
    ),
  );

  Widget _panel(Wallpaper wallpaper) {
    final current = config;
    if (current == null) {
      return const Material(child: Center(child: CircularProgressIndicator()));
    }
    final layer = current.layers[selected];
    return Material(
      color: const Color(0xfffaf8f4),
      borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          InkWell(
            onTap: () => setState(() => panel = false),
            child: const SizedBox(
              height: 28,
              child: Center(
                child: SizedBox(width: 56, child: Divider(thickness: 5)),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            child: Row(
              children: [
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '调整 4D 参数',
                        style: TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      Text('修改仅用于本机预览，最后一次保存全部参数'),
                    ],
                  ),
                ),
                Text(
                  dirty ? '未保存' : '已保存',
                  style: TextStyle(
                    fontWeight: FontWeight.w800,
                    color: dirty ? const Color(0xffc47800) : Colors.green,
                  ),
                ),
              ],
            ),
          ),
          SizedBox(
            height: 62,
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              scrollDirection: Axis.horizontal,
              itemCount: current.layers.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (context, index) => ChoiceChip(
                selected: selected == index,
                onSelected: (_) => setState(() => selected = index),
                label: Text(
                  '${(index + 1).toString().padLeft(2, '0')} ${index == current.layers.length - 1 ? '背景' : '图层'}',
                ),
              ),
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(18, 8, 18, 40),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '图层 ${(selected + 1).toString().padLeft(2, '0')}${selected == current.layers.length - 1 ? ' · 背景' : ''}',
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                    TextButton(
                      onPressed: () => _change(
                        (value) => value.value['layers'][selected] =
                            Map<String, dynamic>.from(
                              baseline!.layers[selected],
                            ),
                      ),
                      child: const Text('复位本层'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'follow', label: Text('跟随')),
                    ButtonSegment(value: 'reverse', label: Text('反向')),
                    ButtonSegment(value: 'fixed', label: Text('固定')),
                  ],
                  selected: {layer['direction'] as String},
                  onSelectionChanged: (value) =>
                      _layer('direction', value.first),
                ),
                _number(
                  '水平运动强度',
                  '100% = 满幅移动一屏宽',
                  ParallaxLabConfig.number(layer['offsetXPercent']),
                  0,
                  _adaptive(layer['offsetXPercent']),
                  (value) => _layer('offsetXPercent', value),
                  openPositive: true,
                ),
                _number(
                  '垂直运动强度',
                  '100% = 满幅移动一屏高',
                  ParallaxLabConfig.number(layer['offsetYPercent']),
                  0,
                  _adaptive(layer['offsetYPercent']),
                  (value) => _layer('offsetYPercent', value),
                  openPositive: true,
                ),
                _number(
                  '初始水平位置',
                  '负数向左，正数向右',
                  ParallaxLabConfig.number(layer['initialOffsetXPercent']),
                  -_adaptiveSigned(layer['initialOffsetXPercent']),
                  _adaptiveSigned(layer['initialOffsetXPercent']),
                  (value) => _layer('initialOffsetXPercent', value),
                  openSigned: true,
                ),
                _number(
                  '初始垂直位置',
                  '负数向上，正数向下',
                  ParallaxLabConfig.number(layer['initialOffsetYPercent']),
                  -_adaptiveSigned(layer['initialOffsetYPercent']),
                  _adaptiveSigned(layer['initialOffsetYPercent']),
                  (value) => _layer('initialOffsetYPercent', value),
                  openSigned: true,
                ),
                _number(
                  '缩放',
                  '保护画面边缘',
                  ParallaxLabConfig.number(layer['scale']),
                  1,
                  1.5,
                  (value) => _layer('scale', value),
                  step: .01,
                ),
                _number(
                  '不透明度',
                  '与素材透明度共同生效',
                  ParallaxLabConfig.number(layer['opacity']) * 100,
                  0,
                  100,
                  (value) => _layer('opacity', value / 100),
                ),
                const SizedBox(height: 16),
                const Text(
                  '混合模式',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 8),
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'normal', label: Text('正常')),
                    ButtonSegment(value: 'screen', label: Text('滤色')),
                    ButtonSegment(value: 'add', label: Text('叠加')),
                  ],
                  selected: {layer['blendMode'] as String},
                  onSelectionChanged: (value) =>
                      _layer('blendMode', value.first),
                ),
                const Padding(
                  padding: EdgeInsets.only(top: 28),
                  child: Text(
                    '全局高级设置',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900),
                  ),
                ),
                const Text('全部图层共用，通常保持默认值即可'),
                _number(
                  '水平最大倾斜角度',
                  '左右运动达到满幅的位置',
                  ParallaxLabConfig.number(current.motion['maxAngleX']),
                  1,
                  75,
                  (value) => _motion('maxAngleX', value),
                ),
                _number(
                  '垂直最大倾斜角度',
                  '上下运动达到满幅的位置',
                  ParallaxLabConfig.number(current.motion['maxAngleY']),
                  1,
                  75,
                  (value) => _motion('maxAngleY', value),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _number(
    String title,
    String hint,
    double value,
    double min,
    double max,
    ValueChanged<double> changed, {
    double step = 1,
    bool openPositive = false,
    bool openSigned = false,
  }) {
    final divisions = ((max - min) / step).round().clamp(1, 10000).toInt();
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    Text(
                      hint,
                      style: const TextStyle(
                        color: Colors.black54,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: 92,
                child: TextFormField(
                  key: ValueKey('$selected-$title-$value'),
                  initialValue: _format(value),
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                    signed: true,
                  ),
                  textAlign: TextAlign.center,
                  onFieldSubmitted: (text) {
                    final parsed = double.tryParse(text);
                    final valid =
                        parsed != null &&
                        parsed.isFinite &&
                        (openSigned || parsed >= min) &&
                        (openPositive
                            ? parsed >= 0
                            : openSigned || parsed <= max);
                    if (valid) changed(parsed);
                  },
                ),
              ),
            ],
          ),
          Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            onChanged: changed,
          ),
        ],
      ),
    );
  }

  static double _adaptive(dynamic value) =>
      ((ParallaxLabConfig.number(value).clamp(0, double.maxFinite) / 100)
                  .ceil()
                  .clamp(1, 1000000) *
              100)
          .toDouble();
  static double _adaptiveSigned(dynamic value) =>
      ((ParallaxLabConfig.number(value).abs() / 100).ceil().clamp(1, 1000000) *
              100)
          .toDouble();
  static String _format(double value) => value == value.roundToDouble()
      ? value.toInt().toString()
      : value.toStringAsFixed(2);
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => IconButton(
    onPressed: onTap,
    icon: Icon(icon),
    color: Colors.white,
    style: IconButton.styleFrom(backgroundColor: Colors.black45),
  );
}

class _TopScrim extends StatelessWidget {
  const _TopScrim();
  @override
  Widget build(BuildContext context) => const DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Colors.black54, Colors.transparent],
      ),
    ),
  );
}
