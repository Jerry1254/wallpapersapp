/* Reusable templates and explicitly confirmed output are separate collections. */
(() => {
  'use strict';
  const C=window.ContentCore,G=window.GalleryCore,$=id=>document.getElementById(id),esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let H,opening,records=[],queue=Promise.resolve(),fingerprints=new Map(),filter='gallery',trash=false,previewURLs=[],worksFilter='all',worksTrash=false;
  const workOperations=new Set();
  const builtins=[{id:'gallery-showcase',name:'壁纸展示图集',type:'gallery',style:'showcase',detail:'4 个画框 · 样机、全图、细节、锁屏'},{id:'gallery-minimal',name:'极简质感图集',type:'gallery',style:'minimal',detail:'3 个画框 · 浅色排版'},{id:'video-showcase',name:'动态样机视频',type:'video',style:'showcase',detail:'多画面轨道 · 12 秒'},{id:'video-detail',name:'细节展示视频',type:'video',style:'detail',detail:'动态与细节 · 12 秒'}];
  function db(){return opening||=new Promise((resolve,reject)=>{const q=indexedDB.open('qingjing-content-templates',1);q.onupgradeneeded=()=>q.result.createObjectStore('templates',{keyPath:'id'});q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});}
  async function load(){const store=(await db()).transaction('templates').objectStore('templates');records=await new Promise((resolve,reject)=>{const q=store.getAll();q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});if(window.CreatorBackend.connected){records=await window.CreatorBackend.migrate('templates',records);for(const record of records)await cachePut(record);}}
  async function cachePut(record){const database=await db();await new Promise((resolve,reject)=>{const tx=database.transaction('templates','readwrite');tx.objectStore('templates').put(record);tx.oncomplete=resolve;tx.onerror=tx.onabort=()=>reject(tx.error);});records=records.filter(t=>t.id!==record.id);records.push(record);}
  async function put(record){await cachePut({...record,_creatorPending:true});if(window.CreatorBackend.connected){await window.CreatorBackend.put('templates',record);await cachePut({...record,_creatorPending:false});}}
  function mount(bridge){
    H=bridge;document.addEventListener('creator-connection',()=>{if(window.CreatorBackend.connected)load().catch(error=>H.toast(error.message));});load().catch(()=>H.toast('模板库暂时无法读取'));$('dialog').addEventListener('close',()=>{if(!$('dialog').open)clearPreview();});
    window.EditingMenu.register({matches:(scope,event)=>scope.id==='dialog'&&!!event.target.closest('[data-work-id]'),items:event=>{
      const id=event.target.closest('[data-work-id]').dataset.workId,item=H.data().items.find(row=>row.id===id);if(!item)return [];
      return [{label:'预览作品',run:()=>openItem(item)},{label:'导出到本地',run:()=>download(item)},{label:'查看生成信息',run:()=>sourceInfo(item)},null,...(item.deletedAt?[{label:'恢复作品',run:()=>setWorkDeleted(item,false)}]:[{label:'修改名称',run:()=>renameWork(item)},{label:'进入下一步',run:()=>nextStep(item)},null,{label:'移入回收站',danger:true,run:()=>setWorkDeleted(item,true)}])];
    }});
  }
  function snapshot(w){const t=C.template(w,w.name);t.id=w.templateId;t.snapshot.slots=C.copy(w.slots);delete t.snapshot.templateId;delete t.snapshot.templateVersion;delete t.snapshot.canvasHistory;if(t.snapshot.gallery){t.snapshot.gallery.view=null;t.snapshot.gallery.selection=[];t.snapshot.gallery.surfaceSelection=[];t.snapshot.gallery.selectedSurface=t.snapshot.gallery.frames[0]?.id||Object.keys(t.snapshot.gallery.components)[0]||G.canvasSurface(t.snapshot.gallery).id;}return t;}
  function save(w){
    if(!w)return queue;if(!w.templateId)w.templateId=C.id();const t=snapshot(w),fingerprint=JSON.stringify(t.snapshot);
    if(fingerprints.get(t.id)===fingerprint)return queue;fingerprints.set(t.id,fingerprint);
    const ids=new Set([...Object.values(w.slots),...G.assets(w),...(w.type==='video'?C.all(w).map(c=>c.assetId):[])].filter(Boolean));
    t.media=[...ids].map(H.resolve).filter(Boolean).map(a=>{const {url,...data}=a;return data;});t.createdAt=records.find(r=>r.id===t.id)?.createdAt||new Date().toISOString();
    queue=queue.catch(()=>{}).then(()=>put(t)).catch(error=>{fingerprints.delete(t.id);H.toast('模板尚未保存到本地后台，请重试');throw error;});queue.catch(()=>{});return queue;
  }
  function mediaFor(t){for(const a of t.media||[]){if(!H.resolve(a.id)){const asset={...a};if(asset.file)asset.url=URL.createObjectURL(asset.file);H.remember(asset);}}}
  function openTemplate(t){mediaFor(t);const w=C.instantiate(t,t.snapshot.slots||{});w.templateId=t.id;H.openDraft(w);H.close();}
  function create(){
    H.modal('新建模板','<div class="content-template-form"><label>模板名称<input id="template-new-name" maxlength="60" value="未命名图片模板"></label><label>模板类型<select id="template-new-type"><option value="gallery">图片模板 · 无限画布</option><option value="video">视频模板 · 多轨时间轴</option></select></label><label>画布尺寸<select id="template-new-size"><option value="1080x1440">3:4 · 1080 × 1440</option><option value="1080x1920">9:16 · 1080 × 1920</option><option value="1080x1080">1:1 · 1080 × 1080</option><option value="1920x1080">16:9 · 1920 × 1080</option><option value="720x1280">9:16 · 720 × 1280</option></select></label></div>',[{label:'取消',run:H.close},{label:'创建模板',primary:true,run:()=>{const name=$('template-new-name').value.trim();if(!name)return $('template-new-name').focus();const type=$('template-new-type').value,w=C.create(type);w.name=name;w.slots=H.slots();w.templateId=C.id();const [width,height]=$('template-new-size').value.split('x').map(Number);w.width=width;w.height=height;if(type==='gallery'){w.pages=[];G.ensure(w);w.gallery.frames=[G.frame(width,height)];w.gallery.selectedSurface=w.gallery.frames[0].id;w.gallery.components={};w.gallery.preset={width,height};}else{w.tracks=[];w.selectedClipId=null;}H.openDraft(w);save(w);H.close();}}]);
    $('template-new-type').onchange=e=>{const name=$('template-new-name');if(name.value==='未命名图片模板'||name.value==='未命名视频模板')name.value=e.target.value==='video'?'未命名视频模板':'未命名图片模板';$('template-new-size').value=e.target.value==='video'?'1080x1920':'1080x1440';};
  }
  async function manage(){
    H.modal('模板库','<p class="content-empty">正在读取模板…</p>',[{label:'返回编辑',run:H.close}]);
    try{await queue.catch(()=>{});await load();}catch{return H.toast('模板库暂时无法读取');}
    H.modal('模板库','<div class="template-manager"><div class="template-manager-toolbar"><div class="segmented"><button data-template-filter="gallery">图片模板</button><button data-template-filter="video">视频模板</button></div><input id="template-search" placeholder="搜索模板名称"><button id="template-trash">'+(trash?'返回模板':'回收站')+'</button><button id="template-create" class="primary">＋ 新建模板</button></div><div id="template-card-list"></div></div>',[{label:'返回编辑',run:H.close}]);
    $('template-create').onclick=create;$('template-trash').onclick=()=>{trash=!trash;manage();};$('template-search').oninput=renderCards;
    document.querySelectorAll('[data-template-filter]').forEach(b=>b.onclick=()=>{filter=b.dataset.templateFilter;renderCards();});renderCards();
  }
  function renderCards(){
    if(!$('template-card-list'))return;document.querySelectorAll('[data-template-filter]').forEach(b=>b.classList.toggle('active',b.dataset.templateFilter===filter));
    const query=$('template-search').value.trim().toLowerCase(),custom=records.filter(t=>t.type===filter&&!!t.deletedAt===trash&&t.name.toLowerCase().includes(query)).sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt)),preset=trash?[]:builtins.filter(t=>t.type===filter&&t.name.toLowerCase().includes(query));
    const card=(t,built)=>'<article class="template-manager-card"><button class="template-manager-art" data-template-open="'+t.id+'"><span>'+(t.type==='gallery'?'▣ ▣ ▣':'▶')+'</span><small>'+(built?'内置模板':t.type==='gallery'?(t.snapshot.gallery?.frames.length??t.snapshot.pages.length)+' 个画框':'视频模板')+'</small></button><strong>'+esc(t.name)+'</strong><small>'+esc(built?t.detail:new Date(t.updatedAt).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}))+'</small><div>'+(built?'<button data-template-open="'+t.id+'">使用并编辑</button>':trash?'<button data-template-restore="'+t.id+'">恢复模板</button>':'<button data-template-rename="'+t.id+'">改名</button><button data-template-copy="'+t.id+'">复制</button><button data-template-delete="'+t.id+'">删除</button>')+'</div></article>';
    $('template-card-list').innerHTML=(custom.length?'<h4>我的模板</h4><div class="template-manager-grid">'+custom.map(t=>card(t,false)).join('')+'</div>':'<p class="content-empty">'+(trash?'回收站为空':'还没有保存的'+(filter==='gallery'?'图片':'视频')+'模板。新建一个，或从内置模板开始。')+'</p>')+(preset.length?'<h4>从内置模板开始</h4><div class="template-manager-grid">'+preset.map(t=>card(t,true)).join('')+'</div>':'');
    document.querySelectorAll('[data-template-open]').forEach(b=>b.onclick=()=>{const built=builtins.find(t=>t.id===b.dataset.templateOpen);if(built){const w=C.create(built.type,built.style);w.slots=H.slots();w.templateId=C.id();if(w.type==='gallery')G.ensure(w);H.openDraft(w);save(w);H.close();}else{const t=records.find(t=>t.id===b.dataset.templateOpen);if(t&&!t.deletedAt)openTemplate(t);}});
    document.querySelectorAll('[data-template-rename]').forEach(b=>b.onclick=()=>{const t=records.find(t=>t.id===b.dataset.templateRename);H.modal('修改模板名称','<div class="content-template-form"><label>模板名称<input id="template-rename-value" maxlength="60" value="'+esc(t.name)+'"></label></div>',[{label:'取消',run:manage},{label:'保存名称',primary:true,run:async()=>{const name=$('template-rename-value').value.trim();if(!name)return;await put({...t,name,snapshot:{...t.snapshot,name},updatedAt:new Date().toISOString()});H.renameDraft(t.id,name);fingerprints.delete(t.id);manage();}}]);});
    document.querySelectorAll('[data-template-copy]').forEach(b=>b.onclick=async()=>{const t=C.copy(records.find(t=>t.id===b.dataset.templateCopy)),source=records.find(t=>t.id===b.dataset.templateCopy);t.media=source.media;t.id=C.id();t.name+=' 副本';t.snapshot.name=t.name;t.updatedAt=new Date().toISOString();await put(t);renderCards();});
    document.querySelectorAll('[data-template-delete]').forEach(b=>b.onclick=async()=>{const t=records.find(t=>t.id===b.dataset.templateDelete);await put({...t,deletedAt:new Date().toISOString()});H.removeDraft(t.id);renderCards();});
    document.querySelectorAll('[data-template-restore]').forEach(b=>b.onclick=async()=>{const t={...records.find(t=>t.id===b.dataset.templateRestore)};delete t.deletedAt;await put(t);renderCards();});
  }
  function clearPreview(){previewURLs.forEach(URL.revokeObjectURL);previewURLs=[];}
  function filePreview(files){clearPreview();previewURLs=files.map(f=>URL.createObjectURL(f));return '<div class="content-result-preview">'+files.map((f,i)=>f.type.startsWith('video')?'<video controls src="'+previewURLs[i]+'"></video>':'<figure><img src="'+previewURLs[i]+'" alt="图集第 '+(i+1)+' 张"><figcaption>'+(i+1)+' · '+esc(f.name)+'</figcaption></figure>').join('')+'</div>';}
  function confirm(files,w,taskId){
    if(!files.length)return H.toast('生成结果没有成品文件，请重新生成');
    const project=H.projectId(),existing=taskId?H.data().items.find(item=>item.taskId===taskId&&item.type===w.type):null;
    const source=generationInfo(w),preview=filePreview(files);let saving=false,savedId=existing?.id;
    const hint=existing?(existing.deletedAt?'这份生成结果已在回收站。保存将恢复原作品。':'这份生成结果已经保存。再次保存只更新名称，不会新增一份作品。'):'保存后可在「内容发布」的素材列表中使用。之后修改模板不会改变这份作品。';
    H.modal('预览生成作品','<div class="content-result"><label>作品名称<input id="content-result-name" value="'+esc(existing?.name||w.name)+'" maxlength="60"></label><p class="content-slot-help">'+hint+'</p><p id="content-result-error" class="content-work-error" role="alert"></p>'+preview+'</div>',[{label:'取消',run:H.close},{label:existing?.deletedAt?'恢复至发布内容':'保存至发布内容',primary:true,run:async()=>{
      const name=input.value.trim();if(!name)return input.focus();if(H.projectId()!==project||saving)return;
      if(!window.CreatorBackend.connected)return H.toast('请先连接本地后台，再保存作品');
      saving=true;input.disabled=true;button.disabled=true;error.textContent='';
      try{
        let item=H.data().items.find(row=>row.id===savedId);
        if(!item){
          // A task always confirms into the same work, even after an interrupted save or undo.
          item={id:taskId?'generated-'+taskId:C.id(),name,type:w.type,taskId:taskId||null,createdAt:new Date().toISOString(),templateId:w.templateId||null,assetIds:[],frames:source.frames||null,source};
          H.data().items.push(item);savedId=item.id;
        }
        for(let i=0;i<files.length;i++){
          const assetId=item.assetIds[i]||item.id+'-asset-'+(i+1),previous=H.resolve(assetId);
          if(!(previous?.file instanceof Blob)){
            const file=files[i],frame=item.frames?.[i],output=source.output;
            H.remember({id:assetId,name:file.name.replace(/\.[^.]+$/,''),type:w.type==='gallery'?'image':'video',file,url:URL.createObjectURL(file),width:frame?.width||output?.width||w.width,height:frame?.height||output?.height||w.height,fps:output?.fps,duration:output?.duration,contentItemId:item.id});
          }else H.remember(previous);
          item.assetIds[i]=assetId;
        }
        item.name=name;item.source||=source;item.updatedAt=new Date().toISOString();delete item.deletedAt;
        H.commit('保存作品至发布内容');H.worksChanged?.();
        // ProjectStore retries the project and work index through the same save queue.
        if(!await H.flush()||!window.CreatorBackend.connected)throw new Error('作品尚未保存到本地后台。请重试保存，会继续保存同一份结果。');
        if(H.projectId()===project&&input.isConnected){H.close();H.toast('已保存至发布内容');}
      }catch(failure){if(input.isConnected)error.textContent=failure.message;else if(H.projectId()===project)H.toast(failure.message);}
      finally{saving=false;if(input.isConnected){input.disabled=false;button.disabled=false;}}
    }}]);
    const input=$('content-result-name'),error=$('content-result-error'),button=$('dialog-actions').querySelector('.primary');
  }
  function workFiles(item){const assets=(item.assetIds||[]).map(H.resolve);return assets.length&&assets.every(asset=>asset?.file instanceof Blob)?assets:null;}
  async function download(item){
    const assets=workFiles(item);if(!assets)return H.toast('成品文件未完整读取，请连接本地后台后重新打开项目');
    try{if(assets.length===1){const file=assets[0].file,extension=file.name?.match(/\.[^.]+$/)?.[0]|| (item.type==='video'?'.mp4':'.png');H.download(window.ResourceExport.filename(item.name)+extension,file);}else{const zip=new JSZip();assets.forEach((a,i)=>zip.file(String(i+1).padStart(2,'0')+'-'+a.file.name,a.file));H.download(window.ResourceExport.filename(item.name)+'.zip',await zip.generateAsync({type:'blob'}));}}catch(error){H.toast(error.message||'作品导出失败，请重试');}
  }
  function openItem(item){
    const assets=workFiles(item),files=assets?.map(a=>a.file)||[];
    H.modal('作品预览','<div class="content-result"><h3>'+esc(item.name)+'</h3><p class="content-slot-help">'+new Date(item.createdAt).toLocaleString('zh-CN')+' · '+(item.type==='gallery'?item.assetIds.length+' 张图片':'视频')+(item.deletedAt?' · 在回收站中':'')+'</p>'+(assets?filePreview(files):'<p class="content-empty">成品文件未完整读取，请连接本地后台后重新打开项目。</p>')+'</div>',[{label:'返回作品库',run:works},{label:'生成信息',run:()=>sourceInfo(item)},{label:'下载成品',run:()=>download(item)},item.deletedAt?{label:'恢复作品',primary:true,run:()=>setWorkDeleted(item,false)}:{label:'进入下一步',primary:true,run:()=>nextStep(item)}]);
  }
  async function nextStep(item){
    const project=H.projectId(),view=$('dialog-body').firstElementChild,current=H.data().items.find(row=>row.id===item.id);if(!current||current.deletedAt)return H.toast('请先从回收站恢复作品');
    if(!workFiles(current))return H.toast('成品文件未完整读取，请连接本地后台后重新打开项目');
    if(!window.CreatorBackend.connected)return H.toast('请先连接本地后台，再将作品带入发布');
    const key=project+':'+item.id;if(workOperations.has(key))return;workOperations.add(key);
    try{if(!await H.flush()||!window.CreatorBackend.connected)return H.toast('作品尚未保存到本地后台，请重试后进入下一步');if(H.projectId()!==project||!view?.isConnected)return;H.close();H.publish([...current.assetIds],current.type,current.name);}finally{workOperations.delete(key);}
  }
  function renameWork(item){
    const project=H.projectId();H.modal('修改作品名称','<div class="content-template-form"><label>作品名称<input id="work-rename-value" maxlength="60" value="'+esc(item.name)+'"></label><p id="work-rename-error" class="content-work-error" role="alert"></p></div>',[{label:'取消',run:works},{label:'保存名称',primary:true,run:async()=>{
      const name=input.value.trim();if(!name)return input.focus();if(button.disabled||H.projectId()!==project)return;
      if(!window.CreatorBackend.connected)return H.toast('请先连接本地后台，再修改作品名称');
      const current=H.data().items.find(row=>row.id===item.id);if(!current)return H.toast('作品已不存在');
      button.disabled=true;input.disabled=true;error.textContent='';current.name=name;current.updatedAt=new Date().toISOString();H.commit('修改作品名称');H.worksChanged?.();
      try{if(!await H.flush()||!window.CreatorBackend.connected)throw new Error('名称尚未保存到本地后台，请重试保存');if(H.projectId()===project&&input.isConnected){works();H.toast('作品名称已保存');}}catch(failure){if(input.isConnected)error.textContent=failure.message;}finally{if(input.isConnected){button.disabled=false;input.disabled=false;}}
    }}]);
    const input=$('work-rename-value'),error=$('work-rename-error'),button=$('dialog-actions').querySelector('.primary');input.focus();input.select();
  }
  async function setWorkDeleted(item,deleted){
    const project=H.projectId(),view=$('dialog-body').firstElementChild,key=project+':'+item.id,current=H.data().items.find(row=>row.id===item.id);if(!current||workOperations.has(key))return;
    if(!window.CreatorBackend.connected)return H.toast('请先连接本地后台，再'+(deleted?'移入回收站':'恢复作品'));
    workOperations.add(key);current.updatedAt=new Date().toISOString();if(deleted)current.deletedAt=current.updatedAt;else delete current.deletedAt;
    H.commit(deleted?'作品移入回收站':'恢复作品');H.worksChanged?.();
    try{const saved=await H.flush();if(H.projectId()!==project)return;if(view?.isConnected&&$('dialog').open)works();H.toast(saved&&window.CreatorBackend.connected?(deleted?'作品已移入回收站，可随时恢复':'作品已恢复至发布内容'):'修改已保留，后台尚未保存。请点击顶部保存状态重试');}finally{workOperations.delete(key);}
  }
  function generationInfo(w){
    const frames=w.type==='gallery'?(w.gallery?.frames||w.pages||[]).map(frame=>({name:frame.name||'画框',width:frame.width||w.width,height:frame.height||w.height})):null;
    const ids=new Set([...Object.values(w.slots||{}),...G.assets(w),...C.all(w).map(clip=>C.resolve(w,clip))].filter(Boolean));
    const output=w.type==='video'?{...C.outputSize(w),fps:C.fps(w),duration:C.outputFrames(w)/C.fps(w),quality:w.output?.quality||'standard',bitrate:w.output?.quality==='custom'?w.output.bitrate:null,format:'MP4'}:null;
    return {version:1,name:w.name,canvas:{width:w.width,height:w.height},frames,output,materials:[...ids].map(H.resolve).filter(Boolean).map(asset=>({id:asset.id,name:asset.name,type:asset.type}))};
  }
  function sourceDetails(item,source){
    const row=(name,value)=>'<dt>'+esc(name)+'</dt><dd>'+esc(value??'生成时未记录')+'</dd>',size=value=>value?.width&&value?.height?value.width+' × '+value.height:null;
    const asset=workFiles(item)?.[0],output=source?.output||asset,frames=source?.frames||item.frames;
    let html='<dl class="content-work-details">'+row('作品名称',item.name)+row('保存时间',new Date(item.createdAt).toLocaleString('zh-CN'))+row('生成时的模板名称',source?.name)+row('画布尺寸',size(source?.canvas));
    if(item.type==='video')html+=row('输出尺寸',size(output))+row('格式','MP4')+row('帧率',output?.fps?output.fps+' 帧/秒':null)+row('视频时长',Number.isFinite(output?.duration)?output.duration.toFixed(2)+' 秒':null)+row('画质',{standard:'标准',high:'高画质',custom:'自定义码率'}[source?.output?.quality])+ (source?.output?.quality==='custom'?row('视频码率',source.output.bitrate+' Mbps'):'');
    else html+=row('图片数量',item.assetIds.length+' 张')+row('画框',frames?.map((frame,i)=>(i+1)+'. '+frame.name+' · '+size(frame)).join('\n')||null);
    html+='</dl>';
    if(source?.materials?.length)html+='<h4>生成使用的素材</h4><ul class="content-work-materials">'+source.materials.map(asset=>'<li>'+esc(asset.name)+' <small>'+({image:'图片',video:'视频',audio:'音频'}[asset.type]||'素材')+'</small></li>').join('')+'</ul>';
    return html;
  }
  async function sourceInfo(item){
    const project=H.projectId();clearPreview();
    H.modal('作品生成信息','<div class="content-result"><p class="content-slot-help">这里显示生成这份作品时的素材和设置。修改模板不会改变成品。</p><div id="work-source-details">'+sourceDetails(item,item.source)+'</div></div>',[{label:'返回作品库',run:works},{label:'预览作品',run:()=>openItem(item)}]);
    if(item.source||!item.taskId||!window.CreatorBackend.connected)return;
    const detail=$('work-source-details');
    try{const task=await window.CreatorBackend.request('/tasks/'+encodeURIComponent(item.taskId));if(H.projectId()!==project||!detail.isConnected)return;detail.innerHTML=sourceDetails(item,task.input?.work?generationInfo(task.input.work):null);}
    catch(error){if(detail.isConnected)detail.insertAdjacentHTML('beforeend','<p class="content-work-error" role="alert">'+esc(error.message)+'</p>');}
  }
  function works(){
    clearPreview();H.stop();
    H.modal('作品库'+(worksTrash?' · 回收站':''),'<div class="template-manager content-works"><div class="template-manager-toolbar"><div class="segmented"><button data-works-filter="all">全部作品</button><button data-works-filter="gallery">图片作品</button><button data-works-filter="video">视频作品</button></div><input id="works-search" type="search" placeholder="搜索作品名称" aria-label="搜索作品名称"><button id="works-trash">'+(worksTrash?'返回作品':'回收站')+'</button></div><p class="content-slot-help">'+(worksTrash?'回收站保留成品文件，恢复后可继续使用。已有发布设置和发布任务会保留。':'当前项目已生成并保存的成品，可下载到本地，或带入下一步。双击作品名称可以改名，右键可查看生成信息或移入回收站。')+'</p><div id="works-card-list"></div></div>',[{label:'返回编辑',run:H.close}]);
    document.querySelectorAll('[data-works-filter]').forEach(button=>button.onclick=()=>{worksFilter=button.dataset.worksFilter;renderWorks();});$('works-search').oninput=renderWorks;$('works-trash').onclick=()=>{worksTrash=!worksTrash;works();};renderWorks();
  }
  function renderWorks(){
    if(!$('works-card-list'))return;
    document.querySelectorAll('[data-works-filter]').forEach(button=>button.classList.toggle('active',button.dataset.worksFilter===worksFilter));
    const query=$('works-search').value.trim().toLocaleLowerCase(),items=(H.data().items||[]).filter(item=>!!item.deletedAt===worksTrash&&(worksFilter==='all'||item.type===worksFilter)&&item.name.toLocaleLowerCase().includes(query)).sort((a,b)=>String(b.deletedAt||b.createdAt).localeCompare(String(a.deletedAt||a.createdAt)));
    $('works-card-list').innerHTML=items.length?'<div class="template-manager-grid">'+items.map((item,index)=>{
      const asset=item.assetIds.map(H.resolve).find(a=>a?.file),image=item.type==='gallery',art=image&&asset?.url?'<img src="'+esc(asset.url)+'" alt="'+esc(item.name)+'" loading="lazy">':'<svg aria-hidden="true"><use href="#i-'+(image?'image':'video')+'"/></svg>';
      return '<article class="template-manager-card" data-work-id="'+esc(item.id)+'"><button class="template-manager-art content-work-art" data-work-preview="'+index+'" aria-label="预览 '+esc(item.name)+'">'+art+'</button><strong data-work-name="'+index+'" title="'+esc(item.name)+(worksTrash?'':' · 双击修改名称')+'">'+esc(item.name)+'</strong><small>'+(image?item.assetIds.length+' 张图片':'视频')+' · '+new Date(item.createdAt).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})+'</small><div><button data-work-preview="'+index+'">预览</button><button data-work-download="'+index+'">下载</button><button data-work-source="'+index+'">生成信息</button>'+(worksTrash?'<button data-work-restore="'+index+'" class="primary">恢复作品</button>':'<button data-work-rename="'+index+'">改名</button><button data-work-trash="'+index+'">移入回收站</button><button data-work-next="'+index+'" class="primary">下一步</button>')+'</div></article>';
    }).join('')+'</div>':'<p class="content-empty">'+(query?'没有找到匹配的作品。':worksTrash?'作品回收站为空。':'还没有保存的作品。点击「生成作品」，预览后选择「保存至发布内容」。')+'</p>';
    document.querySelectorAll('[data-work-preview]').forEach(button=>button.onclick=()=>openItem(items[Number(button.dataset.workPreview)]));
    document.querySelectorAll('[data-work-download]').forEach(button=>button.onclick=()=>download(items[Number(button.dataset.workDownload)]));
    document.querySelectorAll('[data-work-next]').forEach(button=>button.onclick=()=>nextStep(items[Number(button.dataset.workNext)]));
    document.querySelectorAll('[data-work-source]').forEach(button=>button.onclick=()=>sourceInfo(items[Number(button.dataset.workSource)]));
    document.querySelectorAll('[data-work-rename]').forEach(button=>button.onclick=()=>renameWork(items[Number(button.dataset.workRename)]));
    document.querySelectorAll('[data-work-trash]').forEach(button=>button.onclick=()=>setWorkDeleted(items[Number(button.dataset.workTrash)],true));
    document.querySelectorAll('[data-work-restore]').forEach(button=>button.onclick=()=>setWorkDeleted(items[Number(button.dataset.workRestore)],false));
    if(!worksTrash)document.querySelectorAll('[data-work-name]').forEach(name=>name.ondblclick=()=>renameWork(items[Number(name.dataset.workName)]));
  }
  function contents(){window.ContentMediaLibrary.open();}
  window.ContentLibrary={mount,save,manage,create,confirm,contents,works,openItem,clearPreview};
})();
