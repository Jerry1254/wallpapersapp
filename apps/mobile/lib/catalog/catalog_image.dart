import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'catalog.dart';

class CatalogImage extends StatefulWidget {
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
  State<CatalogImage> createState() => _CatalogImageState();
}

class _CatalogImageState extends State<CatalogImage> {
  Future<Map<String, String>>? _headers;

  @override
  void initState() {
    super.initState();
    _prepareHeaders();
  }

  @override
  void didUpdateWidget(CatalogImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository ||
        oldWidget.path != widget.path ||
        oldWidget.previewGenerationStatus != widget.previewGenerationStatus) {
      _prepareHeaders();
    }
  }

  void _prepareHeaders() {
    _headers = null;
    if (widget.previewGenerationStatus != 'READY') return;
    final repository = widget.repository;
    if (repository is HttpCatalogRepository && repository.authenticatedMedia) {
      // Future.sync also keeps invalid URLs and failed sessions in the
      // placeholder path, instead of retrying with anonymous headers.
      _headers = Future.sync(
        () => repository.mediaHeaders(repository.media(widget.path)),
      );
    }
  }

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
    if (widget.previewGenerationStatus != 'READY') {
      return Container(
        color: QingjingWallpaperTokens.colorSurfaceStrong,
        alignment: Alignment.center,
        padding: const EdgeInsets.all(12),
        child: Text(
          widget.previewGenerationStatus == 'FAILED'
              ? '预览资源生成失败'
              : '预览资源生成中，请稍后',
          textAlign: TextAlign.center,
          style: const TextStyle(color: QingjingWallpaperTokens.colorMutedInk),
        ),
      );
    }
    try {
      Widget image(Map<String, String> headers) => Image.network(
        widget.repository.media(widget.path).toString(),
        fit: widget.fit,
        headers: headers,
        errorBuilder: (_, error, stack) => fallback,
        loadingBuilder: (_, child, progress) =>
            progress == null ? child : fallback,
      );
      final headers = _headers;
      if (headers == null) return image(const {});
      return FutureBuilder<Map<String, String>>(
        future: headers,
        builder: (_, snapshot) =>
            snapshot.hasData ? image(snapshot.data!) : fallback,
      );
    } catch (_) {
      return fallback;
    }
  }
}
