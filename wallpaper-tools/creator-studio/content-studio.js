/* Multi-track content authoring and reusable mockup templates for the local workbench. */
(() => {
  'use strict';
  const C=window.ContentCore,R=window.ContentRenderer,G=window.GalleryCore,GE=window.GalleryEditor,T=window.ContentTimeline,S=window.ContentSettings,V=window.ContentCanvasEditor,P=window.GalleryProperties,Lib=window.ContentLibrary,ML=window.ContentMediaLibrary,$=id=>document.getElementById(id),esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),icon=name=>`<svg><use href="#i-${name}"/></svg>`;
  const previewCanvas=document.createElement('canvas');
  let B,libraryTab='assets',selectedSource=null,playing=false,raf,previous=0,previousPaint=0,painting=false,paintAgain=false,paintPromise=Promise.resolve(),previewRevision=0,job=null,clipboard=null;
  const work=()=>B.data().drafts.find(w=>w.id===B.data().activeId);
  const current=()=>C.selected(work());
  const resolve=id=>B.media(id);
  const change=label=>{const w=work();if(w?.type==='gallery'){window.GalleryEditing.commit(w,label);B.save();}else{C.syncDuration(w);B.commit(`内容创作 · ${label}`);}Lib.save(w);render();};
  function slots(){const sources=B.sources().filter(a=>!a.virtual);return {wallpaper:sources.find(a=>a.type==='image')?.id||null,motion:sources.find(a=>a.type==='video')?.id||sources.find(a=>a.type==='image')?.id||null};}
  function create(type,style){stop();const w=C.create(type,style);w.slots=slots();w.templateId=C.id();if(type==='gallery')G.ensure(w);B.data().drafts.push(w);B.data().activeId=w.id;libraryTab='assets';change('新建模板');}
  function ensure(){if(!work())create('gallery','showcase');}
  function mount(bridge){
    B=bridge;const main=document.createElement('main');main.id='content-workspace';main.className='workspace-view';main.hidden=true;
    main.innerHTML=`<div class="content-columns">
      <aside class="content-library"><div class="panel-heading"><div>${icon('folder')}<strong>创作素材</strong></div><button id="content-import" class="icon-button" aria-label="导入创作素材">${icon('plus')}</button></div><div class="content-library-tabs"><button data-content-library="assets" class="active">项目素材</button><button data-content-library="layers">图层</button><button data-content-library="components">组件</button></div><div id="content-library-list" class="content-library-scroll"></div><div class="content-library-foot"><button id="content-add-source">${icon('plus')}加入画面</button><button id="content-import-foot">导入实拍</button></div></aside>
      <section class="content-center"><div class="content-header"><div><div class="segmented"><button data-content-type="gallery">图片编辑</button><button data-content-type="video">视频编辑</button></div></div><div><button id="content-new">${icon('plus')}新建模板</button><button id="content-save-template">${icon('layers')}模板库</button><button id="content-library-btn">${icon('folder')}素材库</button><button id="content-works-btn">${icon('image')}作品库</button></div></div><div id="content-canvas-area" class="content-canvas-area"><canvas id="content-canvas" tabindex="0" aria-label="内容作品预览"></canvas><span class="content-preview-note">点击选择元素 · 拖动移动 · 控制点缩放 · 双击文字编辑</span></div><div class="content-player"><span id="content-position"></span><div><button id="content-play" class="icon-button" aria-label="播放内容视频">${icon('play')}</button><button id="content-prev" class="icon-button" aria-label="上一页">${icon('prev')}</button><button id="content-next" class="icon-button" aria-label="下一页">${icon('next')}</button></div><span id="content-dimensions"></span></div></section>
      <aside class="content-inspector"><div class="panel-heading"><div>${icon('tune')}<strong id="content-inspector-title">内容参数</strong></div><span id="content-kind" class="soft-label"></span></div><div class="content-inspector-scroll"><button id="content-open-settings" class="content-settings-entry" hidden>画布与输出 <span>↗</span></button><div id="content-project-settings"></div><div id="content-clip-context"><section id="content-clip-inspector"></section><section><details class="content-template-details"><summary>模板内容 · 素材关联</summary><div class="section-title"><strong>素材源 · 一键替换</strong></div><div id="content-slots"></div><p class="content-slot-help">替换一次，更新所有绑定的画面。蒙版、样机和动画保留。</p><div class="field"><label for="content-title">画面标题</label><input id="content-title" maxlength="60"></div><div class="field"><label for="content-subtitle">落款</label><input id="content-subtitle" maxlength="60"></div><label>文字颜色<input id="content-accent" type="color"></label></details></section></div></div><div class="content-inspector-foot">自动保存 · 模板与内容作品分别保留</div></aside>
    </div><section class="content-timeline"><div class="content-timeline-tools"><div><button id="content-undo" aria-label="撤销内容编辑">${icon('undo')}</button><button id="content-redo" aria-label="重做内容编辑">${icon('redo')}</button><span class="divider"></span><button id="content-split">${icon('scissors')}剪断</button><button id="content-add-text">＋ 文字</button><button id="content-duplicate">${icon('copy')}复制</button><button id="content-delete">${icon('trash')}删除</button></div><div><span id="content-timeline-label"></span><div class="content-timeline-zoom-tools"><button id="content-timeline-out" aria-label="缩小时间轴">−</button><input id="content-timeline-zoom" type="range" min="1" max="6" step=".05" value="1" aria-label="时间轴缩放"><button id="content-timeline-in" aria-label="放大时间轴">＋</button><span id="content-timeline-zoom-value">100%</span></div><button id="content-add-track">＋ 画面轨道</button><button id="content-add-page">＋ 页面</button><button id="content-delete-page">删除页面</button><button id="content-add-music">＋ 音乐</button></div></div><div id="content-timeline-body" class="content-tracks"></div><div class="content-timeline-foot"><span id="content-timeline-help"></span><span id="content-track-count"></span></div></section><input id="content-input" type="file" accept="image/png,image/jpeg,image/webp,video/mp4,video/quicktime,video/webm,audio/mpeg,audio/wav,audio/mp4,audio/aac,audio/ogg,audio/flac,.mp3,.m4a,.wav,.aac,.ogg,.flac" multiple hidden>`;
    $('publish-workspace').before(main);
    GE.mount({work,active:()=>B.workspace()==='create',projectId:B.projectId,change,save:B.save,resolve,sources:B.sources,restored:()=>{Lib.save(work());B.save();render();},modal:B.modal,close:B.close,toast:B.toast,importFiles,library:renderLibrary,showLibrary:tab=>{libraryTab=tab;renderLibrary();}});
    Lib.mount({...B,work,resolve,slots,stop,openDraft:w=>{stop();B.data().drafts=B.data().drafts.filter(d=>d.templateId!==w.templateId);B.data().drafts.push(w);B.data().activeId=w.id;libraryTab='assets';change('打开模板');},
      renameDraft:(id,name)=>{for(const d of B.data().drafts)if(d.templateId===id)d.name=name;B.commit('修改模板名称');render();},
      removeDraft:id=>{B.data().drafts=B.data().drafts.filter(d=>d.templateId!==id);if(!work()){B.data().activeId=B.data().drafts[0]?.id;ensure();}B.commit('删除模板');render();}
    });
    ML.mount({...B,work,resolve,stop,importFiles,materialize,refresh:renderLibrary,useAsset:async (asset,savedClip)=>{
      if(!asset)return false;if(asset.type!=='image'&&work()?.type==='gallery')document.querySelector('[data-content-type="video"]').click();
      B.remember(asset);selectedSource=asset.id;libraryTab='assets';const added=await addSource(asset.id,null,null,savedClip);B.save();return !!added;
    },useText:(saved,name)=>{
      const w=work();stop();if(w.type==='gallery')return !!GE.addTextClip(saved,name);
      const c={...C.copy(saved),id:C.id(),name,start:w.cursor,duration:Math.min(saved.duration||3,C.maxDuration),slot:null,assetId:null};
      c.w=(saved.libraryWidth||saved.w*w.width/100)/w.width*100;c.h=(saved.libraryHeight||saved.h*w.height/100)/w.height*100;R.syncText(w,c);
      if(!C.place(w,c,{index:0},w.cursor)){B.toast('当前时间放不下文字，请把播放头往前移动');return false;}change('加入收藏文字');return true;
    }});
    S.mount({work,change,stop,toast:B.toast,exportVideo});V.mount({work,active:()=>B.workspace()==='create'&&work()?.type==='video',busy:()=>!!job,select:selectClip,clear:clearSelection,stop,change,save:B.save,renderInspector,requestPaint});T.mount({work,active:()=>B.workspace()==='create',resolve,thumb,change,save:B.save,toast:B.toast,modal:B.modal,close:B.close,importFiles,addSource,stop,updatePosition,requestPaint,renderInspector,clearSelection});const sizeObserver=new ResizeObserver(sizeCanvas);sizeObserver.observe($('content-canvas-area'));bind();window.EditingMenu.register({matches:scope=>scope.id==='content-workspace'&&B.workspace()==='create'&&work()?.type==='video',items:contextItems,focus:()=>$('content-canvas')});
    window.EditingMenu.register({matches:(scope,event)=>scope.id==='content-workspace'&&B.workspace()==='create'&&!!event.target.closest('[data-content-source]'),items:event=>{
      const source=event.target.closest('[data-content-source]'),id=source.dataset.contentSource,asset=B.sources().find(a=>a.id===id);selectedSource=id;renderLibrary();
      return [{label:'加入画面',disabled:!!job,run:()=>addSource(id)},null,{label:'导出到本地',disabled:!!job||!asset,run:()=>B.exportAsset(asset)}];
    }});
  }
  function thumb(a){return a?.demo?'assets/background.jpg':a?.type==='image'?a.url:null;}
  function renderLibrary(){
    const w=work();if(!w)return;document.querySelectorAll('[data-content-library]').forEach(b=>b.classList.toggle('active',b.dataset.contentLibrary===libraryTab));
    if(libraryTab==='assets'){
      const sources=B.sources();let html='';for(const [key,title] of [['static','静态壁纸'],['dynamic','动态壁纸'],['import','导入素材 · 实拍 / 音乐']]){const rows=sources.filter(a=>a.group===key);html+=`<h4>${title}<span>${rows.length}</span></h4>`+rows.map(a=>`<button class="content-source${selectedSource===a.id?' selected':''}" data-content-source="${esc(a.id)}" draggable="true"><span class="content-source-art">${thumb(a)?`<img src="${esc(thumb(a))}" alt="">`:icon(a.type==='audio'?'play':'video')}</span><span><strong>${esc(a.name)}</strong><small>${esc(a.detail||(a.type==='image'?`${a.width} × ${a.height}`:`${a.duration?.toFixed(2)||'—'} 秒`))}</small></span></button>`).join('')+(rows.length?'':'<div class="content-empty">暂无资源，可从壁纸加工保存或直接导入。</div>');}$('content-library-list').innerHTML=html;
      document.querySelectorAll('[data-content-source]').forEach(b=>{b.onclick=()=>{selectedSource=b.dataset.contentSource;renderLibrary();};b.ondblclick=()=>addSource();b.ondragstart=e=>{selectedSource=b.dataset.contentSource;e.dataTransfer.setData('application/x-qingjing-content-source',selectedSource);e.dataTransfer.effectAllowed='copy';};});
    }else{
      $('content-library-list').innerHTML=libraryTab==='layers'?GE.renderLayers():GE.renderComponents();GE.bindLibrary();
    }
    $('content-add-source').disabled=libraryTab!=='assets'||!selectedSource;
  }
  function options(selected,types=['image','video']){return '<option value="">未绑定</option>'+B.sources().filter(a=>types.includes(a.type)).map(a=>`<option value="${esc(a.id)}" ${a.id===selected?'selected':''}>${esc(a.name)}</option>`).join('');}
  function render(){
    const w=work();if(!w)return;if(w.type==='gallery')G.ensure(w);else{C.videoSettings(w);libraryTab='assets';}$('content-kind').textContent=w.type==='gallery'?'图片集':'视频';$('content-workspace').classList.toggle('content-pages',w.type==='gallery');$('content-workspace').classList.toggle('gallery-mode',w.type==='gallery');document.querySelectorAll('[data-content-library]').forEach(b=>b.hidden=w.type!=='gallery'&&b.dataset.contentLibrary!=='assets');
    document.querySelectorAll('[data-content-type]').forEach(b=>b.classList.toggle('active',b.dataset.contentType===w.type));
    for(const [id,key] of [['content-title','title'],['content-subtitle','subtitle'],['content-accent','accent']])$(id).value=w[key];
    $('content-slots').innerHTML=Object.entries(w.slots).map(([key,id])=>`<label class="content-slot-row"><span>${key==='wallpaper'?'主壁纸':'动态素材'}</span><select data-content-slot="${key}" aria-label="替换${key==='wallpaper'?'主壁纸':'动态素材'}">${options(id)}</select></label>`).join('');
    document.querySelectorAll('[data-content-slot]').forEach(s=>s.onchange=async()=>{const target=work(),project=B.projectId(),value=s.value;s.disabled=true;try{const asset=await materialize(value);if(project!==B.projectId()||work()!==target)return;C.replace(target,s.dataset.contentSlot,asset?.id||null);change('替换素材源');}catch(e){B.toast(e.message);render();}});
    if(w.type==='video')S.render();else $('content-inspector-title').textContent='内容参数';
    $('content-add-track').hidden=$('content-add-music').hidden=$('content-split').hidden=w.type!=='video';$('content-add-page').hidden=$('content-delete-page').hidden=w.type!=='gallery';$('content-delete-page').disabled=w.pages.length<=1;$('content-play').hidden=w.type!=='video';$('content-prev').hidden=$('content-next').hidden=w.type!=='gallery';
    $('content-dimensions').textContent=`${w.width} × ${w.height}`;$('content-timeline-label').textContent=w.type==='gallery'?'拖动页面排序':'上层轨道覆盖下层';$('content-timeline-help').textContent=w.type==='gallery'?'单屏样机 · 一份素材用于多页':'拖入已有轨道前后排列 · 拖到间隙新增轨道 · 拖动片段换轨 · 拖动边缘裁帧';
    renderLibrary();if(w.type==='gallery'){GE.render();}else{renderInspector();renderTimeline();updatePosition();sizeCanvas();requestPaint();}Lib.save(w);
  }
  function renderInspector(){
    const w=work(),c=current();P.closeFont();V.draw();$('content-duplicate').disabled=$('content-delete').disabled=!c;$('content-project-settings').hidden=!!c;$('content-clip-context').hidden=!c;$('content-open-settings').hidden=!c;$('content-inspector-title').textContent=c?'片段设置':'画布与输出';S.render();if(!c){$('content-clip-inspector').innerHTML='';return;}
    C.clipStyle(w,c);const audio=c.presentation==='audio',text=c.presentation==='text',asset=resolve(C.resolve(w,c)),g=R.geometry(w,c),layers=C.all(w),field=(label,key,value,min,max,step=1,unit='')=>`<label>${label}<input data-content-param="${key}" type="number" value="${value}" min="${min}" max="${max}" step="${step}" ${unit?`data-content-unit="${unit}"`:''}></label>`;
    const picker=`<div class="field"><label for="content-layer-select">选择图层 / 片段</label><select id="content-layer-select">${layers.map((item,i)=>`<option value="${item.id}" ${item.id===c.id?'selected':''}>${i+1} · ${esc(item.presentation==='text'?R.textValue(w,item):item.presentation==='phone'?'手机样机':resolve(C.resolve(w,item))?.name||'壁纸画面')}</option>`).join('')}</select></div>`;
    const appearance=!audio?'<div class="content-param-group">'+P.appearance({...c,type:text?'text':'image',radius:R.radius(w,c)}).replaceAll('data-gallery-param','data-content-param').replace('data-content-param="opacity"','data-content-param="opacity" data-content-unit="percent"')+'</div>':'';
    const typography=text?`<div class="content-param-group"><div class="section-title"><strong>文字</strong></div><label>文字内容<textarea data-content-param="text" rows="3" maxlength="2000">${esc(R.textValue(w,c))}</textarea></label>${P.fontControl(c.fontFamily,'content-font-picker')}<div class="two-fields">${field('字号 px','fontSize',Number(c.fontSize.toFixed(2)),1,1000)}<label>字重<select data-content-param="fontWeight"><option value="300">细体</option><option value="400">常规</option><option value="500">中等</option><option value="600">中粗</option><option value="700">粗体</option><option value="900">黑体</option></select></label></div><label>文本框<select data-content-param="textResize"><option value="auto-width">自动宽度</option><option value="auto-height">自动高度</option><option value="fixed">固定尺寸</option></select></label>${P.textAlignControls(c)}<div class="two-fields">${field('行高倍数','lineHeight',c.lineHeight,.5,5,.05)}${field('字距 px','letterSpacing',c.letterSpacing,-100,500,.5)}</div>${P.paintControl('文字颜色','color',c.color||w.accent)}</div>`:'';
    const media=!audio&&!text?`<div class="content-param-group"><label>展示方式<select data-content-param="presentation"><option value="phone">手机样机 · 屏幕蒙版</option><option value="full">原图 / 视频画面</option><option value="detail">局部细节</option><option value="text">文字</option></select></label><label>画面素材<select id="content-clip-source"><option value="slot:wallpaper">绑定主壁纸</option><option value="slot:motion">绑定动态素材</option>${options(c.assetId)}</select></label>${field('内容缩放','scale',c.scale,.1,5,.1)}</div>`:'';
    const timing=`<div class="content-param-group"><div class="section-title"><strong>时间</strong></div><div class="two-fields">${field('开始 · 秒','start',c.start,0,C.maxDuration-C.frame(w),C.frame(w))}${field('持续 · 秒','duration',c.duration,C.frame(w),30,C.frame(w))}</div>${!text?`<div class="two-fields">${field('源素材起点','sourceIn',c.sourceIn,0,Math.max(0,(asset?.duration||30)-C.frame(w)),C.frame(w))}${field('片段速度','speed',c.speed,.25,4,.25)}</div><label>短素材处理<select data-content-param="fill"><option value="loop">循环播放</option><option value="hold">停留末帧</option></select></label>`:''}${!audio?'<label>动画 / 转场<select data-content-param="animation"><option value="none">无</option><option value="fade">淡入淡出</option><option value="push">缓慢推近</option><option value="float">轻微移动</option></select></label>':''}</div>`;
    $('content-clip-inspector').innerHTML=picker+`<div class="section-title"><strong>${audio?'音频片段':text?'文字图层':asset?.type==='image'?'图片图层':'视频图层'}</strong></div>`+(audio?`<div class="content-param-group">${field('音量（0–1）','volume',c.volume??.5,0,1,.1)}<div class="two-fields">${field('淡入 · 秒','fadeIn',c.fadeIn??.4,0,5,.1)}${field('淡出 · 秒','fadeOut',c.fadeOut??.6,0,5,.1)}</div></div>`:`<div class="content-param-group"><div class="section-title"><strong>布局</strong></div><div class="two-fields">${field('宽度 px','w',Number(g.width.toFixed(2)),1,16000,1,'px')}${field('高度 px','h',Number(g.height.toFixed(2)),1,16000,1,'px')}</div>${field('旋转 °','rotation',c.rotation||0,-180,180,.1)}</div>`+appearance+(text?typography:media))+timing;
    $('content-layer-select').onchange=e=>selectClip(layers.find(item=>item.id===e.target.value),true);
    const scope=$('content-clip-inspector');scope.querySelectorAll('[data-content-param]').forEach(input=>{
      const key=input.dataset.contentParam;if(input.tagName==='SELECT')input.value=c[key]??input.value;
      input.onchange=()=>{
        let value=input.type==='number'?input.valueAsNumber:input.value;
        if(input.type==='number'&&(!Number.isFinite(value)||input.min!==''&&value<Number(input.min)||input.max!==''&&value>Number(input.max))){B.toast('请输入允许范围内的数值');renderInspector();return;}
        const before=C.copy(c),beforeBox=R.geometry(w,c);if(key==='fontWeight')value=Number(value);if(input.dataset.contentUnit==='percent')value/=100;
        if(input.dataset.contentUnit==='px'){
          const sx=w.width/100,sy=w.height/100;if(c.presentation==='phone'){const width=key==='w'?Math.max(1,value-sx):Math.max(1,(value-sx)*.46);c.w=width/sx;c.h=width/.46/sy;}else c[key]=value/(key==='w'?w.width:w.height)*100;
          const after=R.geometry(w,c),dx=(after.width-beforeBox.width)/2*beforeBox.scale,dy=(after.height-beforeBox.height)/2*beforeBox.scale;c.x+=(Math.cos(beforeBox.angle)*dx-Math.sin(beforeBox.angle)*dy)/w.width*100;c.y+=(Math.sin(beforeBox.angle)*dx+Math.cos(beforeBox.angle)*dy)/w.height*100;
          if(text){if(key==='w'&&c.textResize==='auto-width')c.textResize='auto-height';if(key==='h')c.textResize='fixed';}
        }else c[key]=value;
        if(key==='sourceIn')delete c.loopIn;if(key==='presentation'){c.radius=c.presentation==='phone'?null:0;C.clipStyle(w,c);}
        if((key==='start'||key==='duration')&&!C.place(w,c,{trackId:C.trackOf(w,c)?.id},c.start)){Object.assign(c,before);B.toast('当前轨道没有足够空间，请调整片段位置');renderInspector();return;}
        if(c.presentation==='text')R.syncText(w,c);change('调整片段参数');
      };
    });
    if(text){P.bindFont(c,change,()=>R.syncText(w,c),'content-font-picker');P.bindPaint(c,change,requestPaint,scope);scope.querySelectorAll('[data-text-alignment]').forEach(button=>button.onclick=()=>{c[button.dataset.textAlignment]=button.dataset.textAlignValue;change('设置文字对齐');});}
    if($('content-clip-source')){$('content-clip-source').value=c.slot?`slot:${c.slot}`:c.assetId||'';$('content-clip-source').onchange=async e=>{const value=e.target.value,project=B.projectId();if(value.startsWith('slot:')){c.slot=value.slice(5);c.assetId=null;}else{try{const a=await materialize(value);if(project!==B.projectId()||work()!==w)return;c.slot=null;c.assetId=a?.id||null;}catch(error){B.toast(error.message);return;}}change('替换当前片段');};}
  }
  function selectClip(c,seek=false){
    if(!c)return;const w=work();w.selectedClipId=c.id;if(seek&&(w.cursor<c.start||w.cursor>=c.start+c.duration)&&c.start<w.duration){stop();w.cursor=Math.min(c.start,w.duration-C.frame(w));}renderInspector();renderTimeline();updatePosition();requestPaint();B.save();
  }
  function renderTimeline(){
    const w=work();if(w.type==='gallery'){
      $('content-track-count').textContent=`${w.pages.length} 页`;$('content-timeline-body').innerHTML=`<div class="content-gallery-pages">${w.pages.map((p,i)=>`<button class="content-page${i===w.pageIndex?' selected':''}" data-content-page="${i}" draggable="true"><canvas width="120" height="160" aria-hidden="true"></canvas><span>${i+1} · ${esc(p.name)}</span></button>`).join('')}</div>`;
      document.querySelectorAll('[data-content-page]').forEach(b=>{const index=Number(b.dataset.contentPage),preview={...w,pageIndex:index};R.paint(b.querySelector('canvas'),preview,0,resolve,null,true).catch(()=>{});b.onclick=()=>{w.pageIndex=index;w.selectedClipId=C.all(w)[0]?.id;render();B.save();};b.ondragstart=e=>e.dataTransfer.setData('application/x-qingjing-page',String(index));b.ondragover=e=>e.preventDefault();b.ondrop=e=>{e.preventDefault();const raw=e.dataTransfer.getData('application/x-qingjing-page');if(raw==='')return;const old=Number(raw);w.pages.splice(index,0,w.pages.splice(old,1)[0]);w.pageIndex=index;w.selectedClipId=C.all(w)[0]?.id;change('页面排序');};});return;
    }
    T.render();
  }

  function updatePosition(){const w=work();if(!w)return;$('content-position').textContent=w.type==='gallery'?`第 ${w.pageIndex+1} / ${w.pages.length} 页`:`${T.clock(w.cursor)} / ${T.clock(w.duration)} · ${C.fps(w)} fps`;const play=$('content-play'),playState=playing?'pause':'play';if(play.dataset.state!==playState){play.innerHTML=icon(playState);play.dataset.state=playState;}T.updatePosition();}
  function sizeCanvas(){const w=work();if(!w||w.type!=='video'||B.workspace()!=='create')return;const area=$('content-canvas-area'),scale=Math.min(Math.max(1,area.clientWidth-28)/w.width,Math.max(1,area.clientHeight-28)/w.height),canvas=$('content-canvas');canvas.style.width=Math.round(w.width*scale)+'px';canvas.style.height=Math.round(w.height*scale)+'px';V.draw();}
  function clearSelection(updateTimeline=true){
    const w=work();if(!w||w.type!=='video'||job)return;w.selectedClipId=null;renderInspector();if(updateTimeline)renderTimeline();else document.querySelectorAll('[data-content-clip]').forEach(el=>el.classList.remove('selected'));requestPaint();B.save();
  }
  function requestPaint(){
    V.draw();if(!work()||work().type==='gallery'||B.workspace()!=='create'||job)return;
    if(playing)R.syncPlayback(work(),work().cursor,resolve);
    if(!playing)previewRevision++;paintAgain=true;if(painting)return;
    paintPromise=(async()=>{
      painting=true;
      try{
        while(paintAgain&&!job){
          paintAgain=false;const target=work(),project=B.projectId(),revision=previewRevision;
          if(!target||target.type!=='video'||B.workspace()!=='create')break;
          const active=new Set(C.active(target,target.cursor).map(c=>c.id)),w={...target,slots:{...target.slots},tracks:target.tracks.map(track=>({...track,clips:track.clips.filter(c=>active.has(c.id)).map(c=>C.copy(c))}))},canvas=$('content-canvas'),dpr=Math.min(devicePixelRatio||1,2),scale=Math.min(1,(parseFloat(canvas.style.width)||w.width)*dpr/w.width,(parseFloat(canvas.style.height)||w.height)*dpr/w.height,1600/Math.max(w.width,w.height)),width=Math.max(1,Math.round(w.width*scale)),height=Math.max(1,Math.round(w.height*scale));
          if(previewCanvas.width!==width)previewCanvas.width=width;
          if(previewCanvas.height!==height)previewCanvas.height=height;
          // Decode and compose offscreen so a pending video seek cannot erase the visible frame.
          await R.paint(previewCanvas,w,w.cursor,resolve,null,true,{playback:playing});
          if(job||work()!==target||B.projectId()!==project||B.workspace()!=='create'||revision!==previewRevision)continue;
          if(canvas.width!==width)canvas.width=width;
          if(canvas.height!==height)canvas.height=height;
          const ctx=canvas.getContext('2d');ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.globalAlpha=1;ctx.globalCompositeOperation='copy';ctx.drawImage(previewCanvas,0,0);ctx.restore();
        }
      }catch(error){if(error.name!=='AbortError')B.toast(`预览未完成：${error.message}`);}
      finally{painting=false;if(paintAgain&&!job)requestPaint();}
    })();
  }
  function stop(){playing=false;R.stopPlayback();previewRevision++;cancelAnimationFrame(raf);previous=previousPaint=0;document.querySelectorAll('#content-workspace audio').forEach(a=>a.pause());}
  function tick(now){
    const w=work();if(!playing||!w)return;if(previous)w.cursor=(w.cursor+Math.min((now-previous)/1000,.1))%w.duration;previous=now;R.syncPlayback(w,w.cursor,resolve);
    const interval=1000/C.fps(w);if(!previousPaint||now-previousPaint>=interval-.5){previousPaint=previousPaint?previousPaint+Math.floor((now-previousPaint+.5)/interval)*interval:now;updatePosition();requestPaint();syncAudio();}raf=requestAnimationFrame(tick);
  }
  function syncAudio(){
    const w=work(),clips=w.tracks.filter(track=>track.kind==='audio'&&!track.hidden&&!track.muted).flatMap(track=>track.clips),active=new Set();
    for(const clip of clips){
      if(w.cursor<clip.start||w.cursor>=clip.start+clip.duration)continue;
      const asset=resolve(clip.assetId);if(!asset?.url)continue;
      if(clip.fill==='hold'&&(w.cursor-clip.start)*clip.speed+clip.sourceIn>=asset.duration)continue;
      const id=`content-audio-${clip.id}`;let audio=$(id);
      if(!audio){audio=document.createElement('audio');audio.id=id;$('content-workspace').append(audio);}
      active.add(id);if(audio.dataset.asset!==asset.id){audio.src=asset.url;audio.dataset.asset=asset.id;}
      const t=C.sourceTime(clip,w.cursor,asset,w);if(Number.isFinite(audio.duration)&&Math.abs(audio.currentTime-t)>.2)audio.currentTime=t;
      audio.playbackRate=clip.speed;const local=w.cursor-clip.start;
      audio.volume=C.clamp((clip.volume??.5)*Math.min(1,local/Math.max(.01,clip.fadeIn||0),(clip.duration-local)/Math.max(.01,clip.fadeOut||0)),0,1);
      if(audio.paused)audio.play().catch(()=>{});
    }
    document.querySelectorAll('#content-workspace audio').forEach(audio=>{if(!active.has(audio.id))audio.pause();});
  }
  async function materialize(id){if(!id)return null;const source=B.sources().find(a=>a.id===id);return source?.virtual?B.materialize(source):resolve(id);}
  async function addSource(id=selectedSource,trackId=null,start=null,savedClip=null){
    const w=work(),project=B.projectId();if(!id){B.toast('先在左侧选择素材');return;}try{const asset=await materialize(id);if(project!==B.projectId()||work()!==w)return;if(!asset)throw new Error('素材不可用，请重新导入');if(w.type==='gallery'){if(asset.type!=='image')throw new Error('视频和音频请加入视频作品');GE.addAsset(asset);return asset;}
      const time=C.clamp(start??w.cursor,0,C.maxDuration-C.frame(w)),duration=Math.min(savedClip?.duration||asset.duration||3,C.maxDuration),c=C.clip(asset.type==='audio'?'audio':'full',null,time,duration,{assetId:asset.id,radius:0});
      if(asset.type==='audio'){c.volume=.5;c.fadeIn=.4;c.fadeOut=.6;}if(savedClip)Object.assign(c,C.copy(savedClip),{id:c.id,assetId:asset.id,slot:null,start:time,duration});
      const target=trackId&&typeof trackId==='object'?trackId:{trackId};if(!C.place(w,c,target,time)){B.toast('当前轨道放不下这个素材，请换个位置或新增轨道');return null;}
      change('加入素材并安排轨道');return c;
    }catch(error){B.toast(error.message);}
  }
  async function importFiles(files){const project=B.projectId(),incoming=[];B.importing(true);try{for(const file of files){let asset;if(/^audio\//i.test(file.type)||/\.(mp3|m4a|wav|aac|ogg|flac)$/i.test(file.name)){const audio=new Audio(),url=URL.createObjectURL(file);try{await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('音乐读取超时')),12000);audio.onloadedmetadata=()=>{clearTimeout(timer);Number.isFinite(audio.duration)&&audio.duration>0?resolve():reject(new Error('音乐时长无效'));};audio.onerror=()=>{clearTimeout(timer);reject(new Error('音乐无法读取'));};audio.src=url;});asset={id:C.id(),name:file.name.replace(/\.[^.]+$/,''),type:'audio',file,url,duration:audio.duration};}catch(error){URL.revokeObjectURL(url);throw error;}finally{audio.removeAttribute('src');audio.load();}}else{asset=await B.read(file);if(asset.type==='4d')throw new Error('内容创作请导入实拍图片或视频');}if(project!==B.projectId()){URL.revokeObjectURL(asset.url);return [];}B.remember(asset);incoming.push(asset);}if(incoming.length){selectedSource=incoming[0].id;libraryTab='assets';change('导入创作素材');await B.flush();}}catch(error){B.toast(error.message);}finally{B.importing(false);}return incoming;}
  function duplicate(){const w=work(),c=current();if(!c)return;const next={...C.copy(c),id:C.id()};if(w.type==='gallery'){w.pages[w.pageIndex].clips.push(next);w.selectedClipId=next.id;}else if(!C.place(w,next,{trackId:C.trackOf(w,c)?.id},c.start+c.duration)){B.toast('当前轨道放不下副本，请先移动片段或新增轨道');return;}change('复制片段');}
  function remove(){const w=work(),c=current();if(!c)return;if(w.type==='gallery')w.pages[w.pageIndex].clips=w.pages[w.pageIndex].clips.filter(x=>x!==c);else{for(const t of w.tracks)t.clips=t.clips.filter(x=>x!==c);C.pruneTracks(w);}w.selectedClipId=C.all(w)[0]?.id;change('删除片段');}
  function copyClip(cut=false){const c=current();if(!c)return;clipboard={project:B.projectId(),clip:C.copy(c)};if(cut)remove();else B.toast('已复制片段');}
  function pasteClip(time=work().cursor,target=null){
    if(!clipboard)return;if(clipboard.project!==B.projectId())return B.toast('请在当前项目重新复制片段');
    const w=work(),source=clipboard.clip,c={...C.copy(source),id:C.id()},where=typeof target==='string'?{trackId:target}:target||{trackId:C.trackOf(w,current())?.id};
    if(!C.place(w,c,where,time))return B.toast('当前轨道放不下这个片段，请换个位置或新增轨道');change('粘贴片段');
  }
  function contextItems(event){
    const w=work(),clip=event.target.closest('[data-content-clip]'),lane=event.target.closest('[data-content-track]'),timeline=event.target.closest('#content-timeline-body'),at=timeline?T.time(event):w.cursor,where=timeline?T.target(event,'visual'):null,key=window.EditingMenu.shortcut;
    if(clip){w.selectedClipId=clip.dataset.contentClip;renderInspector();renderTimeline();B.save();requestPaint();}
    const c=current(),track=w.tracks.find(t=>t.id===lane?.dataset.contentTrack)||w.tracks.find(t=>t.clips.includes(c)),time=at;
    const item=(label,run,disabled=false,shortcut='')=>({label,run,disabled:disabled||!!job,shortcut}),button=(label,id,shortcut='')=>item(label,()=>$(id).click(),$(id).disabled,shortcut),items=[
      button('撤销编辑','content-undo',key('⌘ Z','Ctrl+Z')),button('重做编辑','content-redo',key('⇧ ⌘ Z','Ctrl+Shift+Z')),null,
      item('剪切片段',()=>copyClip(true),!c,key('⌘ X','Ctrl+X')),item('复制片段',()=>copyClip(),!c,key('⌘ C','Ctrl+C')),item(timeline?'粘贴到这里':'粘贴到播放头',()=>pasteClip(time,where),!clipboard||clipboard.project!==B.projectId(),key('⌘ V','Ctrl+V')),
      item('创建副本',duplicate,!c,key('⌘ D','Ctrl+D'))];
    if(c)items.push(item('收藏…',()=>ML.favoriteClip(w,c),c.presentation!=='text'&&!resolve(C.resolve(w,c))),item('导出到本地',()=>ML.exportClip(w,c),c.presentation!=='text'&&!resolve(C.resolve(w,c))));
    if(c&&c.presentation!=='audio')items.push(item('移到新轨道',()=>{const index=w.tracks.indexOf(C.trackOf(w,c));if(C.place(w,c,{index},c.start))change('移动片段到新轨道');}));
    if(timeline)items.push(item('播放头移到这里',()=>{stop();w.cursor=Math.min(time,w.duration-C.frame(w));updatePosition();requestPaint();B.save();}));
    items.push(item(lane?'在此处剪断':'在播放头剪断',()=>{stop();if(C.split(w,time))change('剪断片段');},!c||time<=c.start||time>=c.start+c.duration,key('⌘ B','Ctrl+B')));
    if(track){items.push(null,item(track.hidden?'显示轨道':'隐藏轨道',()=>{track.hidden=!track.hidden;change('切换轨道显示');}),item(track.muted?'开启轨道声音':'静音轨道',()=>{track.muted=!track.muted;change('切换轨道声音');}));if(track.kind==='visual'){const visual=w.tracks.filter(t=>t.kind==='visual'),index=visual.indexOf(track);items.push(item('轨道上移',()=>document.querySelector('[data-content-track-up="'+track.id+'"]')?.click(),index<=0),item('轨道下移',()=>document.querySelector('[data-content-track-down="'+track.id+'"]')?.click(),index>=visual.length-1));}}
    items.push(null,button('添加文字','content-add-text'),button('添加画面轨道','content-add-track'),button('导入素材 / 音乐…','content-import'),null,{...item('删除片段',remove,!c,'Delete'),danger:true});return items;
  }
  function bind(){
    document.querySelectorAll('[data-content-library]').forEach(b=>b.onclick=()=>{libraryTab=b.dataset.contentLibrary;renderLibrary();});document.querySelectorAll('[data-content-type]').forEach(b=>b.onclick=()=>{if(work()?.type===b.dataset.contentType)return;const existing=B.data().drafts.find(w=>w.type===b.dataset.contentType);if(existing){stop();B.data().activeId=existing.id;render();B.save();}else create(b.dataset.contentType);});
    $('content-new').onclick=Lib.create;$('content-save-template').onclick=Lib.manage;$('content-library-btn').onclick=Lib.contents;$('content-works-btn').onclick=Lib.works;
    $('content-add-source').onclick=()=>addSource();$('content-import').onclick=$('content-import-foot').onclick=()=>$('content-input').click();$('content-add-music').onclick=()=>ML.open('audio');$('content-input').onchange=async e=>{const list=await importFiles([...e.target.files]);e.target.value='';if(list[0]?.type==='audio'&&work()?.type==='video')await addSource(list[0].id);};
    for(const [id,key] of [['content-title','title'],['content-subtitle','subtitle'],['content-accent','accent']])$(id).onchange=e=>{work()[key]=e.target.value;change('调整模板内容');};
    $('content-open-settings').onclick=()=>clearSelection();
    $('content-play').onclick=()=>{if(playing){stop();updatePosition();requestPaint();}else{playing=true;previous=previousPaint=0;R.startPlayback(work(),work().cursor,resolve);syncAudio();updatePosition();raf=requestAnimationFrame(tick);}};
    for(const [id,delta] of [['content-prev',-1],['content-next',1]])$(id).onclick=()=>{const w=work();w.pageIndex=(w.pageIndex+delta+w.pages.length)%w.pages.length;w.selectedClipId=C.all(w)[0]?.id;render();B.save();};
    $('content-split').onclick=()=>{stop();if(C.split(work(),work().cursor))change('剪断片段');else B.toast('把播放头移到选中片段中间再剪断');};$('content-duplicate').onclick=duplicate;$('content-delete').onclick=remove;$('content-undo').onclick=()=>B.undo();$('content-redo').onclick=()=>B.undo(true);
    $('content-add-text').onclick=()=>{const w=work(),c=C.clip('text',null,w.type==='gallery'?0:w.cursor,w.type==='gallery'?1:Math.min(3,w.duration-w.cursor),{text:'双击编辑文字',x:50,y:15,w:70,h:48*1.3/w.height*100,fontFamily:'system-ui',fontSize:48,fontWeight:400,lineHeight:1.3,letterSpacing:0,align:'left',verticalAlign:'top',textResize:'auto-height',color:w.accent});if(w.type==='gallery')w.pages[w.pageIndex].clips.push(c);else{if(!C.place(w,c,{index:0},w.cursor))return B.toast('请把播放头移到视频结束之前');}w.selectedClipId=c.id;change('新增文字');};$('content-delete-page').onclick=()=>{const w=work();if(w.pages.length<=1)return;w.pages.splice(w.pageIndex,1);w.pageIndex=Math.min(w.pageIndex,w.pages.length-1);w.selectedClipId=C.all(w)[0]?.id;change('删除页面');};$('content-add-track').onclick=()=>{C.addTrack(work(),'visual',0,true);change('新增画面轨道');};$('content-add-page').onclick=()=>{const w=work(),page=C.copy(w.pages[w.pageIndex]);page.id=C.id();page.name='新页面';for(const c of page.clips)c.id=C.id();w.pages.push(page);w.pageIndex=w.pages.length-1;w.selectedClipId=C.all(w)[0]?.id;change('新增页面');};
    $('content-canvas-area').ondragover=e=>{if([...e.dataTransfer.types].includes('application/x-qingjing-content-source'))e.preventDefault();};$('content-canvas-area').ondrop=e=>{e.preventDefault();e.stopPropagation();const w=work(),id=e.dataTransfer.getData('application/x-qingjing-content-source');if(id)addSource(id);else if(e.dataTransfer.files.length)importFiles([...e.dataTransfer.files]).then(async assets=>{if(work()!==w)return;for(const a of assets)await addSource(a.id);});};
    document.addEventListener('keydown',e=>{if(B.workspace()!=='create'||work()?.type==='gallery'||V.editing()||P.isFontOpen()||$('dialog').open||e.target.closest('input,textarea,select,[contenteditable]'))return;const key=e.key.toLowerCase(),modifier=e.metaKey||e.ctrlKey;if(modifier&&key==='c'){e.preventDefault();copyClip();}else if(modifier&&key==='x'){e.preventDefault();copyClip(true);}else if(modifier&&key==='v'){e.preventDefault();pasteClip();}else if(modifier&&key==='d'){e.preventDefault();duplicate();}else if(modifier&&key==='b'){e.preventDefault();$('content-split').click();}else if(key==='delete'||key==='backspace'){e.preventDefault();remove();}else if(key==='escape'){e.preventDefault();clearSelection();}else if(!modifier&&(e.key==='ArrowLeft'||e.key==='ArrowRight')){e.preventDefault();stop();const w=work(),rate=C.fps(w),delta=(e.key==='ArrowRight'?1:-1)*(e.shiftKey?10:1);w.cursor=C.clamp((Math.round(w.cursor*rate)+delta)/rate,0,w.duration-C.frame(w));updatePosition();requestPaint();B.save();}else if(e.code==='Space'&&e.target.tagName!=='BUTTON'){e.preventDefault();$('content-play').click();}});
    $('dialog').addEventListener('close',()=>{if(!$('dialog').open)job?.abort();});
  }
  function exportVideo(){if(work()?.type==='video')return generate('download');}
  async function generate(destination='work'){
    if(job)return;stop();const original=work();if(!original)return;if(original.type==='gallery'){G.ensure(original);if(!original.gallery.frames.length)return B.toast('请先新建画框并放入组件，再生成图片作品');}const w=C.copy(original),project=B.projectId(),controller=new AbortController(),local=destination==='download';job=controller;const signal=controller.signal;R.clear();
    B.modal(local?'导出视频':'生成作品','<div class="content-generation"><strong id="content-progress-title">准备画面…</strong><progress id="content-progress" value="0" max="100"></progress><small id="content-progress-note">'+(local?'完成后将下载 MP4 到本地。':'画面准备时请保持页面打开；开始本地编码后可在「生成任务」查看结果。')+'</small></div>',[{label:'取消',run:()=>{controller.abort();B.close();}}]);
    let task;const progress=(value,text)=>{if($('content-progress'))$('content-progress').value=value;if($('content-progress-title'))$('content-progress-title').textContent=text;};
    try{
      await window.CreatorBackend.requireConnection();if(!await B.flush())throw new Error('请先重试保存当前项目');await paintPromise;if(signal.aborted)throw new DOMException('已取消','AbortError');const canvas=document.createElement('canvas');canvas.width=w.width;canvas.height=w.height;const files=[];task=await window.CreatorJobs.begin(w.type==='gallery'?'GALLERY_RENDER':'CONTENT_RENDER',await window.CreatorJobs.snapshot(w,resolve));
      if(task.state==='SUCCEEDED')files.push(...await window.CreatorJobs.result(task));
      else if(['QUEUED','RUNNING'].includes(task.state))files.push(...await window.CreatorJobs.wait(task,signal,(text,value)=>progress(value??94,text)));
      else if(w.type==='gallery'){
        const frames=w.gallery.frames;if(frames.reduce((sum,f)=>sum+f.width*f.height,0)>100000000)throw new Error('图集总像素过大，请减少画框或尺寸');
        for(let i=0;i<frames.length;i++){const f=frames[i];await window.GalleryRenderer.paint(canvas,w,f,resolve,{signal});files.push(new File([await R.blob(canvas)],window.ResourceExport.filename(w.name)+'-'+String(i+1).padStart(2,'0')+'.png',{type:'image/png'}));progress((i+1)/frames.length*95,'已生成 '+(i+1)+' / '+frames.length+' 张');}
        const outputMediaIds=[];for(const file of files){if(signal.aborted)throw new DOMException('已取消','AbortError');outputMediaIds.push((await window.CreatorBackend.upload(file,{type:'image'})).id);}await window.CreatorJobs.action(task.id,'complete',{outputMediaIds});
      }else{
        const blob=await window.ContentVideoExport.render(canvas,w,resolve,signal,progress,task);
        files.push(new File([blob],`${window.ResourceExport.filename(w.output.name||w.name)}.mp4`,{type:'video/mp4'}));
      }
      if(signal.aborted||project!==B.projectId()||work()!==original)return;
      job=null;B.close();if(local){B.download(files[0].name,files[0]);B.toast('视频已下载到本地');}else{Lib.confirm(files,w,task?.id);renderLibrary();}
    }catch(error){if(task)await window.CreatorJobs.action(task.id,error.name==='AbortError'?'cancel':'fail',{message:error.message}).catch(()=>{});if(error.name!=='AbortError'&&project===B.projectId()){job=null;B.close();B.toast(`${local?'导出':'生成'}未完成：${error.message}`);}}finally{R.clearExport();if(job===controller)job=null;if(work()?.type==='gallery')GE.refresh();else requestPaint();}
  }
  function enter(){ensure();render();}
  function reset(){stop();V.reset();T.reset();GE.reset();Lib.clearPreview();ML.clearPreview();job?.abort();job=null;paintAgain=false;R.clear();selectedSource=null;clipboard=null;}
  window.ContentStudio={mount,enter,render,stop,reset,generate,exportVideo,importFiles};
})();
