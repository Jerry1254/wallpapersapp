/* Small project covers; never modify or duplicate the original media bytes. */
(function(root){
  'use strict';
  const layers=['background.jpg','buildings.png','character.png','light.png','debris.png'];
  function subject(record){
    const data=record.data,view=data.view||{},versions=data.versions||{};
    let p=versions[view.platform]||versions.android||versions.harmony||versions.ios;
    if(view.product==='4d'&&view.sourcePreview){const spec=root.WallpaperDevices?.screen(data.packageView?.mode==='fold'?data.packageView.foldId:data.packageView?.phoneId,data.packageView?.mode==='fold'?'inner':data.packageView?.phoneScreen);return {id:view.selectedId,p:spec||{width:1080,height:2400},time:0};}
    if(view.product==='static'&&data.staticEditor){const e=data.staticEditor;return {id:e.assetId,p:e.crops?.[`${e.assetId}:${e.deviceId}:${e.screenId}`]||{width:1206,height:2622,scale:1,x:0,y:0},time:e.times?.[e.assetId]||0};}
    if(view.product==='static'&&view.sourcePreview)return {id:view.selectedId,p,time:0};
    if(!p?.clips?.length)p=[versions.android,versions.harmony,versions.ios].find(v=>v?.clips?.length);
    const clip=p?.clips?.[0];return clip?{id:clip.assetId,p,time:clip.start||0}:null;
  }
  function paint(sources,p,demo){
    const canvas=document.createElement('canvas');canvas.width=canvas.height=140;
    const ctx=canvas.getContext('2d'),ratio=(p?.width||1080)/(p?.height||1920);
    const w=ratio>1?140:140*ratio,h=ratio>1?140/ratio:140,left=(140-w)/2,top=(140-h)/2;
    ctx.fillStyle='#17191d';ctx.fillRect(0,0,140,140);ctx.save();ctx.beginPath();ctx.rect(left,top,w,h);ctx.clip();
    for(const [i,media] of sources.entries()){
      const mw=media.naturalWidth||media.videoWidth||media.width,mh=media.naturalHeight||media.videoHeight||media.height;
      if(!mw||!mh||media.tagName==='VIDEO'&&(media.readyState<2||media.seeking))return null;
      const scale=Math.max(w/mw,h/mh)*(p?.scale||1)*(demo?1.18:1),dw=mw*scale,dh=mh*scale;
      ctx.save();if(demo&&i===3){ctx.globalAlpha=.25;ctx.globalCompositeOperation='screen';}
      ctx.drawImage(media,left+(w-dw)/2+(p?.x||0)/100*w,top+(h-dh)/2+(p?.y||0)/100*h,dw,dh);ctx.restore();
    }
    ctx.restore();return sources.length?canvas.toDataURL('image/jpeg',.78):null;
  }
  function current(record,container,asset){
    const target=subject(record);if(!target||target.id!==asset?.id)return null;
    try{return paint([...container.querySelectorAll(asset.type==='4d'?'canvas':'img,video')].filter(media=>asset.type!=='4d'||!media.closest('.package-device')?.hidden).slice(0,asset.type==='4d'?1:99),target.p,asset.demo&&asset.type!=='4d');}catch{return null;}
  }
  async function image(src){const img=new Image();img.src=src;await img.decode();return img;}
  async function video(src,time){
    const media=document.createElement('video');media.muted=true;media.preload='auto';
    await new Promise((resolve,reject)=>{
      const finish=error=>{clearTimeout(timer);media.onloadeddata=media.onseeked=media.onerror=null;if(error){media.removeAttribute('src');media.load();reject(error);}else resolve();};
      const timer=setTimeout(()=>finish(new Error('封面载入超时')),5000);
      media.onerror=()=>finish(new Error('无法读取封面'));
      media.onloadeddata=()=>{const position=Math.min(time,Math.max(0,media.duration-1/30));if(Math.abs(media.currentTime-position)<.02)finish();else media.currentTime=position;};
      media.onseeked=()=>finish();media.src=src;
    });return media;
  }
  async function create(record,asset){
    const target=subject(record);if(!target||!asset)return null;
    let url,media;
    try{
      if(asset.type==='4d'){const {scene}=await root.WallpaperPreview.load(asset),canvas=document.createElement('canvas'),p=target.p;canvas.width=140;canvas.height=Math.round(140*p.height/p.width);root.ParallaxCore.render(canvas.getContext('2d'),scene,canvas.width,canvas.height);return paint([canvas],p,false);}
      if(asset.demo)return paint(await Promise.all(layers.map(name=>image(`assets/${name}`))),target.p,true);
      if(!asset.file||!['image','video'].includes(asset.type))return null;
      url=URL.createObjectURL(asset.file);media=asset.type==='image'?await image(url):await video(url,target.time);
      return paint([media],target.p,false);
    }catch{return null;}
    finally{if(media?.tagName==='VIDEO'){media.removeAttribute('src');media.load();}if(url)URL.revokeObjectURL(url);}
  }
  root.ProjectThumbnails={subject,current,create};
})(window);
