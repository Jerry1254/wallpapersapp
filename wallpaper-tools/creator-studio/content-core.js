/* Content compositions keep their template layout separate from replaceable media. */
(function(root){
  'use strict';
  const copy=value=>JSON.parse(JSON.stringify(value));
  const id=()=>`content-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,9)}`;
  const clamp=(n,a,b)=>Math.min(b,Math.max(a,n));
  const maxDuration=30;
  const end=w=>Math.max(0,...w.tracks.flatMap(t=>t.clips.map(c=>c.start+c.duration)));
  const extent=w=>Math.min(maxDuration,Math.max(w.duration+2,end(w)+2,5));
  const trackOf=(w,c)=>w.tracks.find(t=>t.clips.includes(c));
  function addTrack(w,kind='visual',index=0,keepEmpty=false){
    if(kind==='audio'&&w.tracks.some(t=>t.kind==='audio'))return w.tracks.find(t=>t.kind==='audio');
    const audio=w.tracks.findIndex(t=>t.kind==='audio'),track={id:id(),name:kind==='audio'?'音乐':'画面 '+(Math.max(0,...w.tracks.map(t=>Number(/^画面 (\d+)$/.exec(t.name)?.[1])||0))+1),kind,hidden:false,keepEmpty,clips:[]};
    w.tracks.splice(kind==='audio'?w.tracks.length:clamp(index,0,audio<0?w.tracks.length:audio),0,track);return track;
  }
  function pruneTracks(w){w.tracks=w.tracks.filter(t=>t.clips.length||t.keepEmpty);}
  function freeStart(track,start,duration,ignore=null){
    let next=Math.max(0,start);const clips=track.clips.filter(c=>c!==ignore).slice().sort((a,b)=>a.start-b.start);
    for(const c of clips)if(next<c.start+c.duration-1e-7&&next+duration>c.start+1e-7)next=c.start+c.duration;
    return next+duration<=maxDuration+1e-7?next:null;
  }
  function place(w,c,target={},start=c.start){
    const kind=c.presentation==='audio'?'audio':'visual',original=trackOf(w,c);let track=w.tracks.find(t=>t.id===target.trackId&&t.kind===kind);
    if(!track&&kind==='audio')track=w.tracks.find(t=>t.kind==='audio');
    const time=freeStart(track||{clips:[]},start,c.duration,c);if(time===null)return null;
    if(!track)track=addTrack(w,kind,target.index??0);
    if(original&&original!==track)original.clips=original.clips.filter(item=>item!==c);
    if(!track.clips.includes(c))track.clips.push(c);c.start=time;w.duration=Math.max(w.duration,time+c.duration);w.selectedClipId=c.id;pruneTracks(w);return track;
  }
  function clip(presentation,slot,start,duration,extra={}){
    return {id:id(),presentation,slot,assetId:null,start,duration,sourceIn:0,speed:1,fill:'loop',x:50,y:51,w:presentation==='phone'?52:100,h:presentation==='phone'?75:100,scale:1,panX:0,panY:0,rotation:0,opacity:1,animation:'none',text:'',...extra};
  }
  function create(type='gallery',style='showcase'){
    const w={id:id(),name:type==='gallery'?'壁纸展示图集':'动态样机视频',type,width:type==='gallery'?1080:720,height:type==='gallery'?1440:1280,duration:12,title:'把喜欢，留在屏幕里',subtitle:'倾境 · 每日壁纸',background:'#18202c',accent:'#bdd4ff',slots:{wallpaper:null,motion:null},pages:[],tracks:[],pageIndex:0,cursor:0,selectedClipId:null};
    if(type==='gallery'){
      w.pages=[
        {id:id(),name:'样机封面',clips:[clip('phone','wallpaper',0,1,{animation:'none'}),clip('text',null,0,1,{x:50,y:8,w:90,h:8,text:'$title'})]},
        {id:id(),name:'完整画面',clips:[clip('full','wallpaper',0,1,{w:84,h:83,y:49}),clip('text',null,0,1,{x:50,y:95,w:90,h:5,text:'$subtitle'})]},
        {id:id(),name:'局部细节',clips:[clip('detail','wallpaper',0,1,{w:88,h:72,scale:1.8,y:48}),clip('text',null,0,1,{x:50,y:90,w:90,h:7,text:'每一处细节，都值得停留'})]},
        {id:id(),name:'锁屏效果',clips:[clip('phone','wallpaper',0,1,{w:55,h:80,lock:true}),clip('text',null,0,1,{x:50,y:94,w:90,h:5,text:'$subtitle'})]}
      ];
      if(style==='minimal'){w.background='#eee9e2';w.accent='#444c58';w.title='屏幕里的小小世界';w.pages=w.pages.slice(0,3);}
    }else{
      w.tracks=[
        {id:id(),name:'画面 1',kind:'visual',hidden:false,clips:[clip('text',null,0,2,{x:50,y:13,w:90,h:9,text:'$title',animation:'fade'}),clip('text',null,9,3,{x:50,y:91,w:90,h:7,text:'$subtitle',animation:'fade'})]},
        {id:id(),name:'画面 2',kind:'visual',hidden:false,clips:[clip('phone','motion',2,7,{w:66,h:77,animation:'float',lock:false})]},
        {id:id(),name:'画面 3',kind:'visual',hidden:false,clips:[clip('full','motion',0,2,{animation:'push'}),clip('detail','wallpaper',9,3,{scale:1.7,animation:'push'})]}
      ];
      if(style==='detail'){w.name='细节展示视频';w.tracks[1].clips[0].duration=5;w.tracks[2].clips[1].start=7;w.tracks[2].clips[1].duration=5;}
    }
    w.selectedClipId=all(w)[0]?.id||null;return w;
  }
  const all=w=>w.type==='gallery'?(w.pages[w.pageIndex]?.clips||[]):w.tracks.flatMap(t=>t.clips);
  const selected=w=>all(w).find(c=>c.id===w.selectedClipId)||null;
  function active(w,time){return w.type==='gallery'?all(w):w.tracks.filter(t=>t.kind==='visual'&&!t.hidden).slice().reverse().flatMap(t=>t.clips.filter(c=>time>=c.start&&time<c.start+c.duration));}
  function resolve(w,c){return c.slot?w.slots[c.slot]:c.assetId;}
  function sourceTime(c,time,asset){
    const duration=Math.max(1/30,asset?.duration||1),begin=clamp(c.loopIn??c.sourceIn??0,0,Math.max(0,duration-1/30)),elapsed=Math.max(0,time-c.start)*(c.speed||1)+Math.max(0,(c.sourceIn||0)-begin),span=duration-begin;
    return c.fill==='loop'?begin+elapsed%span:Math.min(duration-1/30,begin+elapsed);
  }
  function replace(w,slot,assetId){if(!(slot in w.slots))return false;w.slots[slot]=assetId;return true;}
  function split(w,time){
    const c=selected(w);if(!c||w.type!=='video'||time-c.start<1/30||c.start+c.duration-time<1/30)return false;
    const track=w.tracks.find(t=>t.clips.includes(c)),right={...copy(c),id:id(),start:time,duration:c.start+c.duration-time,sourceIn:c.sourceIn+(time-c.start)*c.speed,loopIn:c.loopIn??c.sourceIn};
    c.duration=time-c.start;track.clips.push(right);w.selectedClipId=right.id;return true;
  }
  function trim(w,c,edge,delta,reference=c){
    const minimum=1/30;if(!Number.isFinite(delta))return false;
    if(edge==='start'){const shift=clamp(delta,-reference.start,reference.duration-minimum);c.start=reference.start+shift;c.duration=reference.duration-shift;c.sourceIn=Math.max(0,reference.sourceIn+shift*reference.speed);c.loopIn=reference.loopIn??reference.sourceIn;}
    else c.duration=clamp(reference.duration+delta,minimum,Math.max(minimum,maxDuration-reference.start));
    const peers=(trackOf(w,c)?.clips||[]).filter(item=>item!==c),before=Math.max(0,...peers.filter(item=>item.start<reference.start).map(item=>item.start+item.duration)),after=Math.min(maxDuration,...peers.filter(item=>item.start>=reference.start+reference.duration-1e-7).map(item=>item.start));
    if(edge==='start'&&c.start<before){const shift=before-reference.start;c.start=before;c.duration=reference.duration-shift;c.sourceIn=Math.max(0,reference.sourceIn+shift*reference.speed);}
    if(edge!=='start')c.duration=Math.max(minimum,Math.min(c.duration,after-c.start));
    return true;
  }
  function template(w,name){const snapshot=copy(w);delete snapshot.id;delete snapshot.selectedClipId;delete snapshot.generated;snapshot.cursor=0;snapshot.pageIndex=0;snapshot.slots=Object.fromEntries(Object.keys(w.slots).map(key=>[key,null]));return {id:id(),name,type:w.type,snapshot,updatedAt:new Date().toISOString()};}
  function instantiate(template,slots){const w=copy(template.snapshot);delete w.generated;w.id=id();w.name=template.name;w.templateId=template.id;w.templateVersion=template.updatedAt;w.slots={...w.slots,...slots};w.selectedClipId=all(w)[0]?.id||null;return w;}
  function normalize(data){
    if(!data)return {drafts:[],items:[],activeId:null};
    const value=copy(data),legacy=!Array.isArray(value.drafts);
    value.drafts=(value.drafts||value.works||[]).filter(w=>['gallery','video'].includes(w.type)&&Array.isArray(w.pages)&&Array.isArray(w.tracks));
    value.items=Array.isArray(value.items)?value.items:[];
    if(legacy)for(const w of value.drafts)if(w.generated?.assetIds?.length)value.items.push({id:'migrated-'+w.id,name:w.name,type:w.type,createdAt:w.generated.at,assetIds:copy(w.generated.assetIds),templateId:w.templateId||null});
    delete value.works;for(const w of value.drafts)delete w.generated;
    if(!value.drafts.some(w=>w.id===value.activeId))value.activeId=value.drafts[0]?.id||null;
    return value;
  }
  const api={id,copy,clamp,maxDuration,end,extent,trackOf,addTrack,pruneTracks,freeStart,place,clip,create,all,selected,active,resolve,sourceTime,replace,split,trim,template,instantiate,normalize};root.ContentCore=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
