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
