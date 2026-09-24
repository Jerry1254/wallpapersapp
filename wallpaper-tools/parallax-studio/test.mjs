import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('./index.html', import.meta.url), 'utf8');
const coreStart = html.indexOf('/* ARC-003 v2 directional-strength geometry shared with the Android implementation. */');
const coreEnd = html.indexOf('</script>', coreStart);
assert.ok(coreStart >= 0 && coreEnd > coreStart, 'must contain the v2 core script');
const module = { exports: {} };
vm.runInNewContext(html.slice(coreStart, coreEnd), { module, exports: module.exports }, { filename: 'parallax-core.js' });
const core = module.exports;

const archiveStart = html.indexOf('/* Offline ZIP and directory transport. Paths are only used to match local files. */');
const archiveEnd = html.indexOf('</script>', archiveStart);
assert.ok(archiveStart >= 0 && archiveEnd > archiveStart, 'must contain the archive transport script');
const archiveContext = { ParallaxCore: core, Uint8Array, TextDecoder };
vm.runInNewContext(html.slice(archiveStart, archiveEnd), archiveContext, { filename: 'parallax-archive.js' });
const archive = archiveContext.ParallaxArchive;

const layer = (index, values = {}) => ({
  index, offsetXPercent: 100, offsetYPercent: 50,
  initialOffsetXPercent: 10, initialOffsetYPercent: -5,
  direction: 'follow', scale: 1.18, opacity: 1, blendMode: 'normal', ...values,
});
const validConfig = {
  formatVersion: 2,
  canvas: { width: 2048, height: 2048 },
  motion: { maxAngleX: 75, maxAngleY: 50 },
  layers: [layer(1), layer(2, { offsetXPercent: 20, offsetYPercent: 10, initialOffsetXPercent: 0, initialOffsetYPercent: 0, direction: 'reverse', scale: 1 })],
};
assert.deepEqual(Array.from(core.validateConfig(validConfig)), []);
assert.match(core.validateConfig({ ...validConfig, formatVersion: 1 }).join('；'), /formatVersion 必须为 2/);
assert.match(core.validateConfig({ ...validConfig, motion: { maxAngleX: 0, maxAngleY: 75 } }).join('；'), /maxAngleX/);
assert.match(core.validateConfig({ ...validConfig, motion: { maxAngleX: 75, maxAngleY: 76 } }).join('；'), /maxAngleY/);
assert.match(core.validateConfig({ ...validConfig, motion: { ...validConfig.motion, strength: 1 } }).join('；'), /只能包含 maxAngleX 和 maxAngleY/);
assert.match(core.validateConfig({ ...validConfig, layers: [layer(1, { offsetXPercent: -1 }), validConfig.layers[1]] }).join('；'), /offsetXPercent 必须为非负有限数值/);
assert.match(core.validateConfig({ ...validConfig, layers: [layer(1, { initialOffsetYPercent: Infinity }), validConfig.layers[1]] }).join('；'), /initialOffsetYPercent 必须为有限数值/);

assert.deepEqual({ ...core.inputFromAngles(75, -25, 75, 50) }, { x: 1, y: -.5 });
assert.deepEqual({ ...core.inputFromAngles(150, -100, 75, 50) }, { x: 1, y: -1 });
const following = core.geometry(1000, 1000, 1000, 1000, layer(1, { scale: 1 }), 1, 1, false);
const beyondScreen = core.geometry(1000, 1000, 1000, 1000, layer(1, { offsetXPercent: 300, offsetYPercent: 200, initialOffsetXPercent: 0, initialOffsetYPercent: 0, scale: 1 }), 1, 1, false);
const opposite = core.geometry(1000, 1000, 1000, 1000, layer(1, { offsetXPercent: 20, offsetYPercent: 10, initialOffsetXPercent: 0, initialOffsetYPercent: 0, direction: 'reverse', scale: 1 }), 1, 1, true);
const correctedOppositeStart = core.geometry(1000, 1000, 1000, 1000, layer(1, { offsetXPercent: 20, offsetYPercent: 10, initialOffsetXPercent: 35, initialOffsetYPercent: -25, direction: 'reverse', scale: 1 }), -1, -1, true);
const correctedOppositeEnd = core.geometry(1000, 1000, 1000, 1000, layer(1, { offsetXPercent: 20, offsetYPercent: 10, initialOffsetXPercent: 35, initialOffsetYPercent: -25, direction: 'reverse', scale: 1 }), 1, 1, true);
const fixed = core.geometry(1000, 1000, 1000, 1000, layer(1, { direction: 'fixed', scale: 1 }), 1, 1, false);
assert.equal(following.dx, -900);
assert.equal(following.dy, 450);
assert.equal(beyondScreen.dx, -3000);
assert.equal(beyondScreen.dy, 2000);
assert.equal(opposite.dx, 200);
assert.equal(opposite.dy, -100);
assert.equal(fixed.dx, 100, 'fixed keeps the neutral-position correction');
assert.equal(fixed.dy, -50);
assert.equal(opposite.relativeScale, 1.4, 'background overscan covers the larger axis travel');
assert.ok(opposite.marginX >= Math.abs(opposite.dx));
assert.ok(opposite.marginY >= Math.abs(opposite.dy));
assert.equal(correctedOppositeStart.relativeScale, opposite.relativeScale, 'neutral correction must not change background scale');
assert.equal(correctedOppositeEnd.dx - correctedOppositeStart.dx, 400, 'neutral correction must not change horizontal animation travel');
assert.equal(correctedOppositeEnd.dy - correctedOppositeStart.dy, -200, 'neutral correction must not change vertical animation travel');

const migrated = core.migrateV1({ formatVersion: 1, canvas: { width: 2048, height: 2048 }, sensor: { maxAngle: 75, smoothing: .2, strength: 1.3, responseCurve: 'depth', responseGain: 10 }, layers: [
  { index: 1, depth: 1, scale: 1.18, opacity: 1, blendMode: 'normal' },
  { index: 2, depth: 0, scale: 1.18, opacity: 1, blendMode: 'normal' },
] });
assert.deepEqual({ ...migrated.motion }, { maxAngleX: 75, maxAngleY: 75 });
assert.equal(migrated.layers[0].offsetXPercent, 13);
assert.equal(migrated.layers[0].offsetYPercent, 13);
assert.equal(migrated.layers[1].direction, 'fixed');
assert.deepEqual(Array.from(core.validateConfig(migrated)), []);

const folderFile = (path, bytes) => ({ path, file: { name: path.split('/').at(-1), size: bytes.length, async arrayBuffer() { return bytes.slice().buffer; } } });
const migratedFolder = await archive.readFolder([
  folderFile('old/config.json', new TextEncoder().encode(JSON.stringify({ formatVersion: 1, canvas: { width: 2048, height: 2048 }, sensor: { maxAngle: 75, smoothing: .2, strength: 1.3, responseCurve: 'depth', responseGain: 10 }, layers: [
    { index: 1, depth: 1, scale: 1.18, opacity: 1, blendMode: 'normal' },
    { index: 2, depth: 0, scale: 1.18, opacity: 1, blendMode: 'normal' },
  ] }))),
  folderFile('old/layers/01.png', new Uint8Array([1])),
  folderFile('old/layers/02.png', new Uint8Array([2])),
]);
assert.equal(migratedFolder.migrated, true);
assert.equal(migratedFolder.config.layers[0].offsetXPercent, 13);
assert.equal(migratedFolder.entries.length, 2);

const exported = core.sourceConfig({ canvas: validConfig.canvas, motion: validConfig.motion, layers: validConfig.layers.map(value => ({ ...value, name: 'ignored', visible: true })) });
assert.equal(JSON.stringify(exported), JSON.stringify(validConfig));

class FakeZip { constructor() { this.files = new Map(); } file(name, value) { this.files.set(name, value); return this; } async generateAsync() { return this.files; } }
const firstBytes = new Uint8Array([11, 22, 33, 44]);
const secondBytes = new Uint8Array([55, 66, 77]);
const archiveFiles = await archive.wallpaper(validConfig, [{ bytes: firstBytes, ext: 'png' }, { bytes: secondBytes, ext: 'webp' }], FakeZip);
assert.strictEqual(archiveFiles.get('layers/01.png'), firstBytes);
assert.strictEqual(archiveFiles.get('layers/02.webp'), secondBytes);
assert.equal(JSON.parse(archiveFiles.get('config.json')).formatVersion, 2);
assert.equal(archiveFiles.has('cover.jpg'), false);

const appStart = html.indexOf("(function(){\n  'use strict';", coreEnd);
const appEnd = html.indexOf('</script>', appStart);
assert.ok(appStart >= 0 && appEnd > appStart, 'must contain the application script');
const appSource = html.slice(appStart, appEnd);
const toolVersion = appSource.match(/const TOOL_VERSION='(\d+\.\d+\.\d+)'/);
assert.ok(toolVersion, 'generator must expose a semantic tool version');
assert.equal(toolVersion[1], '2.2.0');
assert.equal((html.match(/data-tool-version/g) || []).length, 3, 'all visible version labels must use the shared tool version');
for (const stale of ['scene.sensor', 'sensorParams', 'normalizedPNG', 'projectVersion:1', 'formatVersion:1']) assert.equal(appSource.includes(stale), false, `application must not contain stale token: ${stale}`);
assert.match(appSource, /\['offsetXPercent','水平运动强度'/);
assert.match(appSource, /\['offsetYPercent','垂直运动强度'/);
assert.match(appSource, /\['initialOffsetXPercent','初始水平位置'/);
assert.match(appSource, /\['initialOffsetYPercent','初始垂直位置'/);
assert.match(appSource, /\['maxAngleX','水平满幅角度'/);
assert.match(appSource, /\['maxAngleY','垂直满幅角度'/);
assert.match(appSource, /id="layer-direction"/);
assert.equal('MAX_TRAVEL_PERCENT' in core, false);
assert.match(appSource, /function adaptiveOffsetMax\(value\)/);
assert.match(appSource, /function adaptivePositionMax\(value\)/);
assert.match(html, /不做静默降采样/);
assert.match(html, /\.preview-panel\{grid-column:3;/);
assert.match(appSource, /function syncResponsiveLayout\(\)/);
console.log('Parallax Studio independent-axis checks passed');
