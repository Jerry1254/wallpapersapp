/* Named media favorites persist independently of individual projects. */
(() => {
  'use strict';
  const C=window.ContentCore,$=id=>document.getElementById(id),esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={audio:'音频库',image:'图片库',video:'视频库',text:'文字库'},accept={audio:'audio/*,.mp3,.m4a,.wav,.aac,.ogg,.flac',image:'image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp',video:'video/mp4,video/quicktime,video/webm,.mp4,.mov,.webm'};
  const paths={audio:'<path d="M15 5a8 8 0 1 0 5 7M15 14V3l5 2v4l-5-2"/><ellipse cx="12.5" cy="14.5" rx="2.5" ry="1.8"/>',image:'<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8" cy="8" r="1.5"/><path d="m3 17 5-5 4 4 4-6 5 7"/>',video:'<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m10 8 6 4-6 4Z"/>',star:'<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3l-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z"/>',play:'<path d="m8 4 12 8-12 8Z"/>',search:'<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',trash:'<path d="M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7"/>',plus:'<path d="M12 5v14M5 12h14"/>'};
  paths.text='<path d="M4 5h16M12 5v15M8 20h8M4 5v3m16-3v3"/>';
  const icon=name=>'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+paths[name]+'</svg>';
  let H,opening,records=[],type='audio',scope='favorites',query='',session=0,previewRevision=0,rows=[],input,urls=new Map();
  function db(){return opening||=new Promise((resolve,reject)=>{const request=indexedDB.open('qingjing-content-media-library',1);request.onupgradeneeded=()=>request.result.createObjectStore('favorites',{keyPath:'id'});request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);request.onblocked=()=>reject(new Error('素材库暂时无法打开，请关闭旧的创作台页面后重试'));});}
  async function load(){const database=await db();records=await new Promise((resolve,reject)=>{const request=database.transaction('favorites').objectStore('favorites').getAll();request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});if(window.CreatorBackend.connected){records=(await window.CreatorBackend.migrate('favorites',records)).filter(r=>!r.deletedAt);for(const record of records)await cacheWrite(record);}}
  async function cacheWrite(record,remove=false){
    const database=await db();await new Promise((resolve,reject)=>{const tx=database.transaction('favorites','readwrite'),store=tx.objectStore('favorites');if(remove)store.delete(record.id);else store.put(record);tx.oncomplete=resolve;tx.onerror=tx.onabort=()=>reject(tx.error||new Error('收藏保存失败'));});
    records=records.filter(row=>row.id!==record.id);if(!remove)records.push(record);
  }
  async function write(record,remove=false){if(remove){await window.CreatorBackend.requireConnection();await window.CreatorBackend.remove('favorites',record);return cacheWrite(record,true);}await cacheWrite({...record,_creatorPending:true});if(window.CreatorBackend.connected){await window.CreatorBackend.put('favorites',record);await cacheWrite({...record,_creatorPending:false});}}
  function mount(bridge){
    H=bridge;document.addEventListener('creator-connection',()=>{if(window.CreatorBackend.connected)load().catch(error=>H.toast(error.message));});input=document.createElement('input');input.type='file';input.multiple=true;input.hidden=true;document.body.append(input);
    input.onchange=async()=>{
      const files=[...input.files],chosenType=type,token=session,project=H.projectId();input.value='';if(!files.length)return;
      const assets=await H.importFiles(files);
      if(token!==session||project!==H.projectId())return;
      const valid=assets.filter(asset=>asset.type===chosenType);
      if(valid.length)favorite(valid);else H.toast('请选择'+names[chosenType].replace('库','')+'文件');
    };
    $('dialog').addEventListener('close',()=>{if(!$('dialog').open){session++;clearPreview();}});
    window.EditingMenu.register({matches:(scope,event)=>scope.id==='dialog'&&!!event.target.closest('[data-media-row]'),items:event=>{
      const card=event.target.closest('[data-media-row]'),row=rows[Number(card.dataset.mediaRow)];
      if(!row)return [];
      return [{label:type==='audio'?'试听':'预览',run:()=>preview(row)},{label:type==='audio'?'加入时间轴':'加入画面',run:()=>card.querySelector('[data-media-use]').click()},null,{label:'导出到本地',run:()=>exportRow(row)},{label:row.record?'修改收藏名称':'收藏…',run:()=>card.querySelector('[data-media-favorite]').click()}];
    }});
  }
  function clearPreview(){
    previewRevision++;
    document.querySelectorAll('#media-library-preview audio,#media-library-preview video').forEach(media=>{media.pause();media.removeAttribute('src');media.load();});
    urls.forEach(URL.revokeObjectURL);urls.clear();
  }
  function favoriteURL(record){if(!record.media.file)return '';if(!urls.has(record.id))urls.set(record.id,URL.createObjectURL(record.media.file));return urls.get(record.id);}
  function matching(asset){return records.find(record=>record.id===asset.libraryFavoriteId||(!record.clip&&record.sourceId===asset.id&&record.sourceProject===H.projectId()));}
  function favoriteRows(){return records.filter(record=>record.type===type).sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt)).map(record=>({key:record.id,name:record.name,record,asset:{...record.media,name:record.name,url:favoriteURL(record)}}));}
  function projectRows(){
    if(type==='text'){
      const w=H.work();if(!w)return [];
      if(w.type==='video')return C.all(w).filter(clip=>clip.presentation==='text').map(clip=>({key:clip.id,name:window.ContentRenderer.textValue(w,clip),asset:{id:clip.id,type:'text',name:window.ContentRenderer.textValue(w,clip),duration:clip.duration},clip:textClip(w,clip)}));
      const result=[],G=window.GalleryCore;for(const surface of G.surfaces(G.ensure(w)))G.walk(surface.nodes,node=>{if(node.type==='text')result.push({key:node.id,name:node.name||node.text,asset:{id:node.id,type:'text',name:node.name||node.text,duration:3},clip:nodeClip(w,node)});});return result;
    }
    return H.sources().filter(asset=>asset.type===type&&!asset.contentItemId).map(asset=>({key:asset.id,name:asset.name,asset}));
  }
  function info(asset){if(asset.type==='text')return '可编辑文字';if(asset.type==='image')return asset.width+' × '+asset.height;const duration=Math.max(0,Math.round(asset.duration||0));return Math.floor(duration/60).toString().padStart(2,'0')+':'+(duration%60).toString().padStart(2,'0');}
  function shell(){return '<div class="media-library"><div class="media-library-tabs">'+Object.keys(names).map(key=>'<button data-media-type="'+key+'" class="'+(key===type?'active':'')+'">'+icon(key)+'<span>'+names[key]+'</span></button>').join('')+'</div><div class="media-library-toolbar"><div class="segmented"><button data-media-scope="favorites">我的收藏</button><button data-media-scope="project">项目素材</button></div>'+(type!=='text'?'<button id="media-library-import">'+icon('plus')+'导入并收藏</button>':'')+'</div><label class="media-library-search">'+icon('search')+'<input id="media-library-search" type="search" value="'+esc(query)+'" placeholder="搜索'+names[type].replace('库','')+'名称或文件名" aria-label="搜索素材"><span id="media-library-count"></span></label><div id="media-library-list"><p class="content-empty">正在读取素材…</p></div><div id="media-library-preview" class="media-library-preview" hidden></div><p class="media-library-note">连接本地后台后，收藏可在不同项目中重复使用；未连接时保留为浏览器草稿。</p></div>';}
  async function open(nextType=type,nextScope=scope){
    clearPreview();const token=++session;H.stop();type=names[nextType]?nextType:'audio';scope=nextScope==='project'?'project':'favorites';H.modal(names[type],shell(),[{label:'返回编辑',run:H.close}]);
    document.querySelectorAll('[data-media-type]').forEach(button=>button.onclick=()=>{query='';open(button.dataset.mediaType);});
    document.querySelectorAll('[data-media-scope]').forEach(button=>button.onclick=()=>{scope=button.dataset.mediaScope;renderCards();});
    $('media-library-search').oninput=event=>{query=event.target.value;renderCards();};
    if($('media-library-import'))$('media-library-import').onclick=()=>{input.accept=accept[type];input.click();};
    try{await load();if(token===session&&$('media-library-list'))renderCards();}catch(error){if(token===session){$('media-library-list').innerHTML='<p class="content-empty">素材库暂时无法读取，请重新打开。</p>';H.toast(error.message);}}
  }
  function renderCards(){
    if(!$('media-library-list'))return;hidePreview();document.querySelectorAll('[data-media-scope]').forEach(button=>button.classList.toggle('active',button.dataset.mediaScope===scope));
    const search=query.trim().toLocaleLowerCase();rows=(scope==='project'?projectRows():favoriteRows()).filter(row=>(row.name+' '+(row.asset.file?.name||'')+' '+(row.record?.clip?.text||row.clip?.text||'')).toLocaleLowerCase().includes(search));
    $('media-library-count').textContent=rows.length+' 项';
    $('media-library-list').innerHTML=rows.length?'<div class="media-library-grid">'+rows.map((row,index)=>{
      const asset=row.asset,saved=row.record||matching(asset),art=asset.type==='image'&&(asset.url||asset.demo)?'<img src="'+esc(asset.url||'assets/background.jpg')+'" alt="" loading="lazy">':icon(asset.type),useLabel=type==='audio'?(H.work()?.type==='video'?'加入时间轴':'加入视频'):'加入画面';
      return '<article class="media-library-card" data-media-row="'+index+'"><button class="media-library-art '+asset.type+'" data-media-preview="'+index+'" aria-label="预览 '+esc(row.name)+'">'+art+'</button><div class="media-library-copy"><strong title="'+esc(row.name)+'">'+esc(row.name)+'</strong><small>'+esc(info(row.record?.clip?{...asset,duration:row.record.clip.duration}:asset))+(row.record?.clip?' · 已收藏片段':'')+(asset.file?' · '+esc(asset.file.name):'')+'</small><div class="media-library-card-actions"><button data-media-preview="'+index+'">'+icon('play')+(type==='audio'?'试听':'预览')+'</button><button data-media-use="'+index+'" class="primary">'+icon('plus')+useLabel+'</button><button data-media-favorite="'+index+'" class="media-library-star '+(saved?'saved':'')+'" title="'+(saved?'修改收藏名称':'收藏并命名')+'" aria-label="'+(saved?'修改收藏名称':'收藏并命名')+'">'+icon('star')+'</button>'+(row.record?'<button class="media-library-remove" data-media-remove="'+index+'" title="移出收藏" aria-label="移出收藏">'+icon('trash')+'</button>':'')+'</div></div></article>';
    }).join('')+'</div>':'<div class="media-library-empty">'+icon(type)+'<strong>'+(search?'没有找到匹配素材':scope==='favorites'?'还没有收藏的'+names[type].replace('库',''):'当前项目没有'+names[type].replace('库','')+'素材')+'</strong><p>'+(search?'换个名称试试。':type==='text'?'右键文字片段，选择「收藏」并命名；也可以在「项目素材」里收藏文字。':scope==='favorites'?'导入文件，或到「项目素材」中点击星星，命名后收藏。':'点击「导入并收藏」添加素材。')+'</p></div>';
    document.querySelectorAll('[data-media-preview]').forEach(button=>button.onclick=()=>preview(rows[Number(button.dataset.mediaPreview)]));
    document.querySelectorAll('[data-media-use]').forEach(button=>button.onclick=()=>use(rows[Number(button.dataset.mediaUse)],button));
    document.querySelectorAll('[data-media-favorite]').forEach(button=>button.onclick=()=>{const row=rows[Number(button.dataset.mediaFavorite)];favorite([row.asset],row.record||matching(row.asset),row.clip);});
    document.querySelectorAll('[data-media-remove]').forEach(button=>button.onclick=async()=>{const row=rows[Number(button.dataset.mediaRemove)];button.disabled=true;try{await write(row.record,true);renderCards();H.toast('已移出收藏');}catch(error){button.disabled=false;H.toast(error.message);}});
  }
  function hidePreview(){previewRevision++;const panel=$('media-library-preview');if(!panel)return;panel.querySelectorAll('audio,video').forEach(media=>{media.pause();media.removeAttribute('src');media.load();});panel.replaceChildren();panel.hidden=true;}
  async function resolveAsset(asset){return asset.virtual?H.materialize(asset.id):asset;}
  function exportText(text,name){H.download(window.ResourceExport.filename(name||'文字')+'.txt',text,'text/plain;charset=utf-8');H.toast('文字已导出到本地');}
  function exportRow(row){if(row.asset.type==='text')exportText((row.record?.clip||row.clip).text,row.name);else H.exportAsset({...row.asset,name:row.name});}
  function exportClip(w,clip){if(clip.presentation==='text')exportText(window.ContentRenderer.textValue(w,clip),clip.name||'文字');else H.exportAsset(H.resolve(C.resolve(w,clip)));}
  function exportNode(w,node){if(node.type==='text')exportText(node.text,node.name);else if(node.type==='image')H.exportAsset(H.resolve(node.slot?w.slots[node.slot]:node.assetId));}
  async function preview(row){
    const token=session,project=H.projectId();hidePreview();const revision=previewRevision,panel=$('media-library-preview');panel.hidden=false;panel.innerHTML='<p class="content-empty">正在准备预览…</p>';
    try{
      if(row.asset.type==='text'){
        const clip=row.record?.clip||row.clip;panel.innerHTML='<div class="media-library-preview-heading"><strong>'+esc(row.name)+'</strong><button id="media-library-preview-close" aria-label="关闭预览">×</button></div><div class="media-library-text-preview"><div></div></div>';
        const text=panel.querySelector('.media-library-text-preview>div');text.textContent=clip.text;text.style.fontFamily=window.GalleryEditing.fontFamily(clip.fontFamily);text.style.fontSize=Math.min(48,clip.fontSize||40)+'px';text.style.fontWeight=clip.fontWeight||400;text.style.lineHeight=clip.lineHeight||1.3;text.style.letterSpacing=(clip.letterSpacing||0)+'px';text.style.textAlign=clip.align||'left';text.style.color=typeof clip.color==='string'?clip.color:'#c6d7f1';$('media-library-preview-close').onclick=hidePreview;panel.scrollIntoView({block:'nearest'});return;
      }
      const asset=await resolveAsset(row.asset);if(token!==session||revision!==previewRevision||project!==H.projectId()||!panel.isConnected)return;
      const url=asset.url||asset.demo&&'assets/background.jpg';if(!url)throw new Error('素材文件暂不可用，请重新导入');
      panel.innerHTML='<div class="media-library-preview-heading"><strong>'+esc(row.name)+'</strong><button id="media-library-preview-close" aria-label="关闭预览">×</button></div>'+(asset.demo||asset.type==='image'?'<img src="'+esc(url)+'" alt="'+esc(row.name)+'">':asset.type==='audio'?'<audio controls preload="metadata" src="'+esc(url)+'"></audio>':'<video controls playsinline preload="metadata" src="'+esc(url)+'"></video>');
      $('media-library-preview-close').onclick=hidePreview;const media=panel.querySelector('audio,video');if(media){media.onerror=()=>H.toast('素材无法预览，请检查文件格式');const clip=row.record?.clip;if(clip){const start=C.sourceTime(clip,clip.start,asset,H.work());media.playbackRate=clip.speed||1;media.volume=C.clamp(clip.volume??1,0,1);media.onloadedmetadata=()=>{media.currentTime=start;};media.ontimeupdate=()=>{if(media.currentTime>=start+clip.duration*(clip.speed||1))media.pause();};}media.play().catch(()=>{});}panel.scrollIntoView({block:'nearest'});
    }catch(error){if(token===session&&revision===previewRevision){hidePreview();H.toast(error.message);}}
  }
  async function use(row,button){
    const token=session,project=H.projectId();button.disabled=true;
    try{
      if(row.asset.type==='text'){if(await H.useText(row.record?.clip||row.clip,row.name)){H.close();H.toast('已加入可编辑文字');}return;}
      let asset=await resolveAsset(row.asset);if(token!==session||project!==H.projectId())return;
      if(row.record){asset={...row.record.media,id:C.id(),name:row.record.name,libraryFavoriteId:row.record.id};if(asset.file)asset.url=URL.createObjectURL(asset.file);}
      const added=await H.useAsset(asset,row.record?.clip);if(token!==session||project!==H.projectId())return;if(added){H.close();H.toast('已加入'+(asset.type==='audio'?'音频轨道':'当前画面'));}
    }catch(error){if(token===session)H.toast(error.message);}finally{if(button.isConnected)button.disabled=false;}
  }
  function favorite(assets,existing=null,clip=null){
    clearPreview();const token=++session,project=H.projectId(),previousType=type,previousScope=scope;let saving=false;
    H.modal(existing?'修改收藏名称':'收藏并命名','<div class="content-template-form media-favorite-form"><p>给素材起一个方便搜索的名字。</p>'+assets.map((asset,index)=>'<label>'+esc(assets.length>1?asset.file?.name||asset.name:names[asset.type].replace('库','')+'名称')+'<input data-media-name="'+index+'" maxlength="80" value="'+esc(existing?.name||asset.name)+'" required></label>').join('')+'<p id="media-favorite-status" aria-live="polite"></p></div>',[{label:'取消',run:()=>open(previousType,previousScope)},{label:existing?'保存名称':'保存收藏',primary:true,run:async()=>{
      if(saving)return;const fields=[...document.querySelectorAll('[data-media-name]')],empty=fields.find(field=>!field.value.trim());if(empty)return empty.focus();const labels=fields.map(field=>field.value.trim());saving=true;
      try{
        $('media-favorite-status').textContent='正在保存…';await load();
        for(let index=0;index<assets.length;index++){
          const source=assets[index],asset=await resolveAsset(source);if(token!==session||project!==H.projectId())return;
          if(!asset?.file&&!asset?.demo&&asset?.type!=='text')throw new Error('素材文件暂不可用，请重新导入');
          const previous=existing||(!clip&&matching(source)),{url,id,contentItemId,contentDraftOnly,libraryFavoriteId,group,detail,virtual,...media}=asset,now=new Date().toISOString();
          await write({id:previous?.id||C.id(),name:labels[index],type:asset.type,sourceId:previous?.sourceId||source.id,sourceProject:previous?.sourceProject||project,createdAt:previous?.createdAt||now,updatedAt:now,media,clip:clip?C.copy(clip):previous?.clip});
        }
        if(token!==session)return;H.refresh();H.save();query='';open(assets[0].type,'favorites');H.toast(existing?'收藏名称已修改':'已保存到'+names[assets[0].type]);
      }catch(error){if(token===session){saving=false;$('media-favorite-status').textContent='';H.toast('收藏未保存：'+error.message);}}
    }}]);
    const first=document.querySelector('[data-media-name]');first?.focus();first?.select();
  }
  function textClip(w,clip){const saved=C.copy(clip);C.clipStyle(w,saved);saved.text=window.ContentRenderer.textValue(w,saved);saved.color=saved.color||w.accent;saved.libraryWidth=saved.w*w.width/100;saved.libraryHeight=saved.h*w.height/100;return saved;}
  function nodeClip(w,node){const styles=Object.fromEntries(['text','fontFamily','fontSize','fontWeight','lineHeight','letterSpacing','align','verticalAlign','textResize','color','rotation','opacity','blendMode'].map(key=>[key,node[key]])),clip=C.clip('text',null,0,3,{...styles,x:50,y:50,w:node.width/w.width*100,h:node.height/w.height*100});return textClip(w,clip);}
  function favoriteClip(w,clip){
    const asset=clip.presentation==='text'?{id:clip.id,type:'text',name:window.ContentRenderer.textValue(w,clip).slice(0,80)||'文字',duration:clip.duration}:H.resolve(C.resolve(w,clip));if(!asset)return H.toast('素材暂不可用，请重新绑定素材');
    H.stop();type=asset.type;scope='favorites';query='';favorite([asset],null,asset.type==='text'?textClip(w,clip):clip);
  }
  function favoriteNode(w,node){
    if(node.type==='text'){H.stop();type='text';scope='favorites';query='';favorite([{id:node.id,type:'text',name:node.name||node.text,duration:3}],null,nodeClip(w,node));}
    else if(node.type==='image'){const asset=H.resolve(node.slot?w.slots[node.slot]:node.assetId);if(!asset)return H.toast('图片素材暂不可用，请重新绑定');H.stop();type='image';scope='favorites';query='';favorite([asset]);}
  }
  window.ContentMediaLibrary={mount,open,favoriteClip,favoriteNode,exportClip,exportNode,clearPreview,icon};
})();
