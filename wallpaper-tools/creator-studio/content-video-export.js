/* Encode the existing composition directly when supported; retain the frame archive fallback. */
(() => {
  'use strict';
  const C=window.ContentCore,R=window.ContentRenderer,MAX_BYTES=500*1048576;
  class NativeEncodingFailure extends Error {}
  const cancelled=signal=>{if(signal?.aborted)throw new DOMException('已取消','AbortError');};
  function note(message){const element=document.getElementById('content-progress-note');if(element)element.textContent=message;}
  async function encoderFor(size,rate,output,signal){
    if(!window.VideoEncoder||!window.VideoFrame)return null;
    const blocks=Math.ceil(size.width/16)*Math.ceil(size.height/16);if(blocks>36864)return null;
    const level=blocks<=8704&&blocks*rate<=522240?'2A':blocks*rate<=983040?'33':'34';
    const bitrate=output.quality==='custom'?Math.round(output.bitrate*1000000):Math.min(100000000,Math.max(output.quality==='high'?4000000:2000000,Math.round(size.width*size.height*rate*(output.quality==='high'?.35:.2))));
    for(const profile of ['6400','4D00','4200']){
      cancelled(signal);
      const config={codec:'avc1.'+profile+level,width:size.width,height:size.height,bitrate,framerate:rate,hardwareAcceleration:'prefer-hardware',latencyMode:'realtime',bitrateMode:'variable',avc:{format:'annexb'}};
      let support;try{support=await VideoEncoder.isConfigSupported(config);}catch{continue;}
      cancelled(signal);if(!support.supported||support.config.avc?.format!=='annexb')continue;
      let error=null,bytes=0,count=0;const parts=[];
      let encoder;
      try{
        encoder=new VideoEncoder({output:chunk=>{
          if(error)return;bytes+=chunk.byteLength;if(bytes>MAX_BYTES){error=new NativeEncodingFailure('视频数据过大');return;}
          const data=new Uint8Array(chunk.byteLength);chunk.copyTo(data);parts.push(data);count++;
        },error:failure=>{error=new NativeEncodingFailure(failure.message);}});
        encoder.configure(config);
      }catch{if(encoder&&encoder.state!=='closed')encoder.close();continue;}
      const check=()=>{cancelled(signal);if(error)throw error;if(encoder.state!=='configured')throw new NativeEncodingFailure('视频编码器不可用');};
      return {
        async add(canvas,index){
          check();const deadline=performance.now()+30000;
          while(encoder.encodeQueueSize>=6){check();if(performance.now()>deadline)throw new NativeEncodingFailure('视频编码等待超时');await new Promise(resolve=>setTimeout(resolve,2));}
          const timestamp=Math.round(index*1000000/rate);let frame;
          try{frame=new VideoFrame(canvas,{timestamp,duration:Math.round((index+1)*1000000/rate)-timestamp});}catch(failure){throw new NativeEncodingFailure(failure.message);}
          try{encoder.encode(frame,{keyFrame:index%(rate*2)===0});}catch(failure){throw new NativeEncodingFailure(failure.message);}finally{frame.close();}
        },
        async finish(frames){
          check();let timer,abort;
          try{
            await Promise.race([encoder.flush(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new NativeEncodingFailure('视频编码等待超时')),30000);abort=()=>reject(new DOMException('已取消','AbortError'));signal?.addEventListener('abort',abort,{once:true});})]);
          }catch(failure){if(failure.name==='AbortError')throw failure;throw new NativeEncodingFailure(failure.message);}finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
          check();if(count!==frames||!bytes)throw new NativeEncodingFailure('视频帧未完整编码');
          return new Blob(parts,{type:'video/h264'});
        },
        close(){if(encoder.state!=='closed')encoder.close();parts.length=0;}
      };
    }
    return null;
  }
  function audioArchive(zip,w,resolve,total){
    const audio=[];let index=0;
    for(const track of w.tracks.filter(track=>!track.hidden&&!track.muted))for(const clip of track.clips){
      if(clip.start>=total||clip.duration<=0||clip.presentation==='text')continue;
      const asset=resolve(C.resolve(w,clip)),videoSource=track.kind==='visual'&&asset?.type==='video'&&!asset.demo;
      if(track.kind!=='audio'&&!videoSource)continue;if(!asset?.file)throw new Error('声音素材已丢失，请重新导入');
      const path=`audio/${index++}.${asset.file.name.split('.').pop().toLowerCase()}`;zip.file(path,asset.file);
      audio.push({path,videoSource,start:clip.start,duration:Math.min(clip.duration,total-clip.start),sourceIn:clip.sourceIn,loopIn:clip.loopIn??clip.sourceIn,speed:clip.speed,volume:clip.volume??(videoSource?1:.5),fadeIn:clip.fadeIn??(videoSource?0:.4),fadeOut:clip.fadeOut??(videoSource?0:.6),fill:clip.fill});
    }
    return audio;
  }
  async function attempt(canvas,w,resolve,signal,progress,native){
    const size=C.outputSize(w),rate=C.fps(w),frames=C.outputFrames(w),total=frames/rate,zip=new JSZip(),audio=audioArchive(zip,w,resolve,total),outputCanvas=size.width===canvas.width&&size.height===canvas.height?canvas:document.createElement('canvas');
    if(outputCanvas!==canvas){outputCanvas.width=size.width;outputCanvas.height=size.height;}
    const ctx=outputCanvas.getContext('2d');let frameBytes=0;
    const manifest={version:1,width:size.width,height:size.height,fps:rate,frames,quality:w.output.quality,bitrate:w.output.bitrate,audio};
    note(native?'正在直接编码视频，完成后合成声音。':'正在按当前画布与输出设置生成视频。');
    try{
      for(let index=0;index<frames;index++){
        cancelled(signal);await R.paint(canvas,w,index/rate,resolve,signal,false,{export:true});
        if(outputCanvas!==canvas)ctx.drawImage(canvas,0,0,size.width,size.height);
        if(native)await native.add(outputCanvas,index);
        else{
          const frame=await R.blob(outputCanvas,'image/jpeg',w.output.quality==='standard'?.92:.97);frameBytes+=frame.size;
          if(frameBytes>MAX_BYTES)throw new Error('画面数据过大，请降低输出尺寸、帧率或缩短视频');zip.file(`frames/${String(index).padStart(5,'0')}.jpg`,frame);
        }
        progress((index+1)/frames*85,`生成视频画面 ${Math.round((index+1)/frames*100)}%`);
        if(index%10===0)await new Promise(resolve=>setTimeout(resolve,0));
      }
      if(native){progress(87,'完成视频编码…');zip.file('video.h264',await native.finish(frames));manifest.video={path:'video.h264',codec:'h264'};}
      zip.file('content.json',JSON.stringify(manifest));progress(90,native?'合成视频和声音…':'编码视频和音乐…');
      const payload=await zip.generateAsync({type:'blob',compression:'STORE'});cancelled(signal);
      if(payload.size>512*1048576)throw new Error('成片数据超过 512 MB，请缩短视频');
      const response=await fetch('api/render-content',{method:'POST',headers:{'Content-Type':'application/zip','X-Creator-Export':'1'},body:payload,signal});
      if(!response.ok){const failure=await response.json().catch(()=>null),message=failure?.error||'本地视频生成服务不可用';if(native&&response.status===422)throw new NativeEncodingFailure(message);throw new Error(message);}
      if(!response.headers.get('Content-Type')?.includes('video/mp4'))throw new Error('没有收到视频文件');
      return response.blob();
    }finally{native?.close();}
  }
  async function render(canvas,w,resolve,signal,progress){
    C.videoSettings(w);const native=await encoderFor(C.outputSize(w),C.fps(w),w.output,signal);
    try{
      cancelled(signal);
      if(native){
        try{return await attempt(canvas,w,resolve,signal,progress,native);}catch(failure){
          if(!(failure instanceof NativeEncodingFailure))throw failure;
          cancelled(signal);R.clearExport();progress(0,'正在切换兼容生成方式…');
        }
      }
      return await attempt(canvas,w,resolve,signal,progress,null);
    }finally{native?.close();}
  }
  window.ContentVideoExport={render};
})();
