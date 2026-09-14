import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'catalog.dart';
import '../entitlements/redemption.dart';
import '../detail/detail_screen.dart';
import 'catalog_image.dart';

class CatalogScreen extends StatefulWidget {
  const CatalogScreen({
    super.key,
    required this.repository,
    this.redemptions,
    this.category,
    this.search,
  });
  final CatalogRepository repository;
  final RedemptionCoordinator? redemptions;
  final Category? category;
  final String? search;
  @override
  State<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends State<CatalogScreen> {
  late final CatalogController controller;
  final searchText = TextEditingController();
  List<Category> categories = [];
  String? categoryError;
  String view = '精选推荐';
  String? childId;
  bool categoriesLoading = false;
  @override
  void initState() {
    super.initState();
    controller = CatalogController(widget.repository)..addListener(_update);
    _reload();
    if (widget.category == null && widget.search == null) _categories();
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
      '最近上新' => {'sort': 'NEWEST'},
      '4D 景深' => {'kind': 'PARALLAX_4D'},
      '静态壁纸' => {'view': 'STATIC'},
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
      final result = await widget.repository.categories();
      if (mounted) setState(() => categories = result);
    } catch (_) {
      if (mounted) setState(() => categoryError = '分类暂时无法加载');
    } finally {
      if (mounted) setState(() => categoriesLoading = false);
    }
  }

  void _search() {
    final term = searchText.text.trim();
    if (term.isEmpty) return;
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => CatalogScreen(
          repository: widget.repository,
          search: term,
          redemptions: widget.redemptions,
        ),
      ),
    );
  }

  @override
  void dispose() {
    controller.removeListener(_update);
    controller.dispose();
    searchText.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final nested = widget.category != null || widget.search != null;
    final content = RefreshIndicator(
      onRefresh: () async {
        await _reload();
        if (!nested) await _categories();
      },
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.all(20),
            sliver: SliverList.list(
              children: [
                if (!nested) ...[
                  const Text(
                    '让每一屏，都有心动',
                    style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 20),
                  TextField(
                    controller: searchText,
                    maxLength: 100,
                    onSubmitted: (_) => _search(),
                    textInputAction: TextInputAction.search,
                    decoration: InputDecoration(
                      hintText: '搜索喜欢的壁纸',
                      counterText: '',
                      prefixIcon: const Icon(Icons.search),
                      suffixIcon: IconButton(
                        onPressed: _search,
                        tooltip: '搜索',
                        icon: const Icon(Icons.arrow_forward),
                      ),
                      filled: true,
                      fillColor: Colors.white,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(30),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    '壁纸分类',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  if (categoriesLoading) const LinearProgressIndicator(),
                  if (categoryError != null)
                    _error(categoryError!, _categories),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: categories
                        .map(
                          (category) => SizedBox(
                            width: 78,
                            child: InkWell(
                              borderRadius: BorderRadius.circular(16),
                              onTap: () => Navigator.push(
                                context,
                                MaterialPageRoute<void>(
                                  builder: (_) => CatalogScreen(
                                    redemptions: widget.redemptions,
                                    repository: widget.repository,
                                    category: category,
                                  ),
                                ),
                              ),
                              child: Column(
                                children: [
                                  ClipRRect(
                                    borderRadius: BorderRadius.circular(16),
                                    child: SizedBox(
                                      width: 56,
                                      height: 56,
                                      child: category.icon == null
                                          ? const Icon(Icons.category_outlined)
                                          : CatalogImage(
                                              repository: widget.repository,
                                              path: category.icon!,
                                            ),
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    category.name,
                                    maxLines: 2,
                                    textAlign: TextAlign.center,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        )
                        .toList(),
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    '精选壁纸',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: ['精选推荐', '最近上新', '4D 景深', '静态壁纸']
                          .map(
                            (label) => Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: ChoiceChip(
                                label: Text(label),
                                selected: view == label,
                                onSelected: (_) {
                                  setState(() => view = label);
                                  _reload();
                                },
                              ),
                            ),
                          )
                          .toList(),
                    ),
                  ),
                ],
                if (widget.category != null)
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        ChoiceChip(
                          label: const Text('全部'),
                          selected: childId == null,
                          onSelected: (_) {
                            setState(() => childId = null);
                            _reload();
                          },
                        ),
                        ...widget.category!.children.map(
                          (child) => Padding(
                            padding: const EdgeInsets.only(left: 8),
                            child: ChoiceChip(
                              label: Text(child.name),
                              selected: childId == child.id,
                              onSelected: (_) {
                                setState(() => childId = child.id);
                                _reload();
                              },
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                if (controller.loading)
                  const Padding(
                    padding: EdgeInsets.all(16),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                if (controller.error != null)
                  _error(controller.error!, controller.retry),
                if (!controller.loading &&
                    controller.error == null &&
                    controller.items.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 48),
                    child: Center(child: Text('暂时没有符合条件的已发布壁纸')),
                  ),
              ],
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            sliver: SliverLayoutBuilder(
              builder: (context, constraints) => SliverGrid.builder(
                itemCount: controller.items.length,
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: constraints.crossAxisExtent >= 700 ? 3 : 2,
                  mainAxisSpacing: 16,
                  crossAxisSpacing: 12,
                  childAspectRatio: .58,
                ),
                itemBuilder: (context, index) {
                  final item = controller.items[index];
                  return Material(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(22),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => DetailScreen(
                            redemptions: widget.redemptions,
                            repository: widget.repository,
                            id: item.id,
                          ),
                        ),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: SizedBox(
                              width: double.infinity,
                              child: CatalogImage(
                                repository: widget.repository,
                                path: item.cover,
                              ),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
                            child: Text(
                              item.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                            child: Text(
                              item.kindLabel,
                              style: const TextStyle(
                                color: QingjingWallpaperTokens.colorMutedInk,
                                fontSize: 12,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: controller.loadingMore
                  ? const Center(child: CircularProgressIndicator())
                  : controller.hasMore
                  ? OutlinedButton(
                      onPressed: controller.more,
                      child: const Text('加载更多'),
                    )
                  : const SizedBox(height: 16),
            ),
          ),
        ],
      ),
    );
    return nested
        ? Scaffold(
            appBar: AppBar(
              title: Text(widget.category?.name ?? '搜索：${widget.search}'),
            ),
            body: content,
          )
        : content;
  }

  Widget _error(String message, VoidCallback retry) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Column(
      children: [
        Text(message),
        TextButton(onPressed: retry, child: const Text('重试')),
      ],
    ),
  );
}
