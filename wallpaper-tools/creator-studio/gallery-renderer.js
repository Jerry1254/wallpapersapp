/* Image templates use the same scene renderer for the editor and exported PNGs. */
(() => {
  'use strict';
  const G=window.GalleryCore,R=window.ContentRenderer,E=window.GalleryEditing;
  function round(ctx,w,h,r){ctx.beginPath();ctx.roundRect(0,0,w,h,Math.max(0,Math.min(r||0,w/2,h/2)));}
  function image(ctx,source,n,extra={}){
    const iw=source.videoWidth||source.naturalWidth||source.width,ih=source.videoHeight||source.naturalHeight||source.height;
    const ratio=(n.fit==='contain'?Math.min:Math.max)(n.width/iw,n.height/ih)*(n.scale||1)*(extra.scale||1),w=iw*ratio,h=ih*ratio;
    ctx.drawImage(source,(n.width-w)/2+(n.panX||0)/100*n.width,(n.height-h)/2+(n.panY||0)/100*n.height,w,h);
  }
  function text(ctx,n){
    ctx.font=(n.fontWeight||400)+' '+(n.fontSize||40)+'px '+E.fontFamily(n.fontFamily);
    ctx.letterSpacing=(n.letterSpacing||0)+'px';const layout=E.textLayout(n,value=>ctx.measureText(value).width),offset=n.verticalAlign==='center'?(n.height-layout.lines.length*layout.lineHeight)/2:n.verticalAlign==='bottom'?n.height-layout.lines.length*layout.lineHeight:0,x=n.align==='center'?n.width/2:n.align==='right'?n.width:0;
    ctx.save();ctx.beginPath();ctx.rect(0,0,n.width,n.height);ctx.clip();ctx.fillStyle=E.fill(ctx,n.color,n.width,n.height);ctx.textBaseline='top';ctx.textAlign=n.align||'left';
    layout.lines.forEach((line,i)=>ctx.fillText(line,x,offset+i*layout.lineHeight));ctx.restore();
  }
  function buffer(area,ratio=1){const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.ceil(area.width*ratio));canvas.height=Math.max(1,Math.ceil(area.height*ratio));return canvas;}
  function effectArea(b,effects){let pad=0;for(const e of effects||[]){if(e.visible===false||e.type==='inner-shadow')continue;pad=Math.max(pad,Math.max(Math.abs(e.offsetX||0),Math.abs(e.offsetY||0))+Math.max(0,e.spread||0)+(e.blur||0)*3);}return {x:b.x-pad,y:b.y-pad,width:b.width+pad*2,height:b.height+pad*2};}
  // A separable alpha dilation/erosion makes spread work for transparent images as well as shapes.
  function spreadAlpha(source,amount){
    const r=Math.round(Math.abs(amount));if(!r)return source;const canvas=buffer({width:source.width,height:source.height}),ctx=canvas.getContext('2d'),data=source.getContext('2d').getImageData(0,0,source.width,source.height),w=source.width,h=source.height,a=new Uint8ClampedArray(w*h),tmp=new Uint8ClampedArray(w*h),out=new Uint8ClampedArray(w*h),erode=amount<0;
    for(let i=0;i<a.length;i++)a[i]=data.data[i*4+3];
    function pass(input,output,horizontal){const count=horizontal?h:w,length=horizontal?w:h,queue=new Int32Array(length),at=(line,pos)=>horizontal?line*w+pos:pos*w+line;for(let line=0;line<count;line++){let head=0,tail=0,next=0;for(let pos=0;pos<length;pos++){const right=Math.min(length-1,pos+r);while(next<=right){const value=input[at(line,next)];while(tail>head&&(erode?input[at(line,queue[tail-1])]>=value:input[at(line,queue[tail-1])]<=value))tail--;queue[tail++]=next++;}while(head<tail&&queue[head]<pos-r)head++;output[at(line,pos)]=erode&&(pos<r||pos+r>=length)?0:input[at(line,queue[head])];}}}
    pass(a,tmp,true);pass(tmp,out,false);for(let i=0;i<out.length;i++){data.data[i*4]=data.data[i*4+1]=data.data[i*4+2]=255;data.data[i*4+3]=out[i];}ctx.putImageData(data,0,0);return canvas;
  }
  function tint(canvas,value,n,area,ratio){const ctx=canvas.getContext('2d');ctx.save();ctx.globalCompositeOperation='source-in';ctx.scale(ratio,ratio);ctx.translate(-area.x,-area.y);ctx.fillStyle=E.fill(ctx,value,n.width,n.height);ctx.fillRect(area.x,area.y,area.width,area.height);ctx.restore();}
  function applyEffects(source,n,area,ratio){
    let output=buffer({width:source.width,height:source.height}),ctx=output.getContext('2d');ctx.drawImage(source,0,0);
    for(const effect of n.effects||[]){if(effect.visible===false||effect.type==='layer-blur')continue;const shadow=buffer({width:source.width,height:source.height}),s=shadow.getContext('2d'),alpha=spreadAlpha(source,(effect.spread||0)*ratio*(effect.type==='inner-shadow'?-1:1));s.filter='blur('+Math.max(0,(effect.blur||0)*ratio)+'px)';s.drawImage(alpha,(effect.offsetX||0)*ratio,(effect.offsetY||0)*ratio);s.filter='none';
      if(effect.type==='inner-shadow'){const inverse=buffer({width:source.width,height:source.height}),ic=inverse.getContext('2d');ic.fillStyle='#fff';ic.fillRect(0,0,inverse.width,inverse.height);ic.globalCompositeOperation='destination-out';ic.drawImage(shadow,0,0);ic.globalCompositeOperation='destination-in';ic.drawImage(source,0,0);tint(inverse,effect.color||'#000000',n,area,ratio);ctx.globalCompositeOperation='source-over';ctx.drawImage(inverse,0,0);}
      else{tint(shadow,effect.color||'#000000',n,area,ratio);ctx.globalCompositeOperation='destination-over';ctx.drawImage(shadow,0,0);}ctx.globalCompositeOperation='source-over';
    }
    for(const effect of n.effects||[]){if(effect.visible===false||effect.type!=='layer-blur'||!effect.blur)continue;const blurred=buffer({width:source.width,height:source.height}),b=blurred.getContext('2d');b.filter='blur('+effect.blur*ratio+'px)';b.drawImage(output,0,0);output=blurred;}return output;
  }
  async function paintNodes(ctx,nodes,w,resolve,options,stack=new Set()){
    for(const n of nodes){
      if(options.signal?.aborted)throw new DOMException('已取消','AbortError');
      if(n.visible===false||n.id===options.hiddenId)continue;ctx.save();const m=G.transform(n);ctx.transform(...m);ctx.globalAlpha*=n.opacity??1;ctx.globalCompositeOperation=n.blendMode&&n.blendMode!=='pass-through'?n.blendMode:'source-over';
      if(!n._isolated&&((n.effects||[]).some(e=>e.visible!==false)||(['group','instance'].includes(n.type)&&((n.blendMode&&n.blendMode!=='pass-through')||(n.opacity??1)<1)))){
        const local={...n,x:0,y:0,rotation:0,opacity:1,blendMode:'pass-through',effects:[],_isolated:true},area=effectArea(extent({width:n.width,height:n.height,nodes:[local]},true,w.gallery),n.effects),ratio=Math.min(1,(options.preview?1800:8192)/Math.max(area.width,area.height)),source=buffer(area,ratio),layer=source.getContext('2d');layer.scale(ratio,ratio);layer.translate(-area.x,-area.y);await paintNodes(layer,[local],w,resolve,options,stack);const result=applyEffects(source,n,area,ratio);ctx.drawImage(result,area.x,area.y,area.width,area.height);
      }
      else if(n.type==='group'&&n.mask==='alpha'){
        const local={...n,x:0,y:0,rotation:0,opacity:1,effects:[]},area=extent({width:n.width,height:n.height,nodes:[local]},true,w.gallery),ratio=Math.min(1,(options.preview?1800:8192)/Math.max(area.width,area.height)),content=buffer(area,ratio),matte=buffer(area,ratio),c=content.getContext('2d'),a=matte.getContext('2d');for(const target of [c,a]){target.scale(ratio,ratio);target.translate(-area.x,-area.y);if(n.clip||n.radius){round(target,n.width,n.height,n.radius);target.clip();}}await paintNodes(c,(n.children||[]).slice(1),w,resolve,options,stack);await paintNodes(a,(n.children||[]).slice(0,1),w,resolve,options,stack);c.setTransform(1,0,0,1,0,0);c.globalCompositeOperation='destination-in';c.drawImage(matte,0,0);ctx.drawImage(content,area.x,area.y,area.width,area.height);
      }
      else if(n.type==='group'&&(!n.mask||n.mask==='none')){if(n.clip||n.radius){round(ctx,n.width,n.height,n.radius);ctx.clip();}await paintNodes(ctx,n.children||[],w,resolve,options,stack);}
      else if(n.type==='text')text(ctx,n);
      else if(n.type==='rect'){round(ctx,n.width,n.height,n.radius);ctx.fillStyle=E.fill(ctx,n.color,n.width,n.height);ctx.fill();}
      else if(n.type==='instance'||(n.type==='group'&&n.mask)){
        const def=n.type==='group'?{id:n.id,width:n.width,height:n.height,nodes:n.children}:w.gallery.components[n.componentId];
        if(!def&&!options.preview)throw new Error('组件来源缺失，请重新设置组件');
        if(def&&!stack.has(def.id)){
          let width=n.width,height=n.height;ctx.save();
          if(n.mask==='phone'){
            width=Math.min(n.width,n.height*.46);height=width/.46;ctx.translate((n.width-width)/2,(n.height-height)/2);
            ctx.shadowColor='#0008';ctx.shadowBlur=width*.1;ctx.shadowOffsetY=width*.035;round(ctx,width,height,width*.125);ctx.fillStyle='#737984';ctx.fill();ctx.shadowBlur=0;ctx.shadowOffsetY=0;
            const border=width*.024;ctx.translate(border,border);width-=border*2;height-=border*2;round(ctx,width,height,width*.1);ctx.clip();
          }else if(n.mask==='round'||n.radius||(n.clip??def.clip)!==false){round(ctx,width,height,n.radius||(n.mask==='round'?40:0));ctx.clip();}
          let children=n.type==='instance'?G.instanceNodes(w.gallery,n,width,height):G.instanceNodes({components:{[def.id]:def}},{...n,componentId:def.id},width,height,false);
          if(options.instancePreview?.[n.id]){const v=G.instanceViewport(n);children=options.instancePreview[n.id].map(c=>({...c,x:c.x-v.x,y:c.y-v.y}));}
          if(n.type==='instance'&&def.background)children=[{id:n.id+'-background',type:'rect',x:0,y:0,width,height,visible:true,color:def.background},...children];
          if(n.type==='instance'&&def.effects?.length)children=[{id:n.id+'-effects',type:'group',x:0,y:0,width,height,visible:true,children,effects:G.scaleEffects(def.effects,width/def.width,height/def.height)}];
          await paintNodes(ctx,children,w,resolve,options,new Set([...stack,def.id]));
          if(n.mask==='phone'){ctx.fillStyle='#080b0e';ctx.beginPath();ctx.roundRect(width*.36,height*.018,width*.28,height*.025,width*.035);ctx.fill();ctx.fillStyle='#ffffffdf';ctx.beginPath();ctx.roundRect(width*.36,height*.975,width*.28,height*.006,width*.015);ctx.fill();if(n.lock){ctx.font='300 '+width*.18+'px system-ui';ctx.textAlign='center';ctx.fillText('09:41',width/2,height*.19);}}
          ctx.restore();
        }
      }else if(n.type==='image'){
        const asset=resolve(n.slot?w.slots[n.slot]:n.assetId);ctx.save();round(ctx,n.width,n.height,n.radius);ctx.clip();
        if(asset){const data=await R.load(asset);if(data.demo){data.images.forEach((source,i)=>{ctx.save();if(i===3){ctx.globalAlpha*=.25;ctx.globalCompositeOperation='screen';}image(ctx,source,n,{scale:1.18});ctx.restore();});}else image(ctx,data.image||data.video,n);}
        else if(options.preview){ctx.fillStyle='#33445f';ctx.fillRect(0,0,n.width,n.height);ctx.fillStyle='#c0d1ed';ctx.font='30px system-ui';ctx.textAlign='center';ctx.fillText('替换图片',n.width/2,n.height/2);}
        else throw new Error('画框中有图片尚未绑定，请先替换素材');
        ctx.restore();
      }
      ctx.restore();
    }
  }
  function extent(frame,overflow=false,g=null){
    const rectangles=[{x:0,y:0,width:frame.width,height:frame.height}];
    function visit(nodes,m=G.identity,stack=new Set()){for(const n of nodes){if(n.visible===false)continue;const matrix=G.multiply(m,G.transform(n));const area=effectArea({x:0,y:0,width:n.width,height:n.height},n.effects);rectangles.push(G.bounds(area,G.multiply(matrix,[1,0,0,1,area.x,area.y])));if(n.type==='instance'&&g&&!stack.has(n.componentId)&&(n.clip??g.components[n.componentId]?.clip)===false&&n.mask!=='phone'){visit(G.instanceNodes(g,n),matrix,new Set([...stack,n.componentId]));}else if(n.children&&!n.clip)visit(n.children,matrix,stack);}}
    if(overflow)visit(frame.nodes);return G.union(rectangles);
  }
  async function paint(canvas,w,frame,resolve,options={}){
    const root={id:frame.id,type:'group',x:0,y:0,width:frame.width,height:frame.height,rotation:0,opacity:1,visible:true,clip:frame.type==='canvas'?false:frame.clip||!options.preview,effects:frame.effects,children:[...(frame.background?[{id:frame.id+'-background',type:'rect',x:0,y:0,width:frame.width,height:frame.height,visible:true,color:frame.background}]:[]),...frame.nodes]},rect=options.preview?(options.region||extent({width:frame.width,height:frame.height,nodes:[root]},true,w.gallery)):{x:0,y:0,width:frame.width,height:frame.height},ratio=options.preview?Math.min(1,1800/Math.max(rect.width,rect.height)):1;
    canvas.width=Math.max(1,Math.ceil(rect.width*ratio));canvas.height=Math.max(1,Math.ceil(rect.height*ratio));const ctx=canvas.getContext('2d');ctx.scale(ratio,ratio);ctx.translate(-rect.x,-rect.y);
    await paintNodes(ctx,[root],w,resolve,options);if(!options.preview&&(frame.opacity??1)<1){ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.globalAlpha=frame.opacity;ctx.globalCompositeOperation='destination-in';ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.restore();}return rect;
  }
  window.GalleryRenderer={paint,extent};
})();
