const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('render reuse ignores timeline view and selection but retains output and composition changes',async()=>{
  const window={addEventListener(){},CreatorBackend:{escape:s=>s},GalleryCore:{assets:()=>[]},ContentCore:{all:w=>w.tracks.flatMap(t=>t.clips)}};
  vm.runInNewContext(fs.readFileSync(require.resolve('./creator-jobs.js'),'utf8'),{window});
  const w={id:'work',name:'video',type:'video',fps:30,output:{width:360,height:640},slots:{},tracks:[{clips:[{id:'clip',start:0,duration:3,x:100}]}]};
  const snapshot=async()=>JSON.stringify(await window.CreatorJobs.snapshot(w,()=>null)),original=await snapshot();
  w.timelineZoom=2.5;w.cursor=1.5;w.selectedClipId='clip';w.view={zoom:2};w.selection=['clip'];
  assert.equal(await snapshot(),original);
  w.output.width=720;assert.notEqual(await snapshot(),original);
  w.output.width=360;w.tracks[0].clips[0].x=120;assert.notEqual(await snapshot(),original);
});
function lifecycle(initialState='PREPARING'){
  const calls=[],events={};let state=initialState;
  const window={addEventListener:(name,fn)=>events[name]=fn,CreatorBackend:{escape:s=>s,projectId:'project',requireConnection:async()=>{},canonical:JSON.stringify,request:async(path,options)=>{calls.push({path,options});if(options?.data.action==='enqueue')state='QUEUED';return {id:'task',state};}}};
  vm.runInNewContext(fs.readFileSync(require.resolve('./creator-jobs.js'),'utf8'),{window,crypto:{randomUUID:()=> 'task'}});
  return {jobs:window.CreatorJobs,calls,hide:()=>events.pagehide()};
}
test('page closure marks unfinished browser preparation with a keepalive request',async()=>{
  const {jobs,calls,hide}=lifecycle();await jobs.begin('CONTENT_RENDER',{});hide();
  assert.equal(calls.length,2);assert.equal(calls[1].path,'/tasks/task/action');assert.equal(calls[1].options.keepalive,true);assert.equal(calls[1].options.data.action,'fail');assert.match(calls[1].options.data.message,/页面已关闭/);
  hide();assert.equal(calls.length,2);
});
test('page closure does not interrupt queued, running or completed backend work',async()=>{
  const first=lifecycle();await first.jobs.begin('CONTENT_RENDER',{});await first.jobs.action('task','enqueue',{payloadMediaId:'input'});first.hide();assert.equal(first.calls.length,2);
  for(const state of ['QUEUED','RUNNING','SUCCEEDED']){const x=lifecycle(state);await x.jobs.begin('CONTENT_RENDER',{});x.hide();assert.equal(x.calls.length,1);}
});
