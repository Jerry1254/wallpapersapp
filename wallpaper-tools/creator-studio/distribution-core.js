/* Account-specific publication settings belong to the project, without a draft workflow. */
(function(root){
  'use strict';
  const copy=value=>JSON.parse(JSON.stringify(value));
  function accountPost(state,account){return state.accountPosts?.[account.id]||state.posts[account.platform];}
  function currentPost(state){
    const account=state.accounts.find(a=>a.id===state.editAccountId&&a.platform===state.social&&state.selectedAccounts.has(a.id));
    return account?accountPost(state,account):state.posts[state.social];
  }
  function customize(state,account){
    state.accountPosts||={};
    if(!state.accountPosts[account.id])state.accountPosts[account.id]=copy(state.posts[account.platform]);
    state.editAccountId=account.id;state.social=account.platform;
    return state.accountPosts[account.id];
  }
  function source(state,projectId,post){
    const assets=post.assetIds.map(id=>state.postAssets.find(a=>a.id===id)).filter(Boolean);
    return {projectId,assetIds:assets.map(a=>a.id),names:[...new Set(assets.map(a=>state.content.items.find(w=>w.id===a.contentItemId)?.name||a.name))]};
  }
  const api={accountPost,currentPost,customize,source};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.DistributionCore=api;
})(typeof window==='object'?window:globalThis);
