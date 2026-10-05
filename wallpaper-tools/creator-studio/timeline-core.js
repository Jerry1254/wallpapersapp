/* A sequence uses edited time; every clip retains its own original source range. */
(function (root) {
  'use strict';
  const FPS = 30, MIN = 1 / FPS, EPS = 1e-8;
  const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));
  let serial = 0;
  const id = () => `clip-${Date.now().toString(36)}-${(++serial).toString(36)}`;
  const clip = (start, end, speed = 1) => ({ id: id(), start, end, speed });
  const clipDuration = c => (c.end - c.start) / c.speed;
  function create(sourceDuration, frames = 24, assetId = 'demo-video') {
    const first = {...clip(0, sourceDuration),assetId,kind:'video'};
    return {width:1080,height:1920,scale:1,x:0,y:0,frames,clips:[first],selectedClipId:first.id,cursor:0,zoom:100,coverTime:null};
  }
  function normalize(project, sourceDuration, frames = 24, resolveDuration = () => sourceDuration) {
    const p = {...create(sourceDuration, frames), ...project};
    if (!Array.isArray(project?.clips)) {
      const start = clamp(Number.isFinite(project?.start) ? project.start : 0, 0, sourceDuration - MIN);
      const end = clamp(Number.isFinite(project?.end) ? project.end : sourceDuration, start + MIN, sourceDuration);
      const c = {...clip(start, end, Number.isFinite(project?.speed) && project.speed > 0 ? project.speed : 1),assetId:project?.assetId||'demo-video',kind:'video'};
      p.clips = [c]; p.selectedClipId = c.id; p.cursor = 0;
    } else {
      p.clips = project.clips.filter(c => Number.isFinite(c.start) && Number.isFinite(c.end) && Number.isFinite(c.speed) && c.speed >= .01 && c.speed <= 64 && c.end - c.start >= MIN - EPS && c.start >= 0 && (c.kind==='image'||c.end <= resolveDuration(c.assetId) + EPS)).map(c => ({...c,id:c.id||id(),assetId:c.assetId||'demo-video',kind:c.kind==='image'?'image':'video'}));
    }
    if (!p.clips.some(c => c.id === p.selectedClipId)) p.selectedClipId = p.clips[0]?.id || null;
    p.cursor = clamp(Number.isFinite(p.cursor) ? p.cursor : 0, 0, total(p));
    p.zoom = clamp(Number.isFinite(p.zoom) ? p.zoom : 100, 100, 300);
    delete p.start; delete p.end; delete p.speed;
    return p;
  }
  function segments(p) {
    let cursor = 0;
    return p.clips.map((c, index) => {
      const start = cursor, duration = clipDuration(c); cursor += duration;
      return {clip:c,index,start,end:cursor,duration};
    });
  }
  const total = p => p.clips.reduce((sum,c) => sum + clipDuration(c), 0);
  function at(p, time) {
    const rows = segments(p); if (!rows.length) return null;
    const t = clamp(time, 0, total(p));
    const s = rows.find(s => t < s.end - EPS) || rows.at(-1);
    return {...s, sourceTime:s.clip.kind==='image'?0:clamp(s.clip.start + (t-s.start)*s.clip.speed, s.clip.start, s.clip.end)};
  }
  const selected = p => p.clips.find(c => c.id === p.selectedClipId) || null;
  function select(p, clipId) {
    const s = segments(p).find(s => s.clip.id === clipId); if (!s) return false;
    p.selectedClipId = clipId; p.cursor = s.start; return true;
  }
  function split(p, time) {
    const s = at(p, time); if (!s) return false;
    const cut = Math.round((s.clip.start+(clamp(time,s.start,s.end)-s.start)*s.clip.speed) * FPS) / FPS;
    if (cut-s.clip.start < MIN-EPS || s.clip.end-cut < MIN-EPS) return false;
    const right = {...s.clip, ...clip(cut, s.clip.end, s.clip.speed)};
    s.clip.end = cut; p.clips.splice(s.index+1, 0, right);
    p.selectedClipId = right.id; p.cursor = s.start + clipDuration(s.clip); return true;
  }
  function remove(p, clipId) {
    const index = p.clips.findIndex(c => c.id === clipId); if (index < 0) return false;
    p.clips.splice(index,1);
    const next = p.clips[Math.min(index,p.clips.length-1)];
    if(next)select(p,next.id);else{p.selectedClipId=null;p.cursor=0;}
    return true;
  }
  function duplicate(p, clipId) {
    const index = p.clips.findIndex(c => c.id === clipId); if(index<0)return false;
    return paste(p,p.clips[index],index+1);
  }
  function paste(p, source, index = p.clips.length) {
    if(!source?.assetId||!Number.isFinite(source.start)||!Number.isFinite(source.end)||source.start<0||source.end-source.start<MIN-EPS||!Number.isFinite(source.speed)||source.speed<.01||source.speed>64)return false;
    const copy={...source,...clip(source.start,source.end,source.speed)};
    p.clips.splice(clamp(index,0,p.clips.length),0,copy);select(p,copy.id);return true;
  }
  function move(p, clipId, newIndex) {
    const index = p.clips.findIndex(c => c.id === clipId); if(index<0)return false;
    const target = clamp(newIndex,0,p.clips.length-1); if(target===index)return false;
    p.clips.splice(target,0,p.clips.splice(index,1)[0]);select(p,clipId);return true;
  }
  function insert(p, assetId, sourceDuration, index = p.clips.length, kind = 'video') {
    if(kind==='image')sourceDuration=10/FPS;
    if(!assetId || !Number.isFinite(sourceDuration) || sourceDuration < MIN)return false;
    const c={...clip(0,sourceDuration),assetId,kind};
    p.clips.splice(clamp(index,0,p.clips.length),0,c);select(p,c.id);return true;
  }
  function stillFrames(p, clipId, frames) {
    const c=p.clips.find(c=>c.id===clipId);
    if(!c||c.kind!=='image'||!Number.isInteger(frames)||frames<1||frames>30000)return false;
    if(Math.abs(clipDuration(c)*FPS-frames)<EPS)return false;
    c.end=c.start+frames/FPS;c.speed=1;select(p,clipId);return true;
  }
  function trim(p, clipId, start, end, sourceDuration) {
    const c = p.clips.find(c => c.id===clipId); if(!c||!Number.isFinite(start)||!Number.isFinite(end))return false;
    const a = clamp(start,0,sourceDuration-MIN), b = clamp(end,a+MIN,sourceDuration);
    if(Math.abs(a-c.start)<EPS&&Math.abs(b-c.end)<EPS)return false;
    c.start=a;c.end=b;select(p,clipId);return true;
  }
  function trimEdge(p, clipId, edge, delta, sourceDuration, reference) {
    const c=p.clips.find(c=>c.id===clipId),initial=reference||c;
    if(!c||c.kind==='image'||!['start','end'].includes(edge)||!Number.isFinite(delta)||!Number.isFinite(sourceDuration)||sourceDuration<MIN)return false;
    // Pointer movement is measured in edited seconds; remove source frames at the existing speed.
    const offset=Math.round(delta*initial.speed*FPS)/FPS;
    const start=edge==='start'?clamp(initial.start+offset,0,initial.end-MIN):initial.start;
    const end=edge==='end'?clamp(initial.end+offset,initial.start+MIN,sourceDuration):initial.end;
    return trim(p,clipId,start,end,sourceDuration);
  }
  function speed(p, clipId, value) {
    const c=p.clips.find(c=>c.id===clipId);if(!c||!Number.isFinite(value)||value<.01||value>64)return false;
    if(Math.abs(c.speed-value)<EPS)return false;
    const source=at(p,p.cursor),sourceOffset=source?.clip.id===clipId?source.sourceTime-c.start:0;
    c.speed=value;p.selectedClipId=clipId;
    p.cursor=segments(p).find(s=>s.clip.id===clipId).start+sourceOffset/value;return true;
  }
  function snapshot(p) {
    const {undo,redo,...values}=p;return JSON.parse(JSON.stringify(values));
  }
  const api={FPS,MIN,create,normalize,clipDuration,segments,total,at,selected,select,split,remove,duplicate,paste,move,insert,stillFrames,trim,trimEdge,speed,snapshot};
  root.TimelineCore=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
