/* Interactive prototype. Processing and publishing adapters are intentionally deferred. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const $$ = selector => [...document.querySelectorAll(selector)];
  const clamp = (n, a, b) => Math.min(b, Math.max(a, n));
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const Core = window.TimelineCore, FPS = Core.FPS;
  const demoLayers = ['background.jpg', 'buildings.png', 'character.png', 'light.png', 'debris.png'];
  const platformNames = { harmony: '鸿蒙', ios: 'iOS' };
  const socialNames = { xhs: '小红书', douyin: '抖音' };
  const categories = { simple: '简约', zen: '禅意', ancient: '古风', cartoon: '卡通' };
  const demoAssets = () => [
    {id:'demo-video',name:'城市飞行 · 原始视频',type:'video',demo:true,duration:5,width:1080,height:1920},
    {id:'demo-image',name:'城市飞行 · 静态图',type:'image',demo:true,width:2048,height:2048},
    {id:'demo-4d',name:'城市飞行 · 4D 分层',type:'4d',demo:true,width:2048,height:2048}
  ];
  const accounts = [
    {id:'x1',name:'倾境 · 每日壁纸',platform:'xhs',category:'simple'},
    {id:'x2',name:'倾境 · 一念禅意',platform:'xhs',category:'zen'},
    {id:'x3',name:'倾境 · 古风画卷',platform:'xhs',category:'ancient'},
    {id:'x4',name:'倾境 · 卡通小宇宙',platform:'xhs',category:'cartoon'},
    {id:'d1',name:'倾境 · 动态壁纸',platform:'douyin',category:'simple'},
    {id:'d2',name:'倾境 · 禅意时刻',platform:'douyin',category:'zen'},
    {id:'d3',name:'倾境 · 国风意境',platform:'douyin',category:'ancient'},
    {id:'d4',name:'倾境 · 卡通星球',platform:'douyin',category:'cartoon'}
  ];
  const copyPresets = {
    simple: {title:'把城市的风，留在你的屏幕里',body:'今天的屏幕，换一点不一样的视角。\n\n光影穿过城市，定格在刚刚好的那一秒。\n静态与动态，都有各自的好看。\n\n你更喜欢哪一种？',tags:'#壁纸 #动态壁纸 #手机壁纸 #今日分享'},
    zen: {title:'一方屏幕，一刻清静',body:'把喧闹留在屏幕之外。\n\n在光影缓缓流动的片刻，给自己一点安静。\n愿每次点亮手机，都能找回片刻平和。',tags:'#禅意壁纸 #治愈系 #动态壁纸 #静心'},
    ancient: {title:'将一幅流动的画，藏进日常',body:'光影流转，画意渐生。\n\n把喜欢的意境留在屏幕上，\n每次点亮，都是一次小小的相遇。',tags:'#古风壁纸 #国风美学 #动态壁纸 #意境'},
    cartoon: {title:'今天的快乐，从换壁纸开始',body:'给屏幕换上新的小宇宙！\n\n静态耐看，动起来又多了一点惊喜。\n挑了最喜欢的一小段，连解锁都变得有意思。',tags:'#卡通壁纸 #快乐日常 #手机壁纸 #动态壁纸'}
  };
  const newPost = social => ({...copyPresets.simple,title:social==='douyin'?'这一秒，让屏幕动起来':copyPresets.simple.title,type:social==='douyin'?'video':'image',assetIds:[social==='douyin'?'post-video':'post-image'],coverTitle:false,style:'simple'});
  const state = {workspace:'wallpaper',assets:demoAssets(),selectedId:'demo-video',filter:'all',product:'dynamic',platform:'harmony',projects:null,sourcePreview:false,time:0,playing:false,loop:true,name:'城市飞行',social:'xhs',postAssets:[{...demoAssets()[1],id:'post-image'},{...demoAssets()[0],id:'post-video'}],posts:{xhs:newPost('xhs'),douyin:newPost('douyin')},accounts,selectedAccounts:new Set(['x1','d1']),timing:'now',scheduledAt:'',published:[]};
  let toastTimer, lastTimestamp=0, animationFrame, objectUrls=[];
  const selectedAsset = () => state.assets.find(x=>x.id===state.selectedId) || state.assets[0];
  const assetById = id => state.assets.find(a=>a.id===id);
  function freshProfiles() {return {harmony:Core.create(5,60),ios:Core.create(5,24)};}
  const profiles = () => state.projects ||= freshProfiles();
  const profile = () => profiles()[state.platform];
  const outDuration = p => Core.total(p);
  const targetDuration = p => state.platform==='harmony'?2:p.frames/FPS;
  const timelineExtent = () => Math.max(1,Math.ceil(Math.max(outDuration(profile()),targetDuration(profile()))*2)/2+.5);
  const activeSegment = () => Core.at(profile(),state.time);
  const previewAsset = () => state.sourcePreview||state.product==='4d'?selectedAsset():assetById(activeSegment()?.clip.assetId);
  const previewSourceTime = () => state.sourcePreview?0:activeSegment()?.sourceTime||0;
  let loadedMediaId=null, playbackClipId=null;
  const currentPost = () => state.posts[state.social];
  function toast(message) { $('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,3600); }
  function setStatus(message) {$('status-text').textContent=message;}
  function timecode(seconds) { const total=Math.round(seconds*FPS),f=total%FPS,s=Math.floor(total/FPS)%60,m=Math.floor(total/(FPS*60));return `00:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}:${String(f).padStart(2,'0')}`; }
  function setRangeFill(el) {el.style.setProperty('--fill',`${(Number(el.value)-Number(el.min))/(Number(el.max)-Number(el.min))*100}%`);}
  const Hist=window.ProjectHistory, Store=window.ProjectStore, Thumbnails=window.ProjectThumbnails;
  let activeProject=null, actionHistory=null, mediaRegistry=new Map(), dirtyMedia=new Map();
  let saveQueue=Promise.resolve(),saveTimer,historyTimer,pendingLabel,revision=0,projectReady=false,loadingProject=false,importsPending=0,navigatingProject=false,projectMutationPending=false;
  const clone=value=>JSON.parse(JSON.stringify(value));
  function registerMedia(asset){mediaRegistry.set(asset.id,asset);dirtyMedia.set(asset.id,asset);}
  function captureProject(){return {name:state.name,assetIds:state.assets.map(a=>a.id),postAssetIds:state.postAssets.map(a=>a.id),versions:Object.fromEntries(Object.entries(profiles()).map(([key,p])=>[key,Core.snapshot(p)])),posts:clone(state.posts),accounts:clone(state.accounts),selectedAccounts:[...state.selectedAccounts],timing:state.timing,scheduledAt:state.scheduledAt,view:{workspace:state.workspace,platform:state.platform,social:state.social,selectedId:state.selectedId,sourcePreview:state.sourcePreview,product:state.product}};}
  function releaseMedia(registry){const urls=new Set([...registry.values()].map(a=>a.url).filter(Boolean));urls.forEach(url=>URL.revokeObjectURL(url));objectUrls=objectUrls.filter(url=>!urls.has(url));}
  function serializedMedia(asset){const {url,...data}=asset;return data;}
  function saveStatus(text,error=false){$('autosave-status').textContent=text;$('autosave-status').classList.toggle('save-error',error);$('autosave-status').title=error?'自动保存失败，点击重试':'项目和素材自动保存在当前浏览器';}
  function scheduleSave(){if(!projectReady||loadingProject)return;revision++;saveStatus('保存中…');clearTimeout(saveTimer);saveTimer=setTimeout(()=>flushSave(),250);}
  async function flushSave(){
    clearTimeout(saveTimer);if(!activeProject||!actionHistory)return false;
    const projectId=activeProject.id,rev=revision,assets=[...dirtyMedia.values()];
    const record={...activeProject,name:state.name||'未命名项目',schemaVersion:3,updatedAt:new Date().toISOString(),data:captureProject(),history:clone(actionHistory),mediaIds:[...mediaRegistry.keys()]};
    delete record.category;
    record.thumbnail=Thumbnails.subject(record)?Thumbnails.current(record,$('wallpaper-media'),previewAsset())||record.thumbnail:null;
    saveStatus('保存中…');saveQueue=saveQueue.catch(()=>{}).then(()=>Store.save(record,assets.map(serializedMedia)));
    try{await saveQueue;if(activeProject?.id===projectId){activeProject=record;for(const a of assets)if(dirtyMedia.get(a.id)===a)dirtyMedia.delete(a.id);if(revision===rev)saveStatus('已自动保存');}return true;}
    catch(error){if(activeProject?.id===projectId){saveStatus('未保存 · 重试',true);setStatus('自动保存未完成，请保留页面并重试');}return false;}
  }
  function commitAction(label){if(!projectReady||loadingProject)return;clearTimeout(historyTimer);pendingLabel=null;if(Hist.commit(actionHistory,captureProject(),label)){renderHistory();syncTimeline();}scheduleSave();}
  function deferAction(label){if(!projectReady||loadingProject)return;pendingLabel=label;clearTimeout(historyTimer);historyTimer=setTimeout(()=>commitAction(label),650);scheduleSave();}
  function finishPendingAction(){if(pendingLabel)commitAction(pendingLabel);}
  function applyProjectData(data){
    pause();state.name=data.name;state.assets=(data.assetIds||[]).map(id=>mediaRegistry.get(id)).filter(Boolean);state.postAssets=(data.postAssetIds||[]).map(id=>mediaRegistry.get(id)).filter(Boolean);
    state.projects=Object.fromEntries(['harmony','ios'].map(key=>[key,Core.normalize(data.versions?.[key]||{clips:[]},5,key==='ios'?24:60,id=>mediaRegistry.get(id)?.duration||0)]));
    state.posts=clone(data.posts);state.accounts=clone(data.accounts||accounts);state.selectedAccounts=new Set(data.selectedAccounts||[]);state.timing=data.timing||'now';state.scheduledAt=data.scheduledAt||'';
    const view=data.view||{};state.platform=platformNames[view.platform]?view.platform:'harmony';state.social=socialNames[view.social]?view.social:'xhs';state.product=view.product||'dynamic';state.sourcePreview=!!view.sourcePreview;state.selectedId=assetById(view.selectedId)?.id||state.assets[0]?.id;state.time=profile().cursor;state.workspace=view.workspace||'wallpaper';
    $('publish-timing').value=state.timing;$('publish-date').value=state.scheduledAt;$('publish-date').hidden=state.timing!=='scheduled';renderWallpaper();renderPublish();switchWorkspace(state.workspace);renderHistory();
  }
  function restoreHistory(index){finishPendingAction();const data=Hist.restore(actionHistory,index);if(!data)return;loadingProject=true;applyProjectData(data);loadingProject=false;scheduleSave();setStatus(`已还原：${actionHistory.entries[index].label}`);}
  function renderHistory(){if(!actionHistory)return;$('history-project-name').textContent=state.name||'未命名项目';$('history-list').innerHTML=actionHistory.entries.map((entry,i)=>`<button class="history-row${i===actionHistory.index?' active':''}${i>actionHistory.index?' future':''}" data-history-index="${i}" aria-pressed="${i===actionHistory.index}"><span class="history-step">${i===actionHistory.index?'●':String(i).padStart(2,'0')}</span><span><strong>${esc(entry.label)}</strong><small>${new Date(entry.at).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}${i===actionHistory.index?' · 当前状态':''}</small></span></button>`).join('');$$('[data-history-index]').forEach(b=>b.onclick=()=>restoreHistory(Number(b.dataset.historyIndex)));$('history-foot').textContent=actionHistory.index<actionHistory.entries.length-1?'继续编辑会替换后面的历史动作。项目列表始终保留其他作品。':`保留最近 ${Math.min(Hist.LIMIT,actionHistory.entries.length-1)} 个动作，素材文件只保存一份。`;}
  async function openProject(id){if(navigatingProject)return;navigatingProject=true;try{await performOpenProject(id);}catch(error){if(!projectReady)throw error;toast('项目暂时无法打开，请重试');}finally{navigatingProject=false;loadingProject=false;$('project-loading').hidden=true;}}
  async function performOpenProject(id){
    if(importsPending){toast('素材正在导入，完成后再切换项目');return;}finishPendingAction();if(projectReady&&!await flushSave()){toast('当前项目尚未保存成功，请先点击保存状态重试');return;}
    const previousMedia=mediaRegistry;const record=await Store.load(id);if(!record){toast('找不到这个本地项目');return;}loadingProject=true;$('project-loading').hidden=false;
    try{const assets=await Store.media(record.mediaIds||[]);mediaRegistry=new Map();dirtyMedia=new Map();for(const data of assets.filter(Boolean)){const a={...data};if(a.file){a.url=URL.createObjectURL(a.file);objectUrls.push(a.url);}mediaRegistry.set(a.id,a);}activeProject=record;actionHistory=record.history||Hist.create(record.data,'打开项目');if(Hist.fingerprint(actionHistory.entries[actionHistory.index].data)!==Hist.fingerprint(record.data))Hist.commit(actionHistory,record.data,'恢复自动保存');projectReady=true;$('history-btn').disabled=$('export-btn').disabled=false;applyProjectData(record.data);releaseMedia(previousMedia);closeModal();saveStatus('已自动保存');}
    finally{loadingProject=false;$('project-loading').hidden=true;}scheduleSave();await flushSave();
  }
  async function createProject(name,example=false){if(navigatingProject)return;navigatingProject=true;try{await performCreateProject(name,example);}finally{navigatingProject=false;}}
  async function performCreateProject(name,example=false){
    if(importsPending){toast('素材正在导入，完成后再新建项目');return;}finishPendingAction();if(projectReady&&!await flushSave()){toast('当前项目尚未保存成功，请先点击保存状态重试');return;}
    const previousMedia=mediaRegistry;loadingProject=true;mediaRegistry=new Map();dirtyMedia=new Map();state.assets=demoAssets();state.postAssets=example?[{...demoAssets()[1],id:'post-image'},{...demoAssets()[0],id:'post-video'}]:[];
    for(const a of [...state.assets,...state.postAssets])registerMedia(a);
    state.projects=freshProfiles();if(!example)for(const p of Object.values(state.projects)){p.clips=[];p.selectedClipId=null;p.cursor=0;}
    state.name=name.trim()||'未命名项目';state.selectedId='demo-video';state.product='dynamic';state.sourcePreview=false;state.platform='harmony';state.social='xhs';state.workspace='wallpaper';state.time=0;state.posts={xhs:newPost('xhs'),douyin:newPost('douyin')};if(!example)for(const p of Object.values(state.posts))p.assetIds=[];state.selectedAccounts=new Set(['x1','d1']);state.timing='now';state.scheduledAt='';
    const now=new Date().toISOString();activeProject={id:crypto.randomUUID(),createdAt:now,updatedAt:now};actionHistory=Hist.create(captureProject(),example?'城市飞行示例':'新建项目');projectReady=true;loadingProject=false;$('history-btn').disabled=$('export-btn').disabled=false;applyProjectData(captureProject());releaseMedia(previousMedia);closeModal();await flushSave();
  }
  function showNewProject(){
    modal('新建项目',`<form id="new-project-form" class="new-project-form"><div class="field"><label for="new-project-name">项目名称</label><input id="new-project-name" maxlength="60" placeholder="例如：一念禅意 · 金色光环" required autofocus></div><p class="dialog-note">鸿蒙与 iOS 分开编辑，素材和修改自动保存。</p><button type="submit" class="primary full-width">创建项目</button></form>`,[{label:'取消',run:closeModal}]);
    $('new-project-form').onsubmit=async e=>{e.preventDefault();const name=$('new-project-name').value.trim();if(!validProjectName($('new-project-name'),name))return;const button=e.target.querySelector('[type=submit]');button.disabled=true;try{await createProject(name);}catch{toast('项目暂时无法创建，请重试');}finally{if(button.isConnected)button.disabled=false;}};
    $('new-project-name').oninput=()=> $('new-project-name').setCustomValidity('');
  }
  function validProjectName(input,name){input.setCustomValidity(name?'':'请输入项目名称');if(!name)input.reportValidity();return !!name;}
  function projectDuration(record){const versions=record.data?.versions||{};return ['harmony','ios'].map(key=>`${platformNames[key]} ${Core.total(versions[key]||{clips:[]}).toFixed(2)}s`).join(' · ');}
  function closeProjectMenus(){ $$('.project-menu').forEach(menu=>menu.hidden=true);$$('.project-more').forEach(button=>button.setAttribute('aria-expanded','false')); }
  async function hydrateProjectCovers(records){
    for(const record of records){
      if(record.thumbnail)continue;const tile=$$('.project-tile').find(el=>el.dataset.projectId===record.id),target=Thumbnails.subject(record);if(!tile?.isConnected||!target)continue;
      try{const [asset]=await Store.media([target.id]);const cover=await Thumbnails.create(record,asset);if(!cover)continue;await Store.setThumbnail(record.id,cover);
        if(tile.isConnected){const image=document.createElement('img');image.src=cover;image.alt='';tile.querySelector('.project-cover-art').replaceChildren(image);}
        if(activeProject?.id===record.id&&!activeProject.thumbnail)activeProject.thumbnail=cover;
      }catch{/* A missing cover must not prevent opening a project. */}
    }
  }
  async function showProjectList(deleted=false){
    deleted=deleted===true;finishPendingAction();if(projectReady&&!await flushSave()){toast('当前项目未保存成功，请先重试');return;}
    try{
      const [records,trashed]=await Promise.all([Store.list({deleted}),deleted?Promise.resolve([]):Store.list({deleted:true})]);
      modal(deleted?'回收站':'我的项目',`<div class="project-list-tools"><span>${records.length} 个项目${deleted?' · 可随时恢复':' · 最近编辑'}</span><div>${deleted?`<button id="list-back-projects">${icon('folder')}我的项目</button>`:`<button id="list-trash" aria-label="回收站">${icon('trash')}回收站${trashed.length?` (${trashed.length})`:''}</button><button id="list-new-project">${icon('plus')}新建项目</button>`}</div></div><input id="project-search" placeholder="搜索项目名称" aria-label="搜索项目"><div id="project-library" class="project-library">${records.map(r=>`<article class="project-tile" data-project-id="${esc(r.id)}" data-project-name="${esc(r.name.toLowerCase())}"><button class="project-card${r.id===activeProject?.id?' current':''}" data-open-project="${esc(r.id)}" aria-label="${deleted?'恢复':'打开'}项目：${esc(r.name)}"><span class="project-cover"><span class="project-cover-art">${r.thumbnail?`<img src="${esc(r.thumbnail)}" alt="">`:icon('folder')}</span>${r.id===activeProject?.id?'<span class="project-current">编辑中</span>':''}</span><strong title="${esc(r.name)}">${esc(r.name)}</strong><small>${projectDuration(r)}</small><small>${new Date(deleted?r.deletedAt:r.updatedAt).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})}${deleted?' 删除':''}</small></button><button class="project-more" aria-label="更多：${esc(r.name)}" aria-haspopup="menu" aria-controls="project-menu-${esc(r.id)}" aria-expanded="false">${icon('more')}</button><div id="project-menu-${esc(r.id)}" class="project-menu" role="menu" aria-label="${esc(r.name)}的操作" hidden>${deleted?`<button role="menuitem" data-project-action="restore">${icon('undo')}恢复项目</button>`:`<button role="menuitem" data-project-action="rename">${icon('rename')}重命名</button><button role="menuitem" data-project-action="copy">${icon('copy')}复制项目</button><button role="menuitem" data-project-action="trash" class="danger-text">${icon('trash')}删除项目</button>`}</div></article>`).join('')}</div><div id="project-search-empty" class="project-empty" ${records.length?'hidden':''}>${deleted?'回收站是空的':'还没有项目，点击「新建项目」开始创作'}</div><p class="project-storage-note">${deleted?'删除的项目保留素材和编辑记录，恢复后可以继续编辑。':'自动保存到当前浏览器 · 删除的项目可在回收站恢复'}</p>`,activeProject?[{label:'返回编辑',primary:true,run:closeModal}]:[]);
      $('dialog').classList.add('project-browser');$('dialog-close').hidden=!activeProject;$('list-new-project')?.addEventListener('click',showNewProject);$('list-trash')?.addEventListener('click',()=>showProjectList(true));$('list-back-projects')?.addEventListener('click',()=>showProjectList());
      $$('.project-tile').forEach(tile=>{
        const record=records.find(r=>r.id===tile.dataset.projectId),menu=tile.querySelector('.project-menu'),more=tile.querySelector('.project-more');
        tile.querySelector('.project-card').onclick=()=>deleted?restoreProject(record.id):openProject(record.id);
        more.onclick=()=>{const show=menu.hidden;closeProjectMenus();menu.hidden=!show;more.setAttribute('aria-expanded',String(show));if(show)menu.querySelector('button').focus();};
        menu.onkeydown=e=>{const buttons=[...menu.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);if(e.key==='Escape'){e.preventDefault();e.stopPropagation();closeProjectMenus();more.focus();}else if(['ArrowDown','ArrowUp'].includes(e.key)){e.preventDefault();buttons[(index+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length].focus();}};
        menu.querySelectorAll('button').forEach(button=>button.onclick=()=>{closeProjectMenus();const action=button.dataset.projectAction;if(action==='rename')showRenameProject(record);else if(action==='copy')duplicateProject(record.id);else if(action==='trash')showDeleteProject(record);else restoreProject(record.id);});
      });
      $('project-search').oninput=e=>{closeProjectMenus();const query=e.target.value.trim().toLowerCase();let count=0;$$('.project-tile').forEach(tile=>{tile.hidden=!tile.dataset.projectName.includes(query);if(!tile.hidden)count++;});$('project-search-empty').hidden=count>0;$('project-search-empty').textContent=query?'没有匹配的项目。':deleted?'回收站是空的':'还没有项目，点击「新建项目」开始创作';};
      if(!deleted)hydrateProjectCovers(records);
    }catch{toast('暂时无法打开项目列表，请重试');}
  }
  async function mutateProject(work){
    if(projectMutationPending||navigatingProject)return;
    if(importsPending){toast('素材正在导入，完成后再管理项目');return;}
    projectMutationPending=true;const buttons=$$('#dialog button');buttons.forEach(b=>b.disabled=true);
    try{finishPendingAction();if(projectReady&&!await flushSave())throw new Error('请先重试保存当前项目');await work();}
    catch(error){toast(`操作未完成：${error.message||'请重试'}`);}
    finally{projectMutationPending=false;buttons.forEach(b=>{if(b.isConnected)b.disabled=false;});}
  }
  function showRenameProject(record){
    modal('重命名项目',`<form id="rename-project-form" class="new-project-form"><div class="field"><label for="rename-project-name">项目名称</label><input id="rename-project-name" value="${esc(record.name)}" maxlength="60" required autofocus></div><button type="submit" class="primary full-width">确认修改</button></form>`,[{label:'取消',run:()=>showProjectList()}]);
    $('rename-project-name').select();$('rename-project-name').oninput=e=>e.target.setCustomValidity('');
    $('rename-project-form').onsubmit=e=>{e.preventDefault();const name=$('rename-project-name').value.trim();if(!validProjectName($('rename-project-name'),name))return;mutateProject(async()=>{
      if(activeProject?.id===record.id){state.name=name;commitAction('修改项目名称');renderWallpaper();renderHistory();if(!await flushSave())throw new Error('名称尚未保存，请重试');}
      else{const latest=await Store.load(record.id);if(!latest)throw new Error('项目已被删除');latest.name=latest.data.name=name;latest.updatedAt=new Date().toISOString();latest.history ||= Hist.create(latest.data,'打开项目');Hist.commit(latest.history,latest.data,'修改项目名称');await Store.save(latest,[],{activate:false});}
      await showProjectList();toast('项目名称已修改');
    });};
  }
  function duplicateProject(id){return mutateProject(async()=>{
    const original=await Store.load(id);if(!original)throw new Error('项目已被删除');const names=new Set((await Store.list()).map(r=>r.name));let suffix=' · 副本',name=original.name.slice(0,60-suffix.length)+suffix,index=2;
    while(names.has(name)){suffix=` · 副本 ${index++}`;name=original.name.slice(0,60-suffix.length)+suffix;}
    const copy=clone(original),now=new Date().toISOString();copy.id=crypto.randomUUID();copy.name=copy.data.name=name;copy.createdAt=copy.updatedAt=now;delete copy.deletedAt;delete copy.category;delete copy.data.category;copy.history=Hist.create(copy.data,`复制自 ${original.name}`);
    await Store.save(copy,[],{activate:false});await showProjectList();toast(`已复制：${name}`);
  });}
  function showDeleteProject(record){modal('删除项目',`<p class="dialog-intro">将「${esc(record.name)}」移入回收站？</p><p class="hint">素材和编辑记录会保留，可以在项目列表的回收站中恢复。</p>`,[{label:'取消',run:()=>showProjectList()},{label:'移入回收站',primary:true,run:()=>deleteProject(record.id)}]);}
  function clearCurrentProject(){
    pause();clearTimeout(saveTimer);clearTimeout(historyTimer);pendingLabel=null;releaseMedia(mediaRegistry);mediaRegistry=new Map();dirtyMedia=new Map();activeProject=null;actionHistory=null;projectReady=false;
    state.assets=demoAssets();state.postAssets=[];state.projects=freshProfiles();for(const p of Object.values(state.projects)){p.clips=[];p.selectedClipId=null;p.cursor=0;}state.name='尚未打开项目';state.selectedId='demo-video';state.product='dynamic';state.sourcePreview=false;state.time=0;state.posts={xhs:newPost('xhs'),douyin:newPost('douyin')};for(const p of Object.values(state.posts))p.assetIds=[];
    $('history-panel').hidden=true;$('history-list').replaceChildren();renderWallpaper();renderPublish();saveStatus('请选择项目');$('history-btn').disabled=$('export-btn').disabled=true;
  }
  function deleteProject(id){return mutateProject(async()=>{
    await Store.trash(id);if(activeProject?.id===id){clearCurrentProject();const [next]=await Store.list();if(next)await openProject(next.id);}await showProjectList();toast('项目已移入回收站');
  });}
  function restoreProject(id){return mutateProject(async()=>{await Store.restore(id);if(!activeProject)await openProject(id);await showProjectList();toast('项目已恢复');});}
  async function initializeProjects(){
    try{const lastId=await Store.last(),last=lastId&&await Store.load(lastId),records=await Store.list();if(last)await openProject(lastId);else if(records.length)await openProject(records[0].id);else if((await Store.list({deleted:true})).length){clearCurrentProject();await showProjectList();}else await createProject('城市飞行示例',true);}
    catch(error){if(!activeProject){for(const a of [...state.assets,...state.postAssets])registerMedia(a);activeProject={id:crypto.randomUUID(),createdAt:new Date().toISOString()};actionHistory=Hist.create(captureProject(),'城市飞行示例');projectReady=true;renderWallpaper();renderPublish();}saveStatus('未保存 · 重试',true);toast('本地自动保存暂不可用，请保留页面并点击保存状态重试');}
    finally{$('project-loading').hidden=true;}
  }
  function bindProjectPersistence(){
    $('project-list-btn').onclick=showProjectList;$('new-project-btn').onclick=showNewProject;$('autosave-status').onclick=()=>{finishPendingAction();flushSave();};$('history-btn').onclick=()=>{finishPendingAction();renderHistory();$('history-panel').hidden=!$('history-panel').hidden;};$('history-close').onclick=()=>$('history-panel').hidden=true;
    const labels={'project-name':'修改项目名称','post-title':'修改推广标题','post-body':'修改推广正文','post-tags':'修改推广话题','size-preset':'调整成片尺寸','custom-width':'调整成片尺寸','custom-height':'调整成片尺寸','ios-frames':'iOS 修改目标帧数','crop-scale':'调整画面缩放','crop-x':'调整画面位置','crop-y':'调整画面位置','copy-style':'修改文案风格','cover-title-toggle':'调整封面标题','publish-timing':'修改发布时间','publish-date':'修改发布时间'};
    const liveText=new Set(['project-name','post-title','post-body','post-tags']);
    document.addEventListener('input',e=>{if(e.target.closest('#dialog')||!labels[e.target.id])return;if(liveText.has(e.target.id))deferAction(labels[e.target.id]);else scheduleSave();});
    document.addEventListener('change',e=>{if(e.target.closest('#dialog'))return;if(labels[e.target.id])commitAction(labels[e.target.id]);else if(e.target.dataset.account)commitAction('选择发布账号');});
    document.addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;const labels={'reset-crop':'画面回正','capture-cover':'选择静态封面','suggest-copy':'应用示例文案'};if(labels[b.id])commitAction(labels[b.id]);else if(b.dataset.postType)commitAction('修改推广形式');else if(b.dataset.platform||b.dataset.workspace||b.dataset.social||b.dataset.product||b.dataset.assetId)scheduleSave();});
    document.addEventListener('pointerup',e=>{if(e.target.closest('#phone-screen'))commitAction('调整画面位置');});
    document.addEventListener('visibilitychange',()=>{if(document.hidden){finishPendingAction();flushSave();}});
  }

  function sceneHTML(asset, {preview=false,frame=0}={}) {
    if (!asset) return '<div class="empty-assets">请先添加素材</div>';
    if(asset.demo) return `<div class="media-transform demo-scene" data-demo-frame="${frame}">${demoLayers.map((name,i)=>`<img draggable="false" class="scene-layer ${name==='light.png'?'light':''}" src="assets/${name}" alt="${preview&&i===0?'城市飞行示例壁纸':''}" data-layer="${i}" style="transform:scale(1.18)">`).join('')}</div>`;
    if(asset.type==='4d') return `<div class="package-thumbnail">${icon('layers')}</div>`;
    if(asset.type==='video') return `<div class="media-transform"><video class="uploaded-video" ${preview?'':'preload="metadata"'} src="${esc(asset.url)}" muted playsinline ${preview?'preload="auto"':''}></video></div>`;
    return `<div class="media-transform"><img class="uploaded-image" draggable="false" src="${esc(asset.url)}" alt="${preview?esc(asset.name):''}"></div>`;
  }
  function assetMetadata(asset) {return asset.type==='video'?`${asset.duration.toFixed(2)} s · ${asset.width} × ${asset.height}`:asset.type==='4d'?(asset.demo?'5 个图层 · 内置示例':'ZIP · 已导入资源包'):`${asset.width} × ${asset.height} · 图片`;}
  function assetRow(asset, selected, publish=false) {
    return `<button class="asset-row${selected?' selected':''}" data-asset-id="${esc(asset.id)}" ${!publish&&['image','video'].includes(asset.type)?'draggable="true" title="拖到时间轴加入片段"':''} ${publish?'data-post-asset="true"':''} aria-pressed="${selected}"><span class="asset-thumb"><span class="media-content">${sceneHTML(asset)}</span><span class="type-overlay">${icon(asset.type==='4d'?'layers':asset.type)}</span></span><span class="asset-copy"><strong>${esc(asset.name)}</strong><span>${assetMetadata(asset)}</span>${selected?`<small>${publish?'已加入当前推文':'已选素材'}</small>`:''}</span></button>`;
  }
  function renderAssets() {
    const filtered=state.assets.filter(a=>state.filter==='all'||a.type===state.filter);
    $('asset-list').innerHTML=filtered.map(a=>assetRow(a,a.id===state.selectedId)).join('')||'<div class="empty-assets">暂无这类素材<br>点击下方导入</div>';
    $('asset-count').textContent=state.assets.length;
    $$('#asset-filters button').forEach(b=>{b.classList.toggle('active',b.dataset.filter===state.filter);b.setAttribute('aria-pressed',b.dataset.filter===state.filter);});
    $$('#asset-list .asset-row').forEach(b=>{b.onclick=()=>selectAsset(b.dataset.assetId);b.ondragstart=e=>{e.dataTransfer.setData('application/x-qingjing-asset',b.dataset.assetId);e.dataTransfer.effectAllowed='copy';pause();};});
    $('add-to-timeline').disabled=!['image','video'].includes(selectedAsset()?.type);
  }
  function selectAsset(id) {
    pause();state.selectedId=id;const asset=selectedAsset();state.product=asset.type==='image'?'static':asset.type==='4d'?'4d':'dynamic';state.sourcePreview=true;renderWallpaper();setStatus(`${asset.name} · 素材预览${asset.type==='video'?' · 拖入时间轴开始剪辑':''}`);
  }
  function renderWallpaperMedia() {
    const asset=previewAsset();loadedMediaId=asset?.id||null;playbackClipId=null;
    $('wallpaper-media').innerHTML=asset?sceneHTML(asset,{preview:true}):'<div class="empty-preview">时间轴暂无片段<br><span>拖入图片或视频，或点击「添加素材」</span></div>';
    const video=$('wallpaper-media').querySelector('video');
    if(video){video.onloadedmetadata=()=>{if(video.isConnected)syncPosition();};video.onseeked=()=>{if(video.isConnected&&!state.playing)syncPosition();};video.onerror=()=>{if(video.isConnected){pause();toast('浏览器无法预览这个视频，请换用 H.264 编码的 MP4 试试');}};}
    $('four-d-card').hidden=state.product!=='4d';$('device-unit').hidden=state.product==='4d';$('stage-note').hidden=state.product==='4d';$('four-d-name').textContent=asset?.name||'请导入资源包';syncCrop();
  }
  function renderWallpaper() {
    document.querySelector('.project-info strong').textContent=state.name||'未命名项目';renderAssets();renderWallpaperMedia();renderSequence();syncInspector();syncPlatformButtons();syncTimeline();
    $('motion-settings').hidden=state.product!=='dynamic';$('static-settings').hidden=state.product!=='static';$('package-settings').hidden=state.product!=='4d';$('crop-settings').hidden=state.product==='4d';
    $('timeline-empty').hidden=state.product==='dynamic';
    ['.device-toolbar','.device-meta','.view-options','.player-bar'].forEach(selector=>document.querySelector(selector).hidden=state.product==='4d');
    $('size-preset').closest('.field').hidden=state.product==='4d';if(state.product==='4d')$('custom-size').hidden=true;
    $$('#product-tabs button').forEach(b=>{b.classList.toggle('active',b.dataset.product===state.product);b.setAttribute('aria-pressed',b.dataset.product===state.product);});
    $('back-to-sequence').hidden=!state.sourcePreview||state.product!=='dynamic';$('capture-cover').hidden=previewAsset()?.type!=='video';$('cover-time').hidden=previewAsset()?.type!=='video';
  }
  function syncPlatformButtons() {
    $$('[data-platform]').forEach(b=>{b.classList.toggle('active',b.dataset.platform===state.platform);b.setAttribute('aria-pressed',b.dataset.platform===state.platform);});
    $('inspector-platform').textContent=state.product==='4d'?'4D 资源':`${platformNames[state.platform]}工程`;
    $('editing-track-name').textContent=`${platformNames[state.platform]}画面轨道`;
    $('target-caption').textContent=state.product==='static'?'静态图片 · PNG':state.platform==='harmony'?'Moving Photo · 2 秒':'Live Photo · 16–30 帧';$('preview-version').textContent=`${platformNames[state.platform]}独立工程`;
    $('version-note').textContent=state.product==='4d'?'4D 资源保留原始分层与配置':state.product==='static'?'苹果与鸿蒙分别保存尺寸与构图':'两个工程独立保存素材、片段顺序与速度';
  }
  function syncInspector() {
    const p=profile(),c=state.sourcePreview?null:Core.selected(p),still=c?.kind==='image',o=outDuration(p),frames=Math.round(o*FPS),source=assetById(c?.assetId);
    $('project-name').value=state.name;
    $('selected-clip-label').textContent=c?`片段 ${String(p.clips.indexOf(c)+1).padStart(2,'0')}`:state.sourcePreview?'素材预览':'未选片段';
    $('still-duration-field').hidden=!still;$('video-range-fields').hidden=!!still;document.querySelector('.speed-field').hidden=!!still;$('still-frames').value=c?Math.round(Core.clipDuration(c)*FPS):10;
    $('in-point').value=(c?.start||0).toFixed(2);$('out-point').value=(c?.end||0).toFixed(2);$('in-point').max=((c?.end||Core.MIN)-Core.MIN).toFixed(3);$('out-point').max=(source?.duration||0).toFixed(3);
    $('speed').value=clamp(c?.speed||1,.1,4);$('speed-label').textContent=`${(c?.speed||1).toFixed(2).replace(/0$/,'')}×`;
    ['in-point','out-point','speed'].forEach(id=>$(id).disabled=!c);$$('.speed-presets button').forEach(b=>{b.disabled=!c;b.classList.toggle('active',!!c&&Math.abs(Number(b.dataset.speed)-c.speed)<.001);});
    $('ios-frames-field').hidden=state.platform!=='ios';$('ios-frames').value=p.frames;$('ios-frames-value').textContent=`${p.frames} 帧`;
    $('output-duration').innerHTML=`${o.toFixed(2)} <small>s</small>`;$('output-frames').innerHTML=`${frames} <small>帧</small>`;
    $('fit-target').disabled=!p.clips.length;$('fit-target').querySelector('span').textContent=state.platform==='harmony'?'整条成片变速到 2 秒':`整条成片变速到 ${p.frames} 帧`;
    const valid=state.platform==='harmony'?Math.abs(o-2)<.015:frames>=16&&frames<=30;
    const note=!p.clips.length?'拖入图片或视频，开始编辑当前工程':valid?(state.platform==='harmony'?'符合当前鸿蒙 2 秒预设':`当前 ${frames} 帧，符合 16–30 帧预设`):(state.platform==='harmony'?'继续剪辑，或将整条成片适配到 2 秒':'继续剪辑，或将整条成片适配到 16–30 帧');
    $('target-validation').classList.toggle('valid',valid);$('target-validation').querySelector('span').textContent=note;
    const preset=`${p.width}x${p.height}`,known=[...$('size-preset').options].some(x=>x.value===preset);$('size-preset').value=known?preset:'custom';$('custom-size').hidden=known;$('custom-width').value=p.width;$('custom-height').value=p.height;
    $('crop-scale').value=Math.round(p.scale*100);$('crop-scale-label').textContent=`${Math.round(p.scale*100)}%`;$('crop-x').value=Math.round(p.x);$('crop-y').value=Math.round(p.y);
    $$('input[type=range]').forEach(setRangeFill);syncCrop();
    $('cover-time').textContent=p.coverTime===null?'':`已选封面：成片 ${timecode(p.coverTime)}`;
  }
  function syncCrop() {
    const p=profile();$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--scale',p.scale);$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--pan-x',`${p.x}%`);$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--pan-y',`${p.y}%`);
    $('device-unit').querySelector('.phone-body').style.aspectRatio=`${p.width}/${p.height}`;
    $('resolution-label').textContent=`${p.width} × ${p.height} px`;$('preview-dimension').textContent=Math.abs(p.width/p.height-9/16)<.001?'9 : 16':`${(p.height/p.width).toFixed(2)} : 1 纵向`;
  }
  function renderSequence() {
    const p=profile();$('sequence-track').innerHTML=p.clips.map((c,i)=>{const asset=assetById(c.assetId),count=clamp(Math.ceil(Core.clipDuration(c)*3),2,12);return `<div class="sequence-clip${c.id===p.selectedClipId?' selected':''}" data-clip-id="${esc(c.id)}" draggable="true" tabindex="0" role="button" aria-label="片段 ${i+1}：${esc(asset?.name||'缺失素材')}，拖动排序"><div class="sequence-clip-title"><b>${String(i+1).padStart(2,'0')}</b> ${esc(asset?.name||'缺失素材')}</div><div class="filmstrip">${Array.from({length:count},(_,n)=>`<div class="film-frame"><div class="media-content" data-thumb-time="${c.start+n/count*(c.end-c.start)}">${sceneHTML(asset)}</div></div>`).join('')}</div><div class="sequence-clip-info">${c.kind==='image'?`静态 · ${Math.round(Core.clipDuration(c)*FPS)} 帧`:`${c.start.toFixed(2)}–${c.end.toFixed(2)} s · ${c.speed.toFixed(2)}×`}</div><button class="clip-edge start" data-edge="start" aria-label="裁剪片段 ${i+1} 起点" draggable="false"></button><button class="clip-edge end" data-edge="end" aria-label="裁剪片段 ${i+1} 终点" draggable="false"></button></div>`;}).join('')||'<div class="sequence-placeholder">＋ 拖入素材库或文件夹中的图片或视频</div>';
    $$('#sequence-track [data-thumb-time]').forEach(frame=>{const t=Number(frame.dataset.thumbTime),c=profile().clips.find(c=>c.id===frame.closest('.sequence-clip').dataset.clipId);animateDemo(frame,c?.kind==='image'?0:t);const v=frame.querySelector('video');if(v)v.onloadedmetadata=()=>{if(v.isConnected)v.currentTime=t;};});
    $$('.sequence-clip').forEach(el=>{
      el.onclick=e=>{if(e.target.closest('[data-edge]'))return;pause();profile().selectedClipId=el.dataset.clipId;seek(pointerTime(e));syncInspector();syncTimeline();};
      el.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();Core.select(profile(),el.dataset.clipId);seek(profile().cursor);syncInspector();syncTimeline();}};
      el.ondragstart=e=>{if(e.target.closest('[data-edge]')){e.preventDefault();return;}pause();e.dataTransfer.setData('application/x-qingjing-clip',el.dataset.clipId);e.dataTransfer.effectAllowed='move';el.classList.add('dragging');};
      el.ondragend=clearDropMarks;
      el.querySelectorAll('[data-edge]').forEach(edge=>bindClipEdge(edge,el.dataset.clipId));
    });renderRuler();
  }
  function renderRuler() {
    const d=timelineExtent(),step=d<=10?.5:d<=60?5:Math.ceil(d/12),ticks=[];
    for(let t=0;t<d;t+=step){const whole=t%1===0;ticks.push(`<span class="ruler-tick ${whole?'':'minor'}" style="left:${t/d*100}%">${whole?`${String(Math.floor(t/60)).padStart(2,'0')}:${String(Math.floor(t%60)).padStart(2,'0')}`:`${Math.round((t%1)*FPS)}f`}</span>`);}
    $('ruler').innerHTML=ticks.join('');
  }
  function syncTimeline() {
    const p=profile(),d=timelineExtent(),rows=Core.segments(p),o=outDuration(p);
    rows.forEach(s=>{const el=$$('.sequence-clip').find(el=>el.dataset.clipId===s.clip.id);if(!el)return;el.style.left=`${s.start/d*100}%`;el.style.width=`${s.duration/d*100}%`;el.classList.toggle('selected',s.clip.id===p.selectedClipId);el.querySelector('.sequence-clip-info').textContent=`${s.clip.kind==='image'?`静态 · ${Math.round(s.duration*FPS)} 帧`:`${s.clip.start.toFixed(2)}–${s.clip.end.toFixed(2)} s · ${s.clip.speed.toFixed(2)}×`}`;});
    $('output-range').style.width=`${o/d*100}%`;$('output-range').textContent=`${platformNames[state.platform]}成片 · ${o.toFixed(2)} s · ${Math.round(o*FPS)} 帧`;$('target-marker').style.left=`${targetDuration(p)/d*100}%`;$('target-marker').querySelector('span').textContent=state.platform==='harmony'?'目标 2 s':`目标 ${p.frames} 帧`;
    $('trim-summary').textContent=`${platformNames[state.platform]}独立工程 · ${p.clips.length} 个片段 · 成片 ${o.toFixed(2)} s`;
    const index=p.clips.findIndex(c=>c.id===p.selectedClipId),has=p.clips.length>0;
    $('undo-edit').disabled=!actionHistory||actionHistory.index<=0;$('redo-edit').disabled=!actionHistory||actionHistory.index>=actionHistory.entries.length-1;
    ['delete-clip','duplicate-clip'].forEach(id=>$(id).disabled=index<0);$('split-clip').disabled=!has;$('move-clip-left').disabled=index<=0;$('move-clip-right').disabled=index<0||index===p.clips.length-1;
    ['play-btn','previous-frame','next-frame'].forEach(id=>$(id).disabled=!has||state.product==='4d');$('reset-trim').disabled=!has;
    $('timeline-content').style.width=p.zoom+'%';$('timeline-zoom').value=p.zoom;$('timeline-zoom-value').textContent=p.zoom+'%';setRangeFill($('timeline-zoom'));
    renderRuler();syncPosition();
  }
  function syncPosition() {
    const p=profile(),sourceTime=previewSourceTime(),asset=previewAsset();
    if(loadedMediaId!==(asset?.id||null))renderWallpaperMedia();
    $('playhead').style.left=`${clamp(state.time/timelineExtent()*100,0,100)}%`;$('playhead').setAttribute('aria-valuenow',state.time.toFixed(3));$('playhead').setAttribute('aria-valuemax',outDuration(p));$('current-time').textContent=timecode(state.sourcePreview?0:state.time);
    $('source-badge').textContent=state.sourcePreview?`${asset?.name||'素材'} · 素材预览`:`${asset?.name||'空工程'} · 成片预览`;
    $('preview-time-hint').textContent=state.sourcePreview?'素材预览 · 拖入时间轴编辑':state.product==='static'?'选当前帧作为封面':activeSegment()?.clip.kind==='image'?'成片预览 · 静态图片':`成片预览 · 原视频 ${sourceTime.toFixed(2)} s`;
    if(asset?.demo)animateDemo($('wallpaper-media'),asset.type==='image'?0:sourceTime);
    const v=$('wallpaper-media').querySelector('video'),segment=activeSegment();
    if(v&&v.readyState>=1){
      const native=state.playing&&segment&&segment.clip.speed>=.0625&&segment.clip.speed<=16;
      if(native){const changed=playbackClipId!==segment.clip.id;if(changed){v.pause();playbackClipId=segment.clip.id;v.playbackRate=segment.clip.speed;}if(!v.seeking&&(changed||Math.abs(v.currentTime-sourceTime)>.2))v.currentTime=sourceTime;if(v.paused&&!v.seeking)v.play().catch(()=>{if(state.playing){pause();toast('视频暂时无法播放，请等待素材载入后重试');}});}
      else {v.pause();playbackClipId=null;if(!v.seeking&&Math.abs(v.currentTime-sourceTime)>.015){try{v.currentTime=sourceTime;}catch{}}}
    }
  }
  function animateDemo(container,t) {
    container.querySelectorAll('[data-layer]').forEach(img=>{const layer=Number(img.dataset.layer),wave=Math.sin(t/5*Math.PI*2);const x=wave*(layer===2?4:layer===4?7:layer===0?-2:1),y=Math.cos(t/5*Math.PI*2)*(layer===2?2:1);img.style.transform=`translate(${x}%,${y}%) scale(1.18)`;});
  }
  function pause(){state.playing=false;$('wallpaper-media').querySelector('video')?.pause();$('play-btn').innerHTML=icon('play');$('play-btn').setAttribute('aria-label','播放成片');cancelAnimationFrame(animationFrame);syncPosition();}
  function togglePlay() {
    if(state.playing){pause();return;}if(!profile().clips.length||state.product==='4d')return;
    state.sourcePreview=false;state.product='dynamic';if(state.time>=outDuration(profile()))state.time=0;renderWallpaper();
    state.playing=true;lastTimestamp=0;$('play-btn').innerHTML=icon('pause');$('play-btn').setAttribute('aria-label','暂停播放');syncPosition();animationFrame=requestAnimationFrame(tick);
  }
  function tick(timestamp) {
    if(!state.playing)return;if(!lastTimestamp)lastTimestamp=timestamp;const dt=Math.min((timestamp-lastTimestamp)/1000,.12);lastTimestamp=timestamp;
    const v=$('wallpaper-media').querySelector('video');if(!v||v.readyState>=2&&!v.seeking)state.time+=dt;
    const total=outDuration(profile());if(state.time>=total){if(state.loop)state.time=state.time%total;else{state.time=total;profile().cursor=state.time;pause();return;}}
    profile().cursor=state.time;syncPosition();if(state.playing)animationFrame=requestAnimationFrame(tick);
  }
  function seek(t){pause();state.sourcePreview=false;state.time=clamp(t,0,outDuration(profile()));profile().cursor=state.time;syncPosition();}
  function record(before,label='编辑片段') {profile().coverTime=null;commitAction(`${platformNames[state.platform]} · ${label}`);}
  function editProject(mutator,label='编辑片段') {finishPendingAction();pause();const p=profile(),before=Core.snapshot(p);if(mutator(p)===false)return false;if(JSON.stringify(before)===JSON.stringify(Core.snapshot(p)))return false;state.sourcePreview=false;state.product='dynamic';state.time=p.cursor;record(before,label);renderWallpaper();return true;}
  function undoEdit(redo=false) {finishPendingAction();if(actionHistory)restoreHistory(actionHistory.index+(redo?1:-1));}
  function updateTrim(start,end) {const c=Core.selected(profile());if(c)editProject(p=>Core.trim(p,c.id,start,end,c.kind==='image'?Infinity:assetById(c.assetId).duration),'裁剪片段');}
  function changePlatform(key) {pause();state.platform=key;state.time=profile().cursor;state.sourcePreview=false;renderWallpaper();setStatus(`${platformNames[key]}独立剪辑工程`);}
  function fitTarget() {if(editProject(p=>Core.fit(p,targetDuration(p)),'适配成片时长'))toast(state.platform==='harmony'?'已调整整条成片到 2 秒':`已调整整条成片到 ${profile().frames} 帧`);else toast('当前时长变化过大，请先裁剪片段再适配');}
  function splitClip() {if(!editProject(p=>Core.split(p,state.time),'剪断片段'))toast('请把播放头移到片段内部，再剪断');}
  function moveSelected(delta) {const p=profile(),index=p.clips.findIndex(c=>c.id===p.selectedClipId);editProject(p=>Core.move(p,p.selectedClipId,index+delta),'移动片段');}
  function pointerTime(event) {const rect=$('timeline-content').getBoundingClientRect();return clamp((event.clientX-rect.left)/rect.width*timelineExtent(),0,timelineExtent());}
  function dragTimeline(element) {
    element.addEventListener('pointerdown',event=>{if(state.product!=='dynamic')return;event.preventDefault();pause();element.setPointerCapture(event.pointerId);seek(pointerTime(event));
      const move=e=>seek(pointerTime(e));const finish=()=>{element.removeEventListener('pointermove',move);element.removeEventListener('pointerup',finish);element.removeEventListener('pointercancel',finish);};element.addEventListener('pointermove',move);element.addEventListener('pointerup',finish);element.addEventListener('pointercancel',finish);});
  }
  function bindClipEdge(edge,id) {
    edge.onpointerdown=e=>{e.preventDefault();e.stopPropagation();pause();const p=profile(),c=p.clips.find(c=>c.id===id),before=Core.snapshot(p),initial={...c},x=e.clientX,extent=timelineExtent(),width=$('timeline-content').getBoundingClientRect().width;state.sourcePreview=false;
      const move=ev=>{const delta=(ev.clientX-x)/width*extent;
        if(c.kind==='image'){const frames=clamp(Math.round((Core.clipDuration(initial)+(edge.dataset.edge==='end'?delta:-delta))*FPS),1,30000);Core.stillFrames(p,id,frames);}
        else {let start=initial.start,end=initial.end;if(edge.dataset.edge==='start')start=clamp(start+delta*initial.speed,0,end-Core.MIN);else end=clamp(end+delta*initial.speed,start+Core.MIN,assetById(c.assetId).duration);Core.trim(p,id,start,end,assetById(c.assetId).duration);}
        state.time=p.cursor;syncInspector();syncTimeline();scheduleSave();};
      const finish=()=>{window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',finish);window.removeEventListener('pointercancel',finish);if(JSON.stringify(before)!==JSON.stringify(Core.snapshot(p)))record(before,'裁剪片段');renderWallpaper();};window.addEventListener('pointermove',move);window.addEventListener('pointerup',finish);window.addEventListener('pointercancel',finish);};
  }
  function insertionIndex(e) {const el=e.target.closest('.sequence-clip');if(el){const index=profile().clips.findIndex(c=>c.id===el.dataset.clipId),rect=el.getBoundingClientRect();return index+(e.clientX>rect.left+rect.width/2?1:0);}const t=pointerTime(e);return Core.segments(profile()).filter(s=>t>s.start+s.duration/2).length;}
  function clearDropMarks() {$('sequence-track').classList.remove('drop-ready');$$('.sequence-clip').forEach(el=>el.classList.remove('drop-before','drop-after','dragging'));}
  function addAssetToTimeline(asset,index=profile().clips.length) {if(!['image','video'].includes(asset?.type)){toast('时间轴支持图片和视频；4D 包在资源区整理');return false;}const changed=editProject(p=>Core.insert(p,asset.id,asset.duration,index,asset.type),asset.type==='image'?'加入图片 · 默认 10 帧':'加入视频');if(changed)toast(`已加入${platformNames[state.platform]}工程${asset.type==='image'?' · 静态片段 10 帧':''}`);return changed;}
  function bindSequenceDrop() {
    $('timeline').ondragover=e=>{const types=[...e.dataTransfer.types];if(!types.some(t=>['Files','application/x-qingjing-asset','application/x-qingjing-clip'].includes(t)))return;e.preventDefault();clearDropMarks();$('drop-overlay').hidden=true;$('sequence-track').classList.add('drop-ready');const el=e.target.closest('.sequence-clip');if(el){const rect=el.getBoundingClientRect();el.classList.add(e.clientX<rect.left+rect.width/2?'drop-before':'drop-after');}e.dataTransfer.dropEffect=types.includes('application/x-qingjing-clip')?'move':'copy';};
    $('timeline').ondragleave=e=>{if(!$('timeline').contains(e.relatedTarget))clearDropMarks();};
    $('timeline').ondrop=async e=>{e.preventDefault();e.stopPropagation();const index=insertionIndex(e),clipId=e.dataTransfer.getData('application/x-qingjing-clip'),assetId=e.dataTransfer.getData('application/x-qingjing-asset'),files=[...e.dataTransfer.files];clearDropMarks();$('drop-overlay').hidden=true;
      if(clipId){const old=profile().clips.findIndex(c=>c.id===clipId);editProject(p=>Core.move(p,clipId,index-(old<index?1:0)),'拖动片段排序');}else if(assetId)addAssetToTimeline(assetById(assetId),index);else if(files.length)await importFiles(files,'timeline',index);};
  }
  function renderPublishAssets(){const post=currentPost();$('publish-assets').innerHTML=state.postAssets.map(a=>assetRow(a,post.assetIds.includes(a.id),true)).join('');$$('#publish-assets .asset-row').forEach(b=>b.onclick=()=>{const asset=state.postAssets.find(a=>a.id===b.dataset.assetId);if(asset.type!==post.type){post.type=asset.type;post.assetIds=[asset.id];}else if(asset.type==='video'){post.assetIds=[asset.id];}else{post.assetIds=post.assetIds.includes(asset.id)?post.assetIds.filter(id=>id!==asset.id):[...post.assetIds,asset.id];}renderPublish();});}
  function syncPostPreview() {
    const post=currentPost();$('preview-title').textContent=post.title||'在右侧写下标题';$('preview-body').textContent=[post.body,post.tags].filter(Boolean).join('\n\n');$('title-count').textContent=`${[...post.title].length} 字`;$('cover-title-overlay').textContent=post.title;$('cover-title-overlay').hidden=!post.coverTitle;
    const selected=state.accounts.find(a=>a.platform===state.social&&state.selectedAccounts.has(a.id));$('preview-account').textContent=selected?.name||'倾境 · 发布预览';$('social-phone').classList.toggle('douyin',state.social==='douyin');
  }
  function renderPostMedia() {
    const post=currentPost(),asset=state.postAssets.find(a=>a.id===post.assetIds[0]);$('publish-media').innerHTML=sceneHTML(asset,{preview:true});$('post-page-indicator').textContent=post.type==='video'?'视频':`1 / ${post.assetIds.length||0}`;
    const v=$('publish-media').querySelector('video');if(v){v.controls=true;v.style.pointerEvents='auto';v.classList.remove('uploaded-video');v.style.cssText='width:100%;height:100%;object-fit:cover;pointer-events:auto';}
    if(asset?.demo&&post.type==='video')$('post-page-indicator').textContent='视频示意 · 5 s';
  }
  function renderAccounts(){
    $('account-list').innerHTML=state.accounts.map(a=>`<label class="account-row"><span class="account-avatar ${a.category}">${categories[a.category][0]}</span><span class="account-copy"><strong>${esc(a.name)}</strong><span><em class="platform-name ${a.platform}">${socialNames[a.platform]}</em> · ${categories[a.category]}</span></span><input type="checkbox" aria-label="选择${esc(a.name)}" data-account="${a.id}" ${state.selectedAccounts.has(a.id)?'checked':''}></label>`).join('');
    $('account-count').textContent=state.accounts.length;$$('[data-account]').forEach(input=>input.onchange=()=>{input.checked?state.selectedAccounts.add(input.dataset.account):state.selectedAccounts.delete(input.dataset.account);syncAccountSummary();syncPostPreview();});syncAccountSummary();
  }
  function syncAccountSummary(){const selected=state.accounts.filter(a=>state.selectedAccounts.has(a.id));$('selected-count').textContent=`已选 ${selected.length} 个账号`;$('platform-summary').textContent=`小红书 ${selected.filter(a=>a.platform==='xhs').length} · 抖音 ${selected.filter(a=>a.platform==='douyin').length}`;$('publish-preview-btn').disabled=!selected.length;}
  function renderPublish() {
    const post=currentPost();$$('#social-tabs button').forEach(b=>{b.classList.toggle('active',b.dataset.social===state.social);b.setAttribute('aria-pressed',b.dataset.social===state.social);});$$('#post-type-tabs button').forEach(b=>b.classList.toggle('active',b.dataset.postType===post.type));
    $('editing-platform').textContent=socialNames[state.social];$('post-title').value=post.title;$('post-body').value=post.body;$('post-tags').value=post.tags;$('copy-style').value=post.style;$('cover-title-toggle').checked=post.coverTitle;renderPublishAssets();renderPostMedia();syncPostPreview();renderAccounts();
  }
  function switchWorkspace(name) {
    pause();state.workspace=name;$('wallpaper-workspace').hidden=name!=='wallpaper';$('publish-workspace').hidden=name!=='publish';$$('[data-workspace]').forEach(b=>{b.classList.toggle('active',b.dataset.workspace===name);b.setAttribute('aria-pressed',b.dataset.workspace===name);});$('export-btn').querySelector('span').textContent=name==='wallpaper'?'导出产品':'导出内容';$('status-middle').textContent=name==='wallpaper'?'素材导入 → 剪辑调整 → 导出产品':'制作内容 → 选择账号 → 发布清单';
    setStatus(name==='wallpaper'?`${selectedAsset().name} · ${platformNames[state.platform]}版本`:'独立内容工作区 · 示例账号');if(name==='publish')renderPublish();else syncTimeline();
  }
  async function readAsset(file) {
    const ext=file.name.split('.').pop().toLowerCase();let type=['png','jpg','jpeg','webp'].includes(ext)?'image':['mp4','mov','webm'].includes(ext)?'video':ext==='zip'?'4d':null;
    if(!['png','jpg','jpeg','webp','mp4','mov','webm','zip'].includes(ext)||!type)throw new Error(`暂不支持「${file.name}」，请导入图片、MP4/MOV/WebM 视频或 ZIP`);
    const asset={id:`local-${Date.now()}-${Math.random().toString(36).slice(2,8)}`,name:file.name.replace(/\.[^.]+$/,''),type,file,url:URL.createObjectURL(file),demo:false};objectUrls.push(asset.url);
    if(type==='4d')return asset;
    await new Promise((resolve,reject)=>{const element=type==='image'?new Image():document.createElement('video');const timer=setTimeout(()=>{element.src='';reject(new Error(`读取「${file.name}」超时，请重试或更换格式`));},12000);const fail=()=>{clearTimeout(timer);reject(new Error(`无法读取「${file.name}」，请检查文件格式或视频编码`));};element.onerror=fail;if(type==='image'){element.onload=()=>{clearTimeout(timer);asset.width=element.naturalWidth;asset.height=element.naturalHeight;resolve();};}else{element.preload='metadata';element.onloadedmetadata=()=>{clearTimeout(timer);if(!Number.isFinite(element.duration)||element.duration<1/FPS){reject(new Error('视频时长太短或无法读取'));return;}asset.width=element.videoWidth;asset.height=element.videoHeight;asset.duration=element.duration;resolve();};}element.src=asset.url;});
    return asset;
  }
  async function importFiles(files,workspace,index) {
    if(!projectReady)return;importsPending++;['new-project-btn','project-list-btn'].forEach(id=>$(id).disabled=true);
    try{await performImportFiles(files,workspace,index);}finally{importsPending--;['new-project-btn','project-list-btn'].forEach(id=>$(id).disabled=importsPending>0);}
  }
  async function performImportFiles(files,workspace,index) {
    const targetPlatform=state.platform;
    const incoming=[];for(const file of files){try{const asset=await readAsset(file);if(workspace==='publish'&&asset.type==='4d'){URL.revokeObjectURL(asset.url);toast('4D 资源包请导入壁纸加工区；推广区使用图片或视频');continue;}incoming.push(asset);}catch(error){toast(error.message);}}
    if(!incoming.length)return;incoming.forEach(registerMedia);
    if(workspace==='timeline'){
      const videos=incoming.filter(a=>['image','video'].includes(a.type));state.assets.push(...incoming);state.filter='all';
      if(!videos.length){renderAssets();commitAction('导入 4D 素材');toast('资源包已放入素材库，时间轴支持图片或视频');return;}
      const p=profiles()[targetPlatform];let at=index??p.clips.length;videos.forEach(a=>Core.insert(p,a.id,a.duration,at++,a.type));p.coverTime=null;
      if(state.platform===targetPlatform){pause();state.sourcePreview=false;state.product='dynamic';state.time=p.cursor;renderWallpaper();}else renderAssets();
      commitAction(`${platformNames[targetPlatform]} · 加入 ${videos.length} 个素材`);await flushSave();toast(`已将 ${videos.length} 个素材加入${platformNames[targetPlatform]}时间轴，图片默认 10 帧`);return;
    }
    if(workspace==='wallpaper'){state.assets.push(...incoming);state.filter='all';selectAsset(incoming[0].id);}else{state.postAssets.push(...incoming);const post=currentPost();post.type=incoming[0].type;post.assetIds=post.type==='video'?[incoming[0].id]:incoming.filter(a=>a.type==='image').map(a=>a.id);renderPublish();}
    commitAction(`导入 ${incoming.length} 个${workspace==='publish'?'推广':'壁纸'}素材`);await flushSave();toast(`已导入 ${incoming.length} 个素材并自动保存`);
  }
  function modal(title,body,actions=[]) {
    pause();$('dialog').classList.remove('project-browser');$('dialog-close').hidden=false;$('dialog-title').textContent=title;$('dialog-body').innerHTML=body;$('dialog-actions').replaceChildren();
    for(const action of actions){const b=document.createElement('button');b.textContent=action.label;if(action.primary)b.className='primary';b.onclick=action.run;$('dialog-actions').append(b);}if(!$('dialog').open)$('dialog').showModal();
  }
  function closeModal(){if(activeProject)$('dialog').close();else showProjectList();}
  function download(name,data,type='application/json') {const url=URL.createObjectURL(data instanceof Blob?data:new Blob([data],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);}
  function safeName(){return (state.name||'倾境壁纸').replace(/[\\/:*?"<>|]/g,'_').slice(0,60);}
  function wallpaperPlan(allVersions=false){const versions=allVersions?Object.fromEntries(Object.entries(profiles()).map(([k,p])=>[k,Core.snapshot(p)])):{[state.platform]:Core.snapshot(profile())};return {version:3,prototype:true,name:state.name,source:{id:selectedAsset().id,name:selectedAsset().name,type:selectedAsset().type},sourcePreview:state.sourcePreview,sources:state.assets.map(a=>({id:a.id,name:a.name,type:a.type,duration:a.duration||null,demo:!!a.demo})),product:state.product,previewFps:FPS,activePlatform:state.platform,versions,note:'独立工程剪辑方案；不是苹果或鸿蒙成品资源包。视频编码与平台打包尚未接入。'};}
  async function exportStill() {
    const p=profile();if(state.product==='static'&&!state.sourcePreview&&p.coverTime!==null)seek(p.coverTime);const asset=previewAsset();if(!asset){toast('请先加入视频素材');return;}const canvas=document.createElement('canvas');canvas.width=p.width;canvas.height=p.height;const ctx=canvas.getContext('2d');ctx.fillStyle='#15171b';ctx.fillRect(0,0,p.width,p.height);
    const sources=asset.demo?[...$('wallpaper-media').querySelectorAll('img')]:[$('wallpaper-media').querySelector('img,video')].filter(Boolean);
    try {
      const video=sources.find(el=>el.tagName==='VIDEO');
      if(video)await new Promise((resolve,reject)=>{
        const check=()=>{if(video.readyState>=2&&!video.seeking&&Math.abs(video.currentTime-previewSourceTime())<.04){cleanup();resolve();}};
        const cleanup=()=>{clearTimeout(timer);['seeked','loadeddata','loadedmetadata'].forEach(event=>video.removeEventListener(event,check));};
        const timer=setTimeout(()=>{cleanup();reject(new Error('请等待视频当前帧载入后重试'));},8000);
        ['seeked','loadeddata','loadedmetadata'].forEach(event=>video.addEventListener(event,check));check();
      });
      for(const media of sources){if(media.tagName==='IMG')await media.decode();const w=media.naturalWidth||media.videoWidth,h=media.naturalHeight||media.videoHeight;if(!w||!h)throw new Error('素材尚未载入');const ratio=Math.max(p.width/w,p.height/h)*p.scale*(asset.demo?1.18:1),dw=w*ratio,dh=h*ratio;ctx.save();if(media.classList.contains('light')){ctx.globalAlpha=.25;ctx.globalCompositeOperation='screen';}let dx=0,dy=0;if(asset.demo){const layer=Number(media.dataset.layer),wave=Math.sin(previewSourceTime()/5*Math.PI*2);dx=wave*(layer===2?4:layer===4?7:layer===0?-2:1)/100*p.width*p.scale;dy=Math.cos(previewSourceTime()/5*Math.PI*2)*(layer===2?2:1)/100*p.height*p.scale;}ctx.drawImage(media,(p.width-dw)/2+p.x/100*p.width+dx,(p.height-dh)/2+p.y/100*p.height+dy,dw,dh);ctx.restore();}
      const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));if(!blob)throw new Error('图片导出失败');download(`${safeName()}-${p.width}x${p.height}-预览.png`,blob);toast('已导出当前尺寸的 PNG 预览图');
    }catch(error){toast(`暂时无法导出预览：${error.message}`);}
  }
  function openExport() {
    if(state.workspace==='publish'){openContentExport();return;}
    const a=selectedAsset();
    if(state.product==='4d'){
      modal('导出 4D 资源',`<p class="dialog-intro">${esc(a.name)}</p><div class="export-row">${icon('layers')}<div><strong>${a.demo?'内置五层示例':'保留原始 ZIP'}</strong><small>${a.demo?'可前往原 4D 工作台制作与导出':'导出你上传的资源包，不改变包内内容'}</small></div></div><p class="dialog-note">4D 壁纸独立制作，这里提供资源整理入口。</p>`,[{label:'返回编辑',run:closeModal},{label:a.demo?'打开 4D 工作台':'下载原始资源包',primary:true,run:()=>{if(a.demo)window.open('../parallax-studio/','_blank','noopener');else download(a.file.name,a.file);}}]);return;
    }
    const p=profile(),o=outDuration(p),rows=state.product==='dynamic'?`<div class="export-row">${icon('phone')}<div><strong>${platformNames[state.platform]}独立工程</strong><small>${p.width} × ${p.height} · ${p.clips.length} 个片段 · 按当前顺序合成</small></div><span>${o.toFixed(2)} s / ${Math.round(o*FPS)} 帧</span></div>`:'';
    modal('导出当前工程 · 原型预览',`<p class="dialog-intro">${esc(state.name)} · ${platformNames[state.platform]}工程</p><div class="export-row">${icon('image')}<div><strong>静态预览图</strong><small>当前画面 · ${p.width} × ${p.height} px · PNG</small></div><span>可下载</span></div>${rows}<p class="dialog-note">当前可下载 PNG 预览和本工程的剪辑方案。正式视频编码、苹果／鸿蒙动态图片打包将在需求确认后接入。</p>`,[{label:'返回编辑',run:closeModal},{label:'下载剪辑方案',run:()=>{download(`${safeName()}-${platformNames[state.platform]}-剪辑方案.json`,JSON.stringify(wallpaperPlan(),null,2));toast('已下载当前平台的独立剪辑方案');}},{label:'下载 PNG 预览',primary:true,run:exportStill}]);
  }

  function postBundle(){return {prototype:true,posts:state.posts,accounts:state.accounts.filter(a=>state.selectedAccounts.has(a.id)),timing:state.timing,scheduledAt:state.scheduledAt,assets:state.postAssets.map(a=>({id:a.id,name:a.name,type:a.type})),note:'这是内容方案与模拟账号清单，不是已发布结果。'};}
  function downloadCopy(){const post=currentPost();download(`${socialNames[state.social]}-文案.txt`,`${post.title}\n\n${post.body}\n\n${post.tags}`,'text/plain;charset=utf-8');toast('已导出当前平台文案');}
  function openContentExport(){modal('导出推广内容',`<p class="dialog-intro">小红书与抖音的文案分别保留，可在你选择的发布工具中继续使用。</p><div class="export-row">${icon('text')}<div><strong>当前平台文案</strong><small>${socialNames[state.social]} · 标题、正文和话题</small></div><span>TXT</span></div><div class="export-row">${icon('users')}<div><strong>完整内容方案</strong><small>两平台文案、素材清单、选定账号与发布时间</small></div><span>JSON</span></div><p class="dialog-note">示例账号尚未连接。原型不向社交平台发送内容。</p>`,[{label:'返回编辑',run:closeModal},{label:'导出内容方案',run:()=>download('倾境-推广内容方案.json',JSON.stringify(postBundle(),null,2))},{label:'导出文案',primary:true,run:downloadCopy}]);}
  function openPublishPreview() {
    const selected=state.accounts.filter(a=>state.selectedAccounts.has(a.id));if(!selected.length){toast('请至少选择一个发布账号');return;}
    const missing=selected.find(a=>!state.posts[a.platform].title.trim()||!state.posts[a.platform].assetIds.length);if(missing){toast(`请先完善${socialNames[missing.platform]}的标题与素材`);return;}
    if(state.timing==='scheduled'&&(!state.scheduledAt||new Date(state.scheduledAt).getTime()<=Date.now())){toast('请选择未来的发布时间');$('publish-date').focus();return;}
    const rows=selected.map(a=>`<div class="publish-result-row"><span class="account-avatar ${a.category}">${categories[a.category][0]}</span><div><strong>${esc(a.name)}</strong><small>${socialNames[a.platform]} · ${state.posts[a.platform].type==='video'?'视频':'图文'} · ${esc(state.posts[a.platform].title)}</small></div><span class="count">模拟任务</span></div>`).join('');
    modal('发布清单 · 演示',`<p class="dialog-intro">共 ${selected.length} 个账号 · ${state.timing==='now'?'立即发布':esc(state.scheduledAt.replace('T',' '))}</p>${rows}<p class="dialog-note">账号与发布结果均为原型演示。确认后只生成本地演示记录，不会发布到小红书或抖音。</p>`,[{label:'返回修改',run:closeModal},{label:'确认演示',primary:true,run:()=>{state.published.push({at:new Date().toISOString(),accounts:selected.map(a=>a.name)});modal('发布流程演示完成',`<p class="dialog-intro">已模拟创建 ${selected.length} 条发布任务。</p>${selected.map(a=>`<div class="publish-result-row">${icon('check')}<div><strong>${esc(a.name)}</strong><small>${socialNames[a.platform]} · 演示记录，未向平台发送</small></div></div>`).join('')}<p class="dialog-note">正式版会在这里显示真实的发布状态、作品链接和失败重试入口。</p>`,[{label:'完成',primary:true,run:closeModal}]);}}]);
  }
  function manageAccounts(){
    modal('账号管理 · 原型',`<p class="dialog-intro">先确认账号数量与垂直分类。当前都是演示账号。</p><div id="account-manage-list">${state.accounts.map(a=>`<div class="account-manage-row"><span class="platform-name ${a.platform}">${socialNames[a.platform]}</span><span>${esc(a.name)}</span><select aria-label="${esc(a.name)}的分类" data-category-account="${a.id}">${Object.entries(categories).map(([key,label])=>`<option value="${key}"${key===a.category?' selected':''}>${label}</option>`).join('')}</select></div>`).join('')}</div><form id="add-account-form" class="add-account-form"><input id="new-account-name" aria-label="新账号名称" maxlength="24" placeholder="新账号名称" required><select id="new-account-platform" aria-label="新账号平台"><option value="xhs">小红书</option><option value="douyin">抖音</option></select><button type="submit">添加</button></form><p class="dialog-note">正式账号登录与授权在选定分发工具后接入。</p>`,[{label:'完成',primary:true,run:()=>{renderAccounts();syncPostPreview();closeModal();}}]);
    $$('[data-category-account]').forEach(select=>select.onchange=()=>{state.accounts.find(a=>a.id===select.dataset.categoryAccount).category=select.value;commitAction('调整账号分类');});
    $('add-account-form').onsubmit=event=>{event.preventDefault();const name=$('new-account-name').value.trim();if(!name)return;state.accounts.push({id:`account-${Date.now()}`,name,platform:$('new-account-platform').value,category:'simple'});commitAction('添加示例账号');manageAccounts();renderAccounts();toast('已添加演示账号');};
  }
  function bindEvents(){
    $$('[data-workspace]').forEach(b=>b.onclick=()=>switchWorkspace(b.dataset.workspace));$$('[data-platform]').forEach(b=>b.onclick=()=>changePlatform(b.dataset.platform));$$('[data-import]').forEach(b=>b.onclick=()=>$(b.dataset.import+'-input').click());
    $('wallpaper-input').onchange=event=>{importFiles([...event.target.files],'wallpaper');event.target.value='';};$('timeline-input').onchange=event=>{importFiles([...event.target.files],'timeline');event.target.value='';};$('publish-input').onchange=event=>{importFiles([...event.target.files],'publish');event.target.value='';};
    $$('#asset-filters button').forEach(b=>b.onclick=()=>{state.filter=b.dataset.filter;renderAssets();});
    $$('#product-tabs button').forEach(b=>b.onclick=()=>{pause();const kind=b.dataset.product;if(kind==='4d'){state.selectedId=state.assets.find(a=>a.type==='4d')?.id||state.selectedId;state.sourcePreview=true;}else if(kind==='dynamic'){state.sourcePreview=false;state.time=profile().cursor;}else if(selectedAsset().type==='4d'){state.selectedId=state.assets.find(a=>a.type==='image')?.id||state.selectedId;state.sourcePreview=true;}state.product=kind;renderWallpaper();});
    $('back-to-sequence').onclick=()=>{pause();state.sourcePreview=false;state.time=profile().cursor;renderWallpaper();};$('add-to-timeline').onclick=()=>addAssetToTimeline(selectedAsset());$('import-to-timeline').onclick=()=>$('timeline-input').click();
    $('project-name').oninput=e=>{state.name=e.target.value;document.querySelector('.project-info strong').textContent=state.name||'未命名产品';};
    $('size-preset').onchange=e=>{if(e.target.value==='custom'){$('custom-size').hidden=false;return;}const [w,h]=e.target.value.split('x').map(Number);Object.assign(profile(),{width:w,height:h});syncInspector();};
    ['custom-width','custom-height'].forEach(id=>$(id).onchange=()=>{const w=Number($('custom-width').value),h=Number($('custom-height').value);if(!Number.isInteger(w)||!Number.isInteger(h)||w<64||w>8192||h<64||h>8192||w*h>33554432){toast('请输入 64–8192 像素的尺寸，总像素不超过 3200 万');syncInspector();return;}Object.assign(profile(),{width:w,height:h});syncInspector();});
    $('in-point').onchange=e=>{const c=Core.selected(profile());if(!c||!Number.isFinite(e.target.valueAsNumber)){syncInspector();return;}updateTrim(Math.min(e.target.valueAsNumber,c.end-Core.MIN),c.end);};$('out-point').onchange=e=>{const c=Core.selected(profile());if(!c||!Number.isFinite(e.target.valueAsNumber)){syncInspector();return;}updateTrim(c.start,e.target.valueAsNumber);};
    let sliderBefore=null,sliderPlatform=null;
    const beginSlider=()=>{pause();sliderBefore=Core.snapshot(profile());sliderPlatform=state.platform;};
    $('speed').onpointerdown=beginSlider;$('speed').onkeydown=e=>{if(!sliderBefore&&e.key.startsWith('Arrow'))beginSlider();};
    $('speed').oninput=e=>{if(!sliderBefore)beginSlider();const c=Core.selected(profile());if(!c)return;Core.speed(profile(),c.id,Number(e.target.value));state.sourcePreview=false;state.time=profile().cursor;syncInspector();syncTimeline();scheduleSave();};
    $('speed').onchange=()=>{if(sliderBefore&&sliderPlatform===state.platform&&JSON.stringify(sliderBefore)!==JSON.stringify(Core.snapshot(profile())))record(sliderBefore,'调整片段速度');sliderBefore=null;renderWallpaper();};
    $$('[data-speed]').forEach(b=>b.onclick=()=>{const c=Core.selected(profile());if(c)editProject(p=>Core.speed(p,c.id,Number(b.dataset.speed)),'调整片段速度');});
    $('still-frames').onchange=e=>{const c=Core.selected(profile());if(!c)return;const n=e.target.valueAsNumber;if(!Number.isInteger(n)||n<1||n>30000){toast('静态片段时长请输入 1–30000 帧');syncInspector();return;}editProject(p=>Core.stillFrames(p,c.id,n),'调整静态片段帧数');};
    $('ios-frames').oninput=e=>{profile().frames=Number(e.target.value);syncInspector();syncTimeline();};$('fit-target').onclick=fitTarget;
    $('crop-scale').oninput=e=>{profile().scale=Number(e.target.value)/100;syncInspector();};['crop-x','crop-y'].forEach(id=>$(id).onchange=e=>{const n=e.target.valueAsNumber;if(!Number.isFinite(n)){syncInspector();return;}profile()[id==='crop-x'?'x':'y']=clamp(n,-50,50);syncInspector();});
    $('reset-crop').onclick=()=>{Object.assign(profile(),{scale:1,x:0,y:0});syncInspector();};$('phone-toggle').onchange=e=>document.querySelector('.phone-body').classList.toggle('no-frame',!e.target.checked);$('lock-toggle').onchange=e=>$('lock-overlay').hidden=!e.target.checked;$('safe-toggle').onchange=e=>$('safe-overlay').hidden=!e.target.checked;
    $('play-btn').onclick=togglePlay;$('previous-frame').onclick=()=>seek(state.time-1/FPS);$('next-frame').onclick=()=>seek(state.time+1/FPS);$('loop-toggle').onchange=e=>state.loop=e.target.checked;
    $('split-clip').onclick=splitClip;$('delete-clip').onclick=()=>editProject(p=>Core.remove(p,p.selectedClipId),'删除片段');$('duplicate-clip').onclick=()=>editProject(p=>Core.duplicate(p,p.selectedClipId),'复制片段');$('move-clip-left').onclick=()=>moveSelected(-1);$('move-clip-right').onclick=()=>moveSelected(1);$('undo-edit').onclick=()=>undoEdit();$('redo-edit').onclick=()=>undoEdit(true);
    $('reset-trim').onclick=()=>{editProject(p=>{p.clips=[];p.selectedClipId=null;p.cursor=0;},'清空时间轴');toast(`已清空${platformNames[state.platform]}时间轴，可撤销`);};
    $('timeline-zoom').oninput=e=>{profile().zoom=Number(e.target.value);syncTimeline();scheduleSave();};
    dragTimeline($('ruler'));dragTimeline($('playhead').querySelector('span'));bindSequenceDrop();
    $('phone-screen').onpointerdown=event=>{if(state.product==='4d')return;event.preventDefault();const el=$('phone-screen'),rect=el.getBoundingClientRect(),p=profile(),startX=event.clientX,startY=event.clientY,x=p.x,y=p.y;el.setPointerCapture(event.pointerId);const move=e=>{p.x=clamp(x+(e.clientX-startX)/rect.width*100,-50,50);p.y=clamp(y+(e.clientY-startY)/rect.height*100,-50,50);syncInspector();scheduleSave();};const finish=()=>{el.removeEventListener('pointermove',move);el.removeEventListener('pointerup',finish);el.removeEventListener('pointercancel',finish);};el.addEventListener('pointermove',move);el.addEventListener('pointerup',finish);el.addEventListener('pointercancel',finish);};
    $('capture-cover').onclick=()=>{profile().coverTime=state.sourcePreview?null:state.time;$('cover-time').textContent=`已选封面：${timecode(state.sourcePreview?0:state.time)}`;toast('当前画面已标记为静态封面');};
    $('restore-demo').onclick=()=>createProject('城市飞行示例','simple',true);
    $$('#social-tabs button').forEach(b=>b.onclick=()=>{state.social=b.dataset.social;renderPublish();});$$('#post-type-tabs button').forEach(b=>b.onclick=()=>{const post=currentPost();post.type=b.dataset.postType;post.assetIds=state.postAssets.filter(a=>a.type===post.type).slice(0,1).map(a=>a.id);renderPublish();});
    for(const [id,key] of [['post-title','title'],['post-body','body'],['post-tags','tags']])$(id).oninput=e=>{currentPost()[key]=e.target.value;syncPostPreview();};
    $('copy-style').onchange=e=>currentPost().style=e.target.value;$('suggest-copy').onclick=()=>{const post=currentPost(),copy=copyPresets[post.style];Object.assign(post,copy);if(state.social==='douyin'&&post.style==='simple')post.title='这一秒，让屏幕动起来';renderPublish();toast('已填入模板示例，可继续修改；此原型未调用 AI');};$('cover-title-toggle').onchange=e=>{currentPost().coverTitle=e.target.checked;syncPostPreview();};
    $('download-copy').onclick=downloadCopy;$('export-btn').onclick=openExport;$('manage-accounts').onclick=manageAccounts;$('publish-preview-btn').onclick=openPublishPreview;$('publish-timing').onchange=e=>{state.timing=e.target.value;$('publish-date').hidden=state.timing!=='scheduled';};$('publish-date').onchange=e=>state.scheduledAt=e.target.value;
    document.addEventListener('click',e=>{if(!e.target.closest('.project-more,.project-menu'))closeProjectMenus();});$('dialog').addEventListener('cancel',e=>{if(!activeProject){e.preventDefault();showProjectList();}});$('dialog-close').onclick=closeModal;$('dialog').addEventListener('click',e=>{if(e.target===$('dialog')){const r=$('dialog').getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)closeModal();}});
    $('help-btn').onclick=()=>modal('倾境创作台 · 项目原型',`<p class="dialog-intro">每套作品一个项目，鸿蒙与 iOS 各自编辑。</p><ul class="modal-list"><li>从素材库或文件夹拖入图片、视频。图片默认持续 10 帧，可以修改帧数或拖动边缘调整。</li><li>新建项目后自动保存素材、两条时间轴、构图参数与推广内容。项目列表可以打开不同作品。</li><li>历史面板保留最近 15 个动作，点击可还原；撤销与重做使用同一套历史。返回旧状态后继续编辑会替换之后的动作。</li><li>空格播放，方向键逐帧；Cmd/Ctrl+B 剪断，Delete 删除，Cmd/Ctrl+Z 撤销。</li></ul><p class="dialog-note">当前自动保存到本机浏览器，尚未写入电脑上的项目文件夹；清除网站数据会影响本地项目。正式视频编码、动态图片打包与账号发布尚未接入。</p>`,[{label:'知道了',primary:true,run:closeModal}]);
    document.addEventListener('keydown',e=>{if($('dialog').open||state.workspace!=='wallpaper'||state.product!=='dynamic'||/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)||e.target.isContentEditable)return;const modifier=e.metaKey||e.ctrlKey,key=e.key.toLowerCase();if(modifier&&key==='b'){e.preventDefault();splitClip();}else if(modifier&&key==='z'){e.preventDefault();undoEdit(e.shiftKey);}else if(modifier&&key==='y'){e.preventDefault();undoEdit(true);}else if(key==='delete'||key==='backspace'){e.preventDefault();$('delete-clip').click();}else if(e.code==='Space'&&e.target.tagName!=='BUTTON'){e.preventDefault();togglePlay();}else if(e.key==='ArrowLeft'){e.preventDefault();seek(state.time-1/FPS);}else if(e.key==='ArrowRight'){e.preventDefault();seek(state.time+1/FPS);}});
    let dragDepth=0;document.addEventListener('dragenter',e=>{if(![...e.dataTransfer.types].includes('Files'))return;e.preventDefault();dragDepth++;$('drop-overlay').hidden=!!e.target.closest('.timeline');});document.addEventListener('dragover',e=>{if([...e.dataTransfer.types].includes('Files')){e.preventDefault();$('drop-overlay').hidden=!!e.target.closest('.timeline');}});document.addEventListener('dragleave',()=>{dragDepth=Math.max(0,dragDepth-1);if(!dragDepth)$('drop-overlay').hidden=true;});document.addEventListener('dragend',()=>{dragDepth=0;$('drop-overlay').hidden=true;clearDropMarks();});document.addEventListener('drop',e=>{e.preventDefault();dragDepth=0;$('drop-overlay').hidden=true;if(e.dataTransfer.files.length)importFiles(e.dataTransfer.files,state.workspace);});
    window.addEventListener('beforeunload',()=>objectUrls.forEach(url=>URL.revokeObjectURL(url)));
  }
  bindEvents();bindProjectPersistence();renderWallpaper();renderPublish();initializeProjects();
})();
