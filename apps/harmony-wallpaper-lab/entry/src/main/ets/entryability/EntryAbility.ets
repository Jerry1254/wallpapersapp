import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index', (error) => {
      if (error.code) { hilog.error(0x514C, 'WallpaperLab', '页面打开失败：%{public}s', error.message); }
    });
  }

  onForeground(): void {
    AppStorage.setOrCreate<boolean>('labForeground', true);
  }

  onBackground(): void {
    AppStorage.setOrCreate<boolean>('labForeground', false);
  }
}
