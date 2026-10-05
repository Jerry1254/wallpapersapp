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
  async function paintNodes(ctx,nodes,w,resolve,options,stack=new Set()){
    for(const n of nodes){
      if(options.signal?.aborted)throw new DOMException('已取消','AbortError');
      if(!n.visible||n.id===options.hiddenId)continue;ctx.save();const m=G.transform(n);ctx.transform(...m);ctx.globalAlpha*=n.opacity??1;
      if(n.type==='group'&&!n.mask){await paintNodes(ctx,n.children||[],w,resolve,options,stack);}
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
          }else if(n.mask==='round'){round(ctx,width,height,n.radius||40);ctx.clip();}
          // Each image refits to its instance bounds, rather than stretching its source pixels.
          const sx=width/def.width,sy=height/def.height;
          const adapt=nodes=>nodes.map(child=>{const out={...child,x:child.x*sx,y:child.y*sy,width:child.width*sx,height:child.height*sy};if(child.fontSize)out.fontSize=child.fontSize*Math.min(sx,sy);if(child.letterSpacing)out.letterSpacing=child.letterSpacing*Math.min(sx,sy);if(child.children)out.children=adapt(child.children);if(child.type==='image'){out.scale=(child.scale||1)*(n.scale||1);out.panX=(child.panX||0)+(n.panX||0);out.panY=(child.panY||0)+(n.panY||0);}return out;});
          await paintNodes(ctx,adapt(def.nodes),w,resolve,options,new Set([...stack,def.id]));
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
  function extent(frame,overflow=false){
    const rectangles=[{x:0,y:0,width:frame.width,height:frame.height}];
    function visit(nodes,m=G.identity){for(const n of nodes){if(!n.visible)continue;const matrix=G.multiply(m,G.transform(n));rectangles.push(G.bounds(n,matrix));if(n.children)visit(n.children,matrix);}}
    if(overflow)visit(frame.nodes);return G.union(rectangles);
  }
  async function paint(canvas,w,frame,resolve,options={}){
    const rect=extent(frame,options.preview&&!frame.clip),ratio=options.preview?Math.min(1,1800/Math.max(rect.width,rect.height)):1;
    canvas.width=Math.max(1,Math.ceil(rect.width*ratio));canvas.height=Math.max(1,Math.ceil(rect.height*ratio));const ctx=canvas.getContext('2d');ctx.scale(ratio,ratio);ctx.translate(-rect.x,-rect.y);
    if(frame.background){ctx.fillStyle=E.fill(ctx,frame.background,frame.width,frame.height);ctx.fillRect(0,0,frame.width,frame.height);}
    if(frame.clip||!options.preview){ctx.beginPath();ctx.rect(0,0,frame.width,frame.height);ctx.clip();}
    await paintNodes(ctx,frame.nodes,w,resolve,options);return rect;
  }
  window.GalleryRenderer={paint,extent};
})();
