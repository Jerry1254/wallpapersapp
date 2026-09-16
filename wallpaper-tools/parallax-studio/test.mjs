import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('./index.html', import.meta.url), 'utf8');
const coreStart = html.indexOf('/* ARC-003 v2 directional-strength geometry shared with the Android implementation. */');
const coreEnd = html.indexOf('</script>', coreStart);
assert.ok(coreStart >= 0 && coreEnd > coreStart, 'must contain the v2 core script');

const module = { exports: {} };
vm.runInNewContext(html.slice(coreStart, coreEnd), { module, exports: module.exports }, {
  filename: 'parallax-core.js',
});
const core = module.exports;

const archiveStart = html.indexOf('/* Offline ZIP and directory transport. Paths are only used to match local files. */');
const archiveEnd = html.indexOf('</script>', archiveStart);
assert.ok(archiveStart >= 0 && archiveEnd > archiveStart, 'must contain the archive transport script');
const archiveContext = { ParallaxCore: core, Uint8Array, TextDecoder };
vm.runInNewContext(html.slice(archiveStart, archiveEnd), archiveContext, { filename: 'parallax-archive.js' });
const archive = archiveContext.ParallaxArchive;

const validConfig = {
  formatVersion: 2,
  canvas: { width: 2048, height: 2048 },
  motion: { maxAngle: 75 },
  layers: [
    { index: 1, offsetPercent: 55, direction: 'follow', scale: 1.18, opacity: 1, blendMode: 'normal' },
    { index: 2, offsetPercent: 20, direction: 'reverse', scale: 1, opacity: 1, blendMode: 'normal' },
  ],
};

assert.deepEqual(Array.from(core.validateConfig(validConfig)), []);
assert.match(core.validateConfig({ ...validConfig, formatVersion: 1 }).join('；'), /formatVersion 必须为 2/);
assert.match(core.validateConfig({ ...validConfig, motion: { maxAngle: 0 } }).join('；'), /1～75/);
assert.match(core.validateConfig({ ...validConfig, motion: { maxAngle: 76 } }).join('；'), /1～75/);
assert.match(core.validateConfig({ ...validConfig, motion: { maxAngle: 75, strength: 1 } }).join('；'), /只能包含 maxAngle/);
assert.match(core.validateConfig({
  ...validConfig,
  layers: [{ ...validConfig.layers[0], offsetPercent: 101 }, validConfig.layers[1]],
}).join('；'), /offsetPercent 超出范围/);
assert.match(core.validateConfig({
  ...validConfig,
  layers: [{ ...validConfig.layers[0], direction: 'sideways' }, validConfig.layers[1]],
}).join('；'), /运动方向无效/);

assert.equal(core.inputFromAngles(0, 0, 75).x, 0);
assert.ok(core.inputFromAngles(0.1, 0, 75).x > 0, 'motion starts at the first non-zero angle');
assert.equal(core.inputFromAngles(75, -75, 75).x, 1);
assert.equal(core.inputFromAngles(75, -75, 75).y, -1);
assert.equal(core.inputFromAngles(150, -150, 75).x, 1, 'input clamps after maxAngle');

const following = core.geometry(1000, 1000, 1000, 1000, { offsetPercent: 100, direction: 'follow', scale: 1 }, 1, 1, false);
const opposite = core.geometry(1000, 1000, 1000, 1000, { offsetPercent: 20, direction: 'reverse', scale: 1 }, 1, 1, true);
const fixed = core.geometry(1000, 1000, 1000, 1000, { offsetPercent: 100, direction: 'fixed', scale: 1 }, 1, 1, false);
assert.equal(following.dx, -150);
assert.equal(following.dy, 150);
assert.equal(opposite.dx, 30);
assert.equal(opposite.dy, -30);
assert.ok(Math.abs(fixed.dx) === 0);
assert.ok(Math.abs(fixed.dy) === 0);
assert.equal(opposite.relativeScale, 1.06, 'background overscan covers its full directional travel');
assert.ok(opposite.marginX >= Math.abs(opposite.dx));
assert.ok(opposite.marginY >= Math.abs(opposite.dy));

const exported = core.sourceConfig({
  canvas: { width: 2048, height: 2048 },
  motion: { maxAngle: 45 },
  layers: validConfig.layers.map(layer => ({ ...layer, name: 'ignored', visible: true })),
});
assert.equal(JSON.stringify(exported), JSON.stringify({ ...validConfig, motion: { maxAngle: 45 } }));

class FakeZip {
  constructor() { this.files = new Map(); }
  file(name, value) { this.files.set(name, value); return this; }
  async generateAsync() { return this.files; }
}
const firstBytes = new Uint8Array([11, 22, 33, 44]);
const secondBytes = new Uint8Array([55, 66, 77]);
const archiveFiles = await archive.wallpaper(validConfig, [
  { bytes: firstBytes, ext: 'png' },
  { bytes: secondBytes, ext: 'webp' },
], new Uint8Array([1, 2]), FakeZip);
assert.strictEqual(archiveFiles.get('layers/01.png'), firstBytes, 'PNG source bytes are passed through unchanged');
assert.strictEqual(archiveFiles.get('layers/02.webp'), secondBytes, 'WebP source bytes and extension are passed through unchanged');
assert.equal(JSON.parse(archiveFiles.get('config.json')).formatVersion, 2);

const appStart = html.indexOf("(function(){\n  'use strict';", coreEnd);
const appEnd = html.indexOf('</script>', appStart);
assert.ok(appStart >= 0 && appEnd > appStart, 'must contain the application script');
const appSource = html.slice(appStart, appEnd);
for (const stale of ['scene.sensor', 'sensorParams', 'normalizedPNG', 'projectVersion:1', 'formatVersion:1']) {
  assert.equal(appSource.includes(stale), false, `application must not contain stale v1 token: ${stale}`);
}
assert.match(html, /<span class="version">2\.0\.0<\/span>/);
assert.match(appSource, /\['offsetPercent','位移强度','Offset %',0,100/);
assert.match(appSource, /id="layer-direction"/);
assert.equal(core.MAX_TRAVEL_PERCENT, 15);
assert.match(html, /不做静默降采样/);
assert.match(html, /\.preview-panel\{grid-column:3;/);
assert.match(appSource, /function syncResponsiveLayout\(\)/);

console.log('Parallax Studio v2 checks passed');
