/* Tracks are created by drop locations, rather than by fixed presentation roles. */
(() => {
  'use strict';
  const C=window.ContentCore,$=id=>document.getElementById(id),esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let H,observer,finishDrag=null,posters=new Map();
  const work=()=>H.work(),active=()=>H.active()&&work()?.type==='video';
  const clock=time=>{const frames=Math.round(time*30);return String(Math.floor(frames/30)).padStart(2,'0')+':'+String(frames%30).padStart(2,'0');};
  const eye=hidden=>'<svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M2 10s3-5 8-5 8 5 8 5-3 5-8 5-8-5-8-5Z"/><circle cx="10" cy="10" r="2"/>'+(hidden?'<path d="M3 3l14 14"/>':'')+'</svg>';
  function mount(bridge){
    H=bridge;observer=new ResizeObserver(size);observer.observe($('content-timeline-body'));
    $('content-timeline-zoom').oninput=e=>{if(!active())return;work().timelineZoom=Number(e.target.value);size();H.save();};
    for(const [id,factor] of [['content-timeline-out',.8],['content-timeline-in',1.25]])$(id).onclick=()=>{if(!active())return;work().timelineZoom=C.clamp((work().timelineZoom||1)*factor,1,6);$('content-timeline-zoom').value=work().timelineZoom;size();H.save();};
    const body=$('content-timeline-body');
    body.ondragover=e=>{if(!active()||![...e.dataTransfer.types].some(t=>t==='Files'||t==='application/x-qingjing-content-source'))return;e.preventDefault();e.stopPropagation();autoScroll(e);mark(target(e,'visual'));e.dataTransfer.dropEffect='copy';};
    body.ondragleave=e=>{if(!body.contains(e.relatedTarget))clearMarks();};
    body.ondrop=async e=>{
      if(!active())return;e.preventDefault();e.stopPropagation();const w=work(),where=target(e,'visual'),start=time(e),files=[...e.dataTransfer.files],source=e.dataTransfer.getData('application/x-qingjing-content-source');clearMarks();if(!where)return;
      const assets=files.length?await H.importFiles(files):source?[{id:source}]:[];if(work()!==w)return;
      let next=start,index=where.index;for(const asset of assets){const c=await H.addSource(asset.id,where.trackId?where:{index:index??0},next);if(work()!==w)return;if(c){if(where.trackId)next=c.start+c.duration;else if(index!==undefined)index++;}}
    };
    body.ondragend=clearMarks;
    window.addEventListener('blur',()=>finishDrag?.(true));
    document.addEventListener('keydown',e=>{if(finishDrag&&e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();finishDrag(true);}},true);
  }
  function size(){
    if(!active())return;const inner=$('content-timeline-inner'),body=$('content-timeline-body'),zoom=C.clamp(work().timelineZoom||1,1,6);
    if(inner)inner.style.width=Math.round(143+Math.max(100,body.clientWidth-143)*zoom)+'px';
    $('content-timeline-zoom-value').textContent=Math.round(zoom*100)+'%';
  }
  function time(event,snap=true){const ruler=$('content-ruler'),rect=ruler?.getBoundingClientRect();if(!rect?.width)return work().cursor;const value=C.clamp((event.clientX-rect.left)/rect.width*Number(ruler.dataset.span),0,C.maxDuration-1/30);return snap&&!event.shiftKey?Math.round(value*30)/30:value;}
  function target(event,kind){
    const body=$('content-timeline-body'),r=body.getBoundingClientRect();if(event.clientX<r.left+143||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)return null;
    const element=document.elementFromPoint(event.clientX,event.clientY),gap=element?.closest('[data-content-track-insert]'),lane=element?.closest('[data-content-track]'),w=work();
    if(element?.closest('#content-ruler'))return null;if(kind==='audio'){const audio=w.tracks.find(t=>t.kind==='audio');if(audio)return {trackId:audio.id};}
    if(lane){const t=w.tracks.find(t=>t.id===lane.dataset.contentTrack);if(t?.kind===kind)return {trackId:t.id};return {index:Math.max(0,w.tracks.indexOf(t))};}
    if(gap)return {index:Number(gap.dataset.contentTrackInsert)};
    const audio=w.tracks.findIndex(t=>t.kind==='audio');return {index:audio<0?w.tracks.length:audio};
  }
  function clearMarks(){document.querySelectorAll('.content-track-lane.drop-ready,.content-track-gap.drop-ready').forEach(el=>el.classList.remove('drop-ready'));}
  function mark(where){clearMarks();if(!where)return;const selector=where.trackId?'[data-content-track="'+where.trackId+'"]':'[data-content-track-insert="'+where.index+'"]';document.querySelector(selector)?.classList.add('drop-ready');}
  function autoScroll(event){const body=$('content-timeline-body'),r=body.getBoundingClientRect();if(event.clientY<r.top+30)body.scrollTop-=12;else if(event.clientY>r.bottom-30)body.scrollTop+=12;if(event.clientX<r.left+170)body.scrollLeft-=12;else if(event.clientX>r.right-30)body.scrollLeft+=12;}
  function poster(asset){
    const still=H.thumb(asset);if(still)return still;if(!asset||asset.type!=='video'||!asset.url)return '';
    const old=posters.get(asset.id);if(old)return old.url||'';
    const entry={url:'',dispose:null};posters.set(asset.id,entry);const video=document.createElement('video');video.muted=true;video.preload='metadata';video.playsInline=true;
    let done=false,timer;entry.dispose=()=>{if(done)return;done=true;clearTimeout(timer);video.onloadedmetadata=video.onseeked=video.onerror=null;video.removeAttribute('src');video.load();};
    const paint=()=>{if(done)return;try{const canvas=document.createElement('canvas');canvas.width=160;canvas.height=90;const ctx=canvas.getContext('2d'),scale=Math.max(160/video.videoWidth,90/video.videoHeight);ctx.drawImage(video,(160-video.videoWidth*scale)/2,(90-video.videoHeight*scale)/2,video.videoWidth*scale,video.videoHeight*scale);canvas.toBlob(blob=>{if(blob&&posters.get(asset.id)===entry){entry.url=URL.createObjectURL(blob);document.querySelectorAll('[data-content-poster="'+asset.id+'"]').forEach(img=>img.src=entry.url);}},'image/jpeg',.7);}catch(_){}finally{entry.dispose();}};
    video.onloadedmetadata=()=>{video.onseeked=paint;video.currentTime=Math.min(.15,Math.max(0,(video.duration||1)-.04));};video.onerror=entry.dispose;timer=setTimeout(entry.dispose,7000);video.src=asset.url;return '';
  }
  function clipName(w,c,asset){return c.presentation==='text'?(c.text==='$title'?w.title:c.text==='$subtitle'?w.subtitle:c.text):c.presentation==='phone'?'手机样机 · '+(asset?.name||'壁纸'):asset?.name||'壁纸画面';}
  function filmstrip(c,asset){
    if(c.presentation==='audio')return '<div class="content-clip-wave" aria-hidden="true">♫</div>';
    if(c.presentation==='text')return '<div class="content-clip-text-mark" aria-hidden="true">T</div>';
    const src=poster(asset);return '<div class="content-clip-filmstrip" aria-hidden="true">'+Array.from({length:12},()=>src?'<img src="'+esc(src)+'" alt="">':asset?.type==='video'?'<img data-content-poster="'+asset.id+'" alt="">':'<span>▧</span>').join('')+'</div>';
  }
  function render(){
    if(!active())return;const w=work(),body=$('content-timeline-body'),scroll={left:body.scrollLeft,top:body.scrollTop},span=C.extent(w),zoom=C.clamp(w.timelineZoom||1,1,6);
    const ticks=[];for(let t=0;t<span;t+=.5)ticks.push('<span class="'+(Number.isInteger(t)?'':'half-tick')+'" style="left:'+t/span*100+'%">'+(Number.isInteger(t)?String(t).padStart(2,'0')+'s':zoom>=2?'15f':'')+'</span>');
    const gap=(index,last=false)=>'<div class="content-track-gap'+(last?' content-track-end':'')+'" data-content-track-insert="'+index+'"><span></span><div class="content-gap-lane">'+(last?'<span>＋ 拖入素材，新增轨道</span>':'')+'</div></div>';
    let html='<div id="content-timeline-inner"><div class="content-track content-ruler"><div class="content-track-name">30 帧 / 秒</div><div class="content-track-lane content-ruler" id="content-ruler" data-span="'+span+'">'+ticks.join('')+'<div class="content-track-cursor"></div></div></div>'+(w.tracks.length?gap(0):'');
    for(let i=0;i<w.tracks.length;i++){const t=w.tracks[i];html+='<div class="content-track'+(t.hidden?' track-hidden':'')+'" data-content-track-row="'+t.id+'"><div class="content-track-name"><span class="content-track-kind">'+(t.kind==='audio'?'♫':'▣')+'</span><span class="content-track-title">'+(t.kind==='audio'?'音频':'视频')+'</span><button data-content-hide="'+t.id+'" title="'+(t.hidden?'显示':'隐藏')+'轨道" aria-label="'+(t.hidden?'显示':'隐藏')+(t.kind==='audio'?'音频':'视频')+'轨道">'+eye(t.hidden)+'</button>'+(t.kind==='visual'?'<button data-content-track-up="'+t.id+'" title="轨道上移">↑</button><button data-content-track-down="'+t.id+'" title="轨道下移">↓</button>':'')+(!t.clips.length?'<button data-content-track-delete="'+t.id+'" title="删除空轨道">×</button>':'')+'</div><div class="content-track-lane" data-content-track="'+t.id+'"><div class="content-track-limit" style="left:'+w.duration/span*100+'%"></div>'+
      t.clips.map(c=>{const asset=H.resolve(C.resolve(w,c)),name=clipName(w,c,asset);return '<button class="content-track-clip '+(t.kind==='audio'?'audio':c.presentation==='text'?'text-clip':'')+(c.id===w.selectedClipId?' selected':'')+'" data-content-clip="'+c.id+'" style="left:'+c.start/span*100+'%;width:'+c.duration/span*100+'%" aria-label="'+esc((t.kind==='audio'?'音频':'视频')+'：'+name)+'" title="'+esc(name)+' · '+clock(c.duration)+'" draggable="false"><div class="content-clip-heading"><span>'+esc(name)+'</span><small>'+clock(c.duration)+'</small></div>'+filmstrip(c,asset)+'<i class="content-edge start" data-content-edge="start"></i><i class="content-edge end" data-content-edge="end"></i></button>';}).join('')+'<div class="content-track-cursor"></div></div></div>'+gap(i+1,i===w.tracks.length-1);}
    if(!w.tracks.length)html+=gap(0,true);html+='</div>';body.innerHTML=html;size();body.scrollLeft=scroll.left;body.scrollTop=scroll.top;
    $('content-timeline-zoom').value=zoom;$('content-track-count').textContent=w.tracks.filter(t=>t.kind==='visual').length+' 条画面轨道 · '+w.tracks.filter(t=>t.kind==='audio').length+' 条音轨';
    $('content-ruler').onpointerdown=scrub;document.querySelectorAll('[data-content-track]').forEach(lane=>lane.onpointerdown=e=>{if(!e.target.closest('[data-content-clip]'))scrub(e);});
    document.querySelectorAll('[data-content-clip]').forEach(el=>el.onpointerdown=e=>dragClip(e,el));
    document.querySelectorAll('[data-content-hide]').forEach(b=>b.onclick=()=>{const t=w.tracks.find(t=>t.id===b.dataset.contentHide);t.hidden=!t.hidden;H.change('切换轨道显示');});
    document.querySelectorAll('[data-content-track-delete]').forEach(b=>b.onclick=()=>{w.tracks=w.tracks.filter(t=>t.id!==b.dataset.contentTrackDelete||t.clips.length);H.change('删除空轨道');});
    for(const [attr,direction] of [['contentTrackUp',-1],['contentTrackDown',1]])document.querySelectorAll('[data-'+attr.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())+']').forEach(b=>b.onclick=()=>{const index=w.tracks.findIndex(t=>t.id===b.dataset[attr]),next=index+direction;if(next<0||next>=w.tracks.length||w.tracks[next].kind==='audio')return;w.tracks.splice(next,0,w.tracks.splice(index,1)[0]);H.change('调整画面层级');});
    updatePosition();
  }
  function updatePosition(){if(!active())return;document.querySelectorAll('.content-track-cursor').forEach(el=>el.style.left=work().cursor/(Number($('content-ruler')?.dataset.span)||C.extent(work()))*100+'%');}
  function scrub(event){
    if(event.button!==0)return;event.preventDefault();H.stop();const w=work(),el=event.currentTarget;
    const set=e=>{w.cursor=C.clamp(time(e),0,w.duration-1/30);H.updatePosition();H.requestPaint();};set(event);el.setPointerCapture(event.pointerId);
    const done=()=>{el.removeEventListener('pointermove',set);el.removeEventListener('pointerup',done);el.removeEventListener('pointercancel',done);H.save();};el.addEventListener('pointermove',set);el.addEventListener('pointerup',done);el.addEventListener('pointercancel',done);
  }
  function dragClip(event,el){
    if(event.button!==0)return;finishDrag?.(true);event.preventDefault();event.stopPropagation();H.stop();const w=work(),c=C.all(w).find(c=>c.id===el.dataset.contentClip),before=C.copy(c),original=C.trackOf(w,c),kind=original.kind,edge=event.target.dataset.contentEdge,anchor=time(event,false),grab=anchor-before.start,span=C.extent(w);
    let moved=false,ghost=null,where=null,proposed=null;w.selectedClipId=c.id;H.renderInspector();document.querySelectorAll('[data-content-clip]').forEach(b=>b.classList.toggle('selected',b===el));
    const move=e=>{
      if(work()!==w){finishDrag?.(true);return;}moved=moved||Math.hypot(e.clientX-event.clientX,e.clientY-event.clientY)>3;if(!moved)return;autoScroll(e);
      if(edge){C.trim(w,c,edge,time(e,false)-anchor,before);el.style.left=c.start/span*100+'%';el.style.width=c.duration/span*100+'%';}
      else{
        where=target(e,kind);let requested=C.clamp(time(e,false)-grab,0,C.maxDuration-c.duration);if(!e.shiftKey)requested=Math.round(requested*30)/30;
        const track=w.tracks.find(t=>t.id===where?.trackId)||(kind==='audio'?w.tracks.find(t=>t.kind==='audio'):null);proposed=where?C.freeStart(track||{clips:[]},requested,c.duration,c):null;mark(where);
        if(!ghost){ghost=el.cloneNode(true);ghost.removeAttribute('data-content-clip');ghost.classList.add('content-clip-drag-ghost');el.style.opacity='.25';}
        const destination=where?.trackId?document.querySelector('[data-content-track="'+where.trackId+'"]'):document.querySelector('[data-content-track-insert="'+where?.index+'"] .content-gap-lane');
        if(destination&&proposed!==null){destination.append(ghost);ghost.style.display='';ghost.style.left=proposed/span*100+'%';ghost.style.width=c.duration/span*100+'%';c.start=proposed;}else ghost.style.display='none';
      }
      w.cursor=Math.min(c.start,w.duration-1/30);H.updatePosition();H.requestPaint();
    };
    const end=(cancel=false)=>{
      window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',up);window.removeEventListener('pointercancel',cancelled);finishDrag=null;ghost?.remove();el.style.opacity='';clearMarks();
      const modified=JSON.stringify(c)!==JSON.stringify(before);if(work()!==w||cancel){Object.assign(c,before);if(work()===w){H.renderInspector();render();H.requestPaint();}return;}
      if(edge&&moved&&modified){w.duration=Math.max(w.duration,c.start+c.duration);H.change('裁剪片段');return;}
      Object.assign(c,before);if(!edge&&moved&&where&&proposed!==null){const destination=C.place(w,c,where,proposed);if(destination&&(destination!==original||Math.abs(c.start-before.start)>1e-7)){w.cursor=c.start;H.change('移动片段或新增轨道');return;}}
      if(!edge&&moved&&where&&proposed===null)H.toast('当前轨道放不下这个片段，请换个位置或新增轨道');w.cursor=Math.min(c.start,w.duration-1/30);H.renderInspector();render();H.updatePosition();H.requestPaint();H.save();
    };
    const up=()=>end(false),cancelled=()=>end(true);finishDrag=end;window.addEventListener('pointermove',move);window.addEventListener('pointerup',up);window.addEventListener('pointercancel',cancelled);
  }
  function reset(){finishDrag?.(true);clearMarks();for(const entry of posters.values()){entry.dispose?.();if(entry.url)URL.revokeObjectURL(entry.url);}posters.clear();}
  window.ContentTimeline={mount,render,updatePosition,time,target,reset};
})();
