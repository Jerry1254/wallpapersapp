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
  const declarations={
    douyin:['内容由AI生成','可能引人不适','虚构演绎，仅供娱乐','危险行为，请勿模仿','内容为个人观点或见解','内容含营销推广信息'],
    xhs:['虚构演绎，仅供娱乐','笔记含AI合成内容']
  };
  function settingFields(platform,type){
    const fields=[
      {key:'visibility',label:'谁可以看',fallback:'public',options:[['public','公开'],['private','仅自己可见'],['friends','好友可见']]},
      {key:'declaration',label:'内容声明',fallback:'',options:[['','不预设声明'],...declarations[platform].map(v=>[v,v])]}
    ];
    if(platform==='xhs')fields.push({key:'originality',label:'原创设置',fallback:'',options:[['','不预设原创声明'],['original','声明原创'],['not_original','不声明原创']]});
    if(platform==='douyin'&&type==='video')fields.push({key:'downloadPermission',label:'允许保存视频',fallback:'',options:[['','跟随平台默认'],['allow','允许'],['deny','不允许']]});
    return fields;
  }
  function settings(platform,post){
    return Object.fromEntries(settingFields(platform,post.type).map(f=>{
      const value=post[f.key]??f.fallback;
      if(!f.options.some(([v])=>v===value))throw new Error(`${f.label}的选项已变化，请重新选择`);
      return [f.key,value];
    }));
  }
  function settingsSummary(platform,post){
    return settingFields(platform,post.type).map(f=>{
      const value=post[f.key];
      const label=value===undefined?(f.key==='visibility'?'跟随平台默认':f.options.find(([v])=>v===f.fallback)[1]):f.options.find(([v])=>v===value)?.[1]||'未知设置';
      return `${f.label}：${label}`;
    }).join(' · ');
  }
  const api={accountPost,currentPost,customize,source,settingFields,settings,settingsSummary};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.DistributionCore=api;
})(typeof window==='object'?window:globalThis);
