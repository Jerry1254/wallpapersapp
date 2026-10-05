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
    const component={id:componentId,name:'主壁纸',x:-size-220,y:0,width:size,height:size,nodes:[node('image',{name:'原图 · 替换这里',width:size,height:size,slot:'wallpaper',fit:'cover'})]};
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
  function hit(nodes,p,deep=false){for(const n of nodes.slice().reverse()){if(!n.visible||n.locked)continue;const q=point(inverse(transform(n)),p);if(n.children){const child=hit(n.children,q,deep);if(child)return deep?child:n;}if(q.x>=0&&q.y>=0&&q.x<=n.width&&q.y<=n.height)return n;}return null;}
  function selected(g){const f=surface(g);return (g.selection||[]).map(key=>find(f?.nodes||[],key)).filter(Boolean);}
  function roots(g){const list=selected(g),ids=new Set(list.map(r=>r.node.id));return list.filter(r=>{let p=r.parent;while(p){if(ids.has(p.id))return false;p=find(surface(g).nodes,p.id)?.parent;}return true;});}
  function group(g){const rows=roots(g);if(rows.length<2||rows.some(r=>r.list!==rows[0].list))return null;const b=union(rows.map(r=>bounds(r.node))),list=rows[0].list,ids=new Set(rows.map(r=>r.node.id)),children=list.filter(n=>ids.has(n.id));children.forEach(n=>{n.x-=b.x;n.y-=b.y;});const n=node('group',{...b,children}),index=Math.max(...children.map(n=>list.indexOf(n)));list.splice(index+1,0,n);for(let i=list.length-1;i>=0;i--)if(ids.has(list[i].id))list.splice(i,1);g.selection=[n.id];return n;}
  function ungroup(g){const rows=roots(g).filter(r=>r.node.type==='group');for(const r of rows){const p=r.node,m=transform(p);const children=p.children.map(n=>{const center=point(m,{x:n.x+n.width/2,y:n.y+n.height/2});return {...n,x:center.x-n.width/2,y:center.y-n.height/2,rotation:(n.rotation||0)+(p.rotation||0),opacity:n.opacity*p.opacity,visible:n.visible&&p.visible};});r.list.splice(r.list.indexOf(p),1,...children);g.selection=children.map(n=>n.id);}return rows.length;}
  function cloneNode(n){const result=copy(n);walk([result],x=>x.id=id());return result;}
  function duplicate(g){const rows=roots(g);g.selection=rows.map(r=>{const n=cloneNode(r.node);n.x+=32;n.y+=32;r.list.splice(r.list.indexOf(r.node)+1,0,n);return n.id;});return g.selection;}
  function remove(g){for(const r of roots(g))r.list.splice(r.list.indexOf(r.node),1);g.selection=[];}
  function makeComponent(g,name){let rows=roots(g);if(!rows.length)return null;if(rows.length>1){if(!group(g))return null;rows=roots(g);}const r=rows[0],n=r.node,componentId=id(),original=cloneNode(n);original.x=0;original.y=0;original.rotation=0;original.opacity=1;
    const def={id:componentId,name:name||n.name,x:-Math.max(n.width,600)-220,y:Object.values(g.components).reduce((max,c)=>Math.max(max,c.y+c.height+160),0),width:n.width,height:n.height,nodes:[original]};
    g.components[componentId]=def;const instance=node('instance',{name:def.name,componentId,x:n.x,y:n.y,width:n.width,height:n.height,rotation:n.rotation,opacity:n.opacity,mask:'none'});r.list.splice(r.list.indexOf(n),1,instance);g.selection=[instance.id];return def;
  }
  function instances(g,key){const list=[];for(const f of surfaces(g))walk(f.nodes,n=>{if(n.componentId===key)list.push(n);});return list;}
  function wouldCycle(g,container,key,seen=new Set()){if(container===key)return true;if(seen.has(key))return false;seen.add(key);let cyclic=false;walk(g.components[key]?.nodes||[],n=>{if(n.componentId&&wouldCycle(g,container,n.componentId,seen))cyclic=true;});return cyclic;}
  function addInstance(g,key){const f=surface(g),c=g.components[key];if(!f||!c||wouldCycle(g,f.id,key))return null;const n=node('instance',{name:c.name,componentId:key,x:40,y:40,width:Math.min(c.width,f.width*.7),height:Math.min(c.width,f.width*.7)/c.width*c.height,mask:'none'});f.nodes.push(n);g.selection=[n.id];return n;}
  function assets(w){const ids=new Set();if(w.gallery)for(const f of surfaces(w.gallery))walk(f.nodes,n=>{const asset=n.slot?w.slots[n.slot]:n.assetId;if(asset)ids.add(asset);});return [...ids];}
  function resizeGroup(n,width,height){const sx=width/n.width,sy=height/n.height;function scale(nodes){for(const c of nodes){c.x*=sx;c.y*=sy;c.width*=sx;c.height*=sy;if(c.fontSize)c.fontSize*=Math.min(sx,sy);if(c.letterSpacing)c.letterSpacing*=Math.min(sx,sy);if(c.children)scale(c.children);}}if(n.children)scale(n.children);n.width=width;n.height=height;}
  const api={ensure,node,frame,surfaces,surface,find,walk,bounds,union,hit,selected,roots,group,ungroup,duplicate,remove,makeComponent,instances,wouldCycle,addInstance,assets,resizeGroup,cloneNode,multiply,point,inverse,transform,identity};
  root.GalleryCore=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
