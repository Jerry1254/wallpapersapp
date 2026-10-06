/* The preview and exported pages/frames share exactly the same composition renderer. */
(() => {
  'use strict';
  const C=window.ContentCore,E=window.GalleryEditing,cache=new Map(),waits=new Set(),previewVideos=new Map(),layer=document.createElement('canvas'),measure=document.createElement('canvas').getContext('2d');
  let editingId=null,previewPlaying=false;
  const demoNames=['background.jpg','buildings.png','character.png','light.png','debris.png'];
  function ready(element,event){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>finish(new Error('素材读取超时，请重试')),15000);function finish(error){waits.delete(finish);clearTimeout(timer);element.removeEventListener(event,ok);element.removeEventListener('error',bad);error?reject(error):resolve(element);}const ok=()=>finish(),bad=()=>finish(new Error('素材无法读取'));waits.add(finish);element.addEventListener(event,ok,{once:true});element.addEventListener('error',bad,{once:true});});}
  async function load(asset){
    if(!asset)throw new Error('请先替换壁纸素材');
    if(!cache.has(asset.id)){
      const pending=(async()=>{
        if(asset.demo){const images=[];for(const name of demoNames){const image=new Image();const promise=ready(image,'load');image.src=`assets/${name}`;images.push(await promise);}return {images,demo:true};}
        if(asset.type==='image'){const image=new Image(),promise=ready(image,'load');image.src=asset.url;return {image:await promise};}
        if(asset.type==='video'){const video=document.createElement('video');video.muted=true;video.playsInline=true;video.preload='auto';const promise=ready(video,'loadeddata');video.src=asset.url;return {video:await promise};}
        throw new Error('请选择图片或视频');
      })().catch(error=>{cache.delete(asset.id);throw error;});cache.set(asset.id,pending);
    }return cache.get(asset.id);
  }
  function playPreview(entry){
    if(!previewPlaying||!entry.active||entry.hold||entry.playPromise||!entry.video.paused||entry.video.ended&&!entry.video.loop)return;
    entry.playPromise=entry.video.play().catch(()=>{}).finally(()=>{entry.playPromise=null;});
  }
  function alignPreview(entry){
    if(!entry.loaded||!entry.active||!previewPlaying)return;
    const video=entry.video,c=entry.clip,w=entry.work,asset={...entry.asset,duration:Number.isFinite(video.duration)?video.duration:entry.asset.duration},target=C.sourceTime(c,entry.time,asset,w),speed=c.speed||1;
    entry.hold=c.fill==='hold'&&target>=asset.duration-C.frame(w)-.001;
    video.playbackRate=speed;
    video.loop=c.fill==='loop'&&Math.max(0,c.loopIn??c.sourceIn??0)===0;
    const wrapped=!video.loop&&entry.lastTarget!=null&&target<entry.lastTarget-.05,distance=Math.abs(video.currentTime-target),drift=video.loop&&!entry.needsAlign?Math.min(distance,Math.max(0,asset.duration-distance)):distance,threshold=(entry.needsAlign||entry.hold) ? .008 : .25;
    if(!video.seeking&&(entry.needsAlign||wrapped||drift>threshold)){
      if(drift>.008)video.currentTime=target;entry.needsAlign=false;
    }
    entry.lastTarget=target;
    if(entry.hold)video.pause();else playPreview(entry);
  }
  function previewEntry(w,c,asset,time){
    const key=w.id+':'+c.id;let entry=previewVideos.get(key);
    if(entry&&entry.asset.id!==asset.id){entry.active=false;entry.video.pause();entry.video.removeAttribute('src');entry.video.load();previewVideos.delete(key);entry=null;}
    if(!entry){
      const video=document.createElement('video');video.muted=true;video.playsInline=true;video.preload='auto';
      entry={video,asset,clip:c,work:w,time,active:true,loaded:false,needsAlign:true,lastTarget:null,hold:false,playPromise:null};previewVideos.set(key,entry);
      video.onloadedmetadata=()=>{if(previewPlaying&&entry.active){const target=C.sourceTime(entry.clip,entry.time,{...entry.asset,duration:video.duration},entry.work);if(Math.abs(video.currentTime-target)>.008)video.currentTime=target;}};
      const waiting=ready(video,'loadeddata');video.src=asset.url;
      entry.promise=waiting.then(async()=>{
        entry.loaded=true;alignPreview(entry);
        if(video.seeking)await ready(video,'seeked');
        playPreview(entry);return {video};
      }).catch(error=>{if(previewVideos.get(key)===entry)previewVideos.delete(key);video.pause();video.removeAttribute('src');video.load();throw error;});
      entry.promise.catch(()=>{});
    }
    entry.asset=asset;entry.clip=c;entry.work=w;entry.time=time;entry.active=true;return entry;
  }
  function syncPlayback(w,time,resolve){
    if(!previewPlaying)return;const active=new Set();
    for(const c of C.active(w,time)){
      if(c.presentation==='text')continue;const asset=resolve(C.resolve(w,c));if(asset?.type!=='video'||asset.demo)continue;
      const entry=previewEntry(w,c,asset,time),track=C.trackOf(w,c);active.add(entry);entry.video.muted=!!track?.muted;entry.video.volume=C.clamp(c.volume??1,0,1);alignPreview(entry);playPreview(entry);
    }
    for(const entry of previewVideos.values())if(!active.has(entry)){entry.active=false;entry.needsAlign=true;entry.lastTarget=null;entry.video.pause();}
  }
  function startPlayback(w,time,resolve){previewPlaying=true;syncPlayback(w,time,resolve);}
  function stopPlayback(){previewPlaying=false;for(const entry of previewVideos.values()){entry.active=false;entry.needsAlign=true;entry.lastTarget=null;entry.video.pause();}}
  function round(ctx,x,y,w,h,r){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));}
  function cover(ctx,image,x,y,w,h,c){
    const iw=image.videoWidth||image.naturalWidth||image.width,ih=image.videoHeight||image.naturalHeight||image.height;
    const ratio=Math.max(w/iw,h/ih)*(c.scale||1),dw=iw*ratio,dh=ih*ratio;
    ctx.drawImage(image,x+(w-dw)/2+(c.panX||0)/100*w,y+(h-dh)/2+(c.panY||0)/100*h,dw,dh);
  }
  function geometry(w,c,time=w.cursor){
    const sx=w.width/100,sy=w.height/100,angle=(c.rotation||0)*Math.PI/180,progress=C.clamp((time-c.start)/Math.max(.1,c.duration),0,1),offset=c.animation==='float'?Math.sin(progress*Math.PI*2)*sy*1.2:0;
    let width=c.w*sx,height=c.h*sy;if(c.presentation==='phone'){width=Math.min(width,height*.46);height=width/.46;width+=sx;height+=sx;}
    return {x:c.x*sx-Math.sin(angle)*offset,y:c.y*sy+Math.cos(angle)*offset,width,height,angle,scale:c.animation==='push'?1+progress*.06:1};
  }
  function point(g,p){const x=p.x*g.scale,y=p.y*g.scale;return {x:g.x+Math.cos(g.angle)*x-Math.sin(g.angle)*y,y:g.y+Math.sin(g.angle)*x+Math.cos(g.angle)*y};}
  function local(g,p){const x=p.x-g.x,y=p.y-g.y;return {x:(Math.cos(g.angle)*x+Math.sin(g.angle)*y)/g.scale,y:(-Math.sin(g.angle)*x+Math.cos(g.angle)*y)/g.scale};}
  function radius(w,c){
    if(c.presentation==='text')return 0;
    if(c.radius!=null)return Math.max(0,c.radius);const g=geometry(w,c);
    return c.presentation==='phone'?(g.width-w.width/100)*.125:c.presentation==='full'&&c.w===100?0:g.width*.02;
  }
  function pick(w,time,x,y){
    for(const c of C.active(w,time).slice().reverse()){
      const g=geometry(w,c,time),p=local(g,{x,y});if(Math.abs(p.x)>g.width/2||Math.abs(p.y)>g.height/2)continue;
      const r=Math.min(radius(w,c),g.width/2,g.height/2),cx=Math.max(0,Math.abs(p.x)-(g.width/2-r)),cy=Math.max(0,Math.abs(p.y)-(g.height/2-r));
      if(!r||Math.hypot(cx,cy)<=r)return c;
    }return null;
  }
  const textValue=(w,c)=>c.text==='$title'?w.title:c.text==='$subtitle'?w.subtitle:c.text;
  function font(c,scale=1){return (c.fontWeight||400)+' '+(c.fontSize||40)*scale+'px '+E.fontFamily(c.fontFamily);}
  function syncText(w,c){
    if(c.presentation!=='text')return;C.clipStyle(w,c);const width=c.w*w.width/100,height=c.h*w.height/100;
    measure.font=font(c);measure.letterSpacing=(c.letterSpacing||0)+'px';
    const layout=E.textLayout({...c,text:textValue(w,c),width,height},value=>measure.measureText(value).width);
    const dx=(layout.width-width)/2,dy=(layout.height-height)/2,a=(c.rotation||0)*Math.PI/180;
    c.w=layout.width/w.width*100;c.h=layout.height/w.height*100;c.x+=(Math.cos(a)*dx-Math.sin(a)*dy)/w.width*100;c.y+=(Math.sin(a)*dx+Math.cos(a)*dy)/w.height*100;
  }
  function text(ctx,w,c,width,height,scale){
    ctx.font=font(c,scale);ctx.letterSpacing=(c.letterSpacing||0)*scale+'px';
    const layout=E.textLayout({...c,text:textValue(w,c),fontSize:c.fontSize*scale,width,height,textResize:'fixed'},value=>ctx.measureText(value).width);
    const offset=c.verticalAlign==='center'?(height-layout.lines.length*layout.lineHeight)/2:c.verticalAlign==='bottom'?height-layout.lines.length*layout.lineHeight:0;
    ctx.save();ctx.translate(-width/2,-height/2);ctx.beginPath();ctx.rect(0,0,width,height);ctx.clip();ctx.fillStyle=E.fill(ctx,c.color||w.accent,width,height);ctx.textBaseline='top';ctx.textAlign=c.align||'left';
    const x=c.align==='center'?width/2:c.align==='right'?width:0;layout.lines.forEach((line,i)=>ctx.fillText(line,x,offset+i*layout.lineHeight));ctx.restore();ctx.letterSpacing='0px';
  }
  async function paint(canvas,w,time,resolve,signal,placeholders=false,options={}){
    const output=canvas.getContext('2d'),sx=canvas.width/100,sy=canvas.height/100,scale=canvas.width/w.width;
    output.clearRect(0,0,canvas.width,canvas.height);output.fillStyle=w.background;output.fillRect(0,0,canvas.width,canvas.height);
    if(layer.width!==canvas.width)layer.width=canvas.width;if(layer.height!==canvas.height)layer.height=canvas.height;
    for(const c of C.active(w,time)){
      if(signal?.aborted)throw new DOMException('已取消','AbortError');if(placeholders&&c.id===editingId)continue;C.clipStyle(w,c);
      let data=null,asset=null;
      if(c.presentation!=='text'){
        asset=resolve(C.resolve(w,c));
        if(asset){
          const live=options.playback&&asset.type==='video'&&!asset.demo;
          const entry=live?previewVideos.get(w.id+':'+c.id):null;
          data=await (entry&&entry.asset.id===asset.id?entry.promise:load(asset));
          // Native playback decodes sequentially. Precise seeking is reserved for paused frames and export.
          if(data.video&&!live){const t=C.sourceTime(c,time,asset,w);if(Math.abs(data.video.currentTime-t)>.008){const promise=ready(data.video,'seeked');data.video.currentTime=t;await promise;}}
        }
        else if(!placeholders)throw new Error('有画面尚未绑定素材，请替换后生成');
      }
      // Isolate a complete element before applying its opacity and blend mode once.
      if(layer.width!==canvas.width)layer.width=canvas.width;if(layer.height!==canvas.height)layer.height=canvas.height;
      const ctx=layer.getContext('2d');ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,layer.width,layer.height);
      const elapsed=time-c.start,progress=C.clamp(elapsed/Math.max(.1,c.duration),0,1),fade=c.animation==='fade'?Math.min(1,Math.max(0,elapsed)/.3,Math.max(0,c.start+c.duration-time)/.3):1;
      ctx.save();ctx.translate(c.x*sx,c.y*sy);ctx.rotate((c.rotation||0)*Math.PI/180);if(c.animation==='float')ctx.translate(0,Math.sin(progress*Math.PI*2)*sy*1.2);if(c.animation==='push'){const zoom=1+progress*.06;ctx.scale(zoom,zoom);}
      const width=c.w*sx,height=c.h*sy;
      if(c.presentation==='text')text(ctx,w,c,width,height,scale);
      else{
        let mw=width,mh=height,mx=-width/2,my=-height/2,corner=radius(w,c)*scale;
        if(c.presentation==='phone'){
          mw=Math.min(width,height*.46);mh=mw/.46;mx=-mw/2;my=-mh/2;
          ctx.shadowColor='#0009';ctx.shadowBlur=sx*4;ctx.shadowOffsetY=sy*1.5;round(ctx,mx-sx/2,my-sx/2,mw+sx,mh+sx,corner);ctx.fillStyle='#626873';ctx.fill();ctx.shadowBlur=0;ctx.shadowOffsetY=0;
          round(ctx,mx,my,mw,mh,Math.max(0,corner-sx/2));ctx.fillStyle='#080a0d';ctx.fill();const border=mw*.025;mx+=border;my+=border;mw-=border*2;mh-=border*2;corner=Math.max(0,corner-sx/2-border);
        }
        ctx.save();round(ctx,mx,my,mw,mh,corner);ctx.clip();
        if(data?.demo){data.images.forEach((image,i)=>{ctx.save();if(i===3){ctx.globalAlpha=.25;ctx.globalCompositeOperation='screen';}const t=asset.type==='video'?C.sourceTime(c,time,asset,w):0,dx=Math.sin(t*Math.PI*.4)*(i===2?3:i===4?5:1);cover(ctx,image,mx,my,mw,mh,{...c,scale:(c.scale||1)*1.18,panX:(c.panX||0)+dx});ctx.restore();});}
        else if(data)cover(ctx,data.video||data.image,mx,my,mw,mh,c);
        else{ctx.fillStyle='#34435b';ctx.fillRect(mx,my,mw,mh);ctx.fillStyle='#a6b9d8';ctx.font=canvas.width*.03+'px sans-serif';ctx.textAlign='center';ctx.fillText('替换壁纸素材',0,0);}
        if(c.lock){ctx.fillStyle='#fff';ctx.font='300 '+mw*.2+'px sans-serif';ctx.textAlign='center';ctx.fillText('09:41',0,my+mh*.22);}ctx.restore();
        if(c.presentation==='phone'){ctx.fillStyle='#090b0d';round(ctx,-mw*.14,my+mh*.025,mw*.28,mh*.024,mw*.04);ctx.fill();ctx.fillStyle='#ffffffdc';round(ctx,-mw*.14,my+mh*.973,mw*.28,mh*.006,mw*.02);ctx.fill();}
      }
      ctx.restore();output.save();output.globalAlpha=c.opacity*fade;output.globalCompositeOperation=c.blendMode==='pass-through'?'source-over':c.blendMode||'source-over';output.drawImage(layer,0,0);output.restore();
    }
  }
  function clear(){editingId=null;stopPlayback();layer.width=layer.height=1;for(const finish of [...waits])finish(new DOMException('已取消','AbortError'));for(const entry of previewVideos.values()){entry.video.removeAttribute('src');entry.video.load();}previewVideos.clear();for(const p of cache.values())p.then(data=>{if(data.video){data.video.pause();data.video.removeAttribute('src');data.video.load();}}).catch(()=>{});cache.clear();}
  const blob=(canvas,type='image/png',quality=.9)=>new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error('画面导出失败')),type,quality));
  window.ContentRenderer={paint,pick,geometry,point,local,radius,font,textValue,syncText,setEditing:id=>{editingId=id;},startPlayback,syncPlayback,stopPlayback,clear,blob,load};
})();
