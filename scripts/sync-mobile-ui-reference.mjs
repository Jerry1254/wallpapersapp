// H5 is the source for App specification illustrations and Lucide geometry.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const check = process.argv.includes('--check');
async function output(relative, bytes) {
  const target = resolve(root, relative);
  if (check) {
    const actual = await readFile(target);
    if (!actual.equals(Buffer.from(bytes))) throw new Error(`H5 reference differs: ${relative}`);
  } else {
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes);
  }
}
for (const name of ['mountain', 'zen', 'city', 'coast']) {
  await output(`apps/mobile/assets/ui-reference/${name}.svg`,
    await readFile(resolve(root, `apps/h5-prototype/src/assets/demo/${name}.svg`)));
}
const lucide = resolve(root, 'apps/h5-prototype/node_modules/@lucide/vue');
const metadata = JSON.parse(await readFile(resolve(lucide, 'package.json'), 'utf8'));
if (metadata.version !== '1.39.0') throw new Error('Use the H5-locked Lucide version 1.39.0');
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
for (const name of ['house', 'images', 'palette', 'sparkles', 'mountain', 'flower-2', 'flame', 'image',
  'circle-alert', 'package-open', 'wifi-off', 'layers-3', 'chevron-right', 'message-circle-more',
  'loader-circle', 'chevron-left', 'circle-play', 'check', 'panels-top-left', 'lock-keyhole', 'smartphone', 'key-round']) {
  // Lucide renamed Layers3 to Layers; H5 imports the compatibility alias.
  const sourceName = name === 'layers-3' ? 'layers' : name;
  const { __iconNode } = await import(pathToFileURL(resolve(lucide, `dist/esm/icons/${sourceName}.mjs`)).href);
  const geometry = __iconNode.map(([tag, attributes]) => `<${tag} ${Object.entries(attributes)
    .filter(([key]) => key !== 'key').map(([key, value]) => `${key}="${escape(value)}"`).join(' ')}/>`).join('');
  await output(`apps/mobile/assets/lucide/${name}.svg`,
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#191817" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${geometry}</svg>\n`);
}
await output('apps/mobile/assets/lucide/LICENSE.txt', await readFile(resolve(lucide, 'LICENSE')));
process.stdout.write(`H5 illustrations and Lucide 1.39.0 ${check ? 'matched' : 'synchronized'}\n`);
