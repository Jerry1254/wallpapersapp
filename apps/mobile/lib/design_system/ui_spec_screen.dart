import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'qj_components.dart';
import 'qj_theme.dart';

/// Offline specimens derived from H5 DesignSystemView and its actual components.
/// This screen deliberately has no repository, native bridge or device session.
class UiSpecScreen extends StatefulWidget {
  const UiSpecScreen({super.key});
  @override
  State<UiSpecScreen> createState() => _UiSpecScreenState();
}

class _UiSpecScreenState extends State<UiSpecScreen> {
  String category = '推荐', subcategory = '全部';
  int specimenTab = 0;
  QjStateKind state = QjStateKind.empty;

  void feedback(String label) => ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text('组件演示：$label'), duration: T.durationSlow * 3),
  );

  void panel(_PanelKind kind) => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
    builder: (_) => _PanelDemo(kind: kind),
  );

  Widget heading(String number, String title, String subtitle) => Padding(
    padding: const EdgeInsets.only(top: T.space8, bottom: T.space4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 34,
          height: 34,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: T.colorNavigation,
            borderRadius: BorderRadius.circular(T.radiusSmall),
          ),
          child: Text(
            number,
            style: QjTheme.type(
              12,
              FontWeight.w700,
              T.lineHeightCaption,
              T.colorInverseInk,
            ),
          ),
        ),
        const SizedBox(width: T.space3),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 4),
              Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ],
    ),
  );

  Widget specimen(String title, Widget child) => Padding(
    padding: const EdgeInsets.only(bottom: T.space4),
    child: QjSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: T.space4),
          child,
        ],
      ),
    ),
  );

  Widget chips(
    List<String> labels,
    String selected,
    ValueChanged<String> select,
  ) => SingleChildScrollView(
    scrollDirection: Axis.horizontal,
    child: Row(
      children: labels
          .map(
            (label) => Padding(
              padding: const EdgeInsets.only(right: T.space2),
              child: QjFilterChip(
                label: label,
                selected: selected == label,
                onPressed: () => select(label),
              ),
            ),
          )
          .toList(),
    ),
  );

  @override
  Widget build(BuildContext context) => ColoredBox(
    color: T.colorBackground,
    child: SingleChildScrollView(
      key: const PageStorageKey('ui-spec-scroll'),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              T.space5,
              T.space5,
              T.space5,
              T.space8,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '倾境壁纸',
                  style: QjTheme.type(
                    12,
                    FontWeight.w700,
                    T.lineHeightCaption,
                    T.colorAccentStrong,
                  ),
                ),
                const SizedBox(height: T.space2),
                Text(
                  'App UI 规范',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: T.space2),
                Text(
                  '参考 H5 · 移动端视觉 Token、页面骨架与核心组件规范',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                const SizedBox(height: T.space3),
                const Wrap(
                  spacing: T.space2,
                  runSpacing: T.space2,
                  children: [
                    QjTypeBadge('v0.1'),
                    QjTypeBadge('390 基准宽度', tone: QjBadgeTone.amber),
                    QjTypeBadge('H5 / Flutter', tone: QjBadgeTone.light),
                  ],
                ),
                heading('01', '视觉原则', '以壁纸内容为主体，界面保持安静、克制和高对比。'),
                QjSurface(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (final item in [
                        ('大图优先', '卡片把空间留给壁纸，文字只提供名称和类型。'),
                        ('黑白骨架', '白色内容面搭配深黑导航与按钮，保证不同壁纸都能融入。'),
                        ('暖色点睛', '暖黄色只用于选中、关键状态和少量图标，不大面积铺色。'),
                        ('圆润克制', '媒体 22px、卡片 24px、胶囊按钮，不使用强玻璃和炫光。'),
                      ])
                        Padding(
                          padding: const EdgeInsets.only(bottom: T.space3),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                item.$1,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              const SizedBox(height: 4),
                              Text(
                                item.$2,
                                style: Theme.of(context).textTheme.bodyMedium,
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
                ),
                heading('02', '基础 Token', '与 H5 共用颜色、排版、间距、圆角和动效数值。'),
                specimen(
                  '颜色',
                  LayoutBuilder(
                    builder: (context, constraints) => Wrap(
                      spacing: T.space3,
                      runSpacing: T.space4,
                      children:
                          [
                                ('主文字', 'ink', T.colorInk),
                                ('品牌强调', 'accent', T.colorAccent),
                                ('暖粉辅助', 'blush', T.colorBlush),
                                ('成功', 'success', T.colorSuccess),
                                ('页面背景', 'background', T.colorBackground),
                                ('内容面', 'surface', T.colorSurface),
                              ]
                              .map(
                                (color) => SizedBox(
                                  width: (constraints.maxWidth - T.space3) / 2,
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Container(
                                        height: 58,
                                        decoration: BoxDecoration(
                                          color: color.$3,
                                          border: Border.all(
                                            color: T.colorOutline,
                                          ),
                                          borderRadius: BorderRadius.circular(
                                            T.radiusControl,
                                          ),
                                        ),
                                      ),
                                      const SizedBox(height: T.space2),
                                      Text(
                                        color.$1,
                                        style: Theme.of(
                                          context,
                                        ).textTheme.titleMedium,
                                      ),
                                      Text(
                                        '${color.$2}\n#${color.$3.toARGB32().toRadixString(16).substring(2).toUpperCase()}',
                                        style: Theme.of(
                                          context,
                                        ).textTheme.bodySmall,
                                      ),
                                    ],
                                  ),
                                ),
                              )
                              .toList(),
                    ),
                  ),
                ),
                specimen(
                  '字体',
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Page Title · 28 / 800',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        '倾境壁纸',
                        style: Theme.of(context).textTheme.headlineMedium,
                      ),
                      const SizedBox(height: T.space4),
                      Text(
                        'Section · 20 / 700',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        '不同风格，随心切换',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: T.space4),
                      Text(
                        'Body · 14 / 400',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        '中文使用系统字体，避免外部字体加载影响首屏和隐私。',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: T.space3),
                      Text(
                        'Display 34 · Card / Body Large 16\nCaption Large 13 · Caption 12 · Micro 10',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                specimen(
                  '间距与圆角',
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Wrap(
                        spacing: T.space3,
                        runSpacing: T.space3,
                        children: [
                          for (final size in [
                            T.space1,
                            T.space2,
                            T.space3,
                            T.space4,
                            T.space5,
                            T.space6,
                            T.space7,
                            T.space8,
                            T.space10,
                            T.space12,
                          ])
                            Column(
                              children: [
                                Container(
                                  width: size,
                                  height: 8,
                                  color: T.colorAccent,
                                ),
                                Text(
                                  '${size.toInt()}',
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                        ],
                      ),
                      const SizedBox(height: T.space4),
                      Wrap(
                        spacing: T.space2,
                        runSpacing: T.space2,
                        children: [
                          for (final radius in [
                            T.radiusSmall,
                            T.radiusControl,
                            T.radiusMedia,
                            T.radiusCard,
                            T.radiusSheet,
                            T.radiusNavigation,
                          ])
                            Container(
                              width: 64,
                              height: 52,
                              alignment: Alignment.center,
                              decoration: BoxDecoration(
                                color: T.colorSurfaceMuted,
                                border: Border.all(color: T.colorOutline),
                                borderRadius: BorderRadius.circular(radius),
                              ),
                              child: Text('${radius.toInt()}'),
                            ),
                        ],
                      ),
                      const SizedBox(height: T.space3),
                      Text(
                        '胶囊 999 · 点击区域至少 44\n动效 140 / 220 / 360ms · 尊重减少动画设置',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                heading('03', '页面骨架', '白色内容面、20 页面边距、430 内容最大宽度。'),
                specimen(
                  '详情 · 大图与主操作',
                  Column(
                    children: [
                      Row(
                        children: [
                          IconButton(
                            tooltip: '返回',
                            onPressed: () => feedback('返回'),
                            style: IconButton.styleFrom(
                              side: const BorderSide(color: T.colorOutline),
                              minimumSize: const Size(44, 44),
                            ),
                            icon: const QjIcon('chevron-left'),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              '城市余晖',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                          ),
                          OutlinedButton(
                            style: OutlinedButton.styleFrom(
                              foregroundColor: T.colorAccentStrong,
                              side: const BorderSide(
                                color: T.colorAccentStrong,
                              ),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                              ),
                              textStyle: QjTheme.type(
                                12,
                                FontWeight.w700,
                                T.lineHeightCaption,
                              ),
                            ),
                            onPressed: () => feedback('观看设置教程'),
                            child: const Text('观看设置教程'),
                          ),
                        ],
                      ),
                      const SizedBox(height: T.space3),
                      Container(
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(T.radiusCard),
                          boxShadow: const [T.shadowCard],
                        ),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(T.radiusCard),
                          child: AspectRatio(
                            aspectRatio: 1 / 2,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                SvgPicture.asset(
                                  'assets/ui-reference/city.svg',
                                  fit: BoxFit.cover,
                                ),
                                Positioned(
                                  left: 0,
                                  right: 0,
                                  bottom: 0,
                                  height: 132,
                                  child: DecoratedBox(
                                    decoration: BoxDecoration(
                                      gradient: LinearGradient(
                                        begin: Alignment.topCenter,
                                        end: Alignment.bottomCenter,
                                        colors: [
                                          Colors.transparent,
                                          T.colorScrim.withValues(alpha: .48),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                Positioned(
                                  left: 16,
                                  right: 16,
                                  bottom: 16,
                                  child: QjPrimaryAction(
                                    label: '下载壁纸',
                                    accent: true,
                                    onPressed: () => panel(_PanelKind.download),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                heading('04', '业务组件', '以下为组件演示，可点选查看状态，不执行兑换或系统设置。'),
                specimen(
                  '金刚区与二级分类',
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            for (final item in [
                              (
                                '推荐',
                                'sparkles',
                                T.colorAccentSoft,
                                T.colorAccentStrong,
                              ),
                              (
                                '风景',
                                'mountain',
                                T.colorSuccessSoft,
                                T.colorSuccess,
                              ),
                              (
                                '禅意',
                                'flower-2',
                                T.colorBlushSoft,
                                const Color(0xFFA5524B),
                              ),
                              (
                                '角色',
                                'flame',
                                T.colorSurfaceMuted,
                                T.colorInkSoft,
                              ),
                              (
                                '静态',
                                'image',
                                T.colorSurfaceMuted,
                                T.colorInkSoft,
                              ),
                            ])
                              QjCategoryTile(
                                label: item.$1,
                                icon: item.$2,
                                background: item.$3,
                                foreground: item.$4,
                                selected: category == item.$1,
                                onPressed: () =>
                                    setState(() => category = item.$1),
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(height: T.space3),
                      chips(
                        ['全部', '自然风光', '城市夜景', '国风禅意', '原创角色'],
                        subcategory,
                        (label) => setState(() => subcategory = label),
                      ),
                    ],
                  ),
                ),
                specimen(
                  '壁纸卡片',
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: QjWallpaperCard(
                          name: '城市余晖',
                          type: '4D动态',
                          image: 'city',
                          onPressed: () => feedback('城市余晖'),
                        ),
                      ),
                      const SizedBox(width: T.space3),
                      Expanded(
                        child: QjWallpaperCard(
                          name: '深海呼吸',
                          type: '静态',
                          image: 'coast',
                          onPressed: () => feedback('深海呼吸'),
                        ),
                      ),
                    ],
                  ),
                ),
                specimen(
                  '标签与主按钮',
                  Column(
                    children: [
                      const Wrap(
                        spacing: T.space2,
                        runSpacing: T.space2,
                        children: [
                          QjTypeBadge('4D动态'),
                          QjTypeBadge('动态', tone: QjBadgeTone.amber),
                          QjTypeBadge('已获得', tone: QjBadgeTone.success),
                          QjTypeBadge('Android', tone: QjBadgeTone.light),
                        ],
                      ),
                      const SizedBox(height: T.space4),
                      QjPrimaryAction(
                        label: '下载壁纸',
                        onPressed: () => panel(_PanelKind.download),
                      ),
                      const SizedBox(height: T.space3),
                      QjPrimaryAction(
                        label: '设置壁纸',
                        accent: true,
                        onPressed: () => panel(_PanelKind.target),
                      ),
                      const SizedBox(height: T.space3),
                      const TickerMode(
                        enabled: false,
                        child: QjPrimaryAction(label: '正在下载', loading: true),
                      ),
                      const SizedBox(height: T.space3),
                      const QjPrimaryAction(label: '当前设备不支持'),
                    ],
                  ),
                ),
                specimen(
                  '面板',
                  Column(
                    children: [
                      QjPrimaryAction(
                        label: '兑换码面板',
                        onPressed: () => panel(_PanelKind.redeem),
                      ),
                      const SizedBox(height: T.space3),
                      QjPrimaryAction(
                        label: '下载状态',
                        onPressed: () => panel(_PanelKind.download),
                      ),
                      const SizedBox(height: T.space3),
                      QjPrimaryAction(
                        label: 'Android · 设置位置',
                        accent: true,
                        onPressed: () => panel(_PanelKind.target),
                      ),
                    ],
                  ),
                ),
                specimen(
                  '我的 · 教程入口',
                  QjTutorialCard(onPressed: () => feedback('壁纸设置教程')),
                ),
                specimen(
                  '空状态',
                  chips(
                    ['空内容', '加载失败', '网络不可用'],
                    ['空内容', '加载失败', '网络不可用'][state.index],
                    (label) => setState(
                      () => state = QjStateKind
                          .values[['空内容', '加载失败', '网络不可用'].indexOf(label)],
                    ),
                  ),
                ),
                QjStatePanel(
                  kind: state,
                  onPressed: () =>
                      feedback(state == QjStateKind.empty ? '返回首页' : '重新加载'),
                ),
                const SizedBox(height: T.space4),
                specimen(
                  '底部导航 · H5 基础组件',
                  QjBottomNav(
                    items: const [
                      QjNavItem('首页', 'house'),
                      QjNavItem('我的', 'images'),
                    ],
                    keyPrefix: 'specimen-tab',
                    selectedIndex: specimenTab,
                    onSelected: (value) => setState(() => specimenTab = value),
                  ),
                ),
                heading('05', '使用边界', '业务页内容与交互以 H5 为准。'),
                QjSurface(
                  child: Text(
                    '层级：每屏只保留一个明显主操作。\n图片：大图优先，卡片仅名称与类型。\n动效：短促反馈，避免无意义循环。\n适配：尊重安全区域与系统字体缩放。\n\nApp 实际底部菜单增加「UI 规范」作为本页入口；试用入口继续隐藏。',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

enum _PanelKind { redeem, download, target }

class _PanelDemo extends StatefulWidget {
  const _PanelDemo({required this.kind});
  final _PanelKind kind;
  @override
  State<_PanelDemo> createState() => _PanelDemoState();
}

class _PanelDemoState extends State<_PanelDemo> {
  int state = 0, target = 2;
  final code = TextEditingController();
  @override
  void dispose() {
    code.dispose();
    super.dispose();
  }

  Widget states(List<String> labels) => Wrap(
    spacing: T.space2,
    runSpacing: T.space2,
    children: List.generate(
      labels.length,
      (i) => QjFilterChip(
        label: labels[i],
        selected: state == i,
        onPressed: () => setState(() => state = i),
      ),
    ),
  );

  void close() => Navigator.pop(context);

  @override
  Widget build(BuildContext context) => QjSheet(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(child: QjTypeBadge('组件演示', tone: QjBadgeTone.amber)),
            IconButton(
              tooltip: '关闭组件演示',
              onPressed: close,
              icon: const QjIcon('chevron-left'),
            ),
          ],
        ),
        const SizedBox(height: T.space3),
        if (widget.kind == _PanelKind.redeem) ...[
          Text('兑换这张壁纸', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          Text('输入客服发送的兑换码', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: T.space4),
          states(['待输入', '验证中', '成功', '失败']),
          const SizedBox(height: T.space4),
          Text('兑换码', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 8),
          TextField(
            controller: code,
            enabled: state != 1,
            decoration: const InputDecoration(hintText: '请输入兑换码'),
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          if (state >= 2)
            Padding(
              padding: const EdgeInsets.only(top: T.space3),
              child: Text(
                state == 2 ? '兑换成功，已绑定当前设备' : '兑换码无效或额度已用完',
                style: QjTheme.type(
                  13,
                  FontWeight.w500,
                  T.lineHeightBody,
                  state == 2 ? T.colorSuccess : T.colorDanger,
                ),
              ),
            ),
          const SizedBox(height: T.space5),
          TickerMode(
            enabled: false,
            child: QjPrimaryAction(
              label: state == 1
                  ? '正在验证'
                  : state == 2
                  ? '下载壁纸'
                  : '验证并兑换',
              loading: state == 1,
              onPressed: () => state == 2 ? close() : setState(() => state = 2),
            ),
          ),
        ],
        if (widget.kind == _PanelKind.download) ...[
          states(['下载中', '校验中', '完成', '失败']),
          const SizedBox(height: T.space5),
          Center(
            child: Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                color: state == 2
                    ? T.colorSuccessSoft
                    : state == 3
                    ? T.colorDangerSoft
                    : T.colorAccentSoft,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Center(
                child: QjIcon(
                  state == 2
                      ? 'check'
                      : state == 3
                      ? 'circle-alert'
                      : 'loader-circle',
                  size: 28,
                  color: state == 2
                      ? T.colorSuccess
                      : state == 3
                      ? T.colorDanger
                      : T.colorAccentStrong,
                ),
              ),
            ),
          ),
          const SizedBox(height: T.space4),
          Center(
            child: Text(
              ['正在下载壁纸', '正在校验资源', '壁纸下载完成', '下载失败'][state],
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          const SizedBox(height: T.space2),
          Center(
            child: Text(
              ['下载进度 64%', '确认文件完整性与资源签名', '资源已安全保存到当前设备', '网络中断，请重新尝试'][state],
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
          const SizedBox(height: T.space5),
          ClipRRect(
            borderRadius: BorderRadius.circular(T.radiusPill),
            child: const LinearProgressIndicator(
              value: .64,
              minHeight: 8,
              color: T.colorAccent,
              backgroundColor: T.colorSurfaceStrong,
            ),
          ),
          if (state >= 2) ...[
            const SizedBox(height: T.space5),
            QjPrimaryAction(
              label: state == 2 ? '设置壁纸' : '重新尝试',
              accent: state == 2,
              onPressed: () => state == 2 ? close() : setState(() => state = 0),
            ),
          ],
        ],
        if (widget.kind == _PanelKind.target) ...[
          Text(
            state == 0 ? '设置到哪里' : '壁纸设置成功',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 4),
          Text(
            state == 0 ? '选项由当前手机的系统能力决定' : '请返回桌面观看效果',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: T.space5),
          if (state == 0) ...[
            for (final (i, item) in [
              ('桌面壁纸', '显示在手机桌面', 'panels-top-left'),
              ('锁屏壁纸', '显示在锁屏界面', 'lock-keyhole'),
              ('桌面和锁屏', '两处使用同一张壁纸', 'smartphone'),
            ].indexed)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Semantics(
                  button: true,
                  selected: target == i,
                  child: InkWell(
                    onTap: () => setState(() => target = i),
                    borderRadius: BorderRadius.circular(T.radiusControl),
                    child: Container(
                      constraints: const BoxConstraints(minHeight: 68),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: target == i
                            ? T.colorSurface
                            : T.colorSurfaceMuted,
                        border: Border.all(
                          color: target == i ? T.colorAccent : T.colorOutline,
                        ),
                        borderRadius: BorderRadius.circular(T.radiusControl),
                        boxShadow: target == i ? const [T.shadowSoft] : null,
                      ),
                      child: Row(
                        children: [
                          QjIcon(item.$3),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  item.$1,
                                  style: Theme.of(
                                    context,
                                  ).textTheme.titleMedium,
                                ),
                                Text(
                                  item.$2,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                          ),
                          if (target == i)
                            const QjIcon(
                              'check',
                              size: 15,
                              color: T.colorAccentStrong,
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          ],
          QjPrimaryAction(
            label: state == 0 ? '确认设置' : '完成',
            accent: state != 0,
            onPressed: () => state == 0 ? setState(() => state = 1) : close(),
          ),
        ],
      ],
    ),
  );
}
