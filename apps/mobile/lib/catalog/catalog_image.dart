import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'catalog.dart';

class CatalogImage extends StatelessWidget {
  const CatalogImage({
    super.key,
    required this.repository,
    required this.path,
    this.fit = BoxFit.cover,
    this.previewGenerationStatus = 'READY',
  });
  final CatalogRepository repository;
  final String path;
  final BoxFit fit;
  final String previewGenerationStatus;
  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      color: QingjingWallpaperTokens.colorSurfaceStrong,
      alignment: Alignment.center,
      child: const Icon(
        Icons.image_outlined,
        color: QingjingWallpaperTokens.colorMutedInk,
      ),
    );
    if (previewGenerationStatus != 'READY') {
      return Container(
        color: QingjingWallpaperTokens.colorSurfaceStrong,
        alignment: Alignment.center,
        padding: const EdgeInsets.all(12),
        child: Text(
          previewGenerationStatus == 'FAILED' ? '预览资源生成失败' : '预览资源生成中，请稍后',
          textAlign: TextAlign.center,
          style: const TextStyle(color: QingjingWallpaperTokens.colorMutedInk),
        ),
      );
    }
    try {
      return Image.network(
        repository.media(path).toString(),
        fit: fit,
        errorBuilder: (_, error, stack) => fallback,
        loadingBuilder: (_, child, progress) =>
            progress == null ? child : fallback,
      );
    } catch (_) {
      return fallback;
    }
  }
}
