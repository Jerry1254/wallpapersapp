const test=require('node:test');
const assert=require('node:assert/strict');
const Export=require('./timeline-export.js');
const base={width:1206,height:2622,scale:1.2,x:8,y:-5,frames:24};
test('preserves cuts, sequence order, speed and crop; uploads repeated sources once',()=>{
  const video={type:'video',file:{type:'video/mp4',name:'视频.mp4'}},image={type:'image',file:{type:'image/png',name:'静态.png'}};
  const profile={...base,clips:[{assetId:'v',kind:'video',start:2,end:4,speed:2},{assetId:'i',kind:'image',start:0,end:10/30,speed:1},{assetId:'v',kind:'video',start:0,end:1,speed:.5}]};
  const result=Export.prepare(profile,id=>id==='v'?video:image);
  assert.deepEqual(result.job.clips.map(c=>[c.source,c.start,c.end,c.speed]),[[0,2,4,2],[1,0,10/30,1],[0,0,1,.5]]);
  assert.deepEqual(result.job.profile,{width:1206,height:2622,scale:1.2,x:8,y:-5});
  assert.equal(result.files.length,2);assert.equal(result.files[0].file,video.file);
  profile.clips[0].end=3;assert.equal(result.job.clips[0].end,4);
});
test('a 5-second iOS or Harmony timeline can export regardless of the target frame count',()=>{
  const result=Export.prepare({...base,clips:[{assetId:'demo',kind:'video',start:0,end:5,speed:1}]},()=>({type:'video',demo:true}));
  assert.equal(result.job.clips[0].end,5);assert.equal(result.job.profile.frames,undefined);assert.equal(result.files.length,0);
});
test('empty timelines and missing sources give actionable errors',()=>{
  assert.throws(()=>Export.prepare({...base,clips:[]},()=>null),/没有片段/);
  assert.throws(()=>Export.prepare({...base,clips:[{assetId:'missing'}]},()=>null),/素材已丢失/);
});
