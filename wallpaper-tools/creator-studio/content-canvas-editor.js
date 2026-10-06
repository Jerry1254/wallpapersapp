/* Element selection and direct manipulation on the video composition canvas. */
(() => {
  'use strict';
  const C=window.ContentCore,R=window.ContentRenderer,E=window.GalleryEditing,P=window.GalleryProperties,$=id=>document.getElementById(id);
  let H,area,board,ctx,observer,drag=null,input=null,finishText=null;
  const work=()=>H.work(),selected=()=>C.selected(work()),active=()=>H?.active();
  function mount(bridge){
    H=bridge;area=$('content-canvas-area');board=document.createElement('canvas');board.id='content-selection-board';board.tabIndex=0;board.setAttribute('aria-label','视频画布元素：点击选择，拖动移动，控制点缩放，双击文字编辑');area.append(board);ctx=board.getContext('2d');
    observer=new ResizeObserver(draw);observer.observe(area);
    board.onpointerdown=down;board.onpointermove=move;board.onpointerup=()=>finish(false);board.onpointercancel=()=>finish(true);
    board.ondblclick=e=>{if(!active()||H.busy())return;const p=world(e),c=R.pick(work(),work().cursor,p.x,p.y);if(c?.presentation==='text'){H.stop();H.select(c);inlineText(c);}};
    board.onpointerleave=()=>{if(!drag)board.style.cursor='default';};
    window.addEventListener('blur',()=>finish(true));
    document.addEventListener('keydown',e=>{if(active()&&drag&&e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();finish(true);}},true);
  }
  function viewport(){const r=$('content-canvas').getBoundingClientRect(),a=area.getBoundingClientRect(),w=work();return {x:r.left-a.left,y:r.top-a.top,kx:r.width/w.width,ky:r.height/w.height,rect:r};}
  function world(e){const v=viewport();return {x:(e.clientX-v.rect.left)/v.kx,y:(e.clientY-v.rect.top)/v.ky};}
  function screen(p){const v=viewport();return {x:v.x+p.x*v.kx,y:v.y+p.y*v.ky};}
  function visible(c){return c&&c.presentation!=='audio'&&C.active(work(),work().cursor).includes(c);}
  function outline(c=selected()){
    if(!visible(c)||input)return null;const g=R.geometry(work(),c),corners=[[-1,-1],[1,-1],[1,1],[-1,1]].map(([x,y])=>screen(R.point(g,{x:x*g.width/2,y:y*g.height/2}))),edges=['nw','ne','se','sw'];
    const handles=corners.map((p,i)=>({...p,edge:edges[i]}));for(let i=0;i<4;i++){const a=corners[i],b=corners[(i+1)%4];handles.push({x:(a.x+b.x)/2,y:(a.y+b.y)/2,edge:['n','e','s','w'][i]});}
    const top=handles.find(h=>h.edge==='n'),bottom=handles.find(h=>h.edge==='s'),length=Math.hypot(top.x-bottom.x,top.y-bottom.y)||1,rotate={x:top.x+(top.x-bottom.x)/length*25,y:top.y+(top.y-bottom.y)/length*25,edge:'rotate'};
    if(rotate.x<8||rotate.y<8||rotate.x>area.clientWidth-8||rotate.y>area.clientHeight-8){rotate.x=top.x-(top.x-bottom.x)/length*25;rotate.y=top.y-(top.y-bottom.y)/length*25;}
    return {g,corners,handles,top,rotate};
  }
  function draw(){
    if(!board)return;board.hidden=!active();if(!active())return;const rect=area.getBoundingClientRect();if(rect.width<1||rect.height<1)return;
    const dpr=Math.min(devicePixelRatio||1,2),width=Math.round(rect.width*dpr),height=Math.round(rect.height*dpr);if(board.width!==width)board.width=width;if(board.height!==height)board.height=height;ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,rect.width,rect.height);
    const o=outline();if(!o)return;ctx.strokeStyle='#92b8ff';ctx.lineWidth=1;ctx.beginPath();o.corners.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.closePath();ctx.stroke();
    ctx.beginPath();ctx.moveTo(o.top.x,o.top.y);ctx.lineTo(o.rotate.x,o.rotate.y);ctx.stroke();ctx.fillStyle='#edf4ff';ctx.beginPath();ctx.arc(o.rotate.x,o.rotate.y,4,0,Math.PI*2);ctx.fill();ctx.stroke();
    for(const p of o.handles){ctx.fillRect(p.x-3,p.y-3,6,6);ctx.strokeRect(p.x-3,p.y-3,6,6);}
    const bottom=Math.max(...o.corners.map(p=>p.y)),x=o.corners.reduce((sum,p)=>sum+p.x,0)/4;ctx.fillStyle='#9fc2ff';ctx.font='10px system-ui';ctx.textAlign='center';ctx.fillText(Math.round(o.g.width)+' × '+Math.round(o.g.height),x,bottom+17);
  }
  function handle(e){
    const o=outline();if(!o)return null;const r=area.getBoundingClientRect(),p={x:e.clientX-r.left,y:e.clientY-r.top};
    for(const h of [o.rotate,...o.handles])if(Math.hypot(h.x-p.x,h.y-p.y)<=8)return h.edge;
    for(let i=0;i<4;i++){const a=o.corners[i],b=o.corners[(i+1)%4],length=(b.x-a.x)**2+(b.y-a.y)**2;if(!length)continue;const t=C.clamp(((p.x-a.x)*(b.x-a.x)+(p.y-a.y)*(b.y-a.y))/length,0,1);if(Math.hypot(p.x-a.x-t*(b.x-a.x),p.y-a.y-t*(b.y-a.y))<=5)return ['n','e','s','w'][i];}
    return null;
  }
  function cursor(edge){
    if(edge==='rotate')return 'crosshair';if(!edge)return 'default';const angle=selected()?.rotation||0,base=({e:0,w:0,n:90,s:90,nw:45,se:45,ne:135,sw:135})[edge],directions=['ew-resize','nwse-resize','ns-resize','nesw-resize'];return directions[((Math.round((base+angle)/45)%4)+4)%4];
  }
  function down(e){
    if(!active()||H.busy()||e.button!==0)return;finishText?.(true);P.closeFont();H.stop();e.preventDefault();board.focus();const w=work(),p=world(e),v=viewport(),inside=e.clientX>=v.rect.left&&e.clientX<=v.rect.right&&e.clientY>=v.rect.top&&e.clientY<=v.rect.bottom,edge=handle(e),c=edge?selected():inside?R.pick(w,w.cursor,p.x,p.y):null;
    if(!c){H.clear();return;}H.select(c);const g=R.geometry(w,c),kind=edge==='rotate'?'rotate':edge?'resize':'move';
    drag={w,c,kind,edge,p,g,before:C.copy(c),client:{x:e.clientX,y:e.clientY},pointer:e.pointerId,moved:false,angle:Math.atan2(p.y-g.y,p.x-g.x)};board.setPointerCapture(e.pointerId);board.style.cursor=kind==='move'?'grabbing':cursor(edge);draw();
  }
  function move(e){
    if(!active())return;if(!drag){const edge=handle(e),p=world(e),c=R.pick(work(),work().cursor,p.x,p.y);board.style.cursor=edge?cursor(edge):c?.presentation==='text'?'text':c?'move':'default';board.title=edge==='rotate'?'拖动旋转 · Shift 按 15° 旋转':edge?'拖动缩放 · Shift 锁定比例':c?.presentation==='text'?'点击选择 · 双击编辑文字':'点击选择 · 拖动移动';return;}
    const d=drag;if(work()!==d.w){finish(true);return;}d.moved=d.moved||Math.hypot(e.clientX-d.client.x,e.clientY-d.client.y)>3;if(!d.moved)return;
    const p=world(e),w=d.w,c=d.c;Object.assign(c,C.copy(d.before));
    if(d.kind==='move'){c.x=C.clamp(d.before.x+(p.x-d.p.x)/w.width*100,-200,300);c.y=C.clamp(d.before.y+(p.y-d.p.y)/w.height*100,-200,300);}
    if(d.kind==='rotate'){
      let degrees=d.before.rotation+(Math.atan2(p.y-d.g.y,p.x-d.g.x)-d.angle)*180/Math.PI;if(e.shiftKey)degrees=Math.round(degrees/15)*15;c.rotation=((degrees+180)%360+360)%360-180;
      const g=R.geometry(w,c);c.x+=(d.g.x-g.x)/w.width*100;c.y+=(d.g.y-g.y)/w.height*100;
    }
    if(d.kind==='resize'){
      const start=R.local(d.g,d.p),current=R.local(d.g,p),before={x:-d.g.width/2,y:-d.g.height/2,width:d.g.width,height:d.g.height},box=E.resizeBox(before,d.edge,current.x-start.x,current.y-start.y,e.shiftKey||c.presentation==='phone');
      if(c.presentation==='phone'){const border=w.width/100,inner=d.edge==='n'||d.edge==='s'?Math.max(1,(box.height-border)*.46):Math.max(1,box.width-border);box.width=inner+border;box.height=inner/.46+border;box.x=d.edge.includes('w')?d.g.width/2-box.width:-d.g.width/2;box.y=d.edge.includes('n')?d.g.height/2-box.height:-d.g.height/2;}
      if(box.width>16000||box.height>16000){H.requestPaint();return;}const center=R.point(d.g,{x:box.x+box.width/2,y:box.y+box.height/2});c.x=d.before.x+(center.x-d.g.x)/w.width*100;c.y=d.before.y+(center.y-d.g.y)/w.height*100;
      c.w=Math.max(1,box.width-(c.presentation==='phone'?w.width/100:0))/w.width*100;c.h=Math.max(1,box.height-(c.presentation==='phone'?w.width/100:0))/w.height*100;
      if(c.presentation==='text'){c.textResize='fixed';R.syncText(w,c);}
    }
    H.requestPaint();
  }
  function finish(cancel){
    const d=drag;if(!d)return;drag=null;if(board.hasPointerCapture(d.pointer))board.releasePointerCapture(d.pointer);board.style.cursor='default';
    if(cancel||work()!==d.w){Object.assign(d.c,d.before);if(work()===d.w){H.renderInspector();H.requestPaint();}return;}
    if(d.moved&&JSON.stringify(d.c)!==JSON.stringify(d.before))H.change(d.kind==='resize'?'调整图层大小':d.kind==='rotate'?'旋转图层':'移动图层');else{H.save();H.renderInspector();H.requestPaint();}
  }
  function inlineText(c){
    finishText?.(true);const w=work(),before=C.copy(c);C.clipStyle(w,c);input=document.createElement('textarea');const field=input;field.className='content-inline-text';field.value=R.textValue(w,c);field.spellcheck=false;field.maxLength=2000;field.setAttribute('aria-label','编辑画布文字');area.append(field);R.setEditing(c.id);
    const position=()=>{
      const g=R.geometry(w,c),v=viewport(),origin=screen(R.point(g,{x:-g.width/2,y:-g.height/2})),scale=g.scale*v.kx,paint=E.paint(c.color||w.accent),measure=document.createElement('canvas').getContext('2d');measure.font=R.font(c);measure.letterSpacing=(c.letterSpacing||0)+'px';const layout=E.textLayout({...c,text:field.value,width:g.width,height:g.height},text=>measure.measureText(text).width),offset=Math.max(0,g.height-layout.lines.length*layout.lineHeight)*(c.verticalAlign==='center'?.5:c.verticalAlign==='bottom'?1:0);
      Object.assign(field.style,{left:origin.x+'px',top:origin.y+'px',width:Math.max(12,g.width*scale)+'px',height:Math.max(12,g.height*g.scale*v.ky)+'px',fontSize:c.fontSize*scale+'px',fontFamily:E.fontFamily(c.fontFamily),fontWeight:c.fontWeight,lineHeight:c.lineHeight,letterSpacing:c.letterSpacing*scale+'px',textAlign:c.align,color:paint.color||paint.stops?.[0]?.color||w.accent,transform:'rotate('+g.angle+'rad)',transformOrigin:'0 0',whiteSpace:c.textResize==='auto-width'?'pre':'pre-wrap',paddingTop:offset*scale+'px'});field.wrap=c.textResize==='auto-width'?'off':'soft';
    };
    let done=false;finishText=save=>{
      if(done)return;done=true;const value=field.value;field.remove();input=null;finishText=null;R.setEditing(null);
      if(!save||work()!==w||value===R.textValue(w,before))Object.assign(c,before);else{c.text=value;R.syncText(w,c);}
      if(work()===w){if(save&&JSON.stringify(c)!==JSON.stringify(before))H.change('编辑画布文字');else{H.renderInspector();H.requestPaint();}board.focus();}
    };
    field.oninput=()=>{c.text=field.value;R.syncText(w,c);position();H.requestPaint();};field.onblur=()=>finishText?.(true);field.onkeydown=e=>{e.stopPropagation();if(e.key==='Escape'){e.preventDefault();finishText(false);}else if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){e.preventDefault();finishText(true);}};
    position();H.requestPaint();field.focus();field.select();
  }
  function reset(){finish(true);finishText?.(false);R.setEditing(null);if(board){ctx.clearRect(0,0,board.width,board.height);board.style.cursor='default';}}
  window.ContentCanvasEditor={mount,draw,reset,editing:()=>!!input};
})();
