/* Interactive prototype. Processing and publishing adapters are intentionally deferred. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const $$ = selector => [...document.querySelectorAll(selector)];
  const clamp = (n, a, b) => Math.min(b, Math.max(a, n));
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const FPS = 30;
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
  const state = {workspace:'wallpaper',assets:demoAssets(),selectedId:'demo-video',filter:'all',product:'dynamic',platform:'harmony',profiles:{},time:1.25,playing:false,loop:true,zoom:100,name:'城市飞行',coverTime:null,social:'xhs',postAssets:[{...demoAssets()[1],id:'post-image'},{...demoAssets()[0],id:'post-video'}],posts:{xhs:newPost('xhs'),douyin:newPost('douyin')},accounts,selectedAccounts:new Set(['x1','d1']),timing:'now',scheduledAt:'',published:[]};
  let toastTimer, lastTimestamp=0, animationFrame, objectUrls=[];
  const selectedAsset = () => state.assets.find(x=>x.id===state.selectedId) || state.assets[0];
  const duration = () => selectedAsset()?.duration || 5;
  function freshProfiles(d=5) {
    const hStart = d>=4?1.25:0, hEnd = Math.min(d,hStart+2.5);
    const iStart = d>=3?1.9:0, iEnd=Math.min(d,iStart+.8);
    const base={width:1080,height:1920,scale:1,x:0,y:0};
    return {harmony:{...base,start:hStart,end:hEnd,speed:(hEnd-hStart)/2,frames:60},ios:{...base,start:iStart,end:iEnd,speed:(iEnd-iStart)/.8,frames:24}};
  }
  const profiles = () => state.profiles[state.selectedId] ||= freshProfiles(duration());
  const profile = () => profiles()[state.platform];
  const outDuration = p => (p.end-p.start)/p.speed;
  const targetDuration = p => state.platform==='harmony'?2:p.frames/FPS;
  const currentPost = () => state.posts[state.social];
  function toast(message) { $('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,3600); }
  function setStatus(message) {$('status-text').textContent=message;}
  function timecode(seconds) { const total=Math.round(seconds*FPS),f=total%FPS,s=Math.floor(total/FPS)%60,m=Math.floor(total/(FPS*60));return `00:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}:${String(f).padStart(2,'0')}`; }
  function setRangeFill(el) {el.style.setProperty('--fill',`${(Number(el.value)-Number(el.min))/(Number(el.max)-Number(el.min))*100}%`);}
  function saveLocal(key,value) {try{localStorage.setItem(key,JSON.stringify(value));return true;}catch{toast('浏览器未允许保存草稿，可通过导出保留编辑方案');return false;}}
  function loadLocal(key) {try{return JSON.parse(localStorage.getItem(key)||'null');}catch{return null;}}

  function sceneHTML(asset, {preview=false,frame=0}={}) {
    if (!asset) return '<div class="empty-assets">请先添加素材</div>';
    if(asset.demo) return `<div class="media-transform demo-scene" data-demo-frame="${frame}">${demoLayers.map((name,i)=>`<img draggable="false" class="scene-layer ${name==='light.png'?'light':''}" src="assets/${name}" alt="${preview&&i===0?'城市飞行示例壁纸':''}" data-layer="${i}" style="transform:scale(1.18)">`).join('')}</div>`;
    if(asset.type==='4d') return `<div class="package-thumbnail">${icon('layers')}</div>`;
    if(asset.type==='video') return `<div class="media-transform"><video class="uploaded-video" ${preview?'':'preload="metadata"'} src="${esc(asset.url)}" muted playsinline ${preview?'preload="auto"':''}></video></div>`;
    return `<div class="media-transform"><img class="uploaded-image" draggable="false" src="${esc(asset.url)}" alt="${preview?esc(asset.name):''}"></div>`;
  }
  function assetMetadata(asset) {return asset.type==='video'?`${asset.duration.toFixed(2)} s · ${asset.width} × ${asset.height}`:asset.type==='4d'?(asset.demo?'5 个图层 · 内置示例':'ZIP · 已导入资源包'):`${asset.width} × ${asset.height} · 图片`;}
  function assetRow(asset, selected, publish=false) {
    return `<button class="asset-row${selected?' selected':''}" data-asset-id="${esc(asset.id)}" ${publish?'data-post-asset="true"':''} aria-pressed="${selected}"><span class="asset-thumb"><span class="media-content">${sceneHTML(asset)}</span><span class="type-overlay">${icon(asset.type==='4d'?'layers':asset.type)}</span></span><span class="asset-copy"><strong>${esc(asset.name)}</strong><span>${assetMetadata(asset)}</span>${selected?`<small>${publish?'已加入当前推文':'正在编辑'}</small>`:''}</span></button>`;
  }
  function renderAssets() {
    const filtered=state.assets.filter(a=>state.filter==='all'||a.type===state.filter);
    $('asset-list').innerHTML=filtered.map(a=>assetRow(a,a.id===state.selectedId)).join('')||'<div class="empty-assets">暂无这类素材<br>点击下方导入</div>';
    $('asset-count').textContent=state.assets.length;
    $$('#asset-filters button').forEach(b=>{b.classList.toggle('active',b.dataset.filter===state.filter);b.setAttribute('aria-pressed',b.dataset.filter===state.filter);});
    $$('#asset-list .asset-row').forEach(b=>b.onclick=()=>selectAsset(b.dataset.assetId));
  }
  function selectAsset(id) {
    pause();state.selectedId=id;const asset=selectedAsset();state.product=asset.type==='image'?'static':asset.type==='4d'?'4d':'dynamic';state.time=profile().start;state.coverTime=null;renderWallpaper();setStatus(`${asset.name}${asset.demo?' · 示例素材':' · 本地文件'}`);
  }
  function renderWallpaperMedia() {
    const asset=selectedAsset();$('wallpaper-media').innerHTML=sceneHTML(asset,{preview:true});
    const video=$('wallpaper-media').querySelector('video');
    if(video){video.onloadedmetadata=()=>{if(video.isConnected)video.currentTime=clamp(state.time,0,video.duration);};video.onseeked=()=>{if(video.isConnected&&(!state.playing||!nativePlayback())){const target=clamp(state.time,0,video.duration);if(Math.abs(video.currentTime-target)>.04)video.currentTime=target;}};video.onerror=()=>{if(video.isConnected)toast('浏览器无法预览这个视频，请换用 H.264 编码的 MP4 试试');};}
    $('four-d-card').hidden=state.product!=='4d';$('device-unit').hidden=state.product==='4d';$('stage-note').hidden=state.product==='4d';$('four-d-name').textContent=asset?.name||'请导入资源包';
    syncCrop();syncPosition();
  }
  function renderWallpaper() {
    renderAssets();renderWallpaperMedia();renderFilmstrip();syncInspector();syncPlatformButtons();syncTimeline();
    const asset=selectedAsset();$('source-badge').textContent=asset.type==='video'?`${asset.duration.toFixed(2)} s · ${asset.demo?'示例动画':'原始视频'}`:asset.type==='image'?'原始图片':'独立 4D 资源';
    $('motion-settings').hidden=state.product!=='dynamic';$('static-settings').hidden=state.product!=='static';$('package-settings').hidden=state.product!=='4d';$('crop-settings').hidden=state.product==='4d';
    $('timeline-empty').hidden=asset.type==='video'&&state.product!=='4d';
    const canPlay=asset.type==='video'&&state.product!=='4d';['play-btn','previous-frame','next-frame','set-in','set-out','reset-trim'].forEach(id=>$(id).disabled=!canPlay);
    ['.device-toolbar','.device-meta','.view-options','.player-bar'].forEach(selector=>document.querySelector(selector).hidden=state.product==='4d');
    $('size-preset').closest('.field').hidden=state.product==='4d';if(state.product==='4d')$('custom-size').hidden=true;
    $$('#product-tabs button').forEach(b=>{b.classList.toggle('active',b.dataset.product===state.product);b.setAttribute('aria-pressed',b.dataset.product===state.product);});
    $('preview-time-hint').textContent=state.product==='static'?'选当前帧作为封面':'选段循环预览';
    $('capture-cover').hidden=asset.type!=='video';$('cover-time').hidden=asset.type!=='video';
  }
  function syncPlatformButtons() {
    $$('[data-platform]').forEach(b=>{b.classList.toggle('active',b.dataset.platform===state.platform);b.setAttribute('aria-pressed',b.dataset.platform===state.platform);});
    $('inspector-platform').textContent=state.product==='4d'?'4D 资源':`${platformNames[state.platform]}版本`;$('timeline-version').textContent=`正在编辑：${platformNames[state.platform]}`;$('target-caption').textContent=state.product==='static'?'静态图片 · PNG':state.platform==='harmony'?'Moving Photo · 2 秒':'Live Photo · 16–30 帧';$('preview-version').textContent=`${platformNames[state.platform]}版本独立编辑`;
    $('version-note').textContent=state.product==='4d'?'4D 资源保留原始分层与配置':state.product==='static'?'苹果与鸿蒙分别保存尺寸与构图':'苹果与鸿蒙分别保存剪辑参数';
  }
  function syncInspector() {
    const p=profile(),o=outDuration(p),frames=Math.round(o*FPS);
    $('project-name').value=state.name;
    $('in-point').value=p.start.toFixed(2);$('out-point').value=p.end.toFixed(2);$('in-point').max=(p.end-1/FPS).toFixed(3);$('out-point').max=duration().toFixed(3);
    $('speed').value=clamp(p.speed,.1,4);$('speed-label').textContent=`${p.speed.toFixed(2).replace(/0$/,'')}×`;
    $$('.speed-presets button').forEach(b=>b.classList.toggle('active',Math.abs(Number(b.dataset.speed)-p.speed)<.001));
    $('ios-frames-field').hidden=state.platform!=='ios';$('ios-frames').value=p.frames;$('ios-frames-value').textContent=`${p.frames} 帧`;
    $('output-duration').innerHTML=`${o.toFixed(2)} <small>s</small>`;$('output-frames').innerHTML=`${frames} <small>帧</small>`;
    $('fit-target').querySelector('span').textContent=state.platform==='harmony'?'变速适配到 2 秒':`变速适配到 ${p.frames} 帧`;
    const valid=state.platform==='harmony'?Math.abs(o-2)<.015:frames>=16&&frames<=30;
    const note=valid?(state.platform==='harmony'?'符合当前鸿蒙 2 秒预设':`当前 ${frames} 帧，符合 16–30 帧预设`):(state.platform==='harmony'?'当前时长超出 2 秒预设，可点击上方适配':'当前帧数超出 16–30 帧，可点击上方适配');
    $('target-validation').classList.toggle('valid',valid);$('target-validation').querySelector('span').textContent=note;
    const preset=`${p.width}x${p.height}`,known=[...$('size-preset').options].some(x=>x.value===preset);$('size-preset').value=known?preset:'custom';$('custom-size').hidden=known;$('custom-width').value=p.width;$('custom-height').value=p.height;
    $('crop-scale').value=Math.round(p.scale*100);$('crop-scale-label').textContent=`${Math.round(p.scale*100)}%`;$('crop-x').value=Math.round(p.x);$('crop-y').value=Math.round(p.y);
    $$('input[type=range]').forEach(setRangeFill);syncCrop();
    const video=$('wallpaper-media').querySelector('video');if(video&&nativePlayback())video.playbackRate=p.speed;
  }
  function syncCrop() {
    const p=profile();$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--scale',p.scale);$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--pan-x',`${p.x}%`);$('wallpaper-media').querySelector('.media-transform')?.style.setProperty('--pan-y',`${p.y}%`);
    $('device-unit').querySelector('.phone-body').style.aspectRatio=`${p.width}/${p.height}`;
    $('resolution-label').textContent=`${p.width} × ${p.height} px`;$('preview-dimension').textContent=Math.abs(p.width/p.height-9/16)<.001?'9 : 16':`${(p.height/p.width).toFixed(2)} : 1 纵向`;
  }
  function renderFilmstrip() {
    const asset=selectedAsset();$('filmstrip').innerHTML=Array.from({length:18},(_,i)=>`<div class="film-frame"><div class="media-content">${sceneHTML(asset,{frame:i/18*duration()})}</div></div>`).join('');
    if(asset.demo)$('filmstrip').querySelectorAll('.media-content').forEach((frame,i)=>animateDemo(frame,i/18*duration()));
    $('filmstrip').querySelectorAll('video').forEach((v,i)=>{v.preload='metadata';v.onloadedmetadata=()=>{v.currentTime=i/18*duration();};});
    $('clip-name').innerHTML=`${esc(asset.name)} <span>${duration().toFixed(2)} s${asset.demo?' · 示例':''}</span>`;renderRuler();
  }
  function renderRuler() {
    const d=duration(),step=d<=10?.5:d<=60?5:Math.ceil(d/12),ticks=[];
    for(let t=0;t<d;t+=step){const whole=t%1===0;ticks.push(`<span class="ruler-tick ${whole?'':'minor'}" style="left:${t/d*100}%">${whole?`${String(Math.floor(t/60)).padStart(2,'0')}:${String(Math.floor(t%60)).padStart(2,'0')}`:`${Math.round((t%1)*FPS)}f`}</span>`);}
    $('ruler').innerHTML=ticks.join('');
  }
  function syncTimeline() {
    const p=profile(),d=duration();$('trim-selection').style.left=`${p.start/d*100}%`;$('trim-selection').style.width=`${(p.end-p.start)/d*100}%`;$('selection-duration').textContent=`选中 ${(p.end-p.start).toFixed(2)} s`;
    for(const key of ['harmony','ios']) {const v=profiles()[key],button=$(key+'-range');button.style.left=`${v.start/d*100}%`;button.style.width=`${(v.end-v.start)/d*100}%`;button.textContent=key==='harmony'?`鸿蒙 · ${outDuration(v).toFixed(2)} s`:`iOS · ${Math.round(outDuration(v)*FPS)} 帧`;}
    $('trim-summary').textContent=`源片段 ${(p.end-p.start).toFixed(2)} s ÷ ${p.speed.toFixed(2)}× = 输出 ${outDuration(p).toFixed(2)} s`;
    syncPosition();
  }
  function syncPosition() {
    $('playhead').style.left=`${clamp(state.time/duration()*100,0,100)}%`;$('playhead').setAttribute('aria-valuenow',state.time.toFixed(3));$('playhead').setAttribute('aria-valuemax',duration());$('current-time').textContent=timecode(state.time);
    if(selectedAsset()?.demo) animateDemo($('wallpaper-media'),state.time);
    const v=$('wallpaper-media').querySelector('video');if(v&&(!state.playing||!nativePlayback())&&v.readyState>=1&&Math.abs(v.currentTime-state.time)>.04&&!v.seeking){try{v.currentTime=state.time;}catch{}}
  }
  function animateDemo(container,t) {
    container.querySelectorAll('[data-layer]').forEach(img=>{const layer=Number(img.dataset.layer),wave=Math.sin(t/5*Math.PI*2);const x=wave*(layer===2?4:layer===4?7:layer===0?-2:1),y=Math.cos(t/5*Math.PI*2)*(layer===2?2:1);img.style.transform=`translate(${x}%,${y}%) scale(1.18)`;});
  }
  function nativePlayback(){return !!$('wallpaper-media').querySelector('video')&&profile().speed>=.0625&&profile().speed<=16;}
  function pause(){state.playing=false;$('wallpaper-media').querySelector('video')?.pause();$('play-btn').innerHTML=icon('play');$('play-btn').setAttribute('aria-label','播放选中片段');cancelAnimationFrame(animationFrame);syncPosition();}
  function togglePlay() {
    if(state.playing){pause();return;}if(selectedAsset()?.type!=='video'||state.product==='4d')return;
    const p=profile();if(state.time<p.start||state.time>=p.end)state.time=p.start;
    const video=$('wallpaper-media').querySelector('video');if(video&&nativePlayback()){video.currentTime=state.time;video.playbackRate=p.speed;video.play().catch(()=>{if(state.playing){pause();toast('视频暂时无法播放，请等待素材载入后重试');}});}
    state.playing=true;lastTimestamp=0;$('play-btn').innerHTML=icon('pause');$('play-btn').setAttribute('aria-label','暂停播放');animationFrame=requestAnimationFrame(tick);
  }
  function tick(timestamp) {
    if(!state.playing)return;if(!lastTimestamp)lastTimestamp=timestamp;const p=profile(),dt=Math.min((timestamp-lastTimestamp)/1000,.12);lastTimestamp=timestamp;
    const video=$('wallpaper-media').querySelector('video');if(video&&nativePlayback())state.time=video.currentTime;else state.time+=dt*p.speed;
    if(state.time>=p.end-.001){if(state.loop){state.time=p.start+(state.time-p.end)%(p.end-p.start);if(video&&nativePlayback()){video.currentTime=Math.max(p.start,state.time);if(video.paused)video.play().catch(()=>pause());}}else{state.time=p.end;pause();}}
    syncPosition();if(state.playing)animationFrame=requestAnimationFrame(tick);
  }
  function seek(t){pause();state.time=clamp(t,0,duration());syncPosition();}
  function updateTrim(start,end) {const p=profile();p.start=clamp(start,0,duration()-1/FPS);p.end=clamp(end,p.start+1/FPS,duration());state.time=clamp(state.time,p.start,p.end);syncInspector();syncTimeline();}
  function changePlatform(key) {pause();state.platform=key;state.time=profile().start;syncPlatformButtons();syncInspector();syncTimeline();}
  function fitTarget() {pause();const p=profile();p.speed=(p.end-p.start)/targetDuration(p);syncInspector();syncTimeline();toast(state.platform==='harmony'?'已调整速度，输出 2 秒':`已调整速度，输出 ${p.frames} 帧（30 帧/秒预设）`);}
  function pointerTime(event) {const rect=$('timeline-content').getBoundingClientRect();return clamp((event.clientX-rect.left)/rect.width*duration(),0,duration());}
  function dragTimeline(element,type) {
    element.addEventListener('pointerdown',event=>{
      if(selectedAsset()?.type!=='video'||state.product==='4d')return;event.preventDefault();event.stopPropagation();pause();element.setPointerCapture(event.pointerId);
      const initial={x:event.clientX,start:profile().start,end:profile().end},width=$('timeline-content').getBoundingClientRect().width;
      if(type==='seek')seek(pointerTime(event));
      const move=e=>{const p=profile();if(type==='seek'){state.time=pointerTime(e);syncPosition();return;}const t=pointerTime(e);if(type==='start'){updateTrim(Math.min(t,p.end-1/FPS),p.end);state.time=profile().start;}else if(type==='end'){updateTrim(p.start,Math.max(t,p.start+1/FPS));state.time=profile().end;}else{const length=initial.end-initial.start,delta=(e.clientX-initial.x)/width*duration(),newStart=clamp(initial.start+delta,0,duration()-length);updateTrim(newStart,newStart+length);state.time=profile().start;}syncPosition();};
      const finish=()=>{element.removeEventListener('pointermove',move);element.removeEventListener('pointerup',finish);element.removeEventListener('pointercancel',finish);};
      element.addEventListener('pointermove',move);element.addEventListener('pointerup',finish);element.addEventListener('pointercancel',finish);
    });
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
    const ext=file.name.split('.').pop().toLowerCase();let type=file.type.startsWith('image/')?'image':file.type.startsWith('video/')?'video':ext==='zip'?'4d':null;
    if(!['png','jpg','jpeg','webp','mp4','mov','webm','zip'].includes(ext)||!type)throw new Error(`暂不支持「${file.name}」，请导入图片、MP4/MOV/WebM 视频或 ZIP`);
    const asset={id:`local-${Date.now()}-${Math.random().toString(36).slice(2,8)}`,name:file.name.replace(/\.[^.]+$/,''),type,file,url:URL.createObjectURL(file),demo:false};objectUrls.push(asset.url);
    if(type==='4d')return asset;
    await new Promise((resolve,reject)=>{const element=type==='image'?new Image():document.createElement('video');const timer=setTimeout(()=>{element.src='';reject(new Error(`读取「${file.name}」超时，请重试或更换格式`));},12000);const fail=()=>{clearTimeout(timer);reject(new Error(`无法读取「${file.name}」，请检查文件格式或视频编码`));};element.onerror=fail;if(type==='image'){element.onload=()=>{clearTimeout(timer);asset.width=element.naturalWidth;asset.height=element.naturalHeight;resolve();};}else{element.preload='metadata';element.onloadedmetadata=()=>{clearTimeout(timer);if(!Number.isFinite(element.duration)||element.duration<1/FPS){reject(new Error('视频时长太短或无法读取'));return;}asset.width=element.videoWidth;asset.height=element.videoHeight;asset.duration=element.duration;resolve();};}element.src=asset.url;});
    return asset;
  }
  async function importFiles(files,workspace) {
    const incoming=[];for(const file of files){try{const asset=await readAsset(file);if(workspace==='publish'&&asset.type==='4d'){URL.revokeObjectURL(asset.url);toast('4D 资源包请导入壁纸加工区；推广区使用图片或视频');continue;}incoming.push(asset);}catch(error){toast(error.message);}}
    if(!incoming.length)return;
    if(workspace==='wallpaper'){state.assets.push(...incoming);state.filter='all';selectAsset(incoming[0].id);}else{state.postAssets.push(...incoming);const post=currentPost();post.type=incoming[0].type;post.assetIds=post.type==='video'?[incoming[0].id]:incoming.filter(a=>a.type==='image').map(a=>a.id);renderPublish();}
    toast(`已导入 ${incoming.length} 个素材，仅在当前浏览器中预览`);
  }
  function modal(title,body,actions=[]) {
    pause();$('dialog-title').textContent=title;$('dialog-body').innerHTML=body;$('dialog-actions').replaceChildren();
    for(const action of actions){const b=document.createElement('button');b.textContent=action.label;if(action.primary)b.className='primary';b.onclick=action.run;$('dialog-actions').append(b);}if(!$('dialog').open)$('dialog').showModal();
  }
  function closeModal(){$('dialog').close();}
  function download(name,data,type='application/json') {const url=URL.createObjectURL(data instanceof Blob?data:new Blob([data],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);}
  function safeName(){return (state.name||'倾境壁纸').replace(/[\\/:*?"<>|]/g,'_').slice(0,60);}
  function wallpaperPlan(){return {prototype:true,name:state.name,source:{name:selectedAsset().name,type:selectedAsset().type,duration:selectedAsset().duration||null},product:state.product,previewFps:FPS,activePlatform:state.platform,coverSourceTime:state.coverTime,versions:profiles(),note:'交互原型编辑方案；不是苹果或鸿蒙成品资源包。视频处理与平台打包尚未接入。'};}
  async function exportStill() {
    const p=profile(),asset=selectedAsset(),canvas=document.createElement('canvas');canvas.width=p.width;canvas.height=p.height;const ctx=canvas.getContext('2d');ctx.fillStyle='#15171b';ctx.fillRect(0,0,p.width,p.height);
    const sources=asset.demo?[...$('wallpaper-media').querySelectorAll('img')]:[$('wallpaper-media').querySelector('img,video')].filter(Boolean);
    try {
      if(asset.type==='video'&&state.product==='static'&&state.coverTime!==null)seek(state.coverTime);
      const video=sources.find(el=>el.tagName==='VIDEO');
      if(video)await new Promise((resolve,reject)=>{
        const check=()=>{if(video.readyState>=2&&!video.seeking&&Math.abs(video.currentTime-state.time)<.04){cleanup();resolve();}};
        const cleanup=()=>{clearTimeout(timer);['seeked','loadeddata','loadedmetadata'].forEach(event=>video.removeEventListener(event,check));};
        const timer=setTimeout(()=>{cleanup();reject(new Error('请等待视频当前帧载入后重试'));},8000);
        ['seeked','loadeddata','loadedmetadata'].forEach(event=>video.addEventListener(event,check));check();
      });
      for(const media of sources){if(media.tagName==='IMG')await media.decode();const w=media.naturalWidth||media.videoWidth,h=media.naturalHeight||media.videoHeight;if(!w||!h)throw new Error('素材尚未载入');const ratio=Math.max(p.width/w,p.height/h)*p.scale*(asset.demo?1.18:1),dw=w*ratio,dh=h*ratio;ctx.save();if(media.classList.contains('light')){ctx.globalAlpha=.25;ctx.globalCompositeOperation='screen';}let dx=0,dy=0;if(asset.demo){const layer=Number(media.dataset.layer),wave=Math.sin(state.time/5*Math.PI*2);dx=wave*(layer===2?4:layer===4?7:layer===0?-2:1)/100*p.width*p.scale;dy=Math.cos(state.time/5*Math.PI*2)*(layer===2?2:1)/100*p.height*p.scale;}ctx.drawImage(media,(p.width-dw)/2+p.x/100*p.width+dx,(p.height-dh)/2+p.y/100*p.height+dy,dw,dh);ctx.restore();}
      const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));if(!blob)throw new Error('图片导出失败');download(`${safeName()}-${p.width}x${p.height}-预览.png`,blob);toast('已导出当前尺寸的 PNG 预览图');
    }catch(error){toast(`暂时无法导出预览：${error.message}`);}
  }
  function openExport() {
    if(state.workspace==='publish'){openContentExport();return;}
    const a=selectedAsset(),versions=profiles();
    if(state.product==='4d'){
      modal('导出 4D 资源',`<p class="dialog-intro">${esc(a.name)}</p><div class="export-row">${icon('layers')}<div><strong>${a.demo?'内置五层示例':'保留原始 ZIP'}</strong><small>${a.demo?'可前往原 4D 工作台制作与导出':'导出你上传的资源包，不改变包内内容'}</small></div></div><p class="dialog-note">4D 壁纸独立制作，这里提供资源整理入口。</p>`,[{label:'返回编辑',run:closeModal},{label:a.demo?'打开 4D 工作台':'下载原始资源包',primary:true,run:()=>{if(a.demo)window.open('../parallax-studio/','_blank','noopener');else download(a.file.name,a.file);}}]);return;
    }
    const rows=state.product==='dynamic'?['harmony','ios'].map(key=>{const p=versions[key],o=outDuration(p);return `<div class="export-row">${icon('phone')}<div><strong>${platformNames[key]}动态版本</strong><small>${p.width} × ${p.height} · 源 ${p.start.toFixed(2)}–${p.end.toFixed(2)} s · ${p.speed.toFixed(2)}×</small></div><span>${o.toFixed(2)} s / ${Math.round(o*FPS)} 帧</span></div>`;}).join(''):'';
    modal('导出产品 · 原型预览',`<p class="dialog-intro">${esc(state.name)} · 先确认尺寸、片段与输出规格</p><div class="export-row">${icon('image')}<div><strong>静态预览图</strong><small>当前画面 · ${profile().width} × ${profile().height} px · PNG</small></div><span>可下载</span></div>${rows}<p class="dialog-note">本次原型可以下载静态预览图和编辑方案。正式视频、苹果／鸿蒙动态图片打包将在需求确认后接入；这里不会生成成品动态资源包。</p>`,[{label:'返回编辑',run:closeModal},{label:'下载编辑方案',run:()=>{download(`${safeName()}-原型编辑方案.json`,JSON.stringify(wallpaperPlan(),null,2));toast('已下载编辑方案，包含两个平台的独立参数');}},{label:'下载 PNG 预览',primary:true,run:exportStill}]);
  }
  function postBundle(){return {prototype:true,posts:state.posts,accounts:state.accounts.filter(a=>state.selectedAccounts.has(a.id)),timing:state.timing,scheduledAt:state.scheduledAt,assets:state.postAssets.map(a=>({id:a.id,name:a.name,type:a.type})),note:'这是内容方案与模拟账号清单，不是已发布结果。'};}
  function downloadCopy(){const post=currentPost();download(`${socialNames[state.social]}-文案.txt`,`${post.title}\n\n${post.body}\n\n${post.tags}`,'text/plain;charset=utf-8');toast('已导出当前平台文案');}
  function openContentExport(){modal('导出推广内容',`<p class="dialog-intro">小红书与抖音的文案分别保留，可在你选择的发布工具中继续使用。</p><div class="export-row">${icon('text')}<div><strong>当前平台文案</strong><small>${socialNames[state.social]} · 标题、正文和话题</small></div><span>TXT</span></div><div class="export-row">${icon('users')}<div><strong>完整内容方案</strong><small>两平台文案、素材清单、选定账号与发布时间</small></div><span>JSON</span></div><p class="dialog-note">示例账号尚未连接。原型不向社交平台发送内容。</p>`,[{label:'返回编辑',run:closeModal},{label:'导出内容方案',run:()=>download('倾境-推广内容方案.json',JSON.stringify(postBundle(),null,2))},{label:'导出文案',primary:true,run:downloadCopy}]);}
  function saveDraft(){
    if(state.workspace==='wallpaper'){const saved=saveLocal('qingjing-creator-wallpaper-v1',wallpaperPlan());if(saved)toast('已保存编辑参数；重新打开时，本地文件需要再次导入');}
    else {if(saveLocal('qingjing-creator-posts-v1',postBundle())){$('draft-status').textContent='刚刚保存 · 本地草稿';toast('文案与账号选择已保存到当前浏览器');}}
  }
  function restorePostDraft(){const saved=loadLocal('qingjing-creator-posts-v1');if(!saved?.posts?.xhs||!saved?.posts?.douyin){toast('当前浏览器还没有保存的推广草稿');return;}state.posts=saved.posts;let missing=false;for(const post of Object.values(state.posts)){const ids=post.assetIds.filter(id=>state.postAssets.some(a=>a.id===id));if(ids.length!==post.assetIds.length)missing=true;post.assetIds=ids;}state.selectedAccounts=new Set((saved.accounts||[]).map(a=>a.id).filter(id=>state.accounts.some(a=>a.id===id)));renderPublish();toast(missing?'已恢复文案；请重新导入上次的本地素材':'已恢复上次保存的推广草稿');}
  function openPublishPreview() {
    const selected=state.accounts.filter(a=>state.selectedAccounts.has(a.id));if(!selected.length){toast('请至少选择一个发布账号');return;}
    const missing=selected.find(a=>!state.posts[a.platform].title.trim()||!state.posts[a.platform].assetIds.length);if(missing){toast(`请先完善${socialNames[missing.platform]}的标题与素材`);return;}
    if(state.timing==='scheduled'&&(!state.scheduledAt||new Date(state.scheduledAt).getTime()<=Date.now())){toast('请选择未来的发布时间');$('publish-date').focus();return;}
    const rows=selected.map(a=>`<div class="publish-result-row"><span class="account-avatar ${a.category}">${categories[a.category][0]}</span><div><strong>${esc(a.name)}</strong><small>${socialNames[a.platform]} · ${state.posts[a.platform].type==='video'?'视频':'图文'} · ${esc(state.posts[a.platform].title)}</small></div><span class="count">模拟任务</span></div>`).join('');
    modal('发布清单 · 演示',`<p class="dialog-intro">共 ${selected.length} 个账号 · ${state.timing==='now'?'立即发布':esc(state.scheduledAt.replace('T',' '))}</p>${rows}<p class="dialog-note">账号与发布结果均为原型演示。确认后只生成本地演示记录，不会发布到小红书或抖音。</p>`,[{label:'返回修改',run:closeModal},{label:'确认演示',primary:true,run:()=>{state.published.push({at:new Date().toISOString(),accounts:selected.map(a=>a.name)});modal('发布流程演示完成',`<p class="dialog-intro">已模拟创建 ${selected.length} 条发布任务。</p>${selected.map(a=>`<div class="publish-result-row">${icon('check')}<div><strong>${esc(a.name)}</strong><small>${socialNames[a.platform]} · 演示记录，未向平台发送</small></div></div>`).join('')}<p class="dialog-note">正式版会在这里显示真实的发布状态、作品链接和失败重试入口。</p>`,[{label:'完成',primary:true,run:closeModal}]);}}]);
  }
  function manageAccounts(){
    modal('账号管理 · 原型',`<p class="dialog-intro">先确认账号数量与垂直分类。当前都是演示账号。</p><div id="account-manage-list">${state.accounts.map(a=>`<div class="account-manage-row"><span class="platform-name ${a.platform}">${socialNames[a.platform]}</span><span>${esc(a.name)}</span><select aria-label="${esc(a.name)}的分类" data-category-account="${a.id}">${Object.entries(categories).map(([key,label])=>`<option value="${key}"${key===a.category?' selected':''}>${label}</option>`).join('')}</select></div>`).join('')}</div><form id="add-account-form" class="add-account-form"><input id="new-account-name" aria-label="新账号名称" maxlength="24" placeholder="新账号名称" required><select id="new-account-platform" aria-label="新账号平台"><option value="xhs">小红书</option><option value="douyin">抖音</option></select><button type="submit">添加</button></form><p class="dialog-note">正式账号登录与授权在选定分发工具后接入。</p>`,[{label:'完成',primary:true,run:()=>{renderAccounts();syncPostPreview();closeModal();}}]);
    $$('[data-category-account]').forEach(select=>select.onchange=()=>{state.accounts.find(a=>a.id===select.dataset.categoryAccount).category=select.value;});
    $('add-account-form').onsubmit=event=>{event.preventDefault();const name=$('new-account-name').value.trim();if(!name)return;state.accounts.push({id:`account-${Date.now()}`,name,platform:$('new-account-platform').value,category:'simple'});manageAccounts();renderAccounts();toast('已添加演示账号');};
  }
  function bindEvents(){
    $$('[data-workspace]').forEach(b=>b.onclick=()=>switchWorkspace(b.dataset.workspace));$$('[data-platform]').forEach(b=>b.onclick=()=>changePlatform(b.dataset.platform));$$('[data-import]').forEach(b=>b.onclick=()=>$(b.dataset.import+'-input').click());
    $('wallpaper-input').onchange=event=>{importFiles(event.target.files,'wallpaper');event.target.value='';};$('publish-input').onchange=event=>{importFiles(event.target.files,'publish');event.target.value='';};
    $$('#asset-filters button').forEach(b=>b.onclick=()=>{state.filter=b.dataset.filter;renderAssets();});
    $$('#product-tabs button').forEach(b=>b.onclick=()=>{const kind=b.dataset.product;if(kind==='dynamic'&&selectedAsset().type!=='video'){const video=state.assets.find(a=>a.type==='video');if(video)state.selectedId=video.id;}else if(kind==='4d'){const zip=state.assets.find(a=>a.type==='4d');if(zip)state.selectedId=zip.id;}else if(kind==='static'&&selectedAsset().type==='4d'){state.selectedId=state.assets.find(a=>a.type==='image')?.id||state.assets.find(a=>a.type==='video')?.id;}pause();state.product=kind;renderWallpaper();});
    $('project-name').oninput=e=>{state.name=e.target.value;document.querySelector('.project-info strong').textContent=state.name||'未命名产品';};
    $('size-preset').onchange=e=>{if(e.target.value==='custom'){$('custom-size').hidden=false;return;}const [w,h]=e.target.value.split('x').map(Number);Object.assign(profile(),{width:w,height:h});syncInspector();};
    ['custom-width','custom-height'].forEach(id=>$(id).onchange=()=>{const w=Number($('custom-width').value),h=Number($('custom-height').value);if(!Number.isInteger(w)||!Number.isInteger(h)||w<64||w>8192||h<64||h>8192||w*h>33554432){toast('请输入 64–8192 像素的尺寸，总像素不超过 3200 万');syncInspector();return;}Object.assign(profile(),{width:w,height:h});syncInspector();});
    $('in-point').onchange=e=>{if(!Number.isFinite(e.target.valueAsNumber)){syncInspector();return;}pause();updateTrim(Math.min(e.target.valueAsNumber,profile().end-1/FPS),profile().end);};$('out-point').onchange=e=>{if(!Number.isFinite(e.target.valueAsNumber)){syncInspector();return;}pause();updateTrim(profile().start,e.target.valueAsNumber);};
    $('speed').oninput=e=>{pause();profile().speed=Number(e.target.value);syncInspector();syncTimeline();};$$('[data-speed]').forEach(b=>b.onclick=()=>{pause();profile().speed=Number(b.dataset.speed);syncInspector();syncTimeline();});
    $('ios-frames').oninput=e=>{pause();profile().frames=Number(e.target.value);profile().speed=(profile().end-profile().start)/(profile().frames/FPS);syncInspector();syncTimeline();};$('fit-target').onclick=fitTarget;
    $('crop-scale').oninput=e=>{profile().scale=Number(e.target.value)/100;syncInspector();};['crop-x','crop-y'].forEach(id=>$(id).onchange=e=>{const n=e.target.valueAsNumber;if(!Number.isFinite(n)){syncInspector();return;}profile()[id==='crop-x'?'x':'y']=clamp(n,-50,50);syncInspector();});
    $('reset-crop').onclick=()=>{Object.assign(profile(),{scale:1,x:0,y:0});syncInspector();};$('phone-toggle').onchange=e=>document.querySelector('.phone-body').classList.toggle('no-frame',!e.target.checked);$('lock-toggle').onchange=e=>$('lock-overlay').hidden=!e.target.checked;$('safe-toggle').onchange=e=>$('safe-overlay').hidden=!e.target.checked;
    $('play-btn').onclick=togglePlay;$('previous-frame').onclick=()=>seek(state.time-1/FPS);$('next-frame').onclick=()=>seek(state.time+1/FPS);$('loop-toggle').onchange=e=>state.loop=e.target.checked;
    $('set-in').onclick=()=>updateTrim(Math.min(state.time,profile().end-1/FPS),profile().end);$('set-out').onclick=()=>updateTrim(profile().start,Math.max(state.time,profile().start+1/FPS));$('reset-trim').onclick=()=>{pause();const crop={width:profile().width,height:profile().height,scale:profile().scale,x:profile().x,y:profile().y};profiles()[state.platform]={...freshProfiles(duration())[state.platform],...crop};state.time=profile().start;syncInspector();syncTimeline();toast(`已重置${platformNames[state.platform]}剪辑`);};
    $('timeline-zoom').oninput=e=>{state.zoom=Number(e.target.value);$('timeline-content').style.width=state.zoom+'%';$('timeline-zoom-value').textContent=state.zoom+'%';setRangeFill(e.target);};
    dragTimeline($('ruler'),'seek');dragTimeline($('source-track'),'seek');dragTimeline($('playhead'),'seek');dragTimeline($('trim-start-handle'),'start');dragTimeline($('trim-end-handle'),'end');dragTimeline($('trim-selection'),'move');
    $('phone-screen').onpointerdown=event=>{if(state.product==='4d')return;event.preventDefault();const el=$('phone-screen'),rect=el.getBoundingClientRect(),p=profile(),startX=event.clientX,startY=event.clientY,x=p.x,y=p.y;el.setPointerCapture(event.pointerId);const move=e=>{p.x=clamp(x+(e.clientX-startX)/rect.width*100,-50,50);p.y=clamp(y+(e.clientY-startY)/rect.height*100,-50,50);syncInspector();};const finish=()=>{el.removeEventListener('pointermove',move);el.removeEventListener('pointerup',finish);el.removeEventListener('pointercancel',finish);};el.addEventListener('pointermove',move);el.addEventListener('pointerup',finish);el.addEventListener('pointercancel',finish);};
    $('capture-cover').onclick=()=>{state.coverTime=state.time;$('cover-time').textContent=`已选封面：${timecode(state.coverTime)}`;toast('当前画面已标记为静态封面');};
    $('restore-demo').onclick=()=>{pause();state.assets=demoAssets();state.selectedId='demo-video';state.filter='all';state.name='城市飞行';state.profiles={};state.time=1.25;state.product='dynamic';renderWallpaper();toast('已恢复城市飞行示例；已导入的文件仍保留在你的电脑中');};
    $$('#social-tabs button').forEach(b=>b.onclick=()=>{state.social=b.dataset.social;renderPublish();});$$('#post-type-tabs button').forEach(b=>b.onclick=()=>{const post=currentPost();post.type=b.dataset.postType;post.assetIds=state.postAssets.filter(a=>a.type===post.type).slice(0,1).map(a=>a.id);renderPublish();});
    for(const [id,key] of [['post-title','title'],['post-body','body'],['post-tags','tags']])$(id).oninput=e=>{currentPost()[key]=e.target.value;syncPostPreview();};
    $('copy-style').onchange=e=>currentPost().style=e.target.value;$('suggest-copy').onclick=()=>{const post=currentPost(),copy=copyPresets[post.style];Object.assign(post,copy);if(state.social==='douyin'&&post.style==='simple')post.title='这一秒，让屏幕动起来';renderPublish();toast('已填入模板示例，可继续修改；此原型未调用 AI');};$('cover-title-toggle').onchange=e=>{currentPost().coverTitle=e.target.checked;syncPostPreview();};
    $('download-copy').onclick=downloadCopy;$('save-btn').onclick=saveDraft;$('export-btn').onclick=openExport;$('load-draft').onclick=restorePostDraft;$('manage-accounts').onclick=manageAccounts;$('publish-preview-btn').onclick=openPublishPreview;$('publish-timing').onchange=e=>{state.timing=e.target.value;$('publish-date').hidden=state.timing!=='scheduled';};$('publish-date').onchange=e=>state.scheduledAt=e.target.value;
    $('dialog-close').onclick=closeModal;$('dialog').addEventListener('click',e=>{if(e.target===$('dialog')){const r=$('dialog').getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)closeModal();}});
    $('help-btn').onclick=()=>{const saved=loadLocal('qingjing-creator-wallpaper-v1');modal('倾境创作台 · 原型说明',`<p class="dialog-intro">第一版聚焦两个独立工作区，沿用 4D 壁纸工作台的样式。</p><ul class="modal-list"><li>壁纸加工：导入、固定尺寸、裁剪、截取、变速、苹果／鸿蒙分别调整。</li><li>时间轴：拖动两端选片段，拖动选区移动片段，点击刻度定位。空格播放，方向键逐帧，I / O 设置入点与出点。</li><li>内容发布：独立导入素材，为小红书和抖音分别编辑文案，选择账号，预览发布清单。</li></ul><p class="dialog-note">当前用于确认流程。示例视频为五层素材的预览动画；原型时间轴按 30 帧/秒预设工作。正式视频编码、动态图片打包、真实账号授权与发布尚未接入。</p>`,[{label:'知道了',primary:true,run:closeModal},...(saved?[{label:'恢复壁纸草稿',run:()=>{const asset=state.assets.find(a=>a.name===saved.source?.name&&a.type===saved.source?.type);if(!asset){toast('请先重新导入草稿使用的本地素材');return;}state.selectedId=asset.id;state.name=saved.name||'未命名产品';state.profiles[asset.id]=saved.versions;state.platform=saved.activePlatform||'harmony';state.product=saved.product;state.coverTime=saved.coverSourceTime;state.time=profile().start;switchWorkspace('wallpaper');renderWallpaper();closeModal();toast('已恢复壁纸编辑草稿');}}]:[])]);};
    document.addEventListener('keydown',e=>{if($('dialog').open||state.workspace!=='wallpaper'||/^(INPUT|TEXTAREA|SELECT|BUTTON|A|SUMMARY)$/.test(e.target.tagName)||e.target.isContentEditable||selectedAsset()?.type!=='video')return;if(e.code==='Space'){e.preventDefault();togglePlay();}else if(e.key==='ArrowLeft'){e.preventDefault();seek(state.time-1/FPS);}else if(e.key==='ArrowRight'){e.preventDefault();seek(state.time+1/FPS);}else if(e.key.toLowerCase()==='i')$('set-in').click();else if(e.key.toLowerCase()==='o')$('set-out').click();});
    let dragDepth=0;document.addEventListener('dragenter',e=>{if(![...e.dataTransfer.types].includes('Files'))return;e.preventDefault();dragDepth++;$('drop-overlay').hidden=false;});document.addEventListener('dragover',e=>{if([...e.dataTransfer.types].includes('Files'))e.preventDefault();});document.addEventListener('dragleave',()=>{dragDepth=Math.max(0,dragDepth-1);if(!dragDepth)$('drop-overlay').hidden=true;});document.addEventListener('drop',e=>{e.preventDefault();dragDepth=0;$('drop-overlay').hidden=true;if(e.dataTransfer.files.length)importFiles(e.dataTransfer.files,state.workspace);});
    window.addEventListener('beforeunload',()=>objectUrls.forEach(url=>URL.revokeObjectURL(url)));
  }
  bindEvents();renderWallpaper();renderPublish();
})();
