/* A timeline is exportable as soon as it contains a clip; target lengths are advisory. */
(function(root){
  'use strict';
  function prepare(profile, resolveAsset){
    if(!profile.clips?.length)throw new Error('时间轴里还没有片段');
    const sources=[],files=[],indexes=new Map();
    const clips=profile.clips.map(clip=>{
      if(!indexes.has(clip.assetId)){
        const asset=resolveAsset(clip.assetId);
        if(!asset||!['video','image'].includes(asset.type))throw new Error('时间轴素材已丢失，请重新导入');
        const index=sources.length;
        if(asset.demo)sources.push({demo:true});
        else{
          if(!asset.file)throw new Error(`无法读取素材「${asset.name}」，请重新导入`);
          const mime=asset.file.type,extensions={'image/jpeg':'.jpg','image/png':'.png','image/webp':'.webp','video/mp4':'.mp4','video/quicktime':'.mov','video/webm':'.webm'},suffix=asset.file.name?.match(/\.(mp4|mov|webm|png|jpe?g|webp)$/i)?.[0].toLowerCase();
          const ext=extensions[mime]||suffix;if(!ext)throw new Error('请使用 PNG、JPEG、WebP、MP4、MOV 或 WebM 素材');
          const path=`media/${index}${ext}`;sources.push({path});files.push({path,file:asset.file});
        }
        indexes.set(clip.assetId,index);
      }
      return {source:indexes.get(clip.assetId),kind:clip.kind,start:clip.start,end:clip.end,speed:clip.speed};
    });
    return {job:{version:1,fps:30,profile:Object.fromEntries(['width','height','scale','x','y'].map(key=>[key,profile[key]])),clips,sources},files};
  }
  async function render(prepared,JSZip,signal){
    if(signal?.aborted)throw new DOMException('已取消','AbortError');
    const sources=[];for(const entry of prepared.files)sources.push({path:entry.path,hash:await root.CreatorBackend.hash(entry.file)});
    return root.CreatorJobs.video('WALLPAPER_RENDER',{rendererVersion:2,plan:prepared.job,sources},async()=>{
      const zip=new JSZip();zip.file('timeline.json',JSON.stringify(prepared.job));
      for(const entry of prepared.files)zip.file(entry.path,entry.file);
      const payload=await zip.generateAsync({type:'blob',compression:'STORE'});
      if(signal?.aborted)throw new DOMException('已取消','AbortError');
      if(payload.size>512*1048576)throw new Error('素材包超过 512 MB，请分批导出');
      return payload;
    },signal);
  }

  const api={prepare,render};root.TimelineExport=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
