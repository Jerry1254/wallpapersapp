import { QjTokens as T } from '../design/Tokens';
import {
  HarmonyCatalogProjection,
  WallpaperCapability,
  WallpaperCategory,
  WallpaperSummary
} from '../model/CatalogModels';

@Entry
@Component
struct Index {
  @State activeTab: number = 0;
  @State selectedFilter: string = HarmonyCatalogProjection.FILTER_FEATURED;
  @State selectedDetail: WallpaperSummary | null = null;
  @State selectedCapability: string = '鸿蒙动态壁纸';
  @State statusMessage: string = '';

  private categories: WallpaperCategory[] = [
    new WallpaperCategory('1', '国风'),
    new WallpaperCategory('2', '风景'),
    new WallpaperCategory('3', '人物'),
    new WallpaperCategory('4', '萌宠')
  ];

  private rawWallpapers: WallpaperSummary[] = [
    new WallpaperSummary('preview-1', '鎏金国风', '', false, [
      new WallpaperCapability('HARMONYOS', 'MOVING_PHOTO', '鸿蒙动态壁纸'),
      new WallpaperCapability('UNIVERSAL', 'STATIC_IMAGE', '静态壁纸'),
      new WallpaperCapability('ANDROID', 'VIDEO', '安卓动态壁纸'),
      new WallpaperCapability('IOS', 'LIVE_PHOTO', '苹果动态壁纸')
    ]),
    new WallpaperSummary('preview-2', '云海日出', '', true, [
      new WallpaperCapability('HARMONYOS', 'MOVING_PHOTO', '鸿蒙动态壁纸')
    ]),
    new WallpaperSummary('preview-3', '山水之间', '', false, [
      new WallpaperCapability('UNIVERSAL', 'STATIC_IMAGE', '静态壁纸')
    ]),
    new WallpaperSummary('preview-4', '星河漫游', '', false, [
      new WallpaperCapability('HARMONYOS', 'MOVING_PHOTO', '鸿蒙动态壁纸')
    ])
  ];

  private harmonyWallpapers(): WallpaperSummary[] {
    return HarmonyCatalogProjection.projectCatalog(this.rawWallpapers);
  }

  private visibleWallpapers(): WallpaperSummary[] {
    return HarmonyCatalogProjection.filterCatalog(this.harmonyWallpapers(), this.selectedFilter);
  }

  private selectedCapabilityFor(item: WallpaperSummary): WallpaperCapability {
    const matched: WallpaperCapability | undefined = item.capabilities.find((capability: WallpaperCapability) =>
      capability.label === this.selectedCapability);
    return matched === undefined ? item.capabilities[0] : matched;
  }

  private deliveryHint(item: WallpaperSummary): string {
    const capability: WallpaperCapability = this.selectedCapabilityFor(item);
    if (capability.deliveryPlatform === 'HARMONYOS' && capability.resourceType === 'MOVING_PHOTO') {
      return '下载完成后将动态照片保存到图库，再从图库设为锁屏壁纸';
    }
    return '下载高清静态原图并保存到图库，再从图库设置壁纸';
  }

  @Builder
  private BrandHeader(title: string = '倾境动态壁纸') {
    Row() {
      Column() {
        Text('倾境')
          .fontSize(12)
          .fontWeight(FontWeight.Bold)
          .fontColor(T.inverseInk);
      }
      .width(48)
      .height(48)
      .justifyContent(FlexAlign.Center)
      .backgroundColor(T.navigation)
      .borderRadius(16);

      Text(title)
        .fontSize(28)
        .fontWeight(FontWeight.Bolder)
        .fontColor(T.ink)
        .margin({ left: 12 })
        .layoutWeight(1);

      Button('客服')
        .height(42)
        .fontSize(14)
        .fontColor(T.ink)
        .backgroundColor(T.surface)
        .border({ width: 1, color: T.outline })
        .borderRadius(21)
        .onClick(() => {
          this.statusMessage = '客服入口将在正式接口接入后启用';
        });
    }
    .width('100%')
    .height(64);
  }

  @Builder
  private SearchBar() {
    Row() {
      TextInput({ placeholder: '搜索壁纸名称' })
        .fontSize(15)
        .height(46)
        .padding({ left: 16, right: 12 })
        .backgroundColor(T.surfaceMuted)
        .borderRadius(T.controlRadius)
        .layoutWeight(1);
      Button('搜索')
        .height(46)
        .fontSize(14)
        .fontWeight(FontWeight.Bold)
        .fontColor(T.inverseInk)
        .backgroundColor(T.accentStrong)
        .borderRadius(T.controlRadius)
        .margin({ left: 8 });
    }
    .width('100%');
  }

  @Builder
  private FilterChip(label: string) {
    Button(label)
      .height(38)
      .padding({ left: 16, right: 16 })
      .fontSize(13)
      .fontWeight(FontWeight.Bold)
      .fontColor(this.selectedFilter === label ? T.inverseInk : T.ink)
      .backgroundColor(this.selectedFilter === label ? T.navigation : T.surface)
      .border({ width: 1, color: this.selectedFilter === label ? T.navigation : T.outline })
      .borderRadius(19)
      .margin({ right: 8 })
      .onClick(() => {
        this.selectedFilter = label;
      });
  }

  @Builder
  private CapabilityBadge(label: string, accent: boolean = false) {
    Text(label)
      .fontSize(10)
      .fontWeight(FontWeight.Bold)
      .fontColor(accent ? T.ink : T.inverseInk)
      .backgroundColor(accent ? T.accent : '#99191817')
      .padding({ left: 8, right: 8, top: 5, bottom: 5 })
      .borderRadius(12);
  }

  @Builder
  private PlaceholderArtwork(index: number) {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .linearGradient({
          angle: index % 2 === 0 ? 145 : 215,
          colors: index % 2 === 0
            ? [['#171717', 0.0], ['#6D4A21', 0.56], ['#F4A91E', 1.0]]
            : [['#0E2747', 0.0], ['#376BA0', 0.55], ['#D9EFFB', 1.0]]
        });
      Text(index % 2 === 0 ? '倾 境' : 'QINGJING')
        .fontSize(index % 2 === 0 ? 28 : 18)
        .fontWeight(FontWeight.Bolder)
        .fontColor('#E8FFFFFF');
    }
    .width('100%')
    .height('100%');
  }

  @Builder
  private WallpaperCard(item: WallpaperSummary, index: number) {
    Column() {
      Stack({ alignContent: Alignment.TopStart }) {
        if (item.coverUrl.length > 0) {
          Image(item.coverUrl)
            .width('100%')
            .height(236)
            .objectFit(ImageFit.Cover);
        } else {
          this.PlaceholderArtwork(index);
        }
        Row({ space: 5 }) {
          ForEach(item.capabilities, (capability: WallpaperCapability) => {
            this.CapabilityBadge(capability.label);
          }, (capability: WallpaperCapability) => capability.deliveryPlatform + capability.resourceType);
          if (item.free) {
            this.CapabilityBadge('免费', true);
          }
        }
        .padding(10);
      }
      .width('100%')
      .height(236)
      .borderRadius(T.mediaRadius)
      .clip(true);

      Text(item.title)
        .width('100%')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor(T.ink)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 10 });
    }
    .width('100%')
    .onClick(() => {
      this.selectedDetail = item;
      this.selectedCapability = item.capabilities[0].label;
      this.statusMessage = '';
    });
  }

  @Builder
  private HomePage() {
    Scroll() {
      Column() {
        this.BrandHeader();
        this.SearchBar();

        Text('壁纸分类')
          .width('100%')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(T.ink)
          .margin({ top: 28, bottom: 12 });

        Row({ space: 18 }) {
          ForEach(this.categories, (category: WallpaperCategory, index: number) => {
            Column({ space: 8 }) {
              Text(category.name.substring(0, 1))
                .width(56)
                .height(56)
                .fontSize(22)
                .fontWeight(FontWeight.Bold)
                .fontColor(T.ink)
                .textAlign(TextAlign.Center)
                .backgroundColor(index % 2 === 0 ? T.accentSoft : '#E8F1FB')
                .borderRadius(18);
              Text(category.name)
                .fontSize(13)
                .fontColor(T.mutedInk);
            }
          }, (category: WallpaperCategory) => category.id);
        }
        .width('100%');

        Text(this.selectedFilter === HarmonyCatalogProjection.FILTER_FEATURED ? '精选壁纸' : this.selectedFilter)
          .width('100%')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(T.ink)
          .margin({ top: 28, bottom: 12 });

        Scroll() {
          Row() {
            this.FilterChip(HarmonyCatalogProjection.FILTER_FEATURED);
            this.FilterChip(HarmonyCatalogProjection.FILTER_FREE);
            this.FilterChip(HarmonyCatalogProjection.FILTER_MOVING_PHOTO);
            this.FilterChip(HarmonyCatalogProjection.FILTER_STATIC);
          }
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%');

        GridRow({ columns: 2, gutter: 12 }) {
          ForEach(this.visibleWallpapers(), (item: WallpaperSummary, index: number) => {
            GridCol({ span: 1 }) {
              this.WallpaperCard(item, index);
            }
            .margin({ top: 16 });
          }, (item: WallpaperSummary) => item.id);
        }
        .width('100%')
        .padding({ bottom: 110 });
      }
      .width('100%')
      .padding({ left: T.gutter, right: T.gutter });
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off);
  }

  @Builder
  private MinePage() {
    Scroll() {
      Column() {
        this.BrandHeader('我的');

        Row() {
          Column() {
            Text('壁纸设置教程')
              .width('100%')
              .fontSize(17)
              .fontWeight(FontWeight.Bold)
              .fontColor(T.ink);
            Text('查看鸿蒙动态照片和静态壁纸的设置方法')
              .width('100%')
              .fontSize(13)
              .fontColor(T.mutedInk)
              .margin({ top: 5 });
          }
          .layoutWeight(1);
          Text('›')
            .fontSize(30)
            .fontColor(T.mutedInk);
        }
        .width('100%')
        .padding(18)
        .backgroundColor(T.surface)
        .border({ width: 1, color: T.outline })
        .borderRadius(T.cardRadius)
        .margin({ top: 20 });

        Text('已获得壁纸')
          .width('100%')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(T.ink)
          .margin({ top: 28 });
        Text('兑换或免费获取的壁纸会显示在这里')
          .width('100%')
          .fontSize(13)
          .fontColor(T.mutedInk)
          .margin({ top: 5 });

        Column({ space: 10 }) {
          Text('还没有可用壁纸')
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(T.ink);
          Text('去首页选择喜欢的壁纸吧')
            .fontSize(14)
            .fontColor(T.mutedInk);
          Button('去首页')
            .height(48)
            .fontWeight(FontWeight.Bold)
            .fontColor(T.inverseInk)
            .backgroundColor(T.navigation)
            .borderRadius(24)
            .margin({ top: 8 })
            .onClick(() => {
              this.activeTab = 0;
            });
        }
        .width('100%')
        .padding({ top: 70, bottom: 70 })
        .margin({ top: 18 })
        .backgroundColor(T.surfaceMuted)
        .borderRadius(T.cardRadius);
      }
      .width('100%')
      .padding({ left: T.gutter, right: T.gutter, bottom: 110 });
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off);
  }

  @Builder
  private BottomNavigation() {
    Row() {
      Button('首页')
        .layoutWeight(1)
        .height(52)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.activeTab === 0 ? T.inverseInk : T.ink)
        .backgroundColor(this.activeTab === 0 ? T.navigation : Color.Transparent)
        .borderRadius(26)
        .onClick(() => {
          this.activeTab = 0;
        });
      Button('我的')
        .layoutWeight(1)
        .height(52)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.activeTab === 1 ? T.inverseInk : T.ink)
        .backgroundColor(this.activeTab === 1 ? T.navigation : Color.Transparent)
        .borderRadius(26)
        .onClick(() => {
          this.activeTab = 1;
        });
    }
    .width('100%')
    .height(66)
    .padding(7)
    .backgroundColor('#F5FFFFFF')
    .border({ width: 1, color: T.outline })
    .borderRadius(33)
    .shadow({ radius: 30, color: '#2E191817', offsetY: 12 });
  }

  @Builder
  private DetailPage(item: WallpaperSummary) {
    Column() {
      Row() {
        Button('‹')
          .width(48)
          .height(48)
          .fontSize(32)
          .fontColor(T.ink)
          .backgroundColor(T.surface)
          .border({ width: 1, color: T.outline })
          .borderRadius(24)
          .onClick(() => {
            this.selectedDetail = null;
            this.statusMessage = '';
          });
        Text(item.title)
          .fontSize(28)
          .fontWeight(FontWeight.Bolder)
          .fontColor(T.ink)
          .layoutWeight(1)
          .margin({ left: 12 });
        Button('观看设置教程')
          .height(44)
          .fontSize(13)
          .fontColor(T.accentStrong)
          .backgroundColor(T.accentSoft)
          .borderRadius(22);
      }
      .width('100%')
      .height(64);

      if (item.capabilities.length > 1) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(item.capabilities, (capability: WallpaperCapability) => {
              Button(capability.label)
                .height(38)
                .fontSize(13)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.selectedCapability === capability.label ? T.inverseInk : T.ink)
                .backgroundColor(this.selectedCapability === capability.label ? T.navigation : T.surface)
                .border({ width: 1, color: T.outline })
                .borderRadius(19)
                .onClick(() => {
                  this.selectedCapability = capability.label;
                });
            }, (capability: WallpaperCapability) => capability.deliveryPlatform + capability.resourceType);
          }
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ top: 12, bottom: 12 });
      }

      Text(this.deliveryHint(item))
        .width('100%')
        .fontSize(13)
        .fontColor(T.mutedInk)
        .margin({ bottom: 12 });

      Stack({ alignContent: Alignment.Bottom }) {
        this.PlaceholderArtwork(0);
        Column() {
          if (this.statusMessage.length > 0) {
            Text(this.statusMessage)
              .fontSize(13)
              .fontColor(T.inverseInk)
              .backgroundColor('#AA191817')
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .borderRadius(14)
              .margin({ bottom: 10 });
          }
          Button(item.free ? '下载壁纸' : '兑换并下载')
            .width('100%')
            .height(T.primaryHeight)
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(T.ink)
            .backgroundColor(T.accent)
            .borderRadius(27)
            .onClick(() => {
              this.statusMessage = this.deliveryHint(item);
            });
        }
        .width('100%')
        .padding(16);
      }
      .width('100%')
      .layoutWeight(1)
      .borderRadius(T.cardRadius)
      .clip(true)
      .margin({ bottom: 20 });
    }
    .width('100%')
    .height('100%')
    .padding({ left: T.gutter, right: T.gutter });
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      if (this.selectedDetail !== null) {
        this.DetailPage(this.selectedDetail as WallpaperSummary);
      } else {
        if (this.activeTab === 0) {
          this.HomePage();
        } else {
          this.MinePage();
        }
        Row() {
          this.BottomNavigation();
        }
        .width('100%')
        .padding({ left: T.gutter, right: T.gutter, bottom: 12 });
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(T.surface)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM]);
  }
}
