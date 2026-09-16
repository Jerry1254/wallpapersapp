import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';

Wallpaper sample(String id) => Wallpaper.fromJson({
  'id': id,
  'title': '景深 $id',
  'kind': 'PARALLAX_4D',
  'cover': {'contentUrl': '/api/v1/public/assets/1/content'},
  'capabilities': [],
});

class FakeCatalog implements CatalogRepository {
  final pending = <Completer<WallpaperPage>>[];
  final queries = <Map<String, String>>[];
  @override
  Future<WallpaperPage> list(Map<String, String> query) {
    queries.add(query);
    final completion = Completer<WallpaperPage>();
    pending.add(completion);
    return completion.future;
  }

  @override
  Future<List<Category>> categories() async => [];
  @override
  Future<Wallpaper> detail(String id) async => sample(id);
  @override
  Future<List<WallpaperTutorial>> tutorials() async => [];
  @override
  Uri media(String path) => Uri.parse('https://example.invalid$path');
}

void main() {
  test('旧请求晚返回不覆盖中文搜索结果', () async {
    final repo = FakeCatalog();
    final state = CatalogController(repo);
    final first = state.load({'view': 'FEATURED'});
    final next = state.load({'q': '山水'});
    repo.pending[1].complete(WallpaperPage([sample('2')], 1, 1));
    await next;
    repo.pending[0].complete(WallpaperPage([sample('1')], 1, 1));
    await first;
    expect(state.items.single.id, '2');
    expect(repo.queries[1]['q'], '山水');
    expect(repo.queries[1]['platform'], 'ANDROID');
    state.dispose();
  });
  test('翻页失败与刷新断网保留已有列表，重试从原页继续且去重', () async {
    final repo = FakeCatalog();
    final state = CatalogController(repo);
    final first = state.load({});
    repo.pending[0].complete(WallpaperPage([sample('1')], 1, 2));
    await first;
    final failed = state.more();
    repo.pending[1].completeError(const ApiFailure(0, 'NETWORK'));
    await failed;
    expect(state.items.single.id, '1');
    expect(state.hasMore, true);
    expect(state.error, isNotNull);
    final retry = state.more();
    expect(repo.queries[2]['page'], '2');
    repo.pending[2].complete(WallpaperPage([sample('1'), sample('2')], 2, 2));
    await retry;
    expect(state.items.map((x) => x.id), ['1', '2']);
    expect(state.hasMore, false);
    final refresh = state.load({});
    repo.pending[3].completeError(const ApiFailure(0, 'NETWORK'));
    await refresh;
    expect(state.items.length, 2);
    state.dispose();
  });
  test('刷新失败的重试仍读取第一页，即使旧列表有下一页', () async {
    final repo = FakeCatalog();
    final state = CatalogController(repo);
    final first = state.load({});
    repo.pending[0].complete(WallpaperPage([sample('1')], 1, 2));
    await first;
    final refresh = state.load({});
    repo.pending[1].completeError(const ApiFailure(0, 'NETWORK'));
    await refresh;
    final retry = state.retry();
    expect(repo.queries[2]['page'], '1');
    repo.pending[2].complete(WallpaperPage([sample('2')], 1, 2));
    await retry;
    expect(state.items.single.id, '2');
    state.dispose();
  });
  test('销毁后晚响应不通知，未知类型不会伪装为静态', () async {
    final repo = FakeCatalog();
    final state = CatalogController(repo);
    final request = state.load({});
    state.dispose();
    repo.pending[0].complete(WallpaperPage([], 1, 0));
    await request;
    final item = Wallpaper.fromJson({
      'id': '1',
      'title': '未来格式',
      'kind': 'FUTURE',
      'cover': {'contentUrl': '/x'},
      'capabilities': [],
    });
    expect(item.kindLabel, '未知类型');
  });
  test('媒体路径从服务器 origin 解析，拒绝降级和带凭据 URL', () {
    final repo = HttpCatalogRepository(Uri.parse('https://local.test/api/v1'));
    expect(
      repo.media('/api/v1/public/assets/1/content').toString(),
      'https://local.test/api/v1/public/assets/1/content',
    );
    expect(() => repo.media('http://local.test/x'), throwsFormatException);
    expect(
      () => repo.media('https://user:pass@local.test/x'),
      throwsFormatException,
    );
  });
  test('教程按平台和壁纸类型精确匹配', () {
    final tutorials = [
      WallpaperTutorial.fromJson({
        'key': 'ANDROID_PARALLAX_4D',
        'title': '4D动态壁纸教程',
        'platform': 'ANDROID',
        'wallpaperKind': 'PARALLAX_4D',
        'video': {
          'contentUrl': '/tutorials/4d.mp4',
          'mimeType': 'video/mp4',
          'durationMs': 12000,
        },
        'sortOrder': 10,
      }),
      WallpaperTutorial.fromJson({
        'key': 'STATIC',
        'title': '静态壁纸教程',
        'platform': 'UNIVERSAL',
        'wallpaperKind': 'STATIC',
        'video': {
          'contentUrl': '/tutorials/static.mp4',
          'mimeType': 'video/mp4',
          'durationMs': 8000,
        },
        'sortOrder': 30,
      }),
    ];
    expect(
      tutorialFor(tutorials, 'ANDROID', 'PARALLAX_4D')?.key,
      'ANDROID_PARALLAX_4D',
    );
    expect(tutorialFor(tutorials, 'ANDROID', 'STATIC')?.key, 'STATIC');
    expect(tutorialFor(tutorials, 'IOS', 'PARALLAX_4D'), isNull);
  });
}
