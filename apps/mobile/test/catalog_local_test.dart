import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';

void main() {
  const local = String.fromEnvironment('LOCAL_API');
  test(
    '真实本地 API 分类、中文搜索、分页、详情及缺失作品',
    () async {
      final repo = HttpCatalogRepository(Uri.parse(local));
      final categories = await repo.categories();
      expect(categories, isNotEmpty);
      final first = await repo.list({'page': '1', 'pageSize': '1'});
      expect(first.page, 1);
      expect(first.items, isNotEmpty);
      final detail = await repo.detail(first.items.single.id);
      expect(detail.id, first.items.single.id);
      final search = await repo.list({'q': detail.title});
      expect(search.items.map((item) => item.id), contains(detail.id));
      final categorized = await repo.list({
        'rootCategoryId': categories.first.id,
      });
      expect(categorized.page, 1);
      if (first.totalPages > 1) {
        final second = await repo.list({'page': '2', 'pageSize': '1'});
        expect(second.page, 2);
        expect(second.items.single.id, isNot(detail.id));
      }
      await expectLater(
        repo.detail('9223372036854775807'),
        throwsA(isA<ApiFailure>().having((e) => e.status, 'status', 404)),
      );
    },
    skip: local.isEmpty ? '仅显式 LOCAL_API 时读取本地联调环境' : false,
  );
}
