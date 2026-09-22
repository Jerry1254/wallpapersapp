import 'package:flutter/material.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../detail/detail_screen.dart';
import '../detail/help_screen.dart';
import '../device/device_capabilities.dart';
import '../downloads/download_manager.dart';
import '../entitlements/redemption.dart';
import '../lab/parallax_lab_screen.dart';
import 'catalog.dart';
import 'catalog_image.dart';

class CatalogScreen extends StatefulWidget {
  const CatalogScreen({
    super.key,
    required this.repository,
    this.redemptions,
    this.downloads,
    this.playback,
    this.deviceCapabilities,
    this.onTab,
    this.category,
    this.search,
    this.labMode = false,
  });
  final CatalogRepository repository;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final AndroidWallpaperPlayback? playback;
  final DeviceCapabilityManager? deviceCapabilities;
  final ValueChanged<int>? onTab;
  final Category? category;
  final String? search;
  final bool labMode;
  @override
  State<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends State<CatalogScreen> {
  late final CatalogController controller;
  final searchText = TextEditingController();
  List<Category> categories = [];
  String? categoryError, childId;
  String view = '精选推荐';
  bool categoriesLoading = false;
  bool get nested => widget.category != null || widget.search != null;
  @override
  void initState() {
    super.initState();
    searchText.text = widget.search ?? '';
    controller = CatalogController(widget.repository)..addListener(_update);
    _reload();
    if (!nested) _categories();
  }

  void _update() {
    if (mounted) setState(() {});
  }

  Map<String, String> get query {
    if (widget.search != null) return {'q': widget.search!};
    if (widget.category != null) {
      return {
        'rootCategoryId': widget.category!.id,
        'childCategoryId': ?childId,
      };
    }
    return switch (view) {
      '免费壁纸' => {'accessType': 'FREE'},
      '4D动态' => {
        'deliveryPlatform': 'ANDROID',
        'resourceType': 'LAYER_PARALLAX',
      },
      '动态壁纸' => {'deliveryPlatform': 'ANDROID', 'resourceType': 'VIDEO'},
      '静态壁纸' => {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
      },
      _ => {'view': 'FEATURED'},
    };
  }

  Future<void> _reload() => controller.load(query);
  Future<void> _categories() async {
    setState(() {
      categoriesLoading = true;
      categoryError = null;
    });
    try {
      final value = await widget.repository.categories();
      if (mounted) setState(() => categories = value);
    } catch (_) {
      if (mounted) setState(() => categoryError = '分类暂时无法加载');
    } finally {
      if (mounted) setState(() => categoriesLoading = false);
    }
  }

  void _search() {
    final value = searchText.text.trim();
    if (value.isEmpty) return;
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => CatalogScreen(
          repository: widget.repository,
          search: value,
          redemptions: widget.redemptions,
          downloads: widget.downloads,
          playback: widget.playback,
          deviceCapabilities: widget.deviceCapabilities,
          onTab: widget.onTab,
          labMode: widget.labMode,
        ),
      ),
    );
  }

  Future<void> _detail(Wallpaper item) async {
    await Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) =>
            widget.labMode &&
                item.hasCapability('ANDROID', 'LAYER_PARALLAX') &&
                widget.downloads != null
            ? ParallaxLabScreen(
                repository: widget.repository,
                downloads: widget.downloads!,
                id: item.id,
                apiBase: widget.downloads!.apiBase,
              )
            : DetailScreen(
                repository: widget.repository,
                id: item.id,
                redemptions: widget.redemptions,
                downloads: widget.downloads,
                playback: widget.playback,
                deviceCapabilities: widget.deviceCapabilities,
              ),
      ),
    );
    if (mounted) await _reload();
  }

  void _category(Category item) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => CatalogScreen(
        repository: widget.repository,
        category: item,
        redemptions: widget.redemptions,
        downloads: widget.downloads,
        playback: widget.playback,
        deviceCapabilities: widget.deviceCapabilities,
        onTab: widget.onTab,
        labMode: widget.labMode,
      ),
    ),
  );
  @override
  void dispose() {
    controller.removeListener(_update);
    controller.dispose();
    searchText.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final content = RefreshIndicator(
      onRefresh: () async {
        await _reload();
        if (!nested) await _categories();
      },
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(T.space5, 0, T.space5, T.space6),
            sliver: SliverList.list(
              children: [
                if (!nested) ...[
                  QjBrandHeader(
                    onService: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => const HelpScreen(customerService: true),
                      ),
                    ),
                  ),
                  const SizedBox(height: T.space5),
                  QjSearchBar(controller: searchText, onSearch: _search),
                ] else ...[
                  QjPageHeader(
                    title: widget.category?.name ?? '搜索壁纸',
                    serviceAction: true,
                    onAction: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => const HelpScreen(customerService: true),
                      ),
                    ),
                  ),
                  const SizedBox(height: T.space5),
                  QjSearchBar(controller: searchText, onSearch: _search),
                ],
                if (!nested) ...[
                  const SizedBox(height: T.space7),
                  _heading('壁纸分类'),
                  if (categoriesLoading)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: T.space5),
                      child: LinearProgressIndicator(),
                    ),
                  if (categoryError != null)
                    _inlineError(categoryError!, _categories),
                  if (!categoriesLoading && categoryError == null) ...[
                    const SizedBox(height: T.space3),
                    _categoriesRow(),
                  ],
                  const SizedBox(height: T.space7),
                  _heading(view == '精选推荐' ? '精选壁纸' : view),
                  const SizedBox(height: T.space3),
                  _filters(['精选推荐', '免费壁纸', '4D动态', '动态壁纸', '静态壁纸'], view, (
                    value,
                  ) {
                    setState(() => view = value);
                    _reload();
                  }),
                ],
                if (widget.category != null) ...[
                  const SizedBox(height: T.space6),
                  _filters(
                    ['全部', ...widget.category!.children.map((e) => e.name)],
                    childId == null
                        ? '全部'
                        : widget.category!.children
                              .firstWhere((e) => e.id == childId)
                              .name,
                    (value) {
                      setState(
                        () => childId = value == '全部'
                            ? null
                            : widget.category!.children
                                  .firstWhere((e) => e.name == value)
                                  .id,
                      );
                      _reload();
                    },
                  ),
                ],
                if (widget.search != null) ...[
                  const SizedBox(height: T.space7),
                  _heading(
                    '“${widget.search}”',
                    trailing: controller.loading
                        ? null
                        : '${controller.items.length} 张壁纸',
                  ),
                ],
                if (controller.loading && controller.items.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 80),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                if (controller.error != null && controller.items.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: T.space6),
                    child: QjStatePanel(
                      kind: QjStateKind.error,
                      description: controller.error,
                      onPressed: controller.retry,
                    ),
                  ),
                if (!controller.loading &&
                    controller.error == null &&
                    controller.items.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: T.space6),
                    child: QjStatePanel(
                      description: widget.search != null
                          ? '没有找到相关壁纸，换个关键词试试'
                          : widget.category != null
                          ? '这个分类正在补充壁纸'
                          : '暂时没有符合条件的已发布壁纸',
                      actionLabel: nested ? '返回首页' : '刷新目录',
                      onPressed: () =>
                          nested ? Navigator.pop(context) : _reload(),
                    ),
                  ),
                if (controller.items.isNotEmpty)
                  const SizedBox(height: T.space5),
              ],
            ),
          ),
          if (controller.items.isNotEmpty)
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: T.space5),
              sliver: SliverLayoutBuilder(
                builder: (context, constraints) {
                  final columns = constraints.crossAxisExtent >= 700 ? 3 : 2;
                  final width =
                      (constraints.crossAxisExtent - (columns - 1) * T.space3) /
                      columns;
                  final height = width * 4.15 / 3 + T.space3 + 28;
                  return SliverGrid.builder(
                    itemCount: controller.items.length,
                    gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: columns,
                      mainAxisSpacing: T.space5,
                      crossAxisSpacing: T.space3,
                      childAspectRatio: width / height,
                    ),
                    itemBuilder: (_, index) {
                      final item = controller.items[index];
                      return QjCatalogCard(
                        repository: widget.repository,
                        wallpaper: item,
                        onPressed: () => _detail(item),
                      );
                    },
                  );
                },
              ),
            ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                T.space5,
                T.space5,
                T.space5,
                T.space12,
              ),
              child: controller.loadingMore
                  ? const Center(child: CircularProgressIndicator())
                  : controller.hasMore
                  ? OutlinedButton(
                      onPressed: controller.more,
                      child: const Text('加载更多'),
                    )
                  : controller.error != null && controller.items.isNotEmpty
                  ? _inlineError(controller.error!, controller.retry)
                  : const SizedBox.shrink(),
            ),
          ),
        ],
      ),
    );
    return nested
        ? Scaffold(
            body: SafeArea(
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
                  child: content,
                ),
              ),
            ),
            bottomNavigationBar: SafeArea(
              minimum: const EdgeInsets.fromLTRB(
                T.space5,
                0,
                T.space5,
                T.space5,
              ),
              child: Center(
                heightFactor: 1,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 390),
                  child: QjBottomNav(
                    selectedIndex: 0,
                    onSelected: (value) {
                      Navigator.of(context).popUntil((route) => route.isFirst);
                      WidgetsBinding.instance.addPostFrameCallback(
                        (_) => widget.onTab?.call(value),
                      );
                    },
                    items: const [
                      QjNavItem('首页', 'house'),
                      QjNavItem('我的', 'images'),
                    ],
                  ),
                ),
              ),
            ),
          )
        : content;
  }

  Widget _heading(String title, {String? trailing}) => Row(
    children: [
      Expanded(
        child: Text(title, style: Theme.of(context).textTheme.titleLarge),
      ),
      if (trailing != null)
        Text(trailing, style: Theme.of(context).textTheme.bodySmall),
    ],
  );
  Widget _filters(
    List<String> labels,
    String selected,
    ValueChanged<String> onSelect,
  ) => SingleChildScrollView(
    scrollDirection: Axis.horizontal,
    child: Row(
      children: labels
          .map(
            (label) => Padding(
              padding: const EdgeInsets.only(right: T.space2),
              child: QjFilterChip(
                label: label,
                selected: label == selected,
                onPressed: () => onSelect(label),
              ),
            ),
          )
          .toList(),
    ),
  );
  Widget _inlineError(String message, VoidCallback retry) => Padding(
    padding: const EdgeInsets.symmetric(vertical: T.space3),
    child: Row(
      children: [
        Expanded(
          child: Text(message, style: Theme.of(context).textTheme.bodySmall),
        ),
        TextButton(onPressed: retry, child: const Text('重试')),
      ],
    ),
  );
  Widget _categoriesRow() => LayoutBuilder(
    builder: (context, constraints) {
      const colors = [
        (T.colorAccentSoft, T.colorAccentStrong),
        (T.colorSuccessSoft, T.colorSuccess),
        (T.colorBlushSoft, Color(0xFFA5524B)),
        (T.colorSurfaceMuted, T.colorInkSoft),
        (T.colorSurfaceMuted, T.colorInkSoft),
      ];
      const icons = ['sparkles', 'mountain', 'flower-2', 'flame', 'image'];
      final width = (constraints.maxWidth - T.space2 * 4) / 5;
      return Wrap(
        spacing: T.space2,
        runSpacing: T.space3,
        children: List.generate(categories.length, (index) {
          final item = categories[index];
          final tone = colors[index % colors.length];
          return SizedBox(
            width: width,
            child: InkWell(
              onTap: () => _category(item),
              borderRadius: BorderRadius.circular(19),
              child: Column(
                children: [
                  Container(
                    width: T.sizeCategoryIcon,
                    height: T.sizeCategoryIcon,
                    decoration: BoxDecoration(
                      color: item.icon == null ? tone.$1 : Colors.transparent,
                      borderRadius: BorderRadius.circular(19),
                    ),
                    child: item.icon == null
                        ? Center(
                            child: QjIcon(
                              icons[index % icons.length],
                              color: tone.$2,
                            ),
                          )
                        : ClipRRect(
                            borderRadius: BorderRadius.circular(19),
                            child: CatalogImage(
                              repository: widget.repository,
                              path: item.icon!,
                            ),
                          ),
                  ),
                  const SizedBox(height: T.space2),
                  Text(
                    item.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: QjTheme.type(
                      12,
                      FontWeight.w500,
                      T.lineHeightCaption,
                      T.colorMutedInk,
                    ),
                  ),
                ],
              ),
            ),
          );
        }),
      );
    },
  );
}
