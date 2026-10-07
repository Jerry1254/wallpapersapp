const test=require('node:test');
const assert=require('node:assert/strict');
const Core=require('./distribution-core.js');
function fixture(){return {social:'xhs',accounts:[{id:'a',platform:'xhs'},{id:'b',platform:'xhs'},{id:'c',platform:'douyin'}],selectedAccounts:new Set(['a','b','c']),accountPosts:{},posts:{xhs:{title:'共同标题',assetIds:['one','two']},douyin:{title:'视频',assetIds:['video']}},content:{items:[{id:'work',name:'生成作品'}]},postAssets:[{id:'one',name:'第一张',contentItemId:'work'},{id:'two',name:'第二张',contentItemId:'work'}]};}
test('one account can change text and image order without changing other accounts',()=>{
  const s=fixture();Core.customize(s,s.accounts[0]);Core.currentPost(s).title='独立标题';Core.currentPost(s).assetIds.reverse();
  assert.equal(Core.accountPost(s,s.accounts[1]).title,'共同标题');assert.deepEqual(s.posts.xhs.assetIds,['one','two']);
  s.posts.xhs.title='更新统一标题';assert.equal(Core.currentPost(s).title,'独立标题');assert.equal(Core.accountPost(s,s.accounts[1]).title,'更新统一标题');
});
test('account settings survive a project roundtrip, and deselecting cannot edit that account',()=>{
  const s=fixture();Core.customize(s,s.accounts[0]).title='保留标题';
  const restored={...JSON.parse(JSON.stringify(s)),selectedAccounts:new Set(['a','b'])};
  assert.equal(Core.currentPost(restored).title,'保留标题');restored.selectedAccounts.delete('a');assert.equal(Core.currentPost(restored).title,'共同标题');
  restored.social='douyin';assert.equal(Core.currentPost(restored).title,'视频');
});
test('restoring shared settings uses the current shared value; a source preserves selected image order',()=>{
  const s=fixture();Core.customize(s,s.accounts[0]).assetIds.reverse();
  assert.deepEqual(Core.source(s,'project',Core.currentPost(s)),{projectId:'project',assetIds:['two','one'],names:['生成作品']});
  delete s.accountPosts.a;assert.equal(Core.currentPost(s),s.posts.xhs);
});
test('four forms expose only applicable publication settings and retain account choices',()=>{
  for(const platform of ['douyin','xhs'])for(const type of ['image','video']){
    const result=Core.settings(platform,{type});
    assert.equal(result.visibility,'public');assert.equal(result.declaration,'');
    assert.equal('originality' in result,platform==='xhs');
    assert.equal('downloadPermission' in result,platform==='douyin'&&type==='video');
    assert.ok(!JSON.stringify(Core.settingFields(platform,type)).includes('草稿'));
  }
  const s=fixture();s.posts.xhs.type='video';s.posts.xhs.visibility='private';
  const post=Core.customize(s,s.accounts[0]);post.visibility='friends';post.originality='original';post.declaration='笔记含AI合成内容';
  const snapshot=Core.settings('xhs',Core.currentPost(s));post.visibility='public';
  assert.equal(snapshot.visibility,'friends');assert.equal(Core.accountPost(s,s.accounts[1]).visibility,'private');
  assert.match(Core.settingsSummary('xhs',snapshot),/好友可见.*笔记含AI合成内容.*声明原创/);
});
test('unsupported declaration or draft visibility cannot silently become a default',()=>{
  assert.equal(Core.settings('xhs',{type:'image'}).originality,'original');
  assert.equal(Core.settings('xhs',{type:'video'}).originality,'original');
  // Historical tasks retain their actual unset/nonoriginal choice.
  assert.match(Core.settingsSummary('xhs',{type:'image',originality:''}),/不预设原创声明/);
  assert.equal(Core.settings('xhs',{type:'image',originality:'not_original'}).originality,'not_original');
  assert.throws(()=>Core.settings('xhs',{type:'video',declaration:'内容由AI生成'}),/重新选择/);
  assert.throws(()=>Core.settings('douyin',{type:'image',visibility:'draft'}),/重新选择/);
  // Changing video to image omits the inapplicable permission, without forgetting
  // the user's choice when they return to video.
  const post={type:'video',downloadPermission:'deny'};
  assert.equal(Core.settings('douyin',post).downloadPermission,'deny');
  post.type='image';assert.ok(!('downloadPermission' in Core.settings('douyin',post)));
  assert.match(Core.settingsSummary('douyin',{type:'video'}),/谁可以看：跟随平台默认/);
});
test('batch distribution follows ordered selection, repeats accounts, and supports one work for many accounts',()=>{
  const s=fixture();let n=0;const id=()=>String(++n);
  const contents=[1,2,3].map(i=>({id:'work'+i,name:'作品'+i,type:'video',assetIds:['video'+i],available:true}));
  const targets=s.accounts.slice(0,2);
  assert.deepEqual(Core.buildBatch(s,contents,targets,'sequential',id).map(r=>[r.contentId,r.accountId]),[['work1','a'],['work2','b'],['work3','a']]);
  assert.equal(Core.buildBatch(s,contents.slice(0,1),targets,'sequential',id).length,2);
  assert.equal(Core.buildBatch(s,contents,targets,'all',id).length,6);
  const rows=Core.buildBatch(s,contents,targets,'all',id);rows[0].post.title='修改单条';
  assert.notEqual(rows[1].post.title,'修改单条');assert.notEqual(s.posts.xhs.title,'修改单条');
  rows[0].post.assetIds.reverse();assert.deepEqual(contents[0].assetIds,['video1']);
});
test('batch building rejects duplicates and too many tasks without mutating the previous list',()=>{
  const s=fixture(),w={id:'w',name:'作品',type:'image',assetIds:['one','two'],available:true};let n=0;
  const rows=Core.buildBatch(s,[w],s.accounts,'all',()=>String(++n));
  assert.throws(()=>Core.mergeBatch(rows,rows,true),/相同账号与素材/);
  assert.equal(rows.length,3);
  assert.throws(()=>Core.buildBatch(s,Array.from({length:20},(_,i)=>({...w,id:String(i),assetIds:[String(i)]})),s.accounts,'all',()=>String(++n)),/50/);
  assert.throws(()=>Core.buildBatch(s,[{...w,available:false}],s.accounts,'all',()=>String(++n)),/恢复文件/);
});
test('work choices retain the complete gallery and do not expose trash or intermediate files',()=>{
  const s=fixture();s.posts.xhs.type='image';s.content.items.push({id:'missing',name:'缺文件',type:'gallery',assetIds:['none']},{id:'trash',deletedAt:'today',type:'video',assetIds:['video']});
  Object.assign(s.content.items[0],{type:'gallery',assetIds:['two','one']});
  s.postAssets.forEach(a=>a.file={});s.postAssets.push({id:'middle',type:'video',file:{},contentDraftOnly:true});
  const choices=Core.contentChoices(s);
  assert.deepEqual(choices.find(w=>w.id==='work:work').assetIds,['two','one']);
  assert.equal(choices.find(w=>w.id==='work:missing').available,false);
  assert.ok(!choices.some(w=>w.id==='work:trash'||w.id==='asset:middle'||w.id==='asset:one'));
});
test('a saved pending submission keeps the exact batch payload across refreshes and clears only its rows',()=>{
  const s=fixture();s.batchPlan={rows:[{id:'sent'},{id:'later'}]};s.submissionPending={payload:{id:'batch',entries:[{accountId:'a',post:{visibility:'private'}}]},rowIds:['sent'],cards:[]};
  const restored=JSON.parse(JSON.stringify(s)),pending=restored.submissionPending;
  assert.equal(pending.payload.entries[0].post.visibility,'private');
  assert.equal(Core.finishSubmission(restored,{payload:{id:'other'}}),false);
  assert.deepEqual(restored.batchPlan.rows,[{id:'sent'},{id:'later'}]);
  assert.equal(Core.finishSubmission(restored,pending),true);
  assert.equal(restored.submissionPending,null);assert.deepEqual(restored.batchPlan.rows,[{id:'later'}]);
});
