import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const appDirectory = resolve(scriptDirectory, '..');
const sourcePath = resolve(appDirectory, '../../packages/design-tokens/qingjing-wallpaper.tokens.json');
const cssOutputPath = resolve(appDirectory, 'src/design-system/generated/tokens.css');
const tsOutputPath = resolve(appDirectory, 'src/design-system/generated/tokens.ts');
const dartOutputPath = resolve(appDirectory, '../../packages/design-tokens/generated/qingjing_wallpaper_tokens.dart');
const checkOnly = process.argv.includes('--check');

const sourceText = await readFile(sourcePath, 'utf8');
const tokens = JSON.parse(sourceText);
const kebabCase = (value) => value.replace(/[A-Z]/g, (letter) => '-' + letter.toLowerCase());
const pascalCase = (value) => value.charAt(0).toUpperCase() + value.slice(1);
const dartName = (group, name) => group + pascalCase(name);
const dartDouble = (value) => Number.isInteger(value) ? String(value) + '.0' : String(value);

function hexToRgb(hex) {
  const value = hex.replace('#', '');
  return {
    red: Number.parseInt(value.slice(0, 2), 16),
    green: Number.parseInt(value.slice(2, 4), 16),
    blue: Number.parseInt(value.slice(4, 6), 16)
  };
}

function flutterColor(hex, opacity = 1) {
  const value = hex.replace('#', '').toUpperCase();
  const alpha = Math.round(opacity * 255).toString(16).padStart(2, '0').toUpperCase();
  return 'Color(0x' + alpha + value + ')';
}

function cssShadow(shadow) {
  const rgb = hexToRgb(shadow.color);
  return shadow.x + 'px ' + shadow.y + 'px ' + shadow.blur + 'px ' + shadow.spread +
    'px rgba(' + rgb.red + ', ' + rgb.green + ', ' + rgb.blue + ', ' + shadow.opacity + ')';
}

function generateCss() {
  const lines = [
    '/* Generated from packages/design-tokens/qingjing-wallpaper.tokens.json. */',
    ':root,',
    '.qj-design-system {'
  ];

  for (const [name, value] of Object.entries(tokens.color)) {
    lines.push('  --qj-color-' + kebabCase(name) + ': ' + value + ';');
  }
  lines.push('');

  for (const [name, value] of Object.entries(tokens.space)) {
    lines.push('  --qj-space-' + name + ': ' + value + 'px;');
  }
  lines.push('');

  for (const [name, value] of Object.entries(tokens.radius)) {
    lines.push('  --qj-radius-' + kebabCase(name) + ': ' + value + 'px;');
  }
  lines.push('');

  for (const [name, value] of Object.entries(tokens.size)) {
    lines.push('  --qj-size-' + kebabCase(name) + ': ' + value + 'px;');
  }
  lines.push('');

  for (const [name, value] of Object.entries(tokens.fontSize)) {
    lines.push('  --qj-font-size-' + kebabCase(name) + ': ' + value + 'px;');
  }
  for (const [name, value] of Object.entries(tokens.fontWeight)) {
    lines.push('  --qj-font-weight-' + kebabCase(name) + ': ' + value + ';');
  }
  for (const [name, value] of Object.entries(tokens.lineHeight)) {
    lines.push('  --qj-line-height-' + kebabCase(name) + ': ' + value + ';');
  }
  lines.push('');

  lines.push('  --qj-duration-fast: ' + tokens.motion.durationFast + 'ms;');
  lines.push('  --qj-duration-base: ' + tokens.motion.durationBase + 'ms;');
  lines.push('  --qj-duration-slow: ' + tokens.motion.durationSlow + 'ms;');
  lines.push('  --qj-ease-standard: cubic-bezier(' + tokens.motion.easeStandard.join(', ') + ');');
  lines.push('  --qj-ease-emphasized: cubic-bezier(' + tokens.motion.easeEmphasized.join(', ') + ');');
  lines.push('');

  for (const [name, value] of Object.entries(tokens.breakpoint)) {
    lines.push('  --qj-breakpoint-' + kebabCase(name) + ': ' + value + 'px;');
  }
  lines.push('');

  for (const [name, value] of Object.entries(tokens.shadow)) {
    lines.push('  --qj-shadow-' + kebabCase(name) + ': ' + cssShadow(value) + ';');
  }

  lines.push('}', '');
  return lines.join('\n');
}

function generateTypeScript() {
  return [
    '// Generated from packages/design-tokens/qingjing-wallpaper.tokens.json.',
    'export const qingjingWallpaperTokens = ' + JSON.stringify(tokens, null, 2) + ' as const;',
    '',
    'export type QingjingWallpaperTokens = typeof qingjingWallpaperTokens;',
    ''
  ].join('\n');
}

function generateDart() {
  const lines = [
    '// Generated from qingjing-wallpaper.tokens.json.',
    "import 'package:flutter/material.dart';",
    '',
    'abstract final class QingjingWallpaperTokens {'
  ];

  for (const [name, value] of Object.entries(tokens.color)) {
    lines.push('  static const Color ' + dartName('color', name) + ' = ' + flutterColor(value) + ';');
  }
  lines.push('');

  for (const group of ['space', 'radius', 'size', 'fontSize', 'lineHeight', 'breakpoint']) {
    for (const [name, value] of Object.entries(tokens[group])) {
      lines.push('  static const double ' + dartName(group, name) + ' = ' + dartDouble(Number(value)) + ';');
    }
    lines.push('');
  }

  for (const [name, value] of Object.entries(tokens.fontWeight)) {
    lines.push('  static const int ' + dartName('fontWeight', name) + ' = ' + value + ';');
  }
  lines.push('');
  lines.push('  static const Duration durationFast = Duration(milliseconds: ' + tokens.motion.durationFast + ');');
  lines.push('  static const Duration durationBase = Duration(milliseconds: ' + tokens.motion.durationBase + ');');
  lines.push('  static const Duration durationSlow = Duration(milliseconds: ' + tokens.motion.durationSlow + ');');
  lines.push('  static const Curve easeStandard = Cubic(' + tokens.motion.easeStandard.map(dartDouble).join(', ') + ');');
  lines.push('  static const Curve easeEmphasized = Cubic(' + tokens.motion.easeEmphasized.map(dartDouble).join(', ') + ');');
  lines.push('');

  for (const [name, shadow] of Object.entries(tokens.shadow)) {
    lines.push('  static const BoxShadow ' + dartName('shadow', name) + ' = BoxShadow(');
    lines.push('    color: ' + flutterColor(shadow.color, shadow.opacity) + ',');
    lines.push('    offset: Offset(' + dartDouble(shadow.x) + ', ' + dartDouble(shadow.y) + '),');
    lines.push('    blurRadius: ' + dartDouble(shadow.blur) + ',');
    lines.push('    spreadRadius: ' + dartDouble(shadow.spread) + ',');
    lines.push('  );');
  }

  lines.push('}', '');
  return lines.join('\n');
}

const outputs = [
  [cssOutputPath, generateCss()],
  [tsOutputPath, generateTypeScript()],
  [dartOutputPath, generateDart()]
];

if (checkOnly) {
  const stale = [];
  for (const [path, expected] of outputs) {
    let current = '';
    try {
      current = await readFile(path, 'utf8');
    } catch {
      stale.push(path);
      continue;
    }
    if (current !== expected) stale.push(path);
  }
  if (stale.length) {
    throw new Error('Generated design tokens are stale:\n' + stale.join('\n'));
  }
} else {
  await Promise.all(outputs.map(async ([path, content]) => {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content, 'utf8');
  }));
}
