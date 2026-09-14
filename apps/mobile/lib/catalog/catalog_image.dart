import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'catalog.dart';

class CatalogImage extends StatelessWidget {
  const CatalogImage({super.key, required this.repository, required this.path});
  final CatalogRepository repository;
  final String path;
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
    try {
      return Image.network(
        repository.media(path).toString(),
        fit: BoxFit.cover,
        errorBuilder: (_, error, stack) => fallback,
        loadingBuilder: (_, child, progress) =>
            progress == null ? child : fallback,
      );
    } catch (_) {
      return fallback;
    }
  }
}
