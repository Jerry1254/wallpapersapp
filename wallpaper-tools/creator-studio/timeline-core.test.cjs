const {test} = require('node:test');
const assert = require('node:assert/strict');
const C = require('./timeline-core.js');
const near = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-8, `${actual} ≠ ${expected}`);

test('删除中间片段后，成片时间映射到保留的原视频位置', () => {
  const p = C.create(5, 60, 'source-a');
  assert.equal(C.split(p, 1), true);
  assert.equal(C.split(p, 3), true);
  C.remove(p, p.clips[1].id);
  near(C.total(p), 3);
  near(C.at(p, 1).sourceTime, 3);
  near(C.at(p, 2.5).sourceTime, 4.5);
  near(C.at(p, 3).sourceTime, 5);
});

test('多素材拼接与单段变速使用各自的源时间', () => {
  const p = C.create(5, 60, 'source-a');
  C.insert(p, 'source-b', 2, 0);
  C.trim(p, p.clips[1].id, 1, 4, 5);
  C.speed(p, p.clips[0].id, 2);
  near(C.total(p), 4);
  assert.equal(C.at(p, .5).clip.assetId, 'source-b');
  near(C.at(p, .5).sourceTime, 1);
  assert.equal(C.at(p, 1).clip.assetId, 'source-a');
  near(C.at(p, 1).sourceTime, 1);
  near(C.at(p, 2).sourceTime, 2);
});

test('剪断与复制保留素材身份，排序不改变源范围', () => {
  const p = C.create(5, 60, 'source-a');
  C.insert(p, 'source-b', 2);
  C.split(p, 6);
  assert.equal(p.clips[2].assetId, 'source-b');
  C.duplicate(p, p.clips[2].id);
  assert.equal(p.clips[3].assetId, 'source-b');
  assert.notEqual(p.clips[2].id, p.clips[3].id);
  const c = {...p.clips[3]};
  C.move(p, c.id, 0);
  assert.deepEqual(p.clips[0], c);
  near(C.total(p), 8);
});

test('整条成片适配保留片段的相对速度，另一个平台工程不受影响', () => {
  const harmony = C.create(5, 60), ios = C.create(5, 24);
  C.split(harmony, 1);
  C.speed(harmony, harmony.clips[1].id, 2);
  const ratio = harmony.clips[1].speed / harmony.clips[0].speed;
  assert.equal(C.fit(harmony, 2), true);
  near(C.total(harmony), 2);
  near(harmony.clips[1].speed / harmony.clips[0].speed, ratio);
  near(C.total(ios), 5);
  assert.equal(ios.clips.length, 1);
});

test('边界不能产生空片段，清空后仍可重新加入素材', () => {
  const p = C.create(5);
  assert.equal(C.split(p, 0), false);
  assert.equal(C.split(p, 5), false);
  assert.equal(C.split(p, C.MIN / 3), false);
  C.trim(p, p.clips[0].id, -10, 30, 5);
  near(C.total(p), 5);
  C.remove(p, p.clips[0].id);
  assert.equal(C.at(p, 0), null);
  assert.equal(C.fit(p, 2), false);
  assert.equal(C.insert(p, 'too-short', .001), false);
  assert.equal(C.insert(p, 'new-source', 2), true);
  near(C.total(p), 2);
});

test('草稿恢复验证各素材时长，快照排除历史且不与当前片段共享对象', () => {
  const p = C.create(5, 60, 'source-a');
  C.insert(p, 'source-b', 10);
  p.undo = []; p.redo = [];
  const saved = C.snapshot(p);
  assert.equal('undo' in saved, false);
  const restored = C.normalize(saved, 5, 60, id => id === 'source-b' ? 10 : 5);
  assert.equal(restored.clips.length, 2);
  near(C.total(restored), 15);
  C.split(p, 1);
  assert.equal(saved.clips.length, 2);
  assert.equal(C.normalize({clips:[]}, 5).clips.length, 0);
  const legacy = C.normalize({start:1, end:3, speed:2}, 5);
  near(C.total(legacy), 1);
});
