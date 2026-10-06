/* Self-hosted distribution. The browser never receives platform cookies. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id), esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={xhs:'小红书',douyin:'抖音'}, labels={queued:'等待执行',running:'处理中',submitting:'正在提交',submitted:'已提交 · 待确认',published:'已确认发布',failed:'未完成',uncertain:'结果待核对',needs_input:'需要处理',cancelled:'已取消',ready:'已登录',expired:'登录失效',disconnected:'未登录'};
  let hooks, status={}, accounts=[], jobs=[], metrics=[], view='compose', poll, fetching=false, submitting=false, loginEpoch=0;
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
    if(!response.ok)throw new Error(data.error||data.message||'操作失败');return data;
  }
  const safe=fn=>async(...args)=>{try{return await fn(...args);}catch(e){hooks.toast(e.message);}};
  const date=v=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—';
  function init(h){
    hooks=h;
    const bar=document.createElement('div');bar.className='distribution-nav';bar.innerHTML=`<nav aria-label="内容分发"><button data-distribution-view="compose" class="active">发布内容</button><button data-distribution-view="accounts">账号管理</button><button data-distribution-view="jobs">发布任务</button><button data-distribution-view="data">数据概览</button></nav><span id="distribution-status">尚未连接发布助手</span><button id="distribution-connect">连接后台</button>`;
    $('publish-workspace').prepend(bar);
    const pane=document.createElement('section');pane.id='distribution-pane';pane.className='distribution-pane';pane.hidden=true;$('publish-workspace').append(pane);
    bar.querySelectorAll('[data-distribution-view]').forEach(b=>b.onclick=()=>open(b.dataset.distributionView));
    $('distribution-connect').onclick=connection;
    $('dialog').addEventListener('cancel',event=>{if(submitting)event.preventDefault();});
    const fields=document.createElement('div');fields.id='distribution-fields';$('download-copy').before(fields);
    const target=document.createElement('div');target.id='distribution-edit-target';target.className='distribution-edit-target';document.querySelector('.post-form .section-title').after(target);
    poll=setInterval(()=>{if(hooks.state().workspace==='publish')refresh();},6000);
    renderAccounts();
  }
  async function refresh(){
    if(fetching)return;fetching=true;
    try {
      status=await request('/status');
      if(status.connected){[accounts,jobs,metrics]=await Promise.all([request('/accounts'),request('/jobs'),request('/metrics')]);}
      else {accounts=[];jobs=[];metrics=[];uploads.clear();}
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
    $('distribution-fields').innerHTML=`<div class="distribution-parameters"><p class="hint">${names[platform]}${post.type==='video'?'视频':'图文'} · 标题最多 ${platform==='douyin'&&post.type==='video'?30:20} 字，话题最多 10 个。当前助手正文与话题合计支持 1000 字；图片每张 32 MB，视频 2 GB。</p>${post.type==='image'?`<label>图片顺序 · 第一张作为封面</label><ol class="distribution-order">${selected.map((a,i)=>`<li><span>${i+1}. ${esc(a.name)}</span><button data-order="${i}" data-step="-1" ${i===0?'disabled':''} aria-label="前移${esc(a.name)}">↑</button><button data-order="${i}" data-step="1" ${i===selected.length-1?'disabled':''} aria-label="后移${esc(a.name)}">↓</button></li>`).join('')}</ol>`:`<div class="field"><label for="distribution-cover">${platform==='douyin'?'竖版封面':'视频封面'}</label><select id="distribution-cover"><option value="">由平台选取</option>${imageOptions}</select></div>${platform==='douyin'?`<div class="field"><label for="distribution-landscape">横版封面</label><select id="distribution-landscape"><option value="">由平台选取</option>${imageOptions}</select></div><div class="field"><label for="distribution-declaration">内容声明</label><select id="distribution-declaration"><option value="">不预设声明</option><option>内容由AI生成</option><option>内容为个人观点或见解</option></select></div>`:''}<p class="hint">需要自定义封面时，先将封面图片加入左侧素材。</p>`}</div>`;
    for(const [id,key] of [['distribution-cover','coverAssetId'],['distribution-landscape','landscapeCoverAssetId'],['distribution-declaration','declaration']])if($(id)){ $(id).value=post[key]||'';$(id).onchange=()=>{post[key]=$(id).value;hooks.save();};}
    $('distribution-fields').querySelectorAll('[data-order]').forEach(b=>b.onclick=()=>{const i=Number(b.dataset.order),j=i+Number(b.dataset.step);[post.assetIds[i],post.assetIds[j]]=[post.assetIds[j],post.assetIds[i]];hooks.save();hooks.render();});
  }
  function connection(){
    if(status.connected){
      hooks.modal('发布助手',`<p>后台已连接，账号登录信息只保存在这台电脑。</p><p id="distribution-setup-state">${esc(status.message||(status.ready?'助手已准备好':'首次使用需要准备独立浏览器环境'))}</p><p class="hint">定时任务到点开始上传。请保持本地后台、创作台服务和电脑在线；关闭网页不会取消已创建的任务。</p>`,[{label:'关闭',run:hooks.close},{label:'断开连接',run:safe(async()=>{await request('/disconnect',{method:'POST'});hooks.close();await refresh();})},{label:status.ready?'重新准备助手':'准备发布助手',primary:true,run:safe(async()=>{await request('/prepare',{method:'POST'});hooks.close();hooks.toast('正在准备发布环境，可稍后从连接设置查看进度');await refresh();})}]);return;
    }
    hooks.modal('连接本地管理后台',`<p class="dialog-intro">使用本项目管理后台账号。连接后即可添加抖音、小红书账号；无需蚁小二 API。</p><form id="distribution-auth"><div class="field"><label for="distribution-user">管理员用户名</label><input id="distribution-user" autocomplete="username" required></div><div class="field"><label for="distribution-password">管理员密码</label><input id="distribution-password" type="password" autocomplete="current-password" required></div><p id="distribution-auth-error" class="distribution-error" role="alert"></p></form>`,[{label:'取消',run:hooks.close},{label:'连接',primary:true,run:connect}]);
    $('distribution-auth').onsubmit=e=>{e.preventDefault();connect();};
  }
  async function connect(){
    const error=$('distribution-auth-error');if(!error)return;
    error.textContent='正在连接…';
    try{await request('/connect',{method:'POST',body:{username:$('distribution-user').value.trim(),password:$('distribution-password').value}});hooks.close();await refresh();if(!status.ready)connection();}
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
    const result=await request(`/accounts/${account.id}/${mode}`,{method:'POST'}),epoch=++loginEpoch;
    hooks.modal(mode==='login'?`登录${names[account.platform]} · ${account.name}`:mode==='analytics'?'同步平台累计指标':'检查登录状态',`<div class="distribution-login"><p id="distribution-login-message">正在打开浏览器…</p><img id="distribution-qr" alt="平台登录二维码" hidden><p class="hint">如平台要求验证，请在打开的浏览器窗口完成。</p></div>`,[{label:'关闭',run:()=>{loginEpoch++;hooks.close();}}]);
    const check=async()=>{
      if(epoch!==loginEpoch||!$('distribution-login-message'))return;
      try{
        const op=await request('/operations/'+result.operationId);$('distribution-login-message').textContent=op.message;
        $('distribution-qr').hidden=!op.qr;if(op.qr)$('distribution-qr').src=op.qr;
        if(op.status==='running')setTimeout(check,1500);else{$('distribution-qr').hidden=true;await refresh();hooks.toast(op.message);}
      }catch(e){hooks.toast(e.message);}
    };check();
  }
  function renderPane(){
    const pane=$('distribution-pane');if(view==='compose')return;
    if(!status.connected){pane.innerHTML='<div class="distribution-empty"><h2>连接后台后开始分发</h2><p>账号、发布任务和结果集中保存在本地后台。</p><button id="distribution-pane-connect" class="primary">连接后台</button></div>';$('distribution-pane-connect').onclick=connection;return;}
    if(view==='accounts'){
      const oldSearch=$('distribution-search')?.value||'',oldPlatform=$('distribution-filter')?.value||'';
      pane.innerHTML=`<div class="distribution-heading"><div><h2>账号管理</h2><p>账号独立登录，按平台和分组管理。</p></div><button id="distribution-add" class="primary">＋ 添加账号</button></div><div class="distribution-filters"><input id="distribution-search" placeholder="搜索账号或分组" value="${esc(oldSearch)}"><select id="distribution-filter"><option value="">全部平台</option value="xhs">小红书</option><option value="douyin">抖音</option></select></div><div id="distribution-account-grid" class="distribution-account-grid"></div>`;
      $('distribution-filter').value=oldPlatform;$('distribution-add').onclick=()=>newAccount();
      const list=()=>{
        const keyword=$('distribution-search').value.trim().toLowerCase(),platform=$('distribution-filter').value;
        $('distribution-account-grid').innerHTML=accounts.filter(a=>(!platform||a.platform===platform)&&(!keyword||`${a.name} ${a.nickname||''} ${a.platformUserId||''} ${a.group}`.toLowerCase().includes(keyword))).map(a=>`<article class="distribution-account-card"><div><span class="distribution-platform ${a.platform}">${names[a.platform]}</span><span class="distribution-state ${a.status}">${labels[a.status]}</span></div><h3>${avatar(a)} ${esc(accountName(a))}</h3><p>备注：${esc(a.name)}<br>${esc(identityText(a))}</p><p>${esc(a.group||'未分组')} · ${a.runnerId===status.runnerId?'本机账号':'其他电脑'}</p><small>最近检查：${date(a.checkedAt)}<br>身份核对：${date(a.identityCheckedAt)}</small><div class="distribution-card-actions"><button data-edit="${a.id}">编辑</button><button data-login="${a.id}" ${a.runnerId!==status.runnerId?'disabled':''}>${a.status==='ready'?'重新登录':'登录'}</button><button data-check="${a.id}" ${a.runnerId!==status.runnerId?'disabled':''}>检查状态</button><button data-remove="${a.id}">移除</button></div></article>`).join('')||'<p class="distribution-empty">暂无账号，点击“添加账号”开始。</p>';
        pane.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>newAccount(accounts.find(a=>a.id===b.dataset.edit)));
        for(const action of ['login','check'])pane.querySelectorAll(`[data-${action}]`).forEach(b=>b.onclick=safe(()=>login(accounts.find(a=>a.id===b.dataset[action]),action)));
        pane.querySelectorAll('[data-remove]').forEach(b=>b.onclick=()=>{const a=accounts.find(a=>a.id===b.dataset.remove);hooks.modal('移除账号',`<p>移除 ${esc(a.name)} 的本机登录关联，并取消尚未执行的任务。历史发布记录会保留。</p>`,[{label:'取消',run:hooks.close},{label:'移除',primary:true,run:safe(async()=>{await request('/accounts/'+a.id,{method:'DELETE'});hooks.close();refresh();})}]);});
      };$('distribution-search').oninput=list;$('distribution-filter').onchange=list;list();return;
    }
    if(view==='jobs'){
      pane.innerHTML=`<div class="distribution-heading"><div><h2>发布任务</h2><p>每个账号单独执行。已提交与已发布分别记录；结果待核对的任务不会自动重发。</p></div><button id="distribution-refresh">刷新</button></div><div class="distribution-job-list">${jobs.map(j=>`<article class="distribution-job"><div><span class="distribution-state ${j.status}">${labels[j.status]||j.status}</span><strong>${esc(j.post.title)}</strong><small>${names[j.platform]} · ${esc(j.accountName)} · ${j.post.type==='video'?'视频':'图文'}</small><p>${esc(j.message||'等待助手到点开始上传')}</p>${j.post.source?`<p>来源：${esc(j.post.source.projectName)} · ${[...new Set(j.post.source.assets.map(a=>a.workName||a.name))].map(esc).join('、')}</p>`:''}<small>计划开始 ${date(j.dueAt)} · 最近更新 ${date(j.updatedAt)}</small></div><div class="distribution-job-actions">${j.resultUrl?`<a href="${esc(j.resultUrl)}" target="_blank" rel="noopener noreferrer">查看平台</a>`:`<a href="${j.platform==='xhs'?'https://creator.xiaohongshu.com/new/note-manager':'https://creator.douyin.com/creator-micro/content/manage'}" target="_blank" rel="noopener noreferrer">打开平台</a>`}${j.status==='queued'?`<button data-job="${j.id}" data-action="cancel">取消</button>`:''}${['failed','needs_input'].includes(j.status)?`<button data-job="${j.id}" data-action="retry">重试</button>`:''}${['submitted','uncertain'].includes(j.status)?`<button data-job="${j.id}" data-action="resolve">核对结果</button>`:''}${j.post.source?`<button data-source="${j.id}">查看来源作品</button>`:''}<button data-details="${j.id}">查看内容</button></div></article>`).join('')||'<p class="distribution-empty">暂无任务。完成内容并预览发布清单后创建。</p>'}</div>`;
      $('distribution-refresh').onclick=refresh;
      pane.querySelectorAll('[data-source]').forEach(b=>b.onclick=safe(()=>hooks.openSource(jobs.find(j=>j.id===b.dataset.source).post.source)));
      pane.querySelectorAll('[data-details]').forEach(b=>b.onclick=()=>{const j=jobs.find(x=>x.id===b.dataset.details);hooks.modal('任务内容快照',`<p>${names[j.platform]} · ${esc(j.accountName)}</p><h3>${esc(j.post.title)}</h3><p class="distribution-body">${esc(j.post.body)}</p><p>${j.post.tags.map(t=>'#'+esc(t)).join(' ')}</p><p>${j.post.mediaIds.length} 个素材 · ${esc(j.post.declaration||'未预设声明')}</p>`,[{label:'关闭',run:hooks.close}]);});
      pane.querySelectorAll('[data-job]').forEach(b=>b.onclick=safe(async()=>{
        if(b.dataset.action==='resolve'){
          hooks.modal('核对平台结果','<p>请先在平台作品管理中确认这条内容的结果，再选择对应状态。</p>',[{label:'稍后核对',run:hooks.close},{label:'确认未发布',run:safe(async()=>{await request(`/jobs/${b.dataset.job}/action`,{method:'POST',body:{action:'resolve',result:'failed'}});hooks.close();refresh();})},{label:'确认已发布',primary:true,run:safe(async()=>{await request(`/jobs/${b.dataset.job}/action`,{method:'POST',body:{action:'resolve',result:'published'}});hooks.close();refresh();})}]);
        }else{await request(`/jobs/${b.dataset.job}/action`,{method:'POST',body:{action:b.dataset.action}});refresh();}
      }));return;
    }
    const count=s=>jobs.filter(j=>s.includes(j.status)).length;
    const metric=(a,key)=>{const m=metrics.find(m=>m.accountId===a.id);return m?.metrics?.[key]===undefined?'—':Number(m.metrics[key]).toLocaleString('zh-CN');};
    pane.innerHTML=`<div class="distribution-heading"><div><h2>数据概览</h2><p>发布统计来自最近 200 条任务。“已发布”以平台核对结果为准。</p></div><button id="distribution-refresh">刷新</button></div><div class="distribution-metrics">${[['已确认发布',count(['published'])],['待平台确认',count(['submitted','uncertain'])],['等待 / 执行中',count(['queued','running','submitting'])],['需要处理',count(['failed','needs_input'])]].map(([label,value])=>`<article><span>${label}</span><strong>${value}</strong></article>`).join('')}</div><div class="distribution-table"><h3>账号累计指标</h3><p class="hint">点击“同步”读取平台首页明确展示的累计值。— 表示未读取到；平台缩略值、区间值和累计值不会混合统计。</p><table><thead><tr><th>账号</th><th>粉丝</th><th>播放 / 阅读</th><th>点赞</th><th>评论</th><th>收藏</th><th>最近同步</th><th></th></tr></thead><tbody>${accounts.map(a=>`<tr><td>${esc(a.name)}<br><small>${names[a.platform]}</small></td>${['followers','plays','likes','comments','favorites'].map(key=>`<td>${metric(a,key)}</td>`).join('')}<td>${date(metrics.find(m=>m.accountId===a.id)?.collectedAt)}</td><td><button data-sync-metrics="${a.id}" ${a.runnerId!==status.runnerId||a.status!=='ready'?'disabled':''}>同步</button> <a target="_blank" rel="noopener noreferrer" href="${a.platform==='xhs'?'https://creator.xiaohongshu.com/new/home':'https://creator.douyin.com/creator-micro/home'}">平台数据</a></td></tr>`).join('')}</tbody></table></div>`;
    $('distribution-refresh').onclick=refresh;
    pane.querySelectorAll('[data-sync-metrics]').forEach(b=>b.onclick=safe(()=>login(accounts.find(a=>a.id===b.dataset.syncMetrics),'analytics')));
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
    if(assets.length>(post.type==='video'?1:platform==='douyin'?35:18))throw new Error(`${names[platform]}素材数量超过当前助手支持范围`);
    for(const a of assets)if(a.file.size>(a.type==='image'?32:2048)*1024*1024)throw new Error(`“${a.name}”超过当前发布助手支持的大小`);
    const coverIds=post.type==='video'?[post.coverAssetId,platform==='douyin'?post.landscapeCoverAssetId:null].filter(Boolean):[];
    for(const id of coverIds){const a=state.postAssets.find(a=>a.id===id);if(!a?.file||a.type!=='image'||a.file.size>32*1024*1024)throw new Error('封面图片不可用，请重新选择');}
    return {title,body,tags,type:post.type,assets,cover:post.type==='video'?state.postAssets.find(a=>a.id===post.coverAssetId):null,landscape:post.type==='video'&&platform==='douyin'?state.postAssets.find(a=>a.id===post.landscapeCoverAssetId):null,declaration:platform==='douyin'&&post.type==='video'?post.declaration||'':''};
  }
  async function upload(asset){
    if(!asset)return '';
    const key=asset.file;if(uploads.has(key))return uploads.get(key);
    const workspaceMediaId=await window.CreatorBackend.hash(asset.file);
    const value=await request('/media/reuse',{method:'POST',body:{workspaceMediaId}});uploads.set(key,value.id);return value.id;
  }
  async function preview(){
    if(submitting)return;
    try{
      await hooks.prepare();await refresh();const state=hooks.state(),selected=accounts.filter(a=>state.selectedAccounts.has(a.id));
      if(!selected.length)throw new Error('请选择至少一个发布账号');
      if(!status.connected||!status.ready)throw new Error('请先连接后台并准备发布助手');
      if(selected.some(a=>a.status!=='ready'||!a.platformUserId||a.runnerId!==status.runnerId))throw new Error('所选账号需要在本机重新登录');
      const checked=Object.fromEntries(selected.map(a=>[a.id,validate(a.platform,Core.accountPost(state,a),state)]));
      const projectId=hooks.projectId(),sources=Object.fromEntries(selected.map(a=>[a.id,Core.source(state,projectId,Core.accountPost(state,a))]));
      let scheduledAt='';if(state.timing==='scheduled'){const date=new Date(state.scheduledAt);if(!Number.isFinite(date.getTime())||date.getTime()<=Date.now())throw new Error('请选择未来的定时开始时间');scheduledAt=date.toISOString();}
      const batchId=crypto.randomUUID();let payload=null;
      hooks.modal('确认发布清单',`<p class="dialog-intro">${selected.length} 个账号 · ${scheduledAt?'定时开始：'+esc(date(scheduledAt)):'立即开始上传'}</p>${selected.map(a=>{const p=checked[a.id];return `<div class="distribution-preview"><strong>${esc(accountName(a))} · ${names[a.platform]} · ${p.type==='video'?'视频':'图文'}</strong><small>${esc(identityText(a))} · ${state.accountPosts?.[a.id]?'独立设置':'统一设置'}</small><p>来源：${sources[a.id].names.map(esc).join('、')}</p><h3>${esc(p.title)}</h3><p class="distribution-body">${esc(p.body)}</p><p>${p.tags.map(t=>'#'+esc(t)).join(' ')}</p><small>${p.assets.length} 个素材 · ${p.cover?'自定义封面：'+esc(p.cover.name):p.type==='image'?'首图为封面':'平台选取封面'}${p.landscape?' · 横封面：'+esc(p.landscape.name):''} · ${esc(p.declaration||'未预设内容声明')}</small></div>`;}).join('')}<p class="hint">确认后会向平台提交这些内容。定时任务到点开始上传，请保持电脑和本地服务在线。平台审核结果在任务页核对。</p><p id="distribution-submit-status" role="status"></p>`,[{label:'返回编辑',run:()=>{if(!submitting)hooks.close();}},{label:scheduledAt?'确认创建定时任务':'确认发布',primary:true,run:async()=>{
        if(submitting)return;submitting=true;$('dialog-close').disabled=true;renderAccounts();const label=$('distribution-submit-status');label.textContent='正在保存发布素材，请勿关闭窗口…';
        try{
          if(hooks.projectId()!==projectId)throw new Error('项目已切换，请返回当前项目重新预览发布');
          if(!payload){const entries=[];for(const account of selected){const p=checked[account.id],mediaIds=[];for(const a of p.assets)mediaIds.push(await upload(a));entries.push({accountId:account.id,post:{title:p.title,body:p.body,tags:p.tags,type:p.type,mediaIds,coverId:await upload(p.cover),landscapeCoverId:await upload(p.landscape),declaration:p.declaration,source:{projectId,assetIds:sources[account.id].assetIds}}});}payload={id:batchId,scheduledAt,entries};}
          await request('/batches',{method:'POST',body:payload});hooks.close();hooks.toast(`已创建 ${selected.length} 个发布任务`);open('jobs');
        }catch(e){if(label.isConnected)label.textContent=e.message+'。可以点击确认重试，同一批任务不会重复创建。';else hooks.toast(e.message);}
        finally{submitting=false;$('dialog-close').disabled=false;renderAccounts();}
      }}]);
    }catch(e){hooks.toast(e.message);}
  }
  window.Distribution={init,enter,open,fields,renderAccounts,preview,manage:()=>open('accounts')};
})();
