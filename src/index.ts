import { registerPlugin } from '@capacitor/core';

import type { HighRefreshRatePlugin } from './definitions';

const HighRefreshRate = registerPlugin<HighRefreshRatePlugin>('HighRefreshRate', {
  web: () => import('./web').then((m) => new m.HighRefreshRateWeb()),
});

export * from './definitions';
export { HighRefreshRate };
