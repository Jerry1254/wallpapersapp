/* Read-only package viewer. Original archive bytes are never changed. */
(() => {
  'use strict';
  const cache=new Map(),C=window.ParallaxCore;
  const demoFiles=['debris.png','light.png','character.png','buildings.png','background.jpg'];
  function sniff(bytes){
    const text=(a,b)=>String.fromCharCode(...bytes.subarray(a,b));
    if(bytes[0]===137&&text(1,4)==='PNG'){
      if(bytes.length<33)throw new Error('PNG 文件不完整');
      const dv=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
      const width=dv.getUint32(16),height=dv.getUint32(20);let channel=[4,6].includes(bytes[25]);
      for(let pos=8;pos+12<=bytes.length;){const len=dv.getUint32(pos),kind=text(pos+4,pos+8);if(pos+12+len>bytes.length)throw new Error('PNG 数据损坏');if(kind==='acTL')throw new Error('请导入静态 PNG，不支持 APNG 动画');if(kind==='tRNS')channel=true;pos+=12+len;}
      return {mime:'image/png',ext:'png',channel,width,height};
    }
    if(bytes[0]===255&&bytes[1]===216&&bytes[2]===255)return {mime:'image/jpeg',ext:'jpg',channel:false};
    if(text(0,4)==='RIFF'&&text(8,12)==='WEBP'){
      const dv=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);let channel=false;
      for(let pos=12;pos+8<=bytes.length;){const kind=text(pos,pos+4),len=dv.getUint32(pos+4,true),payload=pos+8;if(payload+len>bytes.length)throw new Error('WebP 数据损坏');if(kind==='ANIM'||kind==='ANMF'||(kind==='VP8X'&&(bytes[payload]&2)))throw new Error('请导入静态 WebP');if(kind==='ALPH'||(kind==='VP8X'&&(bytes[payload]&16))||(kind==='VP8L'&&(bytes[payload+4]&16)))channel=true;pos=payload+len+(len%2);}
      return {mime:'image/webp',ext:'webp',channel};
    }
    throw new Error('只支持 PNG、JPEG 或静态 WebP 图片');
  }

  async function layer(bytes,name,params){
    if(bytes.byteLength>64*1048576)throw new Error(`图层「${name}」超过 64 MB`);
    const format=sniff(bytes);if(format.width&&(format.width>4096||format.height>4096))throw new Error('图层宽高超过 4096 像素');const image=await createImageBitmap(new Blob([bytes],{type:format.mime}));
    try{
      if(image.width<512||image.height<512||image.width>4096||image.height>4096)throw new Error('图层尺寸必须为 512–4096 像素');
      const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
      const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.drawImage(image,0,0);
      const pixels=ctx.getImageData(0,0,image.width,image.height).data;let min=255,max=0;
      for(let i=3;i<pixels.length;i+=4){min=Math.min(min,pixels[i]);max=Math.max(max,pixels[i]);}
      canvas.width=canvas.height=1;
      const pngChannel=format.channel;
      return {name,image,width:image.width,height:image.height,visible:true,alpha:{min,max,hasChannel:pngChannel||min<255},...params};
    }catch(error){image.close();throw error;}
  }
  async function parse(asset){
    const layers=[];
    try{
      let config,migrated=false;
      if(asset.demo){
        const params=demoFiles.map((name,i)=>({index:i+1,offsetXPercent:[14,6,8,3,2][i],offsetYPercent:[8,4,5,2,2][i],initialOffsetXPercent:0,initialOffsetYPercent:0,direction:i===4?'reverse':'follow',scale:1.18,opacity:i===1?.25:1,blendMode:i===1?'screen':'normal'}));
        config={formatVersion:2,canvas:{width:2048,height:2048},motion:{maxAngleX:75,maxAngleY:75},layers:params};
        for(let i=0;i<demoFiles.length;i++){const response=await fetch(`assets/${demoFiles[i]}`);if(!response.ok)throw new Error('示例图层未加载');layers.push(await layer(new Uint8Array(await response.arrayBuffer()),demoFiles[i],params[i]));}
      }else{
        const parsed=await window.ParallaxArchive.read(new Uint8Array(await asset.file.arrayBuffer()),window.JSZip);
        if(parsed.type!=='wallpaper')throw new Error('请导入已制作完成的壁纸 ZIP，不是可编辑工程文件');
        config=parsed.config;migrated=parsed.migrated;
        for(const entry of parsed.entries)layers.push(await layer(entry.bytes,entry.name,entry.params));
      }
      const scene={canvas:config.canvas,motion:config.motion,layers};
      const errors=C.validateScene(scene).errors;if(errors.length)throw new Error(errors.join('；'));
      return {scene,config,migrated};
    }catch(error){layers.forEach(l=>l.image.close());throw error;}
  }
  function load(asset){
    if(!cache.has(asset.id))cache.set(asset.id,parse(asset));return cache.get(asset.id);
  }
  function clear(){for(const promise of cache.values())promise.then(value=>value.scene.layers.forEach(l=>l.image.close())).catch(()=>{});cache.clear();}
  window.WallpaperPreview={load,clear};
})();
