const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('render reuse ignores timeline view and selection but retains output and composition changes',async()=>{
  const window={CreatorBackend:{escape:s=>s},GalleryCore:{assets:()=>[]},ContentCore:{all:w=>w.tracks.flatMap(t=>t.clips)}};
  vm.runInNewContext(fs.readFileSync(require.resolve('./creator-jobs.js'),'utf8'),{window});
  const w={id:'work',name:'video',type:'video',fps:30,output:{width:360,height:640},slots:{},tracks:[{clips:[{id:'clip',start:0,duration:3,x:100}]}]};
  const snapshot=async()=>JSON.stringify(await window.CreatorJobs.snapshot(w,()=>null)),original=await snapshot();
  w.timelineZoom=2.5;w.cursor=1.5;w.selectedClipId='clip';w.view={zoom:2};w.selection=['clip'];
  assert.equal(await snapshot(),original);
  w.output.width=720;assert.notEqual(await snapshot(),original);
  w.output.width=360;w.tracks[0].clips[0].x=120;assert.notEqual(await snapshot(),original);
});
