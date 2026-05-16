import { WebPlugin } from '@capacitor/core';

import type {
  EnableHighRefreshRateOptions,
  HighRefreshRatePlugin,
  RefreshRateInfo,
  SetAdaptiveModeOptions,
  SetTargetFpsOptions,
} from './definitions';

/**
 * Web fallback. Browsers don't expose a way to force a high refresh rate, so
 * we just probe what the display is running at via `requestAnimationFrame`
 * timestamps and return that as `currentHz`. `unlockApplied` is always
 * `false` because we can't change the browser's frame rate from JS.
 */
export class HighRefreshRateWeb extends WebPlugin implements HighRefreshRatePlugin {
  async enable(_options?: EnableHighRefreshRateOptions): Promise<RefreshRateInfo> {
    return this.measure();
  }

  async getInfo(): Promise<RefreshRateInfo> {
    return this.measure();
  }

  async disable(): Promise<RefreshRateInfo> {
    return this.measure();
  }

  async setTargetFps(_options: SetTargetFpsOptions): Promise<RefreshRateInfo> {
    /* Web no permite cambiar refresh rate desde JS — devolver lo medido. */
    return this.measure();
  }

  async setAdaptiveMode(_options: SetAdaptiveModeOptions): Promise<RefreshRateInfo> {
    return this.measure();
  }

  async notifyActivity(): Promise<RefreshRateInfo> {
    return this.measure();
  }

  private async measure(): Promise<RefreshRateInfo> {
    const hz = await this.measureRefreshRate();
    return {
      currentHz: hz,
      maxHz: hz,
      supportedHz: [hz],
      unlockApplied: false,
    };
  }

  private measureRefreshRate(): Promise<number> {
    return new Promise((resolve) => {
      let frames = 0;
      const start = performance.now();
      const tick = () => {
        frames += 1;
        const elapsed = performance.now() - start;
        if (elapsed >= 500) {
          resolve(Math.round((frames * 1000) / elapsed));
          return;
        }
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
  }
}
