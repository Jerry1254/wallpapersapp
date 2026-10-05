/* The preview and exported pages/frames share exactly the same composition renderer. */
(() => {
  'use strict';
  const C=window.ContentCore,cache=new Map(),waits=new Set();
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
  function round(ctx,x,y,w,h,r){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));}
  function cover(ctx,image,x,y,w,h,c){
    const iw=image.videoWidth||image.naturalWidth||image.width,ih=image.videoHeight||image.naturalHeight||image.height;
    const ratio=Math.max(w/iw,h/ih)*(c.scale||1),dw=iw*ratio,dh=ih*ratio;
    ctx.drawImage(image,x+(w-dw)/2+(c.panX||0)/100*w,y+(h-dh)/2+(c.panY||0)/100*h,dw,dh);
  }
  async function paint(canvas,w,time,resolve,signal,placeholders=false){
    const ctx=canvas.getContext('2d'),sx=canvas.width/100,sy=canvas.height/100;
    ctx.clearRect(0,0,canvas.width,canvas.height);ctx.fillStyle=w.background;ctx.fillRect(0,0,canvas.width,canvas.height);
    for(const c of C.active(w,time)){
      if(signal?.aborted)throw new DOMException('已取消','AbortError');
      let data=null,asset=null;
      if(c.presentation!=='text'){
        asset=resolve(C.resolve(w,c));
        if(asset){data=await load(asset);if(data.video){const t=C.sourceTime(c,time,asset);if(Math.abs(data.video.currentTime-t)>.008){const promise=ready(data.video,'seeked');data.video.currentTime=t;await promise;}}}
        else if(!placeholders)throw new Error('有画面尚未绑定素材，请替换后生成');
      }
      const elapsed=time-c.start,progress=C.clamp(elapsed/Math.max(.1,c.duration),0,1);
      const fade=c.animation==='fade'?Math.min(1,Math.max(0,elapsed)/.3,Math.max(0,c.start+c.duration-time)/.3):1;
      ctx.save();ctx.globalAlpha=c.opacity*fade;ctx.translate(c.x*sx,c.y*sy);ctx.rotate(c.rotation*Math.PI/180);
      if(c.animation==='float')ctx.translate(0,Math.sin(progress*Math.PI*2)*sy*1.2);
      if(c.animation==='push'){const zoom=1+progress*.06;ctx.scale(zoom,zoom);}
      const width=c.w*sx,height=c.h*sy,x=-width/2,y=-height/2;
      if(c.presentation==='text'){
        const text=c.text==='$title'?w.title:c.text==='$subtitle'?w.subtitle:c.text;
        ctx.fillStyle=w.accent;ctx.textAlign='center';ctx.textBaseline='middle';let font=canvas.width*(c.text==='$title'?.045:.028);ctx.font=`600 ${font}px -apple-system, "PingFang SC", sans-serif`;
        if(ctx.measureText(text).width>width){font*=width/ctx.measureText(text).width;ctx.font=`600 ${font}px -apple-system, "PingFang SC", sans-serif`;}
        ctx.fillText(text,0,0);ctx.restore();continue;
      }
      let mx=x,my=y,mw=width,mh=height;
      if(c.presentation==='phone'){
        mw=Math.min(width,height*.46);mh=mw/.46;mx=-mw/2;my=-mh/2;
        ctx.shadowColor='#0009';ctx.shadowBlur=sx*4;ctx.shadowOffsetY=sy*1.5;round(ctx,mx-5*sx/10,my-5*sx/10,mw+sx,mh+sx,mw*.125);ctx.fillStyle='#626873';ctx.fill();ctx.shadowBlur=0;ctx.shadowOffsetY=0;
        round(ctx,mx,my,mw,mh,mw*.115);ctx.fillStyle='#080a0d';ctx.fill();const border=mw*.025;mx+=border;my+=border;mw-=border*2;mh-=border*2;
      }
      ctx.save();round(ctx,mx,my,mw,mh,c.presentation==='phone'?mw*.1:c.presentation==='full'&&c.w===100?0:mw*.02);ctx.clip();
      if(data?.demo){data.images.forEach((image,i)=>{ctx.save();ctx.globalAlpha*=i===3?.25:1;if(i===3)ctx.globalCompositeOperation='screen';const t=asset.type==='video'?C.sourceTime(c,time,asset):0,dx=Math.sin(t*Math.PI*.4)*(i===2?3:i===4?5:1);cover(ctx,image,mx,my,mw,mh,{...c,scale:(c.scale||1)*1.18,panX:(c.panX||0)+dx});ctx.restore();});}
      else if(data)cover(ctx,data.video||data.image,mx,my,mw,mh,c);
      else{ctx.fillStyle='#34435b';ctx.fillRect(mx,my,mw,mh);ctx.fillStyle='#a6b9d8';ctx.font=`${canvas.width*.03}px sans-serif`;ctx.textAlign='center';ctx.fillText('替换壁纸素材',0,0);}
      if(c.lock){ctx.fillStyle='#fff';ctx.font=`300 ${mw*.2}px sans-serif`;ctx.textAlign='center';ctx.fillText('09:41',0,my+mh*.22);}
      ctx.restore();
      if(c.presentation==='phone'){
        ctx.fillStyle='#090b0d';round(ctx,-mw*.14,my+mh*.025,mw*.28,mh*.024,mw*.04);ctx.fill();ctx.fillStyle='#ffffffdc';round(ctx,-mw*.14,my+mh*.973,mw*.28,mh*.006,mw*.02);ctx.fill();
      }
      ctx.restore();
    }
  }
  function clear(){for(const finish of [...waits])finish(new DOMException('已取消','AbortError'));for(const p of cache.values())p.then(data=>{if(data.video){data.video.pause();data.video.removeAttribute('src');data.video.load();}}).catch(()=>{});cache.clear();}
  const blob=(canvas,type='image/png',quality=.9)=>new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error('画面导出失败')),type,quality));
  window.ContentRenderer={paint,clear,blob,load};
})();
