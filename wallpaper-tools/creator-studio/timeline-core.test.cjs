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

test('修改片段速度保留源范围，另一个平台工程不受影响', () => {
  const harmony = C.create(5, 60), ios = C.create(5, 24);
  C.split(harmony, 1);
  const source={start:harmony.clips[1].start,end:harmony.clips[1].end};
  C.speed(harmony, harmony.clips[1].id, 2);
  near(C.total(harmony),3);
  assert.deepEqual({start:harmony.clips[1].start,end:harmony.clips[1].end},source);
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
  assert.equal(C.insert(p, 'too-short', .001), false);
  assert.equal(C.insert(p, 'new-source', 2), true);
  near(C.total(p), 2);
});

test('拖动前后边缘裁掉源帧，正常和二倍速都保留原速度',()=>{
  for(const speed of [1,2]){
    const p=C.create(5),c=p.clips[0];C.speed(p,c.id,speed);const initial={...c};
    C.trimEdge(p,c.id,'end',-2/speed,5,initial);
    near(c.start,0);near(c.end,3);near(c.speed,speed);near(C.total(p),3/speed);
    near(C.at(p,.5/speed).sourceTime,.5);
    C.trimEdge(p,c.id,'end',-1/speed,5,initial);
    near(c.end,4); // Each pointer position is relative to the gesture start, not the previous move.
    C.trimEdge(p,c.id,'start',1/speed,5);
    near(c.start,1);near(c.end,4);near(c.speed,speed);near(C.at(p,0).sourceTime,1);
    near(C.at(p,.5/speed).sourceTime,1.5);
  }
});

test('边缘按帧裁剪，不能越过原素材或剪成空片段',()=>{
  const p=C.create(5),c=p.clips[0];
  C.trimEdge(p,c.id,'end',-.017,5);near(c.end,5-1/C.FPS);
  C.trimEdge(p,c.id,'end',-50,5);near(c.end-c.start,C.MIN);near(c.speed,1);
  C.trimEdge(p,c.id,'end',50,5);near(c.end,5);
  C.trimEdge(p,c.id,'start',50,5);near(c.start,5-C.MIN);
  C.trimEdge(p,c.id,'start',-50,5);near(c.start,0);
});

test('粘贴保留裁剪范围和速度，源片段、另一个工程和副本各自独立',()=>{
  const harmony=C.create(5,60,'source'),source=harmony.clips[0],ios=C.normalize({clips:[]},5,24);
  C.trim(harmony,source.id,1,3,5);C.speed(harmony,source.id,2);
  assert.equal(C.paste(ios,source),true);const copy=ios.clips[0];
  assert.notEqual(copy.id,source.id);near(copy.start,1);near(copy.end,3);near(copy.speed,2);near(C.total(ios),1);
  C.trimEdge(ios,copy.id,'end',-.5,5);near(copy.end,2);near(source.end,3);near(C.total(harmony),1);
  assert.equal(C.paste(ios,{...source,end:source.start}),false);
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
  assert.equal(legacy.clips[0].kind,'video');
  assert.equal(legacy.clips[0].assetId,'demo-video');
  assert.equal(C.duplicate(legacy,legacy.clips[0].id),true);
  const older=C.normalize({clips:[{start:0,end:1,speed:1,assetId:'source-a'}]},5);
  assert.equal(older.clips[0].kind,'video');
});

test('图片默认十帧，映射固定画面，修改和分割均保持准确帧数', () => {
  const p=C.normalize({clips:[]},5);
  C.insert(p,'still',undefined,0,'image');
  near(C.total(p)*C.FPS,10);
  near(C.at(p,.2).sourceTime,0);
  C.stillFrames(p,p.clips[0].id,24);
  assert.equal(C.split(p,.4),true);
  near(C.total(p)*C.FPS,24);
  assert.deepEqual(p.clips.map(c=>Math.round(C.clipDuration(c)*C.FPS)),[12,12]);
  assert.ok(p.clips.every(c=>c.kind==='image'));
  C.remove(p,p.clips[1].id);
  near(C.total(p)*C.FPS,12);
  assert.equal(C.stillFrames(p,p.clips[0].id,0),false);
});

test('图片与视频交替时分别使用静态画面与视频原始时间', () => {
  const p=C.create(5,60,'video');
  C.insert(p,'still',undefined,0,'image');
  near(C.at(p,.1).sourceTime,0);
  assert.equal(C.at(p,10/C.FPS).clip.assetId,'video');
  near(C.at(p,10/C.FPS+.5).sourceTime,.5);
  const restored=C.normalize(C.snapshot(p),5,60,id=>id==='still'?0:5);
  assert.equal(restored.clips.length,2);
});
