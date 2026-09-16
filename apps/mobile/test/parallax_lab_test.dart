import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/lab/parallax_lab.dart';

const source =
    '''{"formatVersion":2,"canvas":{"width":1080,"height":2400},"motion":{"maxAngleX":75,"maxAngleY":75},"layers":[{"index":1,"offsetXPercent":300,"offsetYPercent":50,"initialOffsetXPercent":-20,"initialOffsetYPercent":10,"direction":"follow","scale":1.18,"opacity":0.7,"blendMode":"screen"},{"index":2,"offsetXPercent":20,"offsetYPercent":10,"initialOffsetXPercent":0,"initialOffsetYPercent":0,"direction":"reverse","scale":1.1,"opacity":1,"blendMode":"normal"}]}''';

void main() {
  test('Lab 配置保留开放位移、独立轴和初始位置', () {
    final config = ParallaxLabConfig.decode(source);
    expect(config.motion['maxAngleX'], 75);
    expect(config.layers.first['offsetXPercent'], 300);
    expect(config.layers.first['offsetYPercent'], 50);
    expect(config.layers.first['initialOffsetXPercent'], -20);
    expect(ParallaxLabConfig.decode(config.encoded).encoded, config.encoded);
  });

  test('Lab 草稿复制后不会修改保存基线', () {
    final baseline = ParallaxLabConfig.decode(source);
    final draft = baseline.copy();
    draft.layers.first['offsetXPercent'] = 999;
    expect(baseline.layers.first['offsetXPercent'], 300);
    expect(draft.layers.first['offsetXPercent'], 999);
  });

  test('Lab 拒绝旧格式和越界角度', () {
    expect(
      () => ParallaxLabConfig.decode(
        source.replaceFirst('"formatVersion":2', '"formatVersion":1'),
      ),
      throwsFormatException,
    );
    expect(
      () => ParallaxLabConfig.decode(
        source.replaceFirst('"maxAngleX":75', '"maxAngleX":76'),
      ),
      throwsFormatException,
    );
  });
}
