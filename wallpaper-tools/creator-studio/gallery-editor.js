/* Lightweight multi-frame editor. Media is shared; frame geometry and instance crops are local. */
(() => {
  'use strict';
  const G=window.GalleryCore,C=window.ContentCore,R=window.GalleryRenderer,$=id=>document.getElementById(id);
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let H,canvas,ctx,viewport,observer,cache=new Map(),space=false,clipboard=null,drag=null,lastWork=null,editorInput=null;
  const work=()=>H.work(),scene=()=>G.ensure(work()),surface=()=>G.surface(scene()),active=()=>H.active()&&work()?.type==='gallery';
  const change=label=>H.change(label),field=(label,key,value,type='number',extra='')=>'<label>'+label+'<input data-gallery-param="'+key+'" type="'+type+'" value="'+esc(value)+'" '+extra+'></label>';
  function mount(bridge){
    H=bridge;viewport=document.createElement('div');viewport.id='gallery-editor';
    viewport.innerHTML='<div class="gallery-tools"><div><button data-gallery-tool="select" class="active" title="选择 V">↖ 选择</button><button id="gallery-add-frame" title="新建画框 F">▣ 画框</button><button id="gallery-add-text" title="文字 T">T 文字</button><button id="gallery-add-rect" title="矩形 R">□ 矩形</button></div><div><button id="gallery-group" title="分组 ⌘/Ctrl G">分组</button><button id="gallery-component" title="创建组件 ⌘/Ctrl Alt K">◇ 组件</button><button id="gallery-shortcuts" title="快捷键">⌨</button></div></div><div id="gallery-viewport"><canvas id="gallery-board" tabindex="0" aria-label="图片模板无限画布"></canvas><div id="gallery-selection-label"></div></div><div class="gallery-viewbar"><span id="gallery-canvas-status">空格拖动画布 · 滚轮平移 · ⌘/Ctrl + 滚轮缩放</span><div><button id="gallery-zoom-out" aria-label="缩小画布">−</button><button id="gallery-zoom-label">100%</button><button id="gallery-zoom-in" aria-label="放大画布">＋</button><button id="gallery-fit">适应画布</button></div></div>';
    $('content-canvas-area').after(viewport);canvas=$('gallery-board');ctx=canvas.getContext('2d');
    const inspector=document.createElement('div');inspector.id='gallery-inspector';inspector.className='content-inspector-scroll';document.querySelector('.content-inspector .panel-heading').after(inspector);
    observer=new ResizeObserver(()=>{if(active())draw();});observer.observe($('gallery-viewport'));bind();
  }
  function render(){
    if(!active())return;const w=work(),g=scene();if(lastWork!==w.id){lastWork=w.id;cache.clear();}
    if(!g.view)fit();renderInspector();draw();
  }
  function view(){return scene().view||{x:70,y:70,zoom:.25};}
  function frameRect(f){return {x:f.x,y:f.y,width:f.width,height:f.height};}
  function fit(selected=false){
    const g=scene(),frames=selected?[surface()]:G.surfaces(g);if(!frames.length)return;const b=G.union(frames.map(frameRect)),rect=$('gallery-viewport').getBoundingClientRect(),z=C.clamp(Math.min((rect.width-100)/(b.width+60),(rect.height-90)/(b.height+60)),.025,4);
    g.view={x:(rect.width-b.width*z)/2-b.x*z,y:(rect.height-b.height*z)/2-b.y*z+12,zoom:z};H.save();draw();
  }
  function zoom(factor,anchor){const v=view(),r=$('gallery-viewport').getBoundingClientRect(),p=anchor||{x:r.width/2,y:r.height/2},before={x:(p.x-v.x)/v.zoom,y:(p.y-v.y)/v.zoom};v.zoom=C.clamp(v.zoom*factor,.025,6);v.x=p.x-before.x*v.zoom;v.y=p.y-before.y*v.zoom;scene().view=v;H.save();draw();}
  function world(e){const r=canvas.getBoundingClientRect(),v=view();return {x:(e.clientX-r.left-v.x)/v.zoom,y:(e.clientY-r.top-v.y)/v.zoom};}
  function cached(f){
    const w=work(),key=JSON.stringify([f,w.slots,w.gallery.components]);let entry=cache.get(f.id);
    if(entry?.key===key)return entry;const prior=entry;entry={key,canvas:prior?.canvas,rect:prior?.rect};cache.set(f.id,entry);
    const target=document.createElement('canvas'),snapshot=C.copy(w),copy=C.copy(f);
    R.paint(target,snapshot,copy,H.resolve,{preview:true}).then(rect=>{if(cache.get(f.id)===entry){entry.canvas=target;entry.rect=rect;if(active())draw();}}).catch(error=>{if(error.name!=='AbortError'&&cache.get(f.id)===entry){entry.error=error.message;H.toast('图片预览失败：'+error.message);}});
    return entry;
  }
  function selectionBounds(){const f=surface(),rows=G.roots(scene());if(!rows.length)return null;const b=G.union(rows.map(r=>G.bounds(r.node,r.matrix)));return {...b,x:b.x+f.x,y:b.y+f.y};}
  function draw(){
    if(!active())return;const rect=$('gallery-viewport').getBoundingClientRect();if(rect.width<1||rect.height<1)return;const dpr=Math.min(devicePixelRatio||1,2);canvas.width=Math.round(rect.width*dpr);canvas.height=Math.round(rect.height*dpr);ctx.setTransform(dpr,0,0,dpr,0,0);
    ctx.fillStyle='#1e2024';ctx.fillRect(0,0,rect.width,rect.height);const v=view(),gap=24;ctx.fillStyle='#353a42';for(let x=((v.x%gap)+gap)%gap;x<rect.width;x+=gap)for(let y=((v.y%gap)+gap)%gap;y<rect.height;y+=gap)ctx.fillRect(x,y,1,1);
    ctx.save();ctx.translate(v.x,v.y);ctx.scale(v.zoom,v.zoom);
    const g=scene();for(const f of G.surfaces(g)){
      const component=!!g.components[f.id],entry=cached(f);ctx.fillStyle='#b2bbca';ctx.font=12/v.zoom+'px system-ui';ctx.textAlign='left';ctx.fillText((component?'◇ ':'▣ ')+f.name,f.x,f.y-13/v.zoom);
      ctx.fillStyle='#282c34';ctx.fillRect(f.x,f.y,f.width,f.height);
      if(entry.canvas){const b=entry.rect;ctx.drawImage(entry.canvas,f.x+b.x,f.y+b.y,b.width,b.height);}
      ctx.strokeStyle=g.selectedSurface===f.id?(component?'#bd92ff':'#80aaff'):'#515660';ctx.lineWidth=(g.selectedSurface===f.id?1.5:1)/v.zoom;ctx.strokeRect(f.x,f.y,f.width,f.height);
    }
    const b=selectionBounds();if(b){ctx.strokeStyle='#92b8ff';ctx.lineWidth=1/v.zoom;ctx.strokeRect(b.x,b.y,b.width,b.height);for(const p of handles(b)){ctx.fillStyle='#edf4ff';ctx.fillRect(p.x-3/v.zoom,p.y-3/v.zoom,6/v.zoom,6/v.zoom);ctx.strokeRect(p.x-3/v.zoom,p.y-3/v.zoom,6/v.zoom,6/v.zoom);}}
    if(drag?.kind==='marquee'){ctx.fillStyle='#80aaff20';ctx.strokeStyle='#82aaff';ctx.lineWidth=1/v.zoom;const b=drag.box;ctx.fillRect(b.x,b.y,b.width,b.height);ctx.strokeRect(b.x,b.y,b.width,b.height);}
    ctx.restore();$('gallery-zoom-label').textContent=Math.round(v.zoom*100)+'%';$('gallery-canvas-status').textContent=g.selection.length?g.selection.length+' 个图层 · 方向键微调 · Shift + 方向键移动 10 px':'空格拖动画布 · 滚轮平移 · ⌘/Ctrl + 滚轮缩放';
  }
  function handles(b){return [{x:b.x,y:b.y,edge:'nw'},{x:b.x+b.width,y:b.y,edge:'ne'},{x:b.x,y:b.y+b.height,edge:'sw'},{x:b.x+b.width,y:b.y+b.height,edge:'se'}];}
  function select(f,n,shift=false){const g=scene();if(g.selectedSurface!==f.id)g.selection=[];g.selectedSurface=f.id;if(!n)g.selection=[];else if(shift)g.selection=g.selection.includes(n.id)?g.selection.filter(id=>id!==n.id):[...g.selection,n.id];else if(!g.selection.includes(n.id))g.selection=[n.id];H.save();H.library();renderInspector();draw();}
  function renderLayers(){
    const g=scene();function rows(nodes,depth){return nodes.slice().reverse().map(n=>'<div class="gallery-layer-row'+(g.selection.includes(n.id)?' selected':'')+'" style="padding-left:'+(9+depth*14)+'px"><button data-gallery-select="'+n.id+'" title="'+esc(n.name)+'">'+(n.type==='instance'?'◇':n.type==='group'?'▧':n.type==='text'?'T':'▨')+' <span>'+esc(n.name)+'</span></button><button data-gallery-visible="'+n.id+'" aria-label="显示或隐藏 '+esc(n.name)+'">'+(n.visible?'◉':'○')+'</button><button data-gallery-lock="'+n.id+'" aria-label="锁定或解锁 '+esc(n.name)+'">'+(n.locked?'▣':'·')+'</button></div>'+(n.children?rows(n.children,depth+1):'')).join('');}
    return '<div class="gallery-layer-list">'+G.surfaces(g).map((f,i)=>'<div class="gallery-frame-tree"><button class="gallery-frame-row'+(f.id===g.selectedSurface?' active':'')+'" data-gallery-surface="'+f.id+'">▣ '+(i+1)+' · '+esc(f.name)+'</button>'+rows(f.nodes,0)+'</div>').join('')+'</div>';
  }
  function renderComponents(){return '<p class="content-slot-help">双击画布上的主组件编辑。修改主组件会同步到全部实例。</p>'+Object.values(scene().components).map(c=>'<div class="gallery-component-card"><button data-gallery-surface="'+c.id+'">◇ '+esc(c.name)+'</button><small>'+G.instances(scene(),c.id).length+' 个实例</small><button data-gallery-insert="'+c.id+'">＋ 添加实例</button></div>').join('');}
  function bindLibrary(){
    document.querySelectorAll('[data-gallery-surface]').forEach(b=>b.onclick=()=>{select(G.surface(scene(),b.dataset.gallerySurface),null);fit(true);});
    document.querySelectorAll('[data-gallery-select]').forEach(b=>b.onclick=e=>{const f=G.surfaces(scene()).find(f=>G.find(f.nodes,b.dataset.gallerySelect));select(f,G.find(f.nodes,b.dataset.gallerySelect).node,e.shiftKey);});
    for(const [attr,key] of [['galleryVisible','visible'],['galleryLock','locked']])document.querySelectorAll('[data-'+attr.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())+']').forEach(b=>b.onclick=()=>{const f=G.surfaces(scene()).find(f=>G.find(f.nodes,b.dataset[attr])),n=G.find(f.nodes,b.dataset[attr]).node;n[key]=!n[key];change(key==='visible'?'切换图层显示':'切换图层锁定');});
    document.querySelectorAll('[data-gallery-insert]').forEach(b=>b.onclick=()=>{if(!G.addInstance(scene(),b.dataset.galleryInsert))H.toast('不能将组件放入自身或它依赖的组件');else change('添加组件实例');});
  }
  function sourceOptions(value){return '<option value="">未绑定</option><option value="slot:wallpaper"'+(value==='slot:wallpaper'?' selected':'')+'>主壁纸 · 项目素材</option>'+H.sources().filter(a=>a.type==='image').map(a=>'<option value="'+a.id+'"'+(a.id===value?' selected':'')+'>'+esc(a.name)+'</option>').join('');}
  function renderInspector(){
    if(!active())return;const g=scene(),f=surface(),rows=G.selected(g),n=rows[0]?.node,component=!!g.components[f.id];let html='';
    if(!n){
      html='<section><div class="section-title"><strong>'+(component?'◇ 主组件':'▣ 画框')+'</strong><span>'+g.frames.length+' 张图片</span></div>'+field('名称','name',f.name,'text')+'<div class="two-fields">'+field('宽度 px','width',f.width)+field('高度 px','height',f.height)+'</div>';
      if(!component)html+='<label>尺寸预设<select id="gallery-size-preset"><option value="">选择尺寸</option><option value="1080x1440">3:4 · 1080 × 1440</option><option value="1080x1920">9:16 · 1080 × 1920</option><option value="1080x1080">1:1 · 1080 × 1080</option><option value="1206x2622">iPhone · 1206 × 2622</option></select></label><button id="gallery-size-all" class="full-width">将此尺寸应用到全部画框</button><label class="gallery-check"><input id="gallery-clip" type="checkbox" '+(f.clip?'checked':'')+'>裁剪溢出内容</label><p class="content-slot-help">关闭后可在画布查看溢出部分；导出的图片仍以画框尺寸为边界。</p>'+field('背景颜色','background',f.background||'#18202c','color');
      html+='</section><section><div class="gallery-action-grid"><button id="gallery-copy-frame">复制'+(component?'组件':'画框')+'</button><button id="gallery-remove-frame">删除'+(component?'组件':'画框')+'</button></div>';
      if(!component)html+='<div class="gallery-action-grid"><button id="gallery-frame-prev">导出顺序前移</button><button id="gallery-frame-next">导出顺序后移</button></div>';
      else html+='<p class="content-slot-help">这里的图层是主组件。选中图片后替换素材，会更新所有实例。</p>';
      html+='</section>';
    }else{
      html='<section><div class="section-title"><strong>'+(rows.length>1?rows.length+' 个图层':n.type==='instance'?'◇ 组件实例':'图层属性')+'</strong><button id="gallery-select-frame" class="text-button">画框</button></div>'+field('名称','name',n.name,'text')+'<div class="two-fields">'+field('X','x',Math.round(n.x))+field('Y','y',Math.round(n.y))+'</div><div class="two-fields">'+field('宽度 px','width',Math.round(n.width))+field('高度 px','height',Math.round(n.height))+'</div><div class="two-fields">'+field('旋转 °','rotation',n.rotation||0)+field('透明度 %','opacity',Math.round(n.opacity*100))+'</div><div class="gallery-action-grid"><button id="gallery-layer-up">上移一层 ]</button><button id="gallery-layer-down">下移一层 [</button></div></section>';
      if(n.type==='text')html+='<section><label>文字内容<textarea id="gallery-text" rows="4">'+esc(n.text)+'</textarea></label><label>字体<select data-gallery-param="fontFamily"><option value="system-ui">系统默认</option><option value="sans-serif">系统无衬线</option><option value="serif">系统衬线 / 宋体</option><option value="monospace">系统等宽</option></select></label><div class="two-fields">'+field('字号','fontSize',n.fontSize||40)+'<label>字重<select data-gallery-param="fontWeight"><option value="400">常规</option><option value="600">中粗</option><option value="700">粗体</option></select></label></div><label>对齐<select data-gallery-param="align"><option value="left">左对齐</option><option value="center">居中</option><option value="right">右对齐</option></select></label>'+field('文字颜色','color',n.color||'#ffffff','color')+'</section>';
      if(n.type==='image')html+='<section><label>图片素材<select id="gallery-image-source">'+sourceOptions(n.slot?'slot:'+n.slot:n.assetId)+'</select></label><label>填充方式<select data-gallery-param="fit"><option value="cover">填满 · 裁剪</option><option value="contain">完整显示</option></select></label></section>';
      if(n.type==='instance')html+='<section><div class="section-title"><strong>'+esc(g.components[n.componentId]?.name||'组件')+'</strong></div><button id="gallery-edit-master" class="full-width">编辑主组件 · 所有实例同步</button><button id="gallery-detach" class="full-width">解除组件关联</button><label>蒙版样式<select data-gallery-param="mask"><option value="none">无</option><option value="round">圆角屏幕</option><option value="phone">手机样机</option></select></label></section>';
      if(['image','instance'].includes(n.type))html+='<section><div class="section-title"><strong>图片构图</strong></div>'+field('内容缩放','scale',n.scale||1,'number','min="0.1" max="10" step="0.1"')+'<div class="two-fields">'+field('水平偏移 %','panX',n.panX||0)+field('垂直偏移 %','panY',n.panY||0)+'</div>'+field('圆角 px','radius',n.radius||0)+'</section>';
      if(n.type==='rect')html+='<section>'+field('填充颜色','color',n.color||'#ffffff','color')+field('圆角 px','radius',n.radius||0)+'</section>';
      html+='<section><div class="gallery-action-grid"><button id="gallery-selection-group">分组</button><button id="gallery-ungroup">取消分组</button><button id="gallery-create-component">创建组件</button><button id="gallery-delete">删除图层</button></div></section>';
    }
    $('gallery-inspector').innerHTML=html;bindInspector(n||f,!!n);
  }
  function action(id,fn){if($(id))$(id).onclick=fn;}
  function bindInspector(target,isNode){
    document.querySelectorAll('[data-gallery-param]').forEach(input=>{const key=input.dataset.galleryParam;if(input.tagName==='SELECT')input.value=target[key]??input.value;input.onchange=()=>{let value=input.type==='number'?input.valueAsNumber:input.value;if(input.type==='number'&&!Number.isFinite(value))return renderInspector();if(['width','height'].includes(key))value=C.clamp(value,1,isNode?16000:4096);if(key==='fontSize')value=C.clamp(value,1,1000);if(key==='opacity')value=C.clamp(value/100,0,1);if(key==='scale')value=C.clamp(value,.1,10);if(key==='radius')value=Math.max(0,value);if(key==='fontWeight')value=Number(value);if(isNode&&target.type==='group'&&['width','height'].includes(key))G.resizeGroup(target,key==='width'?value:target.width,key==='height'?value:target.height);else target[key]=value;change('修改'+(isNode?'图层':'画框')+'属性');};});
    action('gallery-select-frame',()=>select(surface(),null));action('gallery-size-all',()=>{const f=surface();for(const s of scene().frames){s.width=f.width;s.height=f.height;}scene().preset={width:f.width,height:f.height};change('统一画框尺寸');});
    if($('gallery-size-preset'))$('gallery-size-preset').onchange=e=>{if(!e.target.value)return;const [width,height]=e.target.value.split('x').map(Number);Object.assign(surface(),{width,height});scene().preset={width,height};change('设置画框尺寸');};
    if($('gallery-clip'))$('gallery-clip').onchange=e=>{surface().clip=e.target.checked;change('切换画框溢出裁剪');};
    if($('gallery-text'))$('gallery-text').onchange=e=>{target.text=e.target.value;change('编辑文字');};
    if($('gallery-image-source'))$('gallery-image-source').onchange=e=>{const value=e.target.value;target.slot=value==='slot:wallpaper'?'wallpaper':null;target.assetId=target.slot?null:value||null;change('替换组件或图层图片');};
    action('gallery-copy-frame',copyFrame);action('gallery-remove-frame',removeSurface);
    for(const [id,d] of [['gallery-frame-prev',-1],['gallery-frame-next',1]])action(id,()=>{const g=scene(),index=g.frames.indexOf(surface()),next=index+d;if(next<0||next>=g.frames.length)return;g.frames.splice(next,0,g.frames.splice(index,1)[0]);change('调整图集导出顺序');});
    action('gallery-layer-up',()=>reorder(1));action('gallery-layer-down',()=>reorder(-1));action('gallery-selection-group',group);action('gallery-ungroup',ungroup);action('gallery-create-component',component);action('gallery-delete',remove);
    action('gallery-edit-master',()=>{const f=scene().components[target.componentId];select(f,null);fit(true);H.showLibrary('components');});
    action('gallery-detach',()=>{const def=scene().components[target.componentId],r=G.find(surface().nodes,target.id);if(!def)return;const group=G.node('group',{x:target.x,y:target.y,width:def.width,height:def.height,name:target.name,rotation:target.rotation,opacity:target.opacity,children:def.nodes.map(G.cloneNode),mask:target.mask,scale:target.scale,panX:target.panX,panY:target.panY,radius:target.radius,lock:target.lock});G.resizeGroup(group,target.width,target.height);r.list.splice(r.list.indexOf(target),1,group);scene().selection=[group.id];change('解除组件关联');});
  }
  function addFrame(){const g=scene(),f=G.frame(g.preset.width,g.preset.height,g.frames.length),last=g.frames.at(-1);if(last){f.x=last.x+last.width+180;f.y=last.y;}g.frames.push(f);g.selectedSurface=f.id;g.selection=[];change('新增画框');fit(true);}
  function add(type){const f=surface(),n=G.node(type,{x:f.width*.15,y:f.height*.15,width:type==='text'?f.width*.7:Math.min(360,f.width*.5),height:type==='text'?120:Math.min(360,f.height*.5),text:'双击编辑文字',fontSize:48,fontFamily:'system-ui',fontWeight:400,color:type==='text'?'#ffffff':'#6c92ce',align:'left'});f.nodes.push(n);scene().selection=[n.id];change(type==='text'?'新增文字':'新增矩形');}
  function addAsset(asset,position=null){if(asset.type!=='image'){H.toast('图片模板请使用图片或透明样机图片');return;}const f=surface(),width=Math.min(asset.width||f.width,f.width*.8),height=width/(asset.width||1)*(asset.height||1),n=G.node('image',{name:asset.name,assetId:asset.id,width,height,x:(f.width-width)/2,y:(f.height-height)/2,fit:'contain'});if(position){n.x=Math.round(position.x-f.x-width/2);n.y=Math.round(position.y-f.y-height/2);}f.nodes.push(n);scene().selection=[n.id];change('加入图片素材');}
  function group(){if(!G.group(scene()))return H.toast('请选择同一分组下的两个或更多图层');change('图层分组');}
  function ungroup(){if(G.ungroup(scene()))change('取消分组');}
  function component(){const def=G.makeComponent(scene());if(!def)return H.toast('先选择图片、文字或分组');change('创建主组件');H.showLibrary('components');}
  function copyFrame(){const g=scene(),source=surface(),n=C.copy(source);n.id=C.id();n.name+=' 副本';n.x+=n.width+180;n.nodes=n.nodes.map(G.cloneNode);if(g.components[source.id])g.components[n.id]=n;else g.frames.push(n);g.selectedSurface=n.id;g.selection=[];change('复制画框或组件');}
  function removeSurface(){const g=scene(),f=surface();if(g.components[f.id]){if(G.instances(g,f.id).length)return H.toast('该组件仍有实例，请先解除实例关联');delete g.components[f.id];}else{if(g.frames.length===1)return H.toast('至少保留一个画框');g.frames=g.frames.filter(x=>x!==f);}g.selectedSurface=g.frames[0].id;g.selection=[];change('删除画框或组件');}
  function remove(){if(scene().selection.length){G.remove(scene());change('删除图层');}else removeSurface();}
  function reorder(direction){const rows=G.roots(scene());if(rows.length!==1)return H.toast('先选择一个图层或分组');const r=rows[0],index=r.list.indexOf(r.node),next=index+direction;if(next<0||next>=r.list.length)return;r.list.splice(next,0,r.list.splice(index,1)[0]);change('调整图层顺序');}
  function copySelection(cut=false){const g=scene(),rows=G.roots(g);clipboard={project:H.projectId(),nodes:rows.map(r=>C.copy(r.node)),frame:rows.length?null:C.copy(surface()),components:C.copy(g.components)};if(cut&&rows.length)remove();else H.toast(rows.length?'已复制图层':'已复制画框');}
  function paste(){if(!clipboard)return;if(clipboard.project!==H.projectId())return H.toast('请在当前项目重新复制图层');const g=scene();for(const [key,def] of Object.entries(clipboard.components))if(!g.components[key])g.components[key]=C.copy(def);if(clipboard.frame){const f=C.copy(clipboard.frame);f.id=C.id();f.x+=f.width+180;f.name+=' 副本';f.nodes=f.nodes.map(G.cloneNode);g.frames.push(f);g.selectedSurface=f.id;g.selection=[];}else{const f=surface(),nodes=clipboard.nodes.filter(n=>!n.componentId||!G.wouldCycle(g,f.id,n.componentId)).map(G.cloneNode);nodes.forEach(n=>{n.x+=32;n.y+=32;});f.nodes.push(...nodes);g.selection=nodes.map(n=>n.id);}change('粘贴图层或画框');}
  function inlineText(n){if(n.type!=='text')return;editorInput?.remove();const f=surface(),r=G.find(f.nodes,n.id),b=G.bounds(n,r.matrix),v=view(),input=document.createElement('textarea');editorInput=input;input.className='gallery-inline-text';input.value=n.text;Object.assign(input.style,{left:(f.x+b.x)*v.zoom+v.x+'px',top:(f.y+b.y)*v.zoom+v.y+'px',width:Math.max(120,b.width*v.zoom)+'px',height:Math.max(70,b.height*v.zoom)+'px',fontSize:Math.max(14,n.fontSize*v.zoom)+'px',color:n.color});$('gallery-viewport').append(input);input.focus();input.select();let done=false;const finish=save=>{if(done)return;done=true;const value=input.value;input.remove();editorInput=null;if(save&&value!==n.text){n.text=value;change('编辑文字');}};input.onblur=()=>finish(true);input.onkeydown=e=>{if(e.key==='Escape'){e.preventDefault();finish(false);}else if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){e.preventDefault();finish(true);}};}
  function bind(){
    document.querySelector('[data-gallery-tool="select"]').onclick=()=>{space=false;canvas.style.cursor='default';canvas.focus();};
    action('gallery-add-frame',addFrame);action('gallery-add-text',()=>add('text'));action('gallery-add-rect',()=>add('rect'));action('gallery-group',group);action('gallery-component',component);action('gallery-zoom-in',()=>zoom(1.25));action('gallery-zoom-out',()=>zoom(.8));action('gallery-fit',()=>fit());action('gallery-zoom-label',()=>fit(true));
    action('gallery-shortcuts',()=>H.modal('图片模板快捷键','<div class="gallery-shortcut-list"><p>⌘ / Ctrl + C / X / V　复制 / 剪切 / 粘贴</p><p>⌘ / Ctrl + D　创建副本</p><p>⌘ / Ctrl + Z　撤销　·　⌘ / Ctrl + Shift + Z / Ctrl + Y　重做</p><p>⌘ / Ctrl + G　分组　·　⌘ / Ctrl + Shift + G　取消分组</p><p>⌘ / Ctrl + Alt + K　创建组件</p><p>Delete / Backspace　删除　·　[ / ]　调整图层顺序</p><p>方向键　移动 1 px　·　Shift + 方向键　移动 10 px</p><p>空格 + 拖动 / 中键　平移画布</p><p>⌘ / Ctrl + 滚轮 / ＋ / −　缩放</p><p>Shift + 1　查看全部画框　·　Shift + 2　查看当前画框</p><p>V　选择　·　F　新建画框　·　T　文字　·　R　矩形</p><p>Shift + 点击　多选　·　Alt + 点击　选择组内图层</p><p>双击文字　编辑内容　·　F2　重命名</p></div>',[{label:'知道了',primary:true,run:H.close}]));
    canvas.onwheel=e=>{e.preventDefault();if(e.ctrlKey||e.metaKey){const r=canvas.getBoundingClientRect();zoom(Math.exp(-e.deltaY*.003),{x:e.clientX-r.left,y:e.clientY-r.top});}else{view().x-=e.deltaX;view().y-=e.deltaY;H.save();draw();}};
    canvas.oncontextmenu=e=>e.preventDefault();
    canvas.onpointerdown=e=>{
      if(!active()||e.button===2)return;e.preventDefault();canvas.focus();canvas.setPointerCapture(e.pointerId);const p=world(e),g=scene(),v=view();
      if(space||e.button===1){drag={kind:'pan',x:e.clientX,y:e.clientY,view:{...v}};return;}
      const b=selectionBounds(),handle=b&&handles(b).find(h=>Math.hypot(h.x-p.x,h.y-p.y)<8/v.zoom),rows=G.roots(g);
      if(handle&&rows.length===1&&!rows[0].node.locked){drag={kind:'resize',p,edge:handle.edge,row:rows[0],before:C.copy(rows[0].node)};return;}
      for(const f of G.surfaces(g).slice().reverse()){
        const inFrame=p.x>=f.x&&p.x<=f.x+f.width&&p.y>=f.y&&p.y<=f.y+f.height,title=p.x>=f.x&&p.x<=f.x+f.width&&p.y<f.y&&p.y>=f.y-30/v.zoom;
        const hit=(!f.clip||inFrame)?G.hit(f.nodes,{x:p.x-f.x,y:p.y-f.y},e.altKey):null;
        if(hit){select(f,hit,e.shiftKey);drag={kind:'nodes',p,rows:G.roots(g).filter(r=>!r.node.locked).map(r=>({...r,before:C.copy(r.node)}))};return;}
        if(title||inFrame){select(f,null);drag=title?{kind:'frame',p,f,before:{x:f.x,y:f.y}}:{kind:'marquee',p,box:{x:p.x,y:p.y,width:0,height:0},base:[]};return;}
      }
      if(!e.shiftKey)g.selection=[];drag={kind:'marquee',p,box:{x:p.x,y:p.y,width:0,height:0},base:e.shiftKey?[...g.selection]:[]};renderInspector();draw();
    };
    canvas.onpointermove=e=>{
      if(!drag)return;const p=world(e),dx=p.x-drag.p?.x,dy=p.y-drag.p?.y;
      if(drag.kind==='pan'){view().x=drag.view.x+e.clientX-drag.x;view().y=drag.view.y+e.clientY-drag.y;}
      if(drag.kind==='frame'){drag.f.x=Math.round(drag.before.x+dx);drag.f.y=Math.round(drag.before.y+dy);}
      if(drag.kind==='nodes')for(const row of drag.rows){const m=G.inverse(row.parentMatrix),a=G.point(m,p),b=G.point(m,drag.p);row.node.x=Math.round(row.before.x+a.x-b.x);row.node.y=Math.round(row.before.y+a.y-b.y);}
      if(drag.kind==='resize'){const {row,before,edge}=drag,m=G.inverse(row.parentMatrix),a=G.point(m,p),b=G.point(m,drag.p),dx=a.x-b.x,dy=a.y-b.y,width=Math.max(5,before.width+(edge.includes('w')?-dx:dx)),height=e.shiftKey?width/before.width*before.height:Math.max(5,before.height+(edge.includes('n')?-dy:dy));Object.assign(row.node,C.copy(before));if(row.node.type==='group')G.resizeGroup(row.node,width,height);else Object.assign(row.node,{width,height});row.node.x=before.x+(edge.includes('w')?before.width-width:0);row.node.y=before.y+(edge.includes('n')?before.height-height:0);}
      if(drag.kind==='marquee'){drag.box={x:Math.min(p.x,drag.p.x),y:Math.min(p.y,drag.p.y),width:Math.abs(dx),height:Math.abs(dy)};const f=surface(),b=drag.box;scene().selection=[...new Set([...drag.base,...f.nodes.filter(n=>{const r=G.bounds(n);return n.visible&&!n.locked&&r.x+f.x>=b.x&&r.y+f.y>=b.y&&r.x+f.x+r.width<=b.x+b.width&&r.y+f.y+r.height<=b.y+b.height;}).map(n=>n.id)])];}
      drag.moved=true;draw();
    };
    canvas.onpointerup=canvas.onpointercancel=()=>{const d=drag;drag=null;if(!d)return;if(d.moved&&['nodes','resize','frame'].includes(d.kind))change(d.kind==='resize'?'缩放图层':d.kind==='frame'?'移动画框':'移动图层');else{H.save();H.library();renderInspector();draw();}};
    canvas.ondblclick=e=>{const p=world(e),f=surface(),n=G.hit(f.nodes,{x:p.x-f.x,y:p.y-f.y},true);if(n){select(f,n);if(n.type==='text')inlineText(n);else if(n.type==='instance'){select(scene().components[n.componentId],null);fit(true);}}};
    canvas.ondragover=e=>{if([...e.dataTransfer.types].some(t=>t==='Files'||t==='application/x-qingjing-content-source'))e.preventDefault();};
    canvas.ondrop=async e=>{e.preventDefault();e.stopPropagation();$('drop-overlay').hidden=true;const position=world(e),target=G.surfaces(scene()).slice().reverse().find(f=>position.x>=f.x&&position.y>=f.y&&position.x<=f.x+f.width&&position.y<=f.y+f.height);if(target)scene().selectedSurface=target.id;if(e.dataTransfer.files.length){const assets=await H.importFiles([...e.dataTransfer.files]);for(const a of assets)if(a.type==='image')addAsset(a,position);}else{const id=e.dataTransfer.getData('application/x-qingjing-content-source'),asset=H.sources().find(a=>a.id===id);if(asset)addAsset(asset,position);}};
    document.addEventListener('keydown',e=>{
      if(!active()||$('dialog').open||e.target.closest('input,textarea,select,[contenteditable]'))return;const mod=e.metaKey||e.ctrlKey,k=e.key.toLowerCase();let handled=true;
      if(mod&&k==='z')H.undo(e.shiftKey);else if(mod&&k==='y')H.undo(true);
      else if(mod&&k==='c')copySelection();else if(mod&&k==='x')copySelection(true);else if(mod&&k==='v')paste();
      else if(mod&&k==='d'){if(scene().selection.length){G.duplicate(scene());change('复制图层');}else copyFrame();}
      else if(mod&&k==='g')e.shiftKey?ungroup():group();else if(mod&&e.altKey&&k==='k')component();
      else if(k==='delete'||k==='backspace')remove();else if(k==='[')reorder(-1);else if(k===']')reorder(1);
      else if(e.code==='Space'){space=true;canvas.style.cursor='grab';}
      else if(k==='+'||k==='=')zoom(1.2);else if(k==='-')zoom(1/1.2);else if(k==='0'||(e.shiftKey&&e.code==='Digit1'))fit();else if(e.shiftKey&&e.code==='Digit2')fit(true);
      else if(k.startsWith('arrow')){const amount=e.shiftKey?10:1,rows=G.roots(scene()).filter(r=>!r.node.locked);for(const r of rows){if(k==='arrowleft')r.node.x-=amount;if(k==='arrowright')r.node.x+=amount;if(k==='arrowup')r.node.y-=amount;if(k==='arrowdown')r.node.y+=amount;}if(rows.length)change('微调图层位置');}
      else if(k==='f2'){document.querySelector('[data-gallery-param="name"]')?.focus();document.querySelector('[data-gallery-param="name"]')?.select();}
      else if(k==='escape'){scene().selection=[];H.library();renderInspector();draw();}
      else if(!mod&&k==='f')addFrame();else if(!mod&&k==='t')add('text');else if(!mod&&k==='r')add('rect');else if(!mod&&k==='v'){space=false;canvas.style.cursor='default';canvas.focus();}else handled=false;
      if(handled){e.preventDefault();e.stopImmediatePropagation();}
    });
    document.addEventListener('keyup',e=>{if(e.code==='Space'){space=false;canvas.style.cursor='default';}});window.addEventListener('blur',()=>{space=false;drag=null;});
  }
  function reset(){cache.clear();drag=null;space=false;editorInput?.remove();editorInput=null;lastWork=null;}
  window.GalleryEditor={mount,render,draw,fit,addAsset,renderLayers,renderComponents,bindLibrary,reset,refresh:()=>{cache.clear();draw();}};
})();
