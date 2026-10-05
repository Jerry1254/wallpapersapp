/* Serializable image-template scene. Coordinates are pixels, relative to the parent. */
(function(root){
  'use strict';
  const C=root.ContentCore,copy=C.copy,id=C.id;
  const identity=[1,0,0,1,0,0];
  function multiply(a,b){return [a[0]*b[0]+a[2]*b[1],a[1]*b[0]+a[3]*b[1],a[0]*b[2]+a[2]*b[3],a[1]*b[2]+a[3]*b[3],a[0]*b[4]+a[2]*b[5]+a[4],a[1]*b[4]+a[3]*b[5]+a[5]];}
  function point(m,p){return {x:m[0]*p.x+m[2]*p.y+m[4],y:m[1]*p.x+m[3]*p.y+m[5]};}
  function inverse(m){const d=m[0]*m[3]-m[1]*m[2];return [m[3]/d,-m[1]/d,-m[2]/d,m[0]/d,(m[2]*m[5]-m[3]*m[4])/d,(m[1]*m[4]-m[0]*m[5])/d];}
  function transform(n){const a=(n.rotation||0)*Math.PI/180,c=Math.cos(a),s=Math.sin(a),cx=n.width/2,cy=n.height/2;return [c,s,-s,c,n.x+cx-c*cx+s*cy,n.y+cy-s*cx-c*cy];}
  function node(type,extra={}){return {id:id(),type,name:({image:'图片',text:'文字',rect:'矩形',group:'分组',instance:'组件实例'})[type]||type,x:0,y:0,width:400,height:300,rotation:0,opacity:1,visible:true,locked:false,radius:0,scale:1,panX:0,panY:0,...extra};}
  function frame(width=1080,height=1440,index=0){return {id:id(),name:'画框 '+(index+1),x:(index%2)*(width+180),y:Math.floor(index/2)*(height+180),width,height,background:'#18202c',clip:true,nodes:[]};}
  function ensure(w){
    if(w.gallery?.version===1)return w.gallery;
    const componentId=id(),size=1080;
    const component={id:componentId,name:'主壁纸',x:-size-220,y:0,width:size,height:size,clip:true,nodes:[node('image',{name:'原图 · 替换这里',width:size,height:size,slot:'wallpaper',fit:'cover'})]};
    const frames=(w.pages.length?w.pages:[{name:'画框 1',clips:[]}]).map((p,i)=>{
      const f=frame(w.width,w.height,i);f.id=p.id||f.id;f.name=p.name;f.background=w.background;
      f.nodes=p.clips.map(c=>{let type=c.presentation==='text'?'text':c.slot==='wallpaper'?'instance':'image';return node(type,{name:c.presentation==='text'?'文字':c.presentation==='phone'?'手机样机':'壁纸画面',x:(c.x-c.w/2)/100*f.width,y:(c.y-c.h/2)/100*f.height,width:c.w/100*f.width,height:c.h/100*f.height,rotation:c.rotation,opacity:c.opacity,scale:c.scale,panX:c.panX,panY:c.panY,componentId:type==='instance'?componentId:null,slot:type==='instance'?null:c.slot,assetId:c.assetId,mask:c.presentation==='phone'?'phone':'none',lock:c.lock,text:c.text==='$title'?w.title:c.text==='$subtitle'?w.subtitle:c.text,fontFamily:'system-ui',fontSize:f.width*(c.text==='$title'?.045:.028),fontWeight:600,color:w.accent,align:'center'});});
      return f;
    });
    w.gallery={version:1,frames,components:{[componentId]:component},selectedSurface:frames[0].id,selection:[],view:null,preset:{width:w.width,height:w.height}};
    return w.gallery;
  }
  const surfaces=g=>[...g.frames,...Object.values(g.components)];
  const surface=(g,key=g.selectedSurface)=>surfaces(g).find(f=>f.id===key);
  function find(nodes,key,parent=null,matrix=identity){for(const n of nodes){const m=multiply(matrix,transform(n));if(n.id===key)return {node:n,list:nodes,parent,matrix:m,parentMatrix:matrix};if(n.children){const r=find(n.children,key,n,m);if(r)return r;}}return null;}
  function walk(nodes,fn){for(const n of nodes){fn(n);if(n.children)walk(n.children,fn);}}
  function bounds(n,m=transform(n)){const p=[{x:0,y:0},{x:n.width,y:0},{x:0,y:n.height},{x:n.width,y:n.height}].map(p=>point(m,p));const x=Math.min(...p.map(p=>p.x)),y=Math.min(...p.map(p=>p.y));return {x,y,width:Math.max(...p.map(p=>p.x))-x,height:Math.max(...p.map(p=>p.y))-y};}
  function union(rects){const x=Math.min(...rects.map(r=>r.x)),y=Math.min(...rects.map(r=>r.y));return {x,y,width:Math.max(...rects.map(r=>r.x+r.width))-x,height:Math.max(...rects.map(r=>r.y+r.height))-y};}
  function hit(nodes,p,deep=false){for(const n of nodes.slice().reverse()){if(!n.visible||n.locked)continue;const q=point(inverse(transform(n)),p);if(n.children&&(!(n.clip??n.effectiveClip)||q.x>=0&&q.y>=0&&q.x<=n.width&&q.y<=n.height)){const child=hit(n.children,q,deep);if(child)return deep?child:n;}if(q.x>=0&&q.y>=0&&q.x<=n.width&&q.y<=n.height)return n;}return null;}
  function selected(g){return (g.selection||[]).map(key=>{for(const f of surfaces(g)){const r=find(f.nodes,key);if(r)return {...r,surface:f};}}).filter(Boolean);}
  function roots(g){const list=selected(g),ids=new Set(list.map(r=>r.node.id));return list.filter(r=>{let p=r.parent;while(p){if(ids.has(p.id))return false;p=find(r.surface.nodes,p.id)?.parent;}return true;});}
  const selectedSurfaces=g=>(g.surfaceSelection||[]).map(id=>surface(g,id)).filter(Boolean);
  const worldBounds=r=>{const b=bounds(r.node,r.matrix);return {...b,x:b.x+r.surface.x,y:b.y+r.surface.y};};
  function scaleEffects(effects,sx,sy){return (effects||[]).map(e=>({...copy(e),offsetX:(e.offsetX||0)*sx,offsetY:(e.offsetY||0)*sy,blur:(e.blur||0)*Math.min(sx,sy),spread:(e.spread||0)*Math.min(sx,sy)}));}
  // Instance children are editable proxies. Only changed properties are serialized;
  // unmodified properties continue to come from the shared component.
  const instanceBases=new WeakMap();
  function instanceNodes(g,n,width=n.width,height=n.height,scoped=true,stack=new Set()){
    const def=g.components[n.componentId];if(!def||stack.has(def.id))return [];
    const sx=width/def.width,sy=height/def.height,small=Math.min(sx,sy);
    const adapt=nodes=>nodes.filter(source=>!n.overrides?.[source.id]?._removed).map(source=>{const out=copy(source),override=n.overrides?.[source.id]||{};Object.assign(out,override);out.sourceId=source.id;out.id=scoped?n.id+'::'+source.id:source.id;for(const key of ['x','width'])out[key]*=sx;for(const key of ['y','height'])out[key]*=sy;for(const key of ['fontSize','letterSpacing','radius'])if(out[key])out[key]*=small;if(out.effects)out.effects=scaleEffects(out.effects,sx,sy);if(source.children)out.children=adapt(source.children);if(out.type==='image'){out.scale=(out.scale||1)*(n.scale||1);out.panX=(out.panX||0)+(n.panX||0);out.panY=(out.panY||0)+(n.panY||0);}return out;});
    const nodes=copy(def.nodes);for(const local of n.localNodes||[]){const parent=local.localParentId?find(nodes,local.localParentId)?.node:null;if(parent){(parent.children||=[]).push(copy(local));}else nodes.push(copy(local));}return adapt(nodes);
  }
  function instanceViewport(n){if(n.mask!=='phone')return {x:0,y:0,width:n.width,height:n.height};const width=Math.min(n.width,n.height*.46),height=width/.46,border=width*.024;return {x:(n.width-width)/2+border,y:(n.height-height)/2+border,width:width-border*2,height:height-border*2};}
  function refreshInstances(g){
    function visit(nodes,stack=new Set()){for(const n of nodes){if(n.type==='instance'){const def=g.components[n.componentId];if(!def||stack.has(def.id))continue;const v=instanceViewport(n),children=instanceNodes(g,n,v.width,v.height);children.forEach(c=>{c.x+=v.x;c.y+=v.y;});Object.defineProperty(n,'effectiveClip',{value:(n.clip??def.clip)!==false,writable:true,configurable:true,enumerable:false});Object.defineProperty(n,'children',{value:children,writable:true,configurable:true,enumerable:false});instanceBases.set(n,copy(children));visit(children,new Set([...stack,def.id]));}else if(n.children)visit(n.children,stack);}}
    for(const f of surfaces(g))visit(f.nodes);
  }
  function captureOverrides(g){
    const ignored=new Set(['id','sourceId','children','localParentId']);
    function visit(nodes){for(const n of nodes){if(n.type==='instance'&&instanceBases.has(n)){
      const def=g.components[n.componentId],v=instanceViewport(n),sx=v.width/def.width,sy=v.height/def.height,small=Math.min(sx,sy),base=instanceBases.get(n);
      const normalize=(key,value,child,depth)=>{if(key==='effects')return (value||[]).map(e=>({...copy(e),offsetX:(e.offsetX||0)/sx,offsetY:(e.offsetY||0)/sy,blur:(e.blur||0)/small,spread:(e.spread||0)/small}));if(key==='scale'&&child.type==='image')return value/(n.scale||1);if(['panX','panY'].includes(key)&&child.type==='image')return value-(n[key]||0);if(['x','width'].includes(key))return (value-(key==='x'&&!depth?v.x:0))/sx;if(['y','height'].includes(key))return (value-(key==='y'&&!depth?v.y:0))/sy;if(['fontSize','letterSpacing','radius'].includes(key))return value/small;return value;};
      const canonical=(child,depth)=>{const out=copy(child);delete out.sourceId;for(const key of Object.keys(out))if(!ignored.has(key))out[key]=normalize(key,out[key],child,depth);if(child.children)out.children=child.children.map(c=>canonical(c,depth+1));return out;};
      const compare=(current,prior,depth=0,parentId=null)=>{
        for(const old of prior)if(!current.some(c=>c.id===old.id)){(n.overrides||={})[old.sourceId]||={};n.overrides[old.sourceId]._removed=true;}
        for(const child of current){const old=prior.find(b=>b.id===child.id);if(!old){const local=canonical(child,depth);local.localParentId=parentId;(n.localNodes||=[]).push(local);continue;}if(!child.sourceId)continue;
          const original=find([...def.nodes,...(n.localNodes||[])],child.sourceId)?.node;
          for(const key of new Set([...Object.keys(old),...Object.keys(child)])){if(ignored.has(key)||JSON.stringify(old[key])===JSON.stringify(child[key]))continue;const value=normalize(key,copy(child[key]??null),child,depth);(n.overrides||={})[child.sourceId]||={};if(original&&(typeof original[key]==='number'&&typeof value==='number'?Math.abs(original[key]-value)<1e-7:JSON.stringify(original[key])===JSON.stringify(value)))delete n.overrides[child.sourceId][key];else n.overrides[child.sourceId][key]=value;if(!Object.keys(n.overrides[child.sourceId]).length)delete n.overrides[child.sourceId];}
          if(child.children&&old.children)compare(child.children,old.children,depth+1,child.sourceId);
        }
      };
      visit(n.children);compare(n.children,base);instanceBases.set(n,copy(n.children));
    }else if(n.children)visit(n.children);}}
    for(const f of surfaces(g))visit(f.nodes);
  }
  function group(g){const rows=roots(g);if(rows.length<2||rows.some(r=>r.list!==rows[0].list))return null;const b=union(rows.map(r=>bounds(r.node))),list=rows[0].list,ids=new Set(rows.map(r=>r.node.id)),children=list.filter(n=>ids.has(n.id));children.forEach(n=>{n.x-=b.x;n.y-=b.y;});const n=node('group',{...b,children}),index=Math.max(...children.map(n=>list.indexOf(n)));list.splice(index+1,0,n);for(let i=list.length-1;i>=0;i--)if(ids.has(list[i].id))list.splice(i,1);g.selection=[n.id];return n;}
  function toggleMask(g){const rows=roots(g);if(rows.length===1&&rows[0].node.type==='group'&&rows[0].node.children?.length>=2){const n=rows[0].node;n.mask=n.mask==='alpha'?null:'alpha';return n;}const n=group(g);if(n){n.mask='alpha';n.name='蒙版组';}return n;}
  function ungroup(g){const rows=roots(g).filter(r=>r.node.type==='group');for(const r of rows){const p=r.node,m=transform(p);const children=p.children.map(n=>{const center=point(m,{x:n.x+n.width/2,y:n.y+n.height/2});return {...n,x:center.x-n.width/2,y:center.y-n.height/2,rotation:(n.rotation||0)+(p.rotation||0),opacity:n.opacity*p.opacity,visible:n.visible&&p.visible};});r.list.splice(r.list.indexOf(p),1,...children);g.selection=children.map(n=>n.id);}return rows.length;}
  function cloneNode(n){const result=copy(n);walk([result],x=>{x.id=id();delete x.sourceId;});return result;}
  function duplicate(g){const rows=roots(g);g.selection=rows.map(r=>{const n=cloneNode(r.node);n.x+=32;n.y+=32;r.list.splice(r.list.indexOf(r.node)+1,0,n);return n.id;});return g.selection;}
  function remove(g){for(const r of roots(g))r.list.splice(r.list.indexOf(r.node),1);g.selection=[];}
  function makeComponent(g,name){let rows=roots(g);if(!rows.length)return null;if(rows.length>1){if(!group(g))return null;rows=roots(g);}const r=rows[0],n=r.node,componentId=id(),original=cloneNode(n);original.x=0;original.y=0;original.rotation=0;original.opacity=1;original.blendMode=n.type==='group'?'pass-through':'source-over';
    const def={id:componentId,name:name||n.name,x:-Math.max(n.width,600)-220,y:Object.values(g.components).reduce((max,c)=>Math.max(max,c.y+c.height+160),0),width:n.width,height:n.height,clip:true,nodes:[original]};
    g.components[componentId]=def;const instance=node('instance',{name:def.name,componentId,x:n.x,y:n.y,width:n.width,height:n.height,rotation:n.rotation,opacity:n.opacity,blendMode:n.blendMode||'pass-through',mask:'none'});r.list.splice(r.list.indexOf(n),1,instance);g.selection=[instance.id];return def;
  }
  function instances(g,key){const list=[];for(const f of surfaces(g))walk(f.nodes,n=>{if(n.componentId===key)list.push(n);});return list;}
  function wouldCycle(g,container,key,seen=new Set()){if(container===key)return true;if(seen.has(key))return false;seen.add(key);let cyclic=false;walk(g.components[key]?.nodes||[],n=>{if(n.componentId&&wouldCycle(g,container,n.componentId,seen))cyclic=true;});return cyclic;}
  function addInstance(g,key){const f=surface(g),c=g.components[key];if(!f||!c||wouldCycle(g,f.id,key))return null;const n=node('instance',{name:c.name,componentId:key,x:40,y:40,width:Math.min(c.width,f.width*.7),height:Math.min(c.width,f.width*.7)/c.width*c.height,mask:'none'});f.nodes.push(n);g.selection=[n.id];return n;}
  function assets(w){const ids=new Set();if(w.gallery)for(const f of surfaces(w.gallery))walk(f.nodes,n=>{const asset=n.slot?w.slots[n.slot]:n.assetId;if(asset)ids.add(asset);if(n.type==='instance'){for(const o of Object.values(n.overrides||{})){const id=o.slot?w.slots[o.slot]:o.assetId;if(id)ids.add(id);}walk(n.localNodes||[],child=>{const id=child.slot?w.slots[child.slot]:child.assetId;if(id)ids.add(id);});}});return [...ids];}
  function resizeGroup(n,width,height){const sx=width/n.width,sy=height/n.height;function scale(nodes){for(const c of nodes){c.x*=sx;c.y*=sy;c.width*=sx;c.height*=sy;if(c.fontSize)c.fontSize*=Math.min(sx,sy);if(c.letterSpacing)c.letterSpacing*=Math.min(sx,sy);if(c.effects)c.effects=scaleEffects(c.effects,sx,sy);if(c.children)scale(c.children);}}if(n.children)scale(n.children);n.width=width;n.height=height;}
  const api={ensure,node,frame,surfaces,surface,find,walk,bounds,union,hit,selected,selectedSurfaces,worldBounds,roots,instanceNodes,instanceViewport,refreshInstances,captureOverrides,group,ungroup,toggleMask,duplicate,remove,makeComponent,instances,wouldCycle,addInstance,assets,resizeGroup,scaleEffects,cloneNode,multiply,point,inverse,transform,identity};
  root.GalleryCore=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
