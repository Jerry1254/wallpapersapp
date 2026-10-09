import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

const DOMAIN: number = 0x514A;

export default class EntryAbility extends UIAbility {
  onForeground(): void {
    AppStorage.setOrCreate<boolean>('qingjing_app_active', true);
    const revision: number = AppStorage.get<number>('qingjing_foreground_revision') ?? 0;
    AppStorage.setOrCreate<number>('qingjing_foreground_revision', revision + 1);
  }
  onBackground(): void {
    AppStorage.setOrCreate<boolean>('qingjing_app_active', false);
  }

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT);
    } catch (error) {
      hilog.warn(DOMAIN, 'Qingjing', 'Unable to force light appearance: %{public}s', JSON.stringify(error));
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        hilog.error(DOMAIN, 'Qingjing', 'Unable to load main page: %{public}s', JSON.stringify(error));
      }
    });
  }
}
