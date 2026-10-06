/* Reusable templates and explicitly confirmed output are separate collections. */
(() => {
  'use strict';
  const C=window.ContentCore,G=window.GalleryCore,$=id=>document.getElementById(id),esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let H,opening,records=[],queue=Promise.resolve(),fingerprints=new Map(),filter='gallery',trash=false,previewURLs=[];
  const builtins=[{id:'gallery-showcase',name:'壁纸展示图集',type:'gallery',style:'showcase',detail:'4 个画框 · 样机、全图、细节、锁屏'},{id:'gallery-minimal',name:'极简质感图集',type:'gallery',style:'minimal',detail:'3 个画框 · 浅色排版'},{id:'video-showcase',name:'动态样机视频',type:'video',style:'showcase',detail:'多画面轨道 · 12 秒'},{id:'video-detail',name:'细节展示视频',type:'video',style:'detail',detail:'动态与细节 · 12 秒'}];
  function db(){return opening||=new Promise((resolve,reject)=>{const q=indexedDB.open('qingjing-content-templates',1);q.onupgradeneeded=()=>q.result.createObjectStore('templates',{keyPath:'id'});q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});}
  async function load(){const store=(await db()).transaction('templates').objectStore('templates');records=await new Promise((resolve,reject)=>{const q=store.getAll();q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});}
  async function put(record){const database=await db();await new Promise((resolve,reject)=>{const tx=database.transaction('templates','readwrite');tx.objectStore('templates').put(record);tx.oncomplete=resolve;tx.onerror=tx.onabort=()=>reject(tx.error);});records=records.filter(t=>t.id!==record.id);records.push(record);}
  function mount(bridge){H=bridge;load().catch(()=>H.toast('模板库暂时无法读取'));$('dialog').addEventListener('close',()=>{if(!$('dialog').open)clearPreview();});}
  function snapshot(w){const t=C.template(w,w.name);t.id=w.templateId;t.snapshot.slots=C.copy(w.slots);delete t.snapshot.templateId;delete t.snapshot.templateVersion;delete t.snapshot.canvasHistory;if(t.snapshot.gallery){t.snapshot.gallery.view=null;t.snapshot.gallery.selection=[];t.snapshot.gallery.surfaceSelection=[];t.snapshot.gallery.selectedSurface=t.snapshot.gallery.frames[0]?.id||Object.keys(t.snapshot.gallery.components)[0]||G.canvasSurface(t.snapshot.gallery).id;}return t;}
  function save(w){
    if(!w)return queue;if(!w.templateId)w.templateId=C.id();const t=snapshot(w),fingerprint=JSON.stringify(t.snapshot);
    if(fingerprints.get(t.id)===fingerprint)return queue;fingerprints.set(t.id,fingerprint);
    const ids=new Set([...Object.values(w.slots),...G.assets(w),...(w.type==='video'?C.all(w).map(c=>c.assetId):[])].filter(Boolean));
    t.media=[...ids].map(H.resolve).filter(Boolean).map(a=>{const {url,...data}=a;return data;});t.createdAt=records.find(r=>r.id===t.id)?.createdAt||new Date().toISOString();
    queue=queue.catch(()=>{}).then(()=>put(t)).catch(error=>{fingerprints.delete(t.id);H.toast('模板自动保存失败，请检查浏览器存储空间');throw error;});queue.catch(()=>{});return queue;
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
  function confirm(files,w){
    const project=H.projectId(),preview=filePreview(files);let saving=false,savedItem=null;
    H.modal('预览生成作品','<div class="content-result"><label>内容名称<input id="content-result-name" value="'+esc(w.name)+'" maxlength="60"></label><p class="content-slot-help">确认保存后进入'+(w.type==='gallery'?'图片库':'视频库')+'的「已生成」。之后修改模板不会改变这份作品。</p>'+preview+'</div>',[{label:'取消',run:H.close},{label:'保存至'+(w.type==='gallery'?'图片库':'视频库'),primary:true,run:async()=>{
      const name=$('content-result-name').value.trim();if(!name)return $('content-result-name').focus();if(H.projectId()!==project||saving)return;saving=true;
      const item=savedItem||{id:C.id(),name,type:w.type,createdAt:new Date().toISOString(),templateId:w.templateId,assetIds:[],frames:w.type==='gallery'?w.gallery.frames.map(f=>({name:f.name,width:f.width,height:f.height})):null};
      if(!savedItem)for(let i=0;i<files.length;i++){const file=files[i],f=item.frames?.[i],asset={id:C.id(),name:file.name.replace(/\.[^.]+$/,''),type:w.type==='gallery'?'image':'video',file,url:URL.createObjectURL(file),width:f?.width||(w.type==='video'?C.outputSize(w).width:w.width),height:f?.height||(w.type==='video'?C.outputSize(w).height:w.height),fps:w.type==='video'?C.fps(w):undefined,duration:w.type==='video'?C.outputFrames(w)/C.fps(w):undefined,contentItemId:item.id};H.remember(asset);item.assetIds.push(asset.id);}
      if(!savedItem){H.data().items.push(item);savedItem=item;H.commit('确认保存内容作品');}
      if(!await H.flush()){saving=false;H.toast('内容保存失败，请点击顶部保存状态重试');return;}
      H.close();openItem(item);
    }}]);
  }
  async function download(item){const files=item.assetIds.map(H.resolve).filter(a=>a?.file);if(!files.length)return H.toast('成品文件暂不可用');if(files.length===1)H.download(files[0].file.name,files[0].file);else{const zip=new JSZip();files.forEach((a,i)=>zip.file(String(i+1).padStart(2,'0')+'-'+a.file.name,a.file));H.download(window.ResourceExport.filename(item.name)+'.zip',await zip.generateAsync({type:'blob'}));}}
  function openItem(item){
    const files=item.assetIds.map(H.resolve).filter(a=>a?.file).map(a=>a.file);
    H.modal('已保存的内容','<div class="content-result"><h3>'+esc(item.name)+'</h3><p class="content-slot-help">'+new Date(item.createdAt).toLocaleString('zh-CN')+' · '+(item.type==='gallery'?files.length+' 张图片':'视频')+'</p>'+filePreview(files)+'</div>',[{label:'关闭',run:H.close},{label:'下载成品',run:()=>download(item)},{label:'加入待发布',primary:true,run:()=>{H.close();H.publish(item.assetIds,item.type,item.name);}}]);
  }
  function contents(){window.ContentMediaLibrary.open();}
  window.ContentLibrary={mount,save,manage,create,confirm,contents,openItem,clearPreview};
})();
