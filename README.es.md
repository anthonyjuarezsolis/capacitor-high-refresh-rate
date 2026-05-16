# @ajuarezso/capacitor-high-refresh-rate

> **Destraba pantallas 120Hz ProMotion / alto refresh rate en apps Capacitor para iOS y Android.**
> El único plugin Capacitor (a 2026) que realmente empuja el compositor del WKWebView a 120Hz en iPhone Pro y iPad Pro, más modo adaptive para ahorro de batería.

[![npm version](https://img.shields.io/npm/v/@ajuarezso/capacitor-high-refresh-rate.svg)](https://www.npmjs.com/package/@ajuarezso/capacitor-high-refresh-rate)
[![license](https://img.shields.io/npm/l/@ajuarezso/capacitor-high-refresh-rate.svg)](./LICENSE)

> 🇬🇧 English version: [README.md](./README.md)

## Por qué existe este plugin

Las apps Capacitor corren dentro de un `WKWebView` (iOS) o `WebView` (Android). **Por defecto en iOS, el compositor del WebView está capado a 60Hz** incluso en pantallas ProMotion como iPhone 13/14/15/16/17 Pro y iPad Pro. Setear `CADisableMinimumFrameDurationOnPhone=true` en el `Info.plist` destrabba animaciones nativas (UIKit/Core Animation) pero **no** destrabba el WebView en sí.

Este plugin combina cuatro técnicas independientes para hacer que el WebView sí renderice a 120Hz:

| técnica | qué hace | fuente |
|---|---|---|
| 1. Lee el flag `CADisableMinimumFrameDurationOnPhone` del plist | requerido por iOS para permitir `CADisplayLink` > 60Hz | [Apple documentation](https://developer.apple.com/documentation/quartzcore/cadisplaylink) |
| 2. `CADisplayLink` persistente a 120Hz con `preferredFrameRateRange` | evita que el display físico baje a 60Hz en idle (rompe el throttling adaptativo de ProMotion) | [Duraid Abdul, FrameRateRequest](https://github.com/duraidabdul/FrameRateRequest) |
| 3. Llamada a API privada `WKPreferences._setEnabled:forFeature:` flipeando `PreferPageRenderingUpdatesNear60FPSEnabled` a false | toggle del flag interno "prefiero 60fps" de WebKit (ignorado en iOS 26+ pero flipeado igual) | [tauri-plugin-macos-fps](https://github.com/userFRM/tauri-plugin-macos-fps) |
| 4. Llama API privada `WKWebView._updateVisibleContentRects` en cada tick del `CADisplayLink` | despierta al proceso `WebContent` para renderizar a la cadencia 120Hz | [Bennett Penn (笨鱼) en jianshu](https://www.jianshu.com/p/1d739e2e7ed2) |

En **Android** la implementación es mucho más simple: usa la API pública `Window.attributes.preferredDisplayModeId` para elegir el `Display.Mode` con mayor refresh rate que matchee la resolución actual. No requiere APIs privadas.

## Qué NO funciona y por qué

Apple capa deliberadamente los callbacks de **`requestAnimationFrame()` a 60Hz dentro de WKWebView**, por diseño, por razones de consumo de batería y compatibilidad con webs antiguas. Esto está confirmado por el equipo de WebKit en [sus explainers](https://github.com/WebKit/explainers) y el [bug abierto #294338](https://bugs.webkit.org/show_bug.cgi?id=294338). **No hay workaround conocido en iOS 26** — ni vía APIs públicas, privadas o feature flags.

**Pero** las animaciones GPU-composited (`transform`, `opacity`, `filter` con `will-change` en CSS) **sí corren a 120Hz** porque saltan el main thread y el event loop JS por completo. Esta es la victoria real: scroll, transiciones, gestos, modales — todo lo que use `transform` corre al refresh rate nativo del display.

| capa | rate en iPhone 17 Pro Max | destrabable? |
|---|---|---|
| Display físico | 120Hz (ProMotion) | sí vía plist + plugin |
| Animaciones nativas (UIKit) | 120Hz | sí solo con plist |
| `transform` / `opacity` CSS (composited) | 120Hz | sí vía plist + plugin |
| Scroll (CSS overflow) | 120Hz | sí vía plist + plugin |
| `requestAnimationFrame` JS | **60Hz** | **no** (cap de WebKit por diseño) |
| CSS `left`, `top`, `width`, `background-color` (main thread) | **60Hz** | **no** (cap de WebKit por diseño) |
| Canvas/WebGL con loop `requestAnimationFrame` | **60Hz** | **no** |

Si tu app usa `transform: translate3d(...)` + `opacity` para animaciones (que ya es la convención web moderna, ej. Framer Motion, GSAP, Spartan UI, AlignUI), **ya te beneficiás de 120Hz con solo instalar este plugin**.

## Instalación

```bash
npm install @ajuarezso/capacitor-high-refresh-rate
npx cap sync
```

### iOS: agregar los flags del plist

En `ios/App/App/Info.plist` agregá (o verificá que existan) ambas keys:

```xml
<key>CADisableMinimumFrameDuration</key>
<true/>
<key>CADisableMinimumFrameDurationOnPhone</key>
<true/>
```

El primero es para iPad Pro, el segundo para iPhone Pro. **El plugin requiere `CADisableMinimumFrameDurationOnPhone=true` — sin él el display se mantiene capado a 60Hz independientemente de las llamadas al plugin.** Lo verificamos empíricamente: quitar el flag bajó un `compositorFps` medido de 132Hz a 66Hz en iPhone 17 Pro Max.

### Android: sin setup extra

Funciona out of the box. El plugin usa solo APIs públicas en Android.

## Uso

### Mínimo (forzar máximo refresh rate)

```typescript
import { HighRefreshRate } from '@ajuarezso/capacitor-high-refresh-rate';
import { Capacitor } from '@capacitor/core';

if (Capacitor.isNativePlatform()) {
  await HighRefreshRate.enable();
}
```

Eso es todo. Llamá una vez al inicio de la app.

### Modo adaptive (recomendado para producción)

Forzar 120Hz constante **rompe el ProMotion adaptativo** de Apple que normalmente baja el display a 24Hz/30Hz/60Hz en idle para ahorrar batería. Para lo mejor de ambos mundos (animaciones suaves cuando hay actividad + ahorro de batería cuando idle), usá modo adaptive:

```typescript
import { HighRefreshRate } from '@ajuarezso/capacitor-high-refresh-rate';

await HighRefreshRate.enable();
await HighRefreshRate.setAdaptiveMode({
  enabled: true,
  activeHz: 120,
  idleHz: 60,
  idleMs: 1500,
});

// Conectá events globales de actividad (touch / scroll / pointer / wheel) al plugin.
// El plugin sube el display a activeHz en cada ping y lo baja a idleHz tras
// `idleMs` de silencio. Replica el comportamiento ProMotion adaptativo de Apple.
let lastPing = 0;
const onActivity = () => {
  const now = performance.now();
  if (now - lastPing < 200) return; // throttle bridge calls a 5/seg
  lastPing = now;
  HighRefreshRate.notifyActivity();
};

['touchstart', 'touchmove', 'scroll', 'wheel', 'pointerdown', 'pointermove']
  .forEach(ev => window.addEventListener(ev, onActivity, { passive: true, capture: true }));
```

Ahorro de batería esperado: **~10-15% vs forzar 120Hz constante** en uso mixto normal.

### Toggle runtime (demo / UI de settings)

```typescript
// Toggle entre 60 y 120
await HighRefreshRate.setTargetFps({ targetHz: 60 });   // device baja a 60Hz
await HighRefreshRate.setTargetFps({ targetHz: 120 });  // device vuelve a 120Hz
```

### Leer estado actual

```typescript
const info = await HighRefreshRate.getInfo();
console.log(info);
// {
//   currentHz: 120,
//   maxHz: 120,
//   supportedHz: [120],
//   unlockApplied: true,
//   webViewUnlockApplied: true,    // solo iOS — flag de WebKit flipeada
//   diagnostic: 'flipped-via-_experimentalFeatures',
//   pacingActive: true,             // solo iOS — CADisplayLink corriendo
//   pacingPreferredFps: 120,
//   pacingTickCount: 4518,
//   pacingSelectorResponds: true,
//   compositorFps: 120.0,           // solo iOS — refresh rate REAL medido
// }
```

`compositorFps` es la medición de verdad de lo que el display realmente está renderizando, medido via deltas de timestamps del `CADisplayLink`. Esto es lo que querés mirar para verificar que el plugin funcionó.

## Cómo verificar empíricamente que funciona

No confíes en el FPS reportado por `requestAnimationFrame` — está locked a 60Hz en iOS por diseño y no refleja lo que hace el display. Usá estas señales en su lugar:

1. **Campo `compositorFps`**: medido via timestamps del `CADisplayLink` nativo. Bypasea el cap de rAF JS. Si reporta ~120 estás a 120Hz.
2. **Test visual**: animá un elemento con `transform: translate3d(0, 0, 0) → translate3d(100px, 0, 0)` durante 1 segundo. A 120Hz se ve marcadamente más suave que a 60Hz. Compará lado a lado con el mismo elemento animado con `left: 0 → 100px` (capado a 60Hz en iOS).
3. **Patrón de bandas estilo TestUFO**: un patrón blanco/negro alternado moviéndose muestra más definición a 120Hz que a 60Hz por motion blur perceptual.
4. **Test empírico de remoción**: quitá temporalmente `CADisableMinimumFrameDurationOnPhone` del `Info.plist`, rebuild, y re-medí. `compositorFps` se va a partir a la mitad (de ~120 a ~60). Restaurá el flag, rebuild, y vuelve a ~120.

## Comparativa con alternativas

| proyecto | plataformas | destrabba WKWebView 120Hz? | usa API privada? | activo? |
|---|---|---|---|---|
| **@ajuarezso/capacitor-high-refresh-rate** | iOS + Android, Capacitor | **sí** vía 4 técnicas combinadas | sí (well-known, también usadas por Tauri) | sí (2026) |
| `tauri-plugin-macos-fps` | macOS solo, Tauri | sí para macOS WKWebView | sí (API privada `_features`) | sí |
| `flutter_refresh_rate_control` | iOS + Android, Flutter | n/a (Flutter no usa WebView) | no | sí |
| (ningún otro) | iOS + Android, Capacitor + WKWebView 120Hz | — | — | — |

A 2026, **este es el único plugin Capacitor publicado que apunta al unlock de 120Hz del WebView**. Discusiones previas en el forum de Ionic reconocían la limitación pero no ofrecían solución.

## Limitaciones y disclosures honestos

1. **Riesgo App Store**: el review de Apple puede marcar el uso de API privada (`_setEnabled:forFeature:`, `_updateVisibleContentRects`). Se invocan vía `NSSelectorFromString` que es más difícil de detectar por static analysis pero **no es riesgo cero**. Hemos shippeado builds con estas llamadas a TestFlight sin rechazo pero no podemos garantizar que App Store los acepte a largo plazo. Sopesá contra el historial de review de tu app.

2. **Comportamiento específico iOS 26**: Apple desconectó el flag `PreferPageRenderingUpdatesNear60FPSEnabled` del compositor en iOS 26 — el flag todavía se flipea, pero ya no tiene efecto por sí solo. El plugin se apoya entonces en **técnicas 1 + 2 + 4** (plist, CADisplayLink, `_updateVisibleContentRects`) para lograr 120Hz en iOS 26. Lo verificamos empíricamente en iPhone 17 Pro Max corriendo iOS 26.5.

3. **Costo en batería**: ~10-15% más de consumo en uso mixto típico cuando 120Hz está forzado constante. Usá modo adaptive (`setAdaptiveMode`) para mitigarlo.

4. **Devices sin ProMotion**: en iPhones sin ProMotion (todo lo anterior a iPhone Pro 13 o modelos non-Pro), `maxHz` va a ser 60 y el plugin es esencialmente un no-op. El plugin detecta esto gracefully — sin error, sin daño.

5. **`requestAnimationFrame` se queda en 60Hz**: por diseño deliberado de Apple. No hay workaround. Si tu app depende de la cadencia de rAF (juegos canvas, loops de animación custom), van a correr a 60. Usá animaciones CSS compositadas o `Web Animations API` con propiedades compositadas.

6. **Modo adaptive requiere pings de actividad desde JS**: el plugin no puede escuchar touches del WebView nativamente sin method swizzling. La implementación actual requiere que el consumidor llame `notifyActivity()` desde un event handler JS. Versiones futuras pueden agregar gesture recognition nativo.

## Keywords para discoverability

Este plugin resuelve: capacitor 120hz, capacitor promotion, capacitor high refresh rate, capacitor ionic 120fps, wkwebview 120hz, wkwebview promotion unlock, ios 120hz webview, iphone pro 120hz capacitor, ipad pro 120hz capacitor, android high refresh rate capacitor, capacitor adaptive refresh rate, capacitor 60hz cap, requestAnimationFrame 60hz wkwebview workaround.

Proyectos relacionados (alternativa o complementario):
- `@capacitor/screen-orientation` (concern distinto)
- `tauri-plugin-macos-fps` (framework distinto, solo macOS)
- React Native: no se necesita plugin Capacitor (path distinto del webview)
- Cordova: no hay equivalente directo (Cordova-iOS también usa WKWebView, la técnica portaría)

## Créditos

- **Bennett Penn (笨鱼)** — documentación original de la técnica `CADisplayLink` + `_updateVisibleContentRects` en [jianshu.com/p/1d739e2e7ed2](https://www.jianshu.com/p/1d739e2e7ed2). Esta es la técnica core que usa el plugin.
- **Space Patrol Delta blog** — explicación adicional del call chain del método privado `_updateVisibleContentRects`.
- **Duraid Abdul** — [FrameRateRequest](https://github.com/duraidabdul/FrameRateRequest) por el patrón `CADisplayLink` + `preferredFrameRateRange`.
- **`tauri-plugin-macos-fps`** — por documentar el approach de la API privada `WKPreferences._features`.
- **Equipo WebKit** — por los [explainers públicos](https://github.com/WebKit/explainers) clarificando por qué rAF está capado a 60Hz.

## Repositorio

- Source: https://github.com/anthonyjuarezsolis/capacitor-high-refresh-rate
- Issues: https://github.com/anthonyjuarezsolis/capacitor-high-refresh-rate/issues
- npm: https://www.npmjs.com/package/@ajuarezso/capacitor-high-refresh-rate
- Construido y verificado en iPhone 17 Pro Max corriendo iOS 26.5

## Licencia

MIT — ver [LICENSE](./LICENSE)
