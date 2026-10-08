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
    if(platform==='xhs')fields.push({key:'originality',label:'原创设置',fallback:'original',options:[['original','声明原创'],['not_original','不声明原创'],['','不预设原创声明']]});
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
  function contentChoices(state){
    const assets=new Map(state.postAssets.map(a=>[a.id,a]));
    const usable=a=>a?.file&&!a.demo&&['image','video'].includes(a.type);
    const works=(state.content?.items||[]).filter(w=>!w.deletedAt).map(w=>{
      const type=w.type==='gallery'?'image':'video',ids=w.assetIds||[];
      return {id:'work:'+w.id,name:w.name,type,assetIds:[...ids],available:ids.length>0&&ids.every(id=>usable(assets.get(id))&&assets.get(id).type===type)};
    });
    const imported=state.postAssets.filter(a=>!a.contentItemId&&!a.contentDraftOnly&&usable(a)).map(a=>({id:'asset:'+a.id,name:a.name,type:a.type,assetIds:[a.id],available:true}));
    const post=currentPost(state),ids=post.assetIds||[];
    if(post.type==='image'&&ids.length>1&&ids.every(id=>usable(assets.get(id))&&!assets.get(id).contentItemId))imported.unshift({id:'selection:'+JSON.stringify(ids),name:'当前图文组合',type:'image',assetIds:[...ids],available:true});
    return [...works,...imported];
  }
  const rowKey=row=>JSON.stringify([row.accountId,row.post.type,row.post.assetIds]);
  function buildBatch(state,contents,accounts,mode,id){
    if(!['all','sequential'].includes(mode)||!contents.length||!accounts.length)throw new Error('请选择作品、账号和分配方式');
    if(contents.some(w=>!w.available))throw new Error('部分作品文件未完整读取，请恢复文件后再选择');
    if(new Set(contents.map(w=>w.id)).size!==contents.length||new Set(accounts.map(a=>a.id)).size!==accounts.length)throw new Error('作品和账号不能重复选择');
    const pairs=mode==='all'||contents.length===1?contents.flatMap(w=>accounts.map(a=>[w,a])):contents.map((w,i)=>[w,accounts[i%accounts.length]]);
    if(pairs.length>50)throw new Error('单批最多 50 条发布任务，请减少作品或账号');
    const rows=pairs.map(([w,a])=>{const post=copy(accountPost(state,a));post.type=w.type;post.assetIds=[...w.assetIds];post.title=post.title||w.name;return {id:id(),accountId:a.id,contentId:w.id,contentName:w.name,post};});
    if(new Set(rows.map(rowKey)).size!==rows.length)throw new Error('同一账号不能重复加入相同素材');
    return rows;
  }
  function mergeBatch(previous,rows,append){
    const merged=append?[...previous,...rows]:rows;
    if(merged.length>50)throw new Error('单批最多 50 条发布任务');
    if(new Set(merged.map(rowKey)).size!==merged.length)throw new Error('清单已有相同账号与素材，请取消重复选择');
    return merged;
  }
  function finishSubmission(state,pending){
    if(state.submissionPending?.payload.id!==pending.payload.id)return false;
    const ids=new Set(pending.rowIds||[]);
    if(state.batchPlan)state.batchPlan.rows=state.batchPlan.rows.filter(row=>!ids.has(row.id));
    state.submissionPending=null;
    return true;
  }
  function publicationSummary(work){
    const counts={published:0,failed:0,needs_input:0,queued:0,running:0,submitting:0,submitted:0,uncertain:0,cancelled:0,...work.counts};
    const pending=counts.queued+counts.running+counts.submitting,awaiting=counts.submitted+counts.uncertain;
    let status='queued',label='等待发布';
    if(counts.running+counts.submitting){status='running';label='发布中';}
    else if(pending){status='queued';label='等待发布';}
    else if(awaiting){status='uncertain';label='待核对结果';}
    else if(counts.needs_input){status='needs_input';label='需要处理';}
    else if(counts.failed){status='failed';label=counts.published?'部分成功':'发布失败';}
    else if(counts.published){status='published';label=counts.published===work.taskCount?'全部成功':'发布完成';}
    else if(counts.cancelled){status='cancelled';label='已取消';}
    const parts=[[counts.published,'成功'],[counts.failed,'失败'],[counts.needs_input,'需处理'],[pending,'等待 / 处理中'],[awaiting,'待核对'],[counts.cancelled,'取消']].filter(([n])=>n).map(([n,text])=>`${n} ${text}`);
    return {status,label,detail:parts.join(' · ')};
  }
  const api={accountPost,currentPost,customize,source,settingFields,settings,settingsSummary,contentChoices,rowKey,buildBatch,mergeBatch,finishSubmission,publicationSummary};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.DistributionCore=api;
})(typeof window==='object'?window:globalThis);
