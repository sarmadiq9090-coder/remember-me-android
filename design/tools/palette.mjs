// Builds the complete Material 3 colour scheme (every role, light and dark) from the Remember Me brand
// colours with Google's material-color-utilities (Apache-2.0), and writes design/tools/palette.json.
// Usage (PowerShell):
//   $env:MCU_DIR = "<folder that holds node_modules/@material/material-color-utilities>"
//   node design\tools\palette.mjs
// Brand (Windows icon, Remember-Me/assets/icon.svg): tile #2F6249 / #123B2A, bell #C5B3FF, accent #B9F34A.
import { createRequire } from 'module';
import path from 'path';
import fs from 'fs';
import { fileURLToPath, pathToFileURL } from 'url';
const here = path.dirname(fileURLToPath(import.meta.url));
const dir = process.env.MCU_DIR || path.join(here, '_mcu');
const mcu = await import(pathToFileURL(path.join(dir, 'node_modules/@material/material-color-utilities/index.js')).href);
const { argbFromHex, hexFromArgb, Hct, TonalPalette, DynamicScheme, MaterialDynamicColors: M } = mcu;
const { Variant } = await import(pathToFileURL(path.join(dir, 'node_modules/@material/material-color-utilities/dynamiccolor/variant.js')).href);

const SEED = '#2F6249';      // primary: the icon's forest-green tile
const BELL = '#C5B3FF';      // tertiary: the icon's lavender bell
const seed = Hct.fromInt(argbFromHex(SEED));
const bell = Hct.fromInt(argbFromHex(BELL));
const palettes = {
  primaryPalette: TonalPalette.fromHueAndChroma(seed.hue, Math.max(seed.chroma, 36)),
  secondaryPalette: TonalPalette.fromHueAndChroma(seed.hue, 16),
  tertiaryPalette: TonalPalette.fromHueAndChroma(bell.hue, Math.max(bell.chroma, 40)),
  neutralPalette: TonalPalette.fromHueAndChroma(seed.hue, 5),
  neutralVariantPalette: TonalPalette.fromHueAndChroma(seed.hue, 8),
};
const ROLES = ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant',
  'surfaceTint', 'inverseSurface', 'inverseOnSurface', 'outline', 'outlineVariant', 'scrim',
  'surfaceBright', 'surfaceDim', 'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
  'surfaceContainerHigh', 'surfaceContainerHighest'];
const out = { seed: SEED, tertiarySeed: BELL, seedHct: { h: +seed.hue.toFixed(1), c: +seed.chroma.toFixed(1), t: +seed.tone.toFixed(1) } };
for (const isDark of [false, true]) {
  const s = new DynamicScheme({ sourceColorArgb: argbFromHex(SEED), variant: Variant.TONAL_SPOT, contrastLevel: 0, isDark, ...palettes });
  const o = {};
  for (const r of ROLES) o[r] = hexFromArgb(M[r].getArgb(s)).toUpperCase();
  out[isDark ? 'dark' : 'light'] = o;
}
fs.writeFileSync(path.join(here, 'palette.json'), JSON.stringify(out, null, 2) + '\n');
// Kotlin for Theme.kt
const kt = (name, o) => `private val ${name} = ${name === 'Light' ? 'lightColorScheme' : 'darkColorScheme'}(\n` +
  ROLES.map(r => `    ${r} = Color(0xFF${o[r].slice(1)}),`).join('\n') + '\n)\n';
fs.writeFileSync(path.join(here, 'palette.kt.txt'), kt('Light', out.light) + '\n' + kt('Dark', out.dark));
console.log(JSON.stringify(out, null, 1));
