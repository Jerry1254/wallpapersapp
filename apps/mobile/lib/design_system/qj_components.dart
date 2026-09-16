import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'qj_theme.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';

class QjIcon extends StatelessWidget {
  const QjIcon(this.name, {super.key, this.size = 20, this.color});
  final String name;
  final double size;
  final Color? color;
  @override
  Widget build(BuildContext context) => SvgPicture.asset(
    'assets/lucide/$name.svg',
    width: size,
    height: size,
    colorFilter: ColorFilter.mode(
      color ?? IconTheme.of(context).color ?? T.colorInk,
      BlendMode.srcIn,
    ),
    excludeFromSemantics: true,
  );
}

class QjPrimaryAction extends StatelessWidget {
  const QjPrimaryAction({
    super.key,
    required this.label,
    this.onPressed,
    this.accent = false,
    this.loading = false,
  });
  final String label;
  final VoidCallback? onPressed;
  final bool accent, loading;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: double.infinity,
    child: FilledButton(
      style: QjTheme.action(accent: accent),
      onPressed: loading ? null : onPressed,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (loading) ...[
              SizedBox(
                width: 19,
                height: 19,
                child: MediaQuery.disableAnimationsOf(context)
                    ? const QjIcon(
                        'loader-circle',
                        size: 19,
                        color: T.colorSubtleInk,
                      )
                    : const CircularProgressIndicator(
                        strokeWidth: 2,
                        color: T.colorSubtleInk,
                      ),
              ),
              const SizedBox(width: T.space2),
            ],
            Flexible(child: Text(label, textAlign: TextAlign.center)),
          ],
        ),
      ),
    ),
  );
}

class QjSurface extends StatelessWidget {
  const QjSurface({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(T.space4),
  });
  final Widget child;
  final EdgeInsets padding;
  @override
  Widget build(BuildContext context) => Container(
    padding: padding,
    decoration: BoxDecoration(
      color: T.colorSurface,
      borderRadius: BorderRadius.circular(T.radiusCard),
      border: Border.all(color: T.colorOutline),
      boxShadow: const [T.shadowSoft],
    ),
    child: child,
  );
}

enum QjBadgeTone { dark, amber, success, light }

class QjTypeBadge extends StatelessWidget {
  const QjTypeBadge(this.label, {super.key, this.tone = QjBadgeTone.dark});
  final String label;
  final QjBadgeTone tone;
  @override
  Widget build(BuildContext context) {
    final (background, foreground) = switch (tone) {
      QjBadgeTone.dark => (T.colorNavigation, T.colorInverseInk),
      QjBadgeTone.amber => (T.colorAccentSoft, const Color(0xFF855700)),
      QjBadgeTone.success => (T.colorSuccessSoft, T.colorSuccess),
      QjBadgeTone.light => (
        T.colorSurface.withValues(alpha: .9),
        T.colorInkSoft,
      ),
    };
    return Container(
      constraints: const BoxConstraints(minHeight: 24),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(T.radiusPill),
      ),
      child: Text(
        label,
        style: QjTheme.type(
          T.fontSizeMicro,
          FontWeight.w700,
          T.lineHeightCaption,
          foreground,
        ),
      ),
    );
  }
}

class QjCategoryTile extends StatelessWidget {
  const QjCategoryTile({
    super.key,
    required this.label,
    required this.icon,
    required this.background,
    required this.foreground,
    required this.onPressed,
    this.selected = false,
  });
  final String label, icon;
  final Color background, foreground;
  final VoidCallback onPressed;
  final bool selected;
  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    selected: selected,
    label: label,
    child: ExcludeSemantics(
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(19),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
          child: Column(
            children: [
              Container(
                width: T.sizeCategoryIcon,
                height: T.sizeCategoryIcon,
                decoration: BoxDecoration(
                  color: background,
                  borderRadius: BorderRadius.circular(19),
                  border: Border.all(
                    color: selected ? T.colorAccent : Colors.transparent,
                  ),
                  boxShadow: selected ? const [T.shadowSoft] : null,
                ),
                child: Center(child: QjIcon(icon, size: 23, color: foreground)),
              ),
              const SizedBox(height: T.space2),
              Text(
                label,
                style: QjTheme.type(
                  T.fontSizeCaption,
                  selected ? FontWeight.w700 : FontWeight.w500,
                  T.lineHeightCaption,
                  selected ? T.colorInk : T.colorMutedInk,
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class QjFilterChip extends StatelessWidget {
  const QjFilterChip({
    super.key,
    required this.label,
    required this.selected,
    required this.onPressed,
  });
  final String label;
  final bool selected;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    selected: selected,
    label: label,
    child: ExcludeSemantics(
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(T.radiusPill),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: T.sizeTouchTargetMin),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Container(
              constraints: const BoxConstraints(minHeight: 38),
              alignment: Alignment.center,
              padding: const EdgeInsets.symmetric(horizontal: 17),
              decoration: BoxDecoration(
                color: selected ? T.colorNavigation : T.colorSurface,
                border: Border.all(
                  color: selected ? T.colorNavigation : T.colorOutline,
                ),
                borderRadius: BorderRadius.circular(T.radiusPill),
              ),
              child: Text(
                label,
                style: QjTheme.type(
                  T.fontSizeCaptionLarge,
                  FontWeight.w500,
                  T.lineHeightCaption,
                  selected ? T.colorInverseInk : T.colorMutedInk,
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class QjWallpaperCard extends StatelessWidget {
  const QjWallpaperCard({
    super.key,
    required this.name,
    required this.type,
    required this.image,
    this.onPressed,
  });
  final String name, type, image;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(T.radiusMedia),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(T.radiusMedia),
            boxShadow: const [T.shadowCard],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(T.radiusMedia),
            child: AspectRatio(
              aspectRatio: 3 / 4.15,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ColoredBox(
                    color: T.colorSurfaceStrong,
                    child: SvgPicture.asset(
                      'assets/ui-reference/$image.svg',
                      fit: BoxFit.cover,
                    ),
                  ),
                  Positioned(
                    left: 10,
                    top: 10,
                    child: QjTypeBadge(type, tone: QjBadgeTone.light),
                  ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: T.space3),
        Text(
          name,
          style: QjTheme.type(
            T.fontSizeCardTitle,
            FontWeight.w700,
            T.lineHeightSection,
          ),
        ),
      ],
    ),
  );
}

enum QjStateKind { empty, error, offline }

class QjStatePanel extends StatelessWidget {
  const QjStatePanel({
    super.key,
    this.kind = QjStateKind.empty,
    required this.onPressed,
    this.title,
    this.description,
    this.actionLabel,
  });
  final QjStateKind kind;
  final VoidCallback onPressed;
  final String? title, description, actionLabel;
  @override
  Widget build(BuildContext context) {
    final (defaultTitle, icon) = switch (kind) {
      QjStateKind.empty => ('还没有壁纸', 'package-open'),
      QjStateKind.error => ('加载失败', 'circle-alert'),
      QjStateKind.offline => ('网络不可用', 'wifi-off'),
    };
    return QjSurface(
      padding: const EdgeInsets.all(T.space6),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 172),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 54,
              height: 54,
              decoration: BoxDecoration(
                color: T.colorSurfaceMuted,
                borderRadius: BorderRadius.circular(18),
              ),
              child: Center(
                child: QjIcon(icon, size: 26, color: T.colorMutedInk),
              ),
            ),
            const SizedBox(height: T.space2),
            Text(
              title ?? defaultTitle,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: T.space2),
            Text(
              description ?? '稍后再来看看',
              textAlign: TextAlign.center,
              style: QjTheme.type(
                13,
                FontWeight.w400,
                T.lineHeightBody,
                T.colorMutedInk,
              ),
            ),
            const SizedBox(height: T.space2),
            OutlinedButton(
              onPressed: onPressed,
              child: Text(
                actionLabel ?? (kind == QjStateKind.empty ? '返回首页' : '重新加载'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class QjNavItem {
  const QjNavItem(this.label, this.icon);
  final String label, icon;
}

class QjBottomNav extends StatelessWidget {
  const QjBottomNav({
    super.key,
    required this.items,
    required this.selectedIndex,
    required this.onSelected,
    this.keyPrefix = 'app-tab',
  });
  final List<QjNavItem> items;
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final String keyPrefix;
  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minHeight: T.sizeBottomNavigation),
    padding: const EdgeInsets.all(7),
    decoration: BoxDecoration(
      color: T.colorNavigation,
      borderRadius: BorderRadius.circular(T.radiusNavigation),
      boxShadow: const [T.shadowFloating],
    ),
    child: Row(
      children: List.generate(items.length, (index) {
        final selected = index == selectedIndex;
        final color = selected ? T.colorInk : T.colorNavigationInactive;
        return Expanded(
          child: Semantics(
            button: true,
            selected: selected,
            label: items[index].label,
            child: ExcludeSemantics(
              child: InkWell(
                key: ValueKey('$keyPrefix-$index'),
                onTap: () => onSelected(index),
                borderRadius: BorderRadius.circular(T.radiusPill),
                child: Container(
                  constraints: const BoxConstraints(minHeight: 52),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: selected ? T.colorAccent : Colors.transparent,
                    borderRadius: BorderRadius.circular(T.radiusPill),
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      QjIcon(items[index].icon, color: color),
                      const SizedBox(width: 7),
                      Flexible(
                        child: Text(
                          items[index].label,
                          textAlign: TextAlign.center,
                          style: QjTheme.type(
                            12,
                            FontWeight.w600,
                            T.lineHeightCaption,
                            color,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      }),
    ),
  );
}

class QjTutorialCard extends StatelessWidget {
  const QjTutorialCard({super.key, required this.onPressed});
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(T.radiusCard),
    child: Container(
      constraints: const BoxConstraints(minHeight: 118),
      padding: const EdgeInsets.all(T.space4),
      decoration: BoxDecoration(
        color: T.colorSurfaceMuted,
        borderRadius: BorderRadius.circular(T.radiusCard),
        boxShadow: const [T.shadowSoft],
        gradient: RadialGradient(
          center: Alignment.topRight,
          radius: .8,
          colors: [T.colorAccent.withValues(alpha: .12), T.colorSurfaceMuted],
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 50,
            height: 50,
            decoration: BoxDecoration(
              color: T.colorNavigation,
              borderRadius: BorderRadius.circular(17),
            ),
            child: const Center(
              child: QjIcon('layers-3', size: 24, color: T.colorInverseInk),
            ),
          ),
          const SizedBox(width: T.space3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '使用指南',
                  style: QjTheme.type(
                    10,
                    FontWeight.w700,
                    T.lineHeightCaption,
                    T.colorAccentStrong,
                  ),
                ),
                const SizedBox(height: 4),
                Text('壁纸设置教程', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  '了解不同手机的预览、下载和设置方式',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          const QjIcon('chevron-right'),
        ],
      ),
    ),
  );
}

class QjSheet extends StatelessWidget {
  const QjSheet({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => SafeArea(
    top: false,
    child: SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(
        T.space5,
        12,
        T.space5,
        T.space6 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 38,
            height: 4,
            margin: const EdgeInsets.only(bottom: T.space5),
            decoration: BoxDecoration(
              color: T.colorOutlineStrong,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          child,
        ],
      ),
    ),
  );
}

class QjBrandHeader extends StatelessWidget {
  const QjBrandHeader({super.key, this.title = '倾境', required this.onService});
  final String title;
  final VoidCallback onService;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: T.sizeTopBar,
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        const Positioned(
          top: -20,
          right: -20,
          width: 280,
          height: 205,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment(.56, -.68),
                  radius: 1.05,
                  colors: [
                    Color(0x61DE9C95),
                    Color(0x8CF8E8E5),
                    Color(0x52FFF3D8),
                    Color(0x00FFF3D8),
                  ],
                  stops: [0, .34, .54, .74],
                ),
              ),
            ),
          ),
        ),
        Positioned.fill(
          child: Row(
            children: [
              Expanded(
                child: Text(
                  title,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              Tooltip(
                message: '微信客服',
                child: TextButton.icon(
                  onPressed: onService,
                  style: TextButton.styleFrom(
                    backgroundColor: T.colorNavigation,
                    foregroundColor: T.colorInverseInk,
                    minimumSize: const Size(0, 44),
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    shape: const StadiumBorder(),
                    textStyle: QjTheme.type(
                      12,
                      FontWeight.w700,
                      T.lineHeightCaption,
                    ),
                  ),
                  icon: const QjIcon(
                    'message-circle-more',
                    size: 21,
                    color: T.colorInverseInk,
                  ),
                  label: const Text('客服'),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class QjPageHeader extends StatelessWidget {
  const QjPageHeader({
    super.key,
    required this.title,
    this.actionLabel,
    this.onAction,
    this.serviceAction = false,
  });
  final String title;
  final String? actionLabel;
  final VoidCallback? onAction;
  final bool serviceAction;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: T.sizeTopBar,
    child: Row(
      children: [
        IconButton(
          tooltip: '返回',
          onPressed: () => Navigator.maybePop(context),
          style: IconButton.styleFrom(
            side: const BorderSide(color: T.colorOutline),
            minimumSize: const Size(44, 44),
          ),
          icon: const QjIcon('chevron-left', size: 22),
        ),
        const SizedBox(width: T.space2),
        Expanded(
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
          ),
        ),
        if (serviceAction)
          IconButton(
            tooltip: '微信客服',
            onPressed: onAction,
            style: IconButton.styleFrom(
              backgroundColor: T.colorNavigation,
              foregroundColor: T.colorInverseInk,
              minimumSize: const Size(44, 44),
            ),
            icon: const QjIcon(
              'headphones',
              size: 20,
              color: T.colorInverseInk,
            ),
          )
        else if (actionLabel != null)
          OutlinedButton(
            style: OutlinedButton.styleFrom(
              foregroundColor: T.colorAccentStrong,
              backgroundColor: T.colorAccentSoft,
              side: const BorderSide(color: T.colorAccent),
              padding: const EdgeInsets.symmetric(horizontal: 12),
              textStyle: QjTheme.type(12, FontWeight.w700, T.lineHeightCaption),
            ),
            onPressed: onAction,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const QjIcon(
                  'circle-play',
                  size: 16,
                  color: T.colorAccentStrong,
                ),
                const SizedBox(width: 5),
                Text(actionLabel!),
              ],
            ),
          ),
      ],
    ),
  );
}

class QjSearchBar extends StatelessWidget {
  const QjSearchBar({
    super.key,
    required this.controller,
    required this.onSearch,
  });
  final TextEditingController controller;
  final VoidCallback onSearch;
  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minHeight: 50),
    decoration: BoxDecoration(
      color: T.colorSurface,
      border: Border.all(color: T.colorOutline),
      borderRadius: BorderRadius.circular(18),
      boxShadow: const [T.shadowSoft],
    ),
    padding: const EdgeInsets.fromLTRB(16, 5, 5, 5),
    child: Row(
      children: [
        const QjIcon('search', size: 19, color: T.colorMutedInk),
        const SizedBox(width: 10),
        Expanded(
          child: TextField(
            controller: controller,
            inputFormatters: [LengthLimitingTextInputFormatter(40)],
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => onSearch(),
            decoration: const InputDecoration(
              hintText: '搜索壁纸名称',
              isCollapsed: true,
              filled: false,
              contentPadding: EdgeInsets.zero,
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              disabledBorder: InputBorder.none,
              errorBorder: InputBorder.none,
              focusedErrorBorder: InputBorder.none,
            ),
          ),
        ),
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: controller,
          builder: (context, value, _) => value.text.isEmpty
              ? const SizedBox.shrink()
              : IconButton(
                  tooltip: '清空搜索',
                  onPressed: controller.clear,
                  constraints: const BoxConstraints.tightFor(
                    width: 38,
                    height: 38,
                  ),
                  padding: EdgeInsets.zero,
                  icon: const QjIcon('x', size: 17, color: T.colorMutedInk),
                ),
        ),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: T.colorAccentStrong,
            foregroundColor: T.colorInverseInk,
            minimumSize: const Size(66, 40),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(T.radiusControl),
            ),
            textStyle: QjTheme.type(
              14,
              FontWeight.w700,
              T.lineHeightSection,
              T.colorInverseInk,
            ),
          ),
          onPressed: onSearch,
          child: const Text('搜索'),
        ),
      ],
    ),
  );
}

class QjCatalogCard extends StatelessWidget {
  const QjCatalogCard({
    super.key,
    required this.repository,
    required this.wallpaper,
    required this.onPressed,
  });
  final CatalogRepository repository;
  final Wallpaper wallpaper;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(T.radiusMedia),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(T.radiusMedia),
              boxShadow: const [T.shadowCard],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(T.radiusMedia),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  CatalogImage(repository: repository, path: wallpaper.cover),
                  Positioned(
                    left: 10,
                    top: 10,
                    child: Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        QjTypeBadge(
                          wallpaper.kindLabel,
                          tone: QjBadgeTone.light,
                        ),
                        if (wallpaper.isFree)
                          const QjTypeBadge('免费', tone: QjBadgeTone.amber),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: T.space3),
        Text(
          wallpaper.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.titleMedium,
        ),
      ],
    ),
  );
}

class QjOwnedRow extends StatelessWidget {
  const QjOwnedRow({
    super.key,
    required this.repository,
    required this.wallpaper,
    required this.onPressed,
  });
  final CatalogRepository repository;
  final Wallpaper wallpaper;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(T.radiusCard),
    child: Container(
      constraints: const BoxConstraints(minHeight: 118),
      padding: const EdgeInsets.all(T.space3),
      decoration: BoxDecoration(
        color: T.colorSurface,
        border: Border.all(color: T.colorOutline),
        borderRadius: BorderRadius.circular(T.radiusCard),
      ),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: SizedBox(
              width: 76,
              height: 96,
              child: CatalogImage(
                repository: repository,
                path: wallpaper.cover,
              ),
            ),
          ),
          const SizedBox(width: T.space3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                QjTypeBadge(wallpaper.kindLabel, tone: QjBadgeTone.light),
                const SizedBox(height: 5),
                Text(
                  wallpaper.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ],
            ),
          ),
          const QjIcon('chevron-right'),
        ],
      ),
    ),
  );
}

class QjSettingTutorialCard extends StatelessWidget {
  const QjSettingTutorialCard({super.key, required this.onPressed});
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(T.radiusCard),
    child: Container(
      constraints: const BoxConstraints(minHeight: 78),
      padding: const EdgeInsets.symmetric(
        horizontal: T.space4,
        vertical: T.space3,
      ),
      decoration: BoxDecoration(
        color: T.colorSurface,
        border: Border.all(color: T.colorOutline),
        borderRadius: BorderRadius.circular(T.radiusCard),
        boxShadow: const [T.shadowSoft],
      ),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: T.colorAccentSoft,
              borderRadius: BorderRadius.circular(15),
            ),
            child: const Center(
              child: QjIcon(
                'circle-play',
                size: 23,
                color: T.colorAccentStrong,
              ),
            ),
          ),
          const SizedBox(width: T.space3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text('观看设置教程', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  '了解如何设置桌面和锁屏壁纸',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          const QjIcon('chevron-right'),
        ],
      ),
    ),
  );
}

class QjCustomerServiceCard extends StatelessWidget {
  const QjCustomerServiceCard({
    super.key,
    required this.onCopy,
    required this.onPreview,
    required this.onSave,
  });
  final VoidCallback onCopy, onPreview, onSave;
  @override
  Widget build(BuildContext context) => QjSurface(
    padding: const EdgeInsets.all(T.space5),
    child: Column(
      children: [
        Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: T.colorAccentSoft,
                borderRadius: BorderRadius.circular(15),
              ),
              child: const Center(
                child: QjIcon(
                  'message-circle-more',
                  size: 22,
                  color: T.colorAccentStrong,
                ),
              ),
            ),
            const SizedBox(width: T.space3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('微信客服', style: Theme.of(context).textTheme.titleMedium),
                  Text(
                    '添加客服获取兑换码或处理设备恢复',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: T.space5),
        InkWell(
          onTap: onPreview,
          borderRadius: BorderRadius.circular(T.radiusMedia),
          child: Container(
            width: 164,
            constraints: const BoxConstraints(minHeight: 184),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: T.colorSurfaceMuted,
              border: Border.all(color: T.colorOutline),
              borderRadius: BorderRadius.circular(T.radiusMedia),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Image.asset(
                  'assets/ui-reference/customer-service-qr.png',
                  width: 132,
                  height: 132,
                ),
                const SizedBox(height: 8),
                Text(
                  '点击查看二维码',
                  style: Theme.of(
                    context,
                  ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: T.space2),
        OutlinedButton.icon(
          onPressed: onSave,
          icon: const QjIcon('download', size: 16),
          label: const Text('保存二维码'),
        ),
        const SizedBox(height: T.space5),
        Container(
          constraints: const BoxConstraints(minHeight: 58),
          padding: const EdgeInsets.symmetric(horizontal: T.space4),
          decoration: BoxDecoration(
            color: T.colorSurfaceMuted,
            borderRadius: BorderRadius.circular(T.radiusControl),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      '微信号',
                      style: Theme.of(
                        context,
                      ).textTheme.labelSmall?.copyWith(color: T.colorMutedInk),
                    ),
                    Text(
                      'qingjing_service',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
              FilledButton(
                style: FilledButton.styleFrom(
                  minimumSize: const Size(0, 38),
                  padding: const EdgeInsets.symmetric(horizontal: 13),
                ),
                onPressed: onCopy,
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    QjIcon('copy', size: 15, color: Colors.white),
                    SizedBox(width: 5),
                    Text('复制'),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: T.space3),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const QjIcon('clock-3', size: 16, color: T.colorMutedInk),
            const SizedBox(width: 6),
            Text(
              '服务时间 09:00–21:00',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ],
    ),
  );
}
