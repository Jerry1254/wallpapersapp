/* Self-hosted distribution. The browser never receives platform cookies. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id), esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={xhs:'小红书',douyin:'抖音'}, labels={queued:'等待执行',running:'处理中',submitting:'正在提交',submitted:'已提交 · 待确认',published:'已确认发布',failed:'未完成',uncertain:'结果待核对',needs_input:'需要处理',cancelled:'已取消',ready:'已登录',expired:'登录失效',disconnected:'未登录'};
  let hooks, status={}, accounts=[], view='compose', poll, fetching=false, submitting=false, loginEpoch=0, autoLink=true;
  const connectionPreference='qingjing-publish-autolink';
  try{autoLink=localStorage.getItem(connectionPreference)!=='off';}catch{}
  function setAutoLink(enabled){autoLink=enabled;try{localStorage.setItem(connectionPreference,enabled?'on':'off');}catch{}}
  const uploads=new Map();
  const Core=window.DistributionCore;
  labels.unverified='待核对身份';
  const accountName=a=>a.nickname||a.name;
  const identityText=a=>a.platformUserId?`${a.platformUserId.startsWith('handle:')?'账号':'ID'}：${a.platformUserId.split(':').slice(1).join(':')}`:'尚未读取平台身份';
  const avatar=a=>`<span class="account-avatar">${a.avatarUrl?.startsWith('https://')?`<img src="${esc(a.avatarUrl)}" alt="" referrerpolicy="no-referrer">`:a.platform==='xhs'?'小':'抖'}</span>`;
  async function request(route,options={}) {
    const headers={'X-Creator-Request':'1',...options.headers};
    if(options.body && !(options.body instanceof Blob)){headers['Content-Type']='application/json';options.body=JSON.stringify(options.body);}
    const response=await fetch('/creator-studio/api/distribution'+route,{...options,headers,credentials:'same-origin'});
    let data;try{data=await response.json();}catch{throw new Error('发布助手未启动，请重新启动本地创作台服务');}
    if(!response.ok){const error=new Error(data.error||data.message||'操作失败');error.status=response.status;throw error;}return data;
  }
  const safe=fn=>async(...args)=>{try{return await fn(...args);}catch(e){hooks.toast(e.message);}};
  const date=v=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—';
  function init(h){
    hooks=h;
    const bar=document.createElement('div');bar.className='distribution-nav';bar.innerHTML=`<nav aria-label="内容分发"><button data-distribution-view="compose" class="active">发布内容</button><button data-distribution-view="batch">批量发布</button><button data-distribution-view="accounts">账号管理</button><button data-distribution-view="jobs">发布任务</button><button data-distribution-view="data">数据概览</button></nav><span id="distribution-status">尚未连接发布助手</span><button id="distribution-connect">连接后台</button>`;
    $('publish-workspace').prepend(bar);
    const pane=document.createElement('section');pane.id='distribution-pane';pane.className='distribution-pane';pane.hidden=true;$('publish-workspace').append(pane);
    bar.querySelectorAll('[data-distribution-view]').forEach(b=>b.onclick=()=>open(b.dataset.distributionView));
    $('distribution-connect').onclick=connection;
    $('dialog').addEventListener('cancel',event=>{if(submitting)event.preventDefault();});
    const fields=document.createElement('div');fields.id='distribution-fields';$('download-copy').before(fields);
    const target=document.createElement('div');target.id='distribution-edit-target';target.className='distribution-edit-target';document.querySelector('.post-form .section-title').after(target);
    poll=setInterval(()=>{if(hooks.state().workspace==='publish')refresh();},6000);
    window.DistributionBatch.init({state:hooks.state,projectId:hooks.projectId,accounts:()=>accounts,status:()=>status,modal:hooks.modal,close:hooks.close,toast:hooks.toast,save:hooks.save,validate,preview,render:()=>{renderAccounts();renderPane();}});
    window.DistributionConsole.init({accounts:()=>accounts,status:()=>status,request,newAccount,login,bulk,refresh,isView:next=>view===next,openSource:hooks.openSource,modal:hooks.modal,close:hooks.close,toast:hooks.toast});
    renderAccounts();
  }
  async function refresh(){
    if(fetching)return;fetching=true;
    try {
      status=await request('/status');
      if(!status.connected&&autoLink&&window.CreatorBackend?.connected){await window.CreatorBackend.request('/publish-session',{method:'POST',data:{}});status=await request('/status');}
      if(status.connected)accounts=await request('/accounts');
      else {accounts=[];uploads.clear();}
      const previous=Core.currentPost(hooks.state());hooks.state().accounts=accounts;
      $('distribution-status').textContent=status.connected?(status.preparing?'正在准备助手…':status.ready?'发布助手在线':'助手尚未准备'): '尚未连接后台';
      $('distribution-connect').textContent=status.connected?'连接设置':'连接后台';
      renderAccounts();if(view!=='compose'&&!$('distribution-pane').querySelector('input:focus,select:focus'))renderPane();
      if(view==='compose'&&previous!==Core.currentPost(hooks.state()))hooks.render();
    }catch(e){$('distribution-status').textContent=e.message;}
    finally{fetching=false;}
  }
  function enter(){refresh();}
  function open(next){view=next;document.querySelector('#publish-workspace .publish-columns').hidden=next!=='compose';$('distribution-pane').hidden=next==='compose';document.querySelectorAll('[data-distribution-view]').forEach(b=>b.classList.toggle('active',b.dataset.distributionView===next));if(next!=='compose')renderPane();refresh();}
  function renderAccounts(){
    if(!hooks)return;
    const state=hooks.state();state.accounts=accounts;
    $('account-list').innerHTML=accounts.length?accounts.map(a=>`<label class="account-row">${avatar(a)}<span class="account-copy"><strong>${esc(accountName(a))}</strong><span>${names[a.platform]} · ${esc(a.name)}<br><em class="distribution-state ${a.status}">${labels[a.status]||a.status}${a.runnerId!==status.runnerId?' · 其他电脑':''}</em></span></span><input type="checkbox" data-distribution-account="${a.id}" aria-label="选择${esc(accountName(a))}" ${state.selectedAccounts.has(a.id)?'checked':''} ${a.status!=='ready'||!a.platformUserId||a.runnerId!==status.runnerId?'disabled':''}></label>`).join(''):`<div class="distribution-empty">${status.connected?'添加账号并扫码登录后，即可发布。':'连接本地后台后管理发布账号。'}<button id="distribution-empty-connect">${status.connected?'添加账号':'连接后台'}</button></div>`;
    $('account-count').textContent=accounts.length;
    document.querySelectorAll('[data-distribution-account]').forEach(el=>el.onchange=()=>{el.checked?state.selectedAccounts.add(el.dataset.distributionAccount):state.selectedAccounts.delete(el.dataset.distributionAccount);if(!state.selectedAccounts.has(state.editAccountId))state.editAccountId='';hooks.save();hooks.render();});
    if($('distribution-empty-connect'))$('distribution-empty-connect').onclick=()=>status.connected?newAccount():connection();
    const selected=accounts.filter(a=>state.selectedAccounts.has(a.id)&&a.status==='ready'&&a.platformUserId&&a.runnerId===status.runnerId);
    $('selected-count').textContent=`已选 ${selected.length} 个账号`;
    $('platform-summary').textContent=`小红书 ${selected.filter(a=>a.platform==='xhs').length} · 抖音 ${selected.filter(a=>a.platform==='douyin').length}`;
    $('publish-preview-btn').textContent=state.submissionPending?'继续确认上次清单':'预览发布清单';
    $('publish-preview-btn').disabled=!selected.length||!status.ready||submitting;
    renderTarget();
  }
  function renderTarget(){
    const state=hooks.state(),target=$('distribution-edit-target');if(!target)return;
    if(target.contains(document.activeElement))return;
    const selected=accounts.filter(a=>a.platform===state.social&&state.selectedAccounts.has(a.id));
    if(!selected.some(a=>a.id===state.editAccountId))state.editAccountId='';
    target.innerHTML=`<label for="distribution-editor-account">正在编辑</label><select id="distribution-editor-account"><option value="">${names[state.social]} · 统一设置</option>${selected.map(a=>`<option value="${a.id}">${esc(accountName(a))} · ${state.accountPosts?.[a.id]?'独立设置':'跟随统一设置'}</option>`).join('')}</select><p class="hint">${state.editAccountId?'当前修改只用于这个账号。':'统一设置用于尚未单独调整的账号。选择账号可单独修改。'}</p>${state.editAccountId?'<button id="distribution-reset-account">恢复跟随统一设置</button>':''}`;
    $('distribution-editor-account').value=state.editAccountId;
    $('distribution-editor-account').onchange=e=>{const a=selected.find(a=>a.id===e.target.value);if(a)Core.customize(state,a);else state.editAccountId='';e.target.blur();hooks.save();hooks.render();};
    if($('distribution-reset-account'))$('distribution-reset-account').onclick=()=>{delete state.accountPosts[state.editAccountId];state.editAccountId='';hooks.save();hooks.render();};
  }
  function fields(){
    if(!hooks||!$('distribution-fields'))return;
    const state=hooks.state(),post=Core.currentPost(state),platform=state.social;
    const imageOptions=state.postAssets.filter(a=>a.type==='image'&&!a.demo&&a.file).map(a=>`<option value="${a.id}">${esc(a.name)}</option>`).join('');
    const selected=post.assetIds.map(id=>state.postAssets.find(a=>a.id===id)).filter(Boolean);
    $('distribution-fields').innerHTML=`<div class="distribution-parameters">${state.submissionPending?'<p class="distribution-error">上次确认的清单等待创建；继续确认会使用已固定的内容，当前修改用于之后的新清单。</p>':''}<p class="hint">${names[platform]}${post.type==='video'?'视频':'图文'} · 标题最多 ${platform==='douyin'&&post.type==='video'?30:20} 字，话题最多 10 个。当前助手正文与话题合计支持 1000 字；图片每张 32 MB，视频 2 GB。</p>${post.type==='image'?`<label>图片顺序 · 第一张作为封面</label><ol class="distribution-order">${selected.map((a,i)=>`<li><span>${i+1}. ${esc(a.name)}</span><button data-order="${i}" data-step="-1" ${i===0?'disabled':''} aria-label="前移${esc(a.name)}">↑</button><button data-order="${i}" data-step="1" ${i===selected.length-1?'disabled':''} aria-label="后移${esc(a.name)}">↓</button></li>`).join('')}</ol>`:`<div class="field"><label for="distribution-cover">${platform==='douyin'?'竖版封面':'视频封面'}</label><select id="distribution-cover"><option value="">由平台选取</option>${imageOptions}</select></div>${platform==='douyin'?`<div class="field"><label for="distribution-landscape">横版封面</label><select id="distribution-landscape"><option value="">由平台选取</option>${imageOptions}</select></div>`:''}<p class="hint">需要自定义封面时，先将封面图片加入左侧素材。</p>`}${Core.settingFields(platform,post.type).map(f=>`<div class="field"><label for="distribution-${f.key}">${f.label}</label><select id="distribution-${f.key}">${f.options.map(([v,label])=>`<option value="${esc(v)}">${esc(label)}</option>`).join('')}</select></div>`).join('')}<p class="hint">所选设置会在提交前核对；平台不支持时会停止并提示处理。</p></div>`;
    for(const [id,key] of [['distribution-cover','coverAssetId'],['distribution-landscape','landscapeCoverAssetId']])if($(id)){ $(id).value=post[key]||'';$(id).onchange=()=>{post[key]=$(id).value;hooks.save();};}
    for(const f of Core.settingFields(platform,post.type)){const el=$('distribution-'+f.key);el.value=post[f.key]??f.fallback;el.onchange=()=>{post[f.key]=el.value;hooks.save();};}
    $('distribution-fields').querySelectorAll('[data-order]').forEach(b=>b.onclick=()=>{const i=Number(b.dataset.order),j=i+Number(b.dataset.step);[post.assetIds[i],post.assetIds[j]]=[post.assetIds[j],post.assetIds[i]];hooks.save();hooks.render();});
  }
  function connection(){
    if(!status.connected&&window.CreatorBackend?.connected){safe(async()=>{await window.CreatorBackend.request('/publish-session',{method:'POST',data:{}});setAutoLink(true);await refresh();if(status.connected)connection();})();return;}
    if(status.connected){
      hooks.modal('发布助手',`<p>后台已连接，账号登录信息只保存在这台电脑。</p><p id="distribution-setup-state">${esc(status.message||(status.ready?'助手已准备好':'首次使用需要准备独立浏览器环境'))}</p><p class="hint">定时任务到点开始上传。请保持本地后台、创作台服务和电脑在线；关闭网页不会取消已创建的任务。</p>`,[{label:'关闭',run:hooks.close},{label:'断开连接',run:safe(async()=>{setAutoLink(false);await request('/disconnect',{method:'POST'});hooks.close();await refresh();})},{label:status.ready?'重新准备助手':'准备发布助手',primary:true,run:safe(async()=>{await request('/prepare',{method:'POST'});hooks.close();hooks.toast('正在准备发布环境，可稍后从连接设置查看进度');await refresh();})}]);return;
    }
    hooks.modal('连接本地管理后台',`<p class="dialog-intro">使用本项目管理后台账号。连接后即可添加抖音、小红书账号；无需蚁小二 API。</p><form id="distribution-auth"><div class="field"><label for="distribution-user">管理员用户名</label><input id="distribution-user" autocomplete="username" required></div><div class="field"><label for="distribution-password">管理员密码</label><input id="distribution-password" type="password" autocomplete="current-password" required></div><p id="distribution-auth-error" class="distribution-error" role="alert"></p></form>`,[{label:'取消',run:hooks.close},{label:'连接',primary:true,run:connect}]);
    $('distribution-auth').onsubmit=e=>{e.preventDefault();connect();};
  }
  async function connect(){
    const error=$('distribution-auth-error');if(!error)return;
    error.textContent='正在连接…';
    try{await request('/connect',{method:'POST',body:{username:$('distribution-user').value.trim(),password:$('distribution-password').value}});setAutoLink(true);hooks.close();await refresh();if(!status.ready)connection();}
    catch(e){error.textContent=e.message;}
  }
  function newAccount(existing){
    if(!status.connected){connection();return;}
    hooks.modal(existing?'编辑账号':'添加账号',`<div class="field"><label for="distribution-account-name">账号备注名</label><input id="distribution-account-name" maxlength="80" value="${esc(existing?.name||'')}" placeholder="例如：倾境 · 每日壁纸"></div><div class="field"><label for="distribution-account-platform">平台</label><select id="distribution-account-platform" ${existing?'disabled':''}><option value="xhs">小红书</option><option value="douyin">抖音</option></select></div><div class="field"><label for="distribution-account-group">分组</label><input id="distribution-account-group" maxlength="80" value="${esc(existing?.group||'')}" placeholder="例如：壁纸主账号"></div><p class="hint">每个账号单独扫码。登录后自动读取平台身份，备注名仅用于自己区分账号。</p>`,[{label:'取消',run:hooks.close},{label:existing?'保存':'添加并登录',primary:true,run:safe(async()=>{
      const body={name:$('distribution-account-name').value.trim(),group:$('distribution-account-group').value.trim(),platform:$('distribution-account-platform').value};if(!body.name)throw new Error('请填写账号备注名');
      const a=await request('/accounts'+(existing?'/'+existing.id:''),{method:existing?'PUT':'POST',body});hooks.close();await refresh();open('accounts');if(!existing&&status.ready)await login(a,'login');else if(!status.ready)connection();
    })}]);if(existing)$('distribution-account-platform').value=existing.platform;
  }
  async function login(account,mode){
    const result=await request(`/accounts/${account.id}/${mode}`,{method:'POST'});
    monitorOperation(result,mode==='login'?`登录${names[account.platform]} · ${account.name}`:mode==='analytics'?'同步平台累计指标':'检查登录状态');
  }
  async function bulk(ids,mode){
    const result=await request('/accounts/operations',{method:'POST',body:{accountIds:ids,mode}});
    monitorOperation(result,mode==='analytics'?'批量同步账号数据':'批量检查账号');
  }
  function monitorOperation(result,title){
    const epoch=++loginEpoch;
    hooks.modal(title,`<div class="distribution-login"><p id="distribution-login-message">正在处理账号…</p><img id="distribution-qr" alt="平台登录二维码" hidden><img id="distribution-login-diagnostic" alt="账号身份核对失败时的平台页面" hidden style="width:100%;max-height:65vh;object-fit:contain"><p class="hint">如平台要求验证，请在打开的浏览器窗口完成。关闭此弹窗后，当前操作仍会继续。</p><ol id="distribution-operation-results" class="console-operation-results"></ol></div>`,[{label:'关闭',run:()=>{loginEpoch++;hooks.close();}}]);
    const check=async()=>{
      if(epoch!==loginEpoch||!$('distribution-login-message'))return;
      try{
        const op=await request('/operations/'+result.operationId);if(epoch!==loginEpoch||!$('distribution-login-message'))return;
        $('distribution-login-message').textContent=op.message;
        $('distribution-qr').hidden=!op.qr;if(op.qr)$('distribution-qr').src=op.qr;
        const diagnostic=$('distribution-login-diagnostic'),image=op.diagnostic;
        diagnostic.hidden=!(typeof image==='string'&&image.startsWith('data:image/jpeg;base64,'));if(!diagnostic.hidden)diagnostic.src=image;
        $('distribution-operation-results').innerHTML=(op.results||[]).map(r=>`<li><strong>${esc(r.name)}</strong><span class="distribution-state ${r.status}">${r.status==='ready'?'完成':'需要处理'}</span><p>${esc(r.message)}</p></li>`).join('');
        if(op.status==='running')setTimeout(check,1500);else{$('distribution-qr').hidden=true;await refresh();hooks.toast(op.message);}
      }catch(e){hooks.toast(e.message);}
    };check();
  }
  function renderPane(){
    const pane=$('distribution-pane');if(view==='compose')return;
    if(!status.connected){pane.innerHTML='<div class="distribution-empty"><h2>连接后台后开始分发</h2><p>账号、发布任务和结果集中保存在本地后台。</p><button id="distribution-pane-connect" class="primary">连接后台</button></div>';$('distribution-pane-connect').onclick=connection;return;}
    if(view==='batch'){window.DistributionBatch.render(pane);return;}
    window.DistributionConsole.render(pane,view);
  }
  function validate(platform,post,state){
    const title=post.title.trim(),body=post.body.trim(),tags=post.tags.split(/[\s#]+/u).filter(Boolean),assets=post.assetIds.map(id=>state.postAssets.find(a=>a.id===id));
    const max=platform==='douyin'&&post.type==='video'?30:20;
    if(!title||[...title].length>max)throw new Error(`${names[platform]}标题需为 1–${max} 字，不会自动截断`);
    if([...body].length>1000)throw new Error(`${names[platform]}正文当前支持 1000 字，请精简后发布`);
    if(new Set(tags).size!==tags.length)throw new Error('话题不能重复，请调整后发布');
    if([...body].length+tags.reduce((n,t)=>n+[...t].length+3,0)>1000)throw new Error(`${names[platform]}正文与话题合计超过当前助手支持的 1000 字`);
    if(tags.length>10||tags.some(t=>[...t].length>50))throw new Error(`${names[platform]}最多 10 个话题，单个不超过 50 字`);
    if(!assets.length||assets.some(a=>!a?.file||a.demo||a.type!==post.type))throw new Error(`${names[platform]}请选择真实的${post.type==='video'?'视频':'图片'}素材`);
    if(assets.some(a=>a.contentItemId&&!state.content.items.some(w=>w.id===a.contentItemId&&!w.deletedAt)))throw new Error('来源作品已不存在或已移入回收站，请先恢复作品');
    if(assets.length>(post.type==='video'?1:platform==='douyin'?35:18))throw new Error(`${names[platform]}素材数量超过当前助手支持范围`);
    for(const a of assets)if(a.file.size>(a.type==='image'?32:2048)*1024*1024)throw new Error(`“${a.name}”超过当前发布助手支持的大小`);
    const coverIds=post.type==='video'?[post.coverAssetId,platform==='douyin'?post.landscapeCoverAssetId:null].filter(Boolean):[];
    for(const id of coverIds){const a=state.postAssets.find(a=>a.id===id);if(!a?.file||a.type!=='image'||a.file.size>32*1024*1024)throw new Error('封面图片不可用，请重新选择');}
    return {title,body,tags,type:post.type,assets,cover:post.type==='video'?state.postAssets.find(a=>a.id===post.coverAssetId):null,landscape:post.type==='video'&&platform==='douyin'?state.postAssets.find(a=>a.id===post.landscapeCoverAssetId):null,...Core.settings(platform,post)};
  }
  async function upload(asset){
    if(!asset)return '';
    const key=asset.file;if(uploads.has(key))return uploads.get(key);
    const workspaceMediaId=await window.CreatorBackend.hash(asset.file);
    const value=await request('/media/reuse',{method:'POST',body:{workspaceMediaId}});uploads.set(key,value.id);return value.id;
  }
  function reviewHTML(cards){
    return cards.map(c=>`<div class="distribution-preview"><strong>${esc(c.accountName)} · ${names[c.platform]} · ${c.post.type==='video'?'视频':'图文'}</strong><small>${esc(c.identity)} · ${esc(c.editing)}</small><p>来源：${c.sourceNames.map(esc).join('、')}</p><h3>${esc(c.post.title)}</h3><p class="distribution-body">${esc(c.post.body)}</p><p>${c.post.tags.map(t=>'#'+esc(t)).join(' ')}</p><small>${c.assetCount} 个素材 · ${c.coverName?'自定义封面：'+esc(c.coverName):c.post.type==='image'?'首图为封面':'平台选取封面'}${c.landscapeName?' · 横封面：'+esc(c.landscapeName):''} · ${esc(Core.settingsSummary(c.platform,c.post))}</small></div>`).join('');
  }
  async function preview(rows){
    if(submitting)return;
    try{
      await hooks.prepare();await refresh();const state=hooks.state(),projectId=hooks.projectId(),existing=state.submissionPending;
      if(!status.connected||!status.ready)throw new Error('请先连接后台并准备发布助手');
      let targets=[],cards=[],scheduledAt='',payload=existing?.payload||null;
      const rowIds=existing?.rowIds|| (Array.isArray(rows)?rows.map(r=>r.id):[]);
      if(existing){cards=existing.cards;scheduledAt=payload.scheduledAt;}
      else {
        const inputs=Array.isArray(rows)?rows.map(row=>({row,account:accounts.find(a=>a.id===row.accountId),post:row.post})):accounts.filter(a=>state.selectedAccounts.has(a.id)).map(a=>({account:a,post:Core.accountPost(state,a)}));
        if(!inputs.length||inputs.length>50)throw new Error('请选择 1–50 条发布内容');
        if(inputs.some(({account:a})=>!a||a.status!=='ready'||!a.platformUserId||a.runnerId!==status.runnerId))throw new Error('所选账号需要在本机重新登录并核对身份');
        targets=inputs.map(({account,row,post})=>({account,row,post:validate(account.platform,post,state),source:Core.source(state,projectId,post)}));
        cards=targets.map(({account:a,row,post:p,source})=>({accountId:a.id,accountName:accountName(a),platform:a.platform,identity:identityText(a),editing:row?'清单独立设置':state.accountPosts?.[a.id]?'独立设置':'统一设置',sourceNames:source.names,assetCount:p.assets.length,coverName:p.cover?.name,landscapeName:p.landscape?.name,post:{title:p.title,body:p.body,tags:p.tags,type:p.type,...Core.settings(a.platform,p)}}));
        if(state.timing==='scheduled'){const value=new Date(state.scheduledAt);if(!Number.isFinite(value.getTime())||value.getTime()<=Date.now())throw new Error('请选择未来的定时开始时间');scheduledAt=value.toISOString();}
      }
      const batchId=payload?.id||crypto.randomUUID();
      hooks.modal('确认发布清单',`<p class="dialog-intro">${cards.length} 条任务 · ${new Set(cards.map(c=>c.accountId)).size} 个账号 · ${scheduledAt?'定时开始：'+esc(date(scheduledAt)):'立即开始上传'}</p>${existing?'<p class="hint">继续创建上次已确认的同一批次，内容已固定。</p>':''}${reviewHTML(cards)}<p class="hint">确认后会向平台提交这些内容。定时任务到点开始上传，请保持电脑和本地服务在线。平台审核结果在任务页核对。</p><p id="distribution-submit-status" role="status"></p>`,[{label:'返回',run:()=>{if(!submitting)hooks.close();}},{label:existing?'继续创建同一批次':scheduledAt?'确认创建定时任务':'确认发布',primary:true,run:async()=>{
        if(submitting)return;submitting=true;$('dialog-close').disabled=true;renderAccounts();const label=$('distribution-submit-status');label.textContent='正在保存发布清单，请勿关闭窗口…';let created=false;
        try{
          if(hooks.projectId()!==projectId)throw new Error('项目已切换，请重新打开原项目的待确认清单');
          if(state.submissionPending&&state.submissionPending.payload.id!==batchId)throw new Error('已有其他清单等待确认，请重新打开');
          if(!payload){const entries=[];for(const {account:a,post:p,source} of targets){const mediaIds=[];for(const asset of p.assets)mediaIds.push(await upload(asset));entries.push({accountId:a.id,post:{title:p.title,body:p.body,tags:p.tags,type:p.type,mediaIds,coverId:await upload(p.cover),landscapeCoverId:await upload(p.landscape),...Core.settings(a.platform,p),source:{projectId,assetIds:source.assetIds}}});}payload={id:batchId,scheduledAt,entries};}
          if(hooks.projectId()!==projectId)throw new Error('项目已切换，请重新预览发布');
          const pending={payload,cards,rowIds};state.submissionPending=pending;hooks.save();await hooks.prepare();
          if(hooks.projectId()!==projectId)throw new Error('项目已切换，清单已保留在原项目，请在那里继续确认');
          await request('/batches',{method:'POST',body:payload});created=true;
          if(hooks.projectId()===projectId){Core.finishSubmission(state,pending);hooks.save();await hooks.prepare();}
          hooks.close();hooks.toast(`已创建 ${cards.length} 条发布任务`);open('jobs');
        }catch(e){
          if(e.status===422&&hooks.projectId()===projectId&&state.submissionPending?.payload.id===batchId){state.submissionPending=null;hooks.save();}
          const message=created?'任务已创建，清单状态尚未保存，请重试保存项目':e.status===422?e.message+'。请返回编辑并重新预览。':e.message+'。继续确认会使用同一批次，不会重复创建。';
          if(e.status===422)$('dialog-actions').querySelector('.primary').disabled=true;
          if(label.isConnected)label.textContent=message;else hooks.toast(message);
        }finally{submitting=false;$('dialog-close').disabled=false;renderAccounts();}
      }}]);
    }catch(e){hooks.toast(e.message);}
  }
  window.Distribution={init,enter,open,fields,renderAccounts,preview,manage:()=>open('accounts')};
})();
