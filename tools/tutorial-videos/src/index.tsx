import React from 'react';
import {AbsoluteFill, Composition, Easing, Img, interpolate, registerRoot, staticFile, useCurrentFrame} from 'remotion';
import {duration, tutorials, type Step, type Tutorial} from './tutorials';
import {narration} from './narration';

const C = {bg:'#F4F3F0', ink:'#191817', muted:'#6F6A64', line:'#E7E3DD', gold:'#F4A91E', pale:'#FFF3D8', green:'#287A55'};
const font = '"PingFang SC", "Microsoft YaHei", sans-serif';
const ease = Easing.bezier(0.2, 0.8, 0.2, 1);
const lerp = (frame:number, from:number, to:number, a:number, b:number) => interpolate(frame,[from,to],[a,b],{extrapolateLeft:'clamp',extrapolateRight:'clamp',easing:ease});
const Txt = ({children, size=16, weight=500, color=C.ink, style={}}: {children:React.ReactNode,size?:number,weight?:number,color?:string,style?:React.CSSProperties}) => <div style={{fontFamily:font,fontSize:size,fontWeight:weight,color,lineHeight:1.4,...style}}>{children}</div>;

function Icon({name, size=22, color=C.ink}:{name:string,size?:number,color?:string}) {
  const paths:Record<string,React.ReactNode> = {
    back:<path d="m14 5-7 7 7 7"/>,
    share:<><path d="M12 15V3m-4 4 4-4 4 4M7 10H4v11h16V10h-3"/></>,
    more:<><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></>,
    check:<path d="m5 12 5 5L20 7"/>,
    chat:<><path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2v-8.5A8.5 8.5 0 1 1 21 11.5Z"/><path d="M7 11h.01M12 11h.01M17 11h.01"/></>,
    close:<path d="m6 6 12 12M6 18 18 6"/>,
    live:<><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></>,
    wallpaper:<><rect x="3" y="3" width="18" height="18" rx="4"/><path d="m4 17 5-6 4 4 3-3 5 5"/><circle cx="16" cy="8" r="1"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name] || paths.wallpaper}</svg>;
}

function Landscape({frame, effect, uid}:{frame:number,effect:Tutorial['effect'],uid:string}) {
  const phase = effect === 'static' ? 0 : Math.sin(frame / 48);
  const parallax = effect === 'parallax';
  return <svg width="100%" height="100%" viewBox="0 0 240 450" preserveAspectRatio="xMidYMid slice" style={{display:'block'}}>
    <defs>
      <linearGradient id={`${uid}-sky`} x1="0" y1="0" x2="0" y2="1"><stop stopColor="#EBD8B8"/><stop offset="1" stopColor="#F9EFDE"/></linearGradient>
      <linearGradient id={`${uid}-mount`} x1="0" y1="0" x2="1" y2="1"><stop stopColor="#50796A"/><stop offset="1" stopColor="#2B5046"/></linearGradient>
    </defs>
    <rect width="240" height="450" fill={`url(#${uid}-sky)`}/>
    <g transform={`translate(${phase*(parallax ? -7:2)},${phase*2})`}><circle cx="171" cy="104" r="40" fill="#ECAE5C"/><circle cx="171" cy="104" r="49" fill="#ECAE5C" opacity=".1"/></g>
    <g transform={`translate(${phase*(parallax ? 4:2)},0)`} opacity=".45"><path d="M-35 258 48 140 100 226 166 184 280 296V460H-35Z" fill="#A5B3A1"/></g>
    <g transform={`translate(${phase*(parallax ? 10:3)},0)`}><path d="M-30 331 41 209 108 309 153 252 279 369V461H-30Z" fill="#6B9080"/><path d="m41 209-4 39 20-14Z" fill="#DAE2D5"/></g>
    <g transform={`translate(${phase*(parallax ? -17:4)},0)`}><path d="M-30 455V358c65-20 87-74 148-42s78-20 164 8V465Z" fill={`url(#${uid}-mount)`}/><path d="M-30 390c98-31 139-7 228 41 30 16 40 20 90 14v30H-30Z" fill="#21483E"/></g>
    <g opacity=".4" transform={`translate(${phase*3},0)`}><path d="M30 103h37m-18-4h27M87 58h32m-17-4h24" stroke="#FFF" strokeWidth="2" strokeLinecap="round"/></g>
  </svg>;
}

function Click({frame,x,y,delay=36}:{frame:number,x:number,y:number,delay?:number}) {
  const cycle = frame-delay;
  if (cycle < 0 || cycle > 30) return null;
  const p = cycle/30;
  return <div style={{position:'absolute',left:x,top:y,width:0,height:0,pointerEvents:'none'}}>
    <div style={{position:'absolute',width:30+p*42,height:30+p*42,border:'2px solid #F4A91E',borderRadius:'50%',transform:'translate(-50%,-50%)',opacity:1-p}}/>
    <div style={{position:'absolute',width:16,height:16,borderRadius:'50%',background:'#F4A91E',boxShadow:'0 0 0 5px #ffffffb3',transform:`translate(-50%,-50%) scale(${1-0.25*Math.sin(p*Math.PI)})`}}/>
  </div>;
}

const Button = ({label, yellow=false}:{label:string,yellow?:boolean}) => <div style={{height:40,borderRadius:20,background:yellow?C.gold:C.ink,color:yellow?C.ink:'white',display:'flex',alignItems:'center',justifyContent:'center',fontSize:label.length>10?11:15,fontWeight:600}}>{label}</div>;

function AppScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const downloading = step.action==='下载中';
  const progress = Math.min(100, Math.round(frame/105*100));
  const label = downloading ? (progress<100 ? `下载中 ${progress}%` : '设置壁纸') : step.action;
  const formats = tutorial.effect==='parallax' ? ['4D壁纸','静态壁纸'] : ['动态壁纸','静态壁纸'];
  return <>
    <div style={{display:'flex',alignItems:'center',gap:12,padding:'8px 12px 12px'}}><Icon name="back" size={17}/><Txt size={16} weight={700}>壁纸详情</Txt></div>
    <div style={{display:'flex',justifyContent:'center',gap:8,paddingBottom:10}}>{formats.map(x=><div key={x} style={{fontSize:10,padding:'6px 9px',borderRadius:15,background:x===tutorial.format?C.gold:'#F1EFEA',fontWeight:x===tutorial.format?700:400}}>{x}</div>)}</div>
    <div style={{margin:'0 17px',height:234,borderRadius:18,overflow:'hidden'}}><Landscape uid="app" effect={tutorial.effect} frame={frame}/></div>
    <div style={{padding:'12px 17px 0'}}><Txt size={14} weight={700}>山间的温柔</Txt><Txt size={10} color={downloading&&progress>=100?C.green:C.muted}>{step.action==='兑换并下载'?'需兑换 · 使用客服发送的兑换码':downloading?(progress<100?'兑换成功 · 正在下载':'下载完成 · 可以设置'):'让每一次点亮，都有好风景'}</Txt><div style={{marginTop:11}}><Button label={label}/></div></div>
    {!downloading&&<Click frame={frame} x={112} y={381}/>}
  </>;
}

function HomeScreen({tutorial,frame}:{tutorial:Tutorial,frame:number}) {
  return <>
    <div style={{padding:'12px 12px 10px',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
      <Txt size={21} weight={750}>倾境壁纸</Txt>
      <div style={{display:'flex',gap:5,alignItems:'center',borderRadius:18,padding:'7px 9px',background:C.ink,outline:`2px solid ${C.gold}`,outlineOffset:3}}><Icon name="chat" size={15} color="white"/><Txt size={11} weight={650} color="white">客服</Txt></div>
    </div>
    <div style={{margin:'4px 12px 13px',border:`1px solid ${C.line}`,borderRadius:14,padding:'9px 12px'}}><Txt size={10} color={C.muted}>搜索喜欢的壁纸</Txt></div>
    <div style={{margin:'0 12px 12px',display:'flex',gap:8}}>{['精选','4D壁纸','静态壁纸'].map(label=><Txt key={label} size={10} weight={label==='4D壁纸'?700:500} style={{padding:'6px 9px',borderRadius:14,background:label==='4D壁纸'?C.gold:'#F1EFEA'}}>{label}</Txt>)}</div>
    <div style={{padding:'0 12px',display:'grid',gridTemplateColumns:'1fr 1fr',gap:9}}>{[0,1].map(index=><div key={index}><div style={{height:185,borderRadius:13,overflow:'hidden',filter:index===1?'hue-rotate(30deg)':'none'}}><Landscape uid={`home-${index}`} frame={frame} effect={tutorial.effect}/></div><Txt size={10} weight={650} style={{marginTop:7}}>{index===0?'山间的温柔':'落日漫游'}</Txt><Txt size={8} color={C.muted}>4D 壁纸</Txt></div>)}</div>
    <div style={{position:'absolute',bottom:18,left:17,right:17,display:'flex',justifyContent:'space-around'}}><Txt size={11} weight={700}>首页</Txt><Txt size={11} color={C.muted}>分类</Txt><Txt size={11} color={C.muted}>我的</Txt></div>
    <Click frame={frame} x={181} y={28}/>
  </>;
}

function SupportScreen({tutorial,frame}:{tutorial:Tutorial,frame:number}) {
  const copied = frame>=70;
  return <>
    <HomeScreen tutorial={tutorial} frame={100}/>
    <div style={{position:'absolute',inset:0,background:'#19181738'}}/>
    <div style={{position:'absolute',top:18,left:10,right:10,padding:12,borderRadius:20,background:'white',boxShadow:'0 8px 24px #19181720'}}>
      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between'}}><Txt size={15} weight={750}>联系客服</Txt><Icon name="close" size={15}/></div>
      <Txt size={10} color={C.muted} style={{marginTop:8}}>微信客服 · 添加获取兑换码</Txt>
      <Img src={staticFile('customer-service-qr.png')} style={{display:'block',width:96,height:96,margin:'10px auto'}}/>
      <Txt size={9} color={C.muted} style={{textAlign:'center'}}>扫描二维码，或复制微信号</Txt>
      <div style={{marginTop:10,padding:'7px 9px',borderRadius:12,background:'#F4F3F0',display:'flex',justifyContent:'space-between',alignItems:'center'}}>
        <div><Txt size={8} color={C.muted}>微信号</Txt><Txt size={14} weight={700}>jykj992</Txt></div>
        <div style={{padding:'7px 11px',borderRadius:13,background:C.ink}}><Txt size={10} color="white" weight={650}>复制</Txt></div>
      </div>
      <Txt size={11} weight={600} style={{marginTop:13,whiteSpace:'pre-line',textAlign:'center'}}>打开微信 → 搜索微信号{'\n'}添加客服，联系获取兑换码</Txt>
      <div style={{marginTop:11,padding:'8px 6px',borderRadius:10,background:copied?'#E7F4EC':C.pale}}><Txt size={10} color={copied?C.green:C.muted} weight={600} style={{textAlign:'center'}}>{copied?'客服微信已复制':'获取兑换码后，返回这张壁纸'}</Txt></div>
    </div>
    <Click frame={frame} x={178} y={239}/>
  </>;
}

function RedemptionScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const dots = Math.round(lerp(frame,18,75,0,20));
  const verified = frame>=140;
  return <>
    <AppScreen tutorial={tutorial} step={{...step,action:'兑换并下载'}} frame={100}/>
    <div style={{position:'absolute',inset:0,background:'#19181738'}}/>
    <div style={{position:'absolute',bottom:13,left:0,right:0,borderRadius:'22px 22px 0 0',padding:15,background:'white'}}>
      <Txt size={16} weight={750}>兑换这张壁纸</Txt><Txt size={10} color={C.muted} style={{marginTop:4}}>输入客服发送的兑换码</Txt>
      <Txt size={10} weight={600} style={{marginTop:15}}>兑换码</Txt>
      <div style={{marginTop:5,height:38,borderRadius:11,border:`1px solid ${C.gold}`,background:C.pale,padding:'11px 10px',fontSize:11,letterSpacing:dots?1.2:0,color:dots?C.ink:C.muted}}>{dots?'•'.repeat(dots):'请输入兑换码'}</div>
      <div style={{height:30,display:'flex',alignItems:'center',gap:5}}>{verified?<><Icon name="check" size={13} color={C.green}/><Txt size={10} color={C.green} weight={650}>兑换成功，准备下载</Txt></>:<Txt size={9} color={C.muted}>使用客服发给你的兑换码</Txt>}</div>
      <Button label={step.action}/>
      <div style={{marginTop:9,height:30,borderRadius:15,border:`1px solid ${C.line}`,display:'flex',alignItems:'center',justifyContent:'center',gap:6}}><Icon name="chat" size={13}/><Txt size={10}>联系客服</Txt></div>
    </div>
    <Click frame={frame} x={112} y={330} delay={96}/>
  </>;
}

function GalleryScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const ios = step.device==='ios' || tutorial.id.includes('ios');
  const isStatic = tutorial.effect==='static';
  return <>
    <div style={{padding:'9px 16px 12px'}}><Txt size={24} weight={750}>{ios?'照片':'图库'}</Txt><Txt size={11} color={C.muted}>最近保存</Txt></div>
    <div style={{padding:'0 12px',display:'grid',gridTemplateColumns:'repeat(3,1fr)',gap:5}}>{[0,1,2,3,4,5,6,7,8].map(n=><div key={n} style={{height:94,borderRadius:7,overflow:'hidden',position:'relative',filter:n===0?'none':`saturate(.4) hue-rotate(${n*16}deg)`,opacity:n===0?1:.4,outline:n===0?'3px solid #F4A91E':'none'}}><Landscape uid={`thumb-${n}`} frame={0} effect="static"/>{n===0&&<div style={{position:'absolute',top:4,left:4,fontSize:7,background:'#ffffffdc',borderRadius:4,padding:3}}>{isStatic?'刚刚保存':ios?'◎ LIVE':'◎ 动态'}</div>}</div>)}</div>
    <div style={{position:'absolute',bottom:15,left:0,right:0,textAlign:'center',fontSize:10,color:C.muted}}>{isStatic?(ios?'图库　　相簿':'照片　　相册'):'照片　　相册　　搜索'}</div>
    <Click frame={frame} x={44} y={124}/>
  </>;
}

function StaticSaveScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const harmony = step.device==='harmony';
  const saved = frame>=110;
  if (!harmony) {
    const label = saved ? '已保存到相册，请设置' : frame>=70 ? `下载中 ${Math.min(99,Math.round((frame-70)*2.5))}%` : '下载壁纸';
    return <>
      <AppScreen tutorial={tutorial} step={{...step,action:label}} frame={saved?100:frame}/>
      {saved&&<div style={{position:'absolute',top:287,left:26,right:26,padding:'10px 6px',borderRadius:12,background:'#E7F4EC',display:'flex',justifyContent:'center',alignItems:'center',gap:5}}><Icon name="check" size={15} color={C.green}/><Txt size={10} color={C.green} weight={650}>图片已保存到手机照片</Txt></div>}
    </>;
  }
  return <>
    <AppScreen tutorial={tutorial} step={{...step,action:'下载壁纸'}} frame={100}/>
    <div style={{position:'absolute',inset:0,background:'#19181738'}}/>
    <div style={{position:'absolute',top:127,left:15,right:15,padding:'18px 12px',borderRadius:18,background:'white',textAlign:'center',boxShadow:'0 8px 22px #19181720'}}>
      <div style={{width:37,height:37,borderRadius:19,margin:'0 auto 10px',background:saved?'#E7F4EC':C.pale,display:'grid',placeItems:'center'}}><Icon name="check" color={saved?C.green:C.ink}/></div>
      <Txt size={17} weight={750}>{saved?'已保存到相册':'下载完成'}</Txt>
      <Txt size={10} color={C.muted} style={{marginTop:7,whiteSpace:'pre-line'}}>{saved?'请前往手机图库设置壁纸':'点击保存按钮\n将静态图片保存到手机图库'}</Txt>
      <div style={{display:'flex',gap:7,marginTop:15}}><div style={{flex:1,border:`1px solid ${C.line}`,borderRadius:18,padding:'10px 0'}}><Txt size={10}>暂不保存</Txt></div><div style={{flex:1,background:saved?'#E7F4EC':C.gold,borderRadius:18,padding:'10px 0'}}><Txt size={10} weight={700} color={saved?C.green:C.ink}>{saved?'保存成功':'保存图片'}</Txt></div></div>
    </div>
    <Click frame={frame} x={157} y={286}/>
  </>;
}

function StaticPhotoScreen({step,frame}:{step:Step,frame:number}) {
  const ios = step.device==='ios';
  const menu = lerp(frame,75,87,0,1);
  return <>
    <div style={{padding:'9px 13px',display:'flex',justifyContent:'space-between',alignItems:'center'}}><Icon name="back" size={18}/><Txt size={11} weight={600}>刚刚保存</Txt><div style={{borderRadius:12,padding:3,background:ios?'transparent':C.pale}}><Icon name="more" size={19}/></div></div>
    <div style={{height:312,margin:'2px 12px',borderRadius:12,overflow:'hidden'}}><Landscape uid={`static-photo-${step.device}`} frame={0} effect="static"/></div>
    <div style={{position:'absolute',bottom:20,left:15,right:15,display:'flex',alignItems:'center',justifyContent:'space-between'}}>
      <div style={{padding:'6px 9px',background:ios?C.pale:'transparent',borderRadius:12,display:'flex',gap:5,alignItems:'center'}}><Icon name="share" size={20}/><Txt size={10}>分享</Txt></div><Txt size={11} color={C.muted}>编辑</Txt><Icon name="more" size={20}/>
    </div>
    {menu>0&&(ios?<div style={{position:'absolute',bottom:12,left:6,right:6,padding:12,borderRadius:20,background:'white',boxShadow:'0 -6px 20px #19181722',opacity:menu,transform:`translateY(${(1-menu)*18}px)`}}>
      <div style={{width:28,height:3,background:C.line,borderRadius:3,margin:'0 auto 8px'}}/>
      <Txt size={10} color={C.muted}>分享图片 · 向上滑动查看选项</Txt>
      <div style={{display:'flex',justifyContent:'space-between',margin:'11px 0 9px'}}>{['隔空投送','信息','邮件'].map((label,index)=><div key={label} style={{textAlign:'center'}}><div style={{width:32,height:32,borderRadius:9,background:['#6C9BC1','#62A377','#90A7BB'][index],margin:'0 auto 4px'}}/><Txt size={8} color={C.muted}>{label}</Txt></div>)}</div>
      {['拷贝照片','添加到相簿','用作墙纸'].map(label=><div key={label} style={{padding:'9px 8px',borderRadius:10,background:label==='用作墙纸'?C.pale:'#F6F5F2',marginTop:5,display:'flex',alignItems:'center',justifyContent:'space-between'}}><Txt size={12} weight={label==='用作墙纸'?700:500}>{label}</Txt>{label==='用作墙纸'&&<Icon name="wallpaper" size={17}/>}</div>)}
    </div>:<div style={{position:'absolute',top:51,left:47,right:10,padding:8,borderRadius:15,background:'white',boxShadow:'0 7px 22px #19181730',opacity:menu,transform:`translateY(${(1-menu)*-8}px)`}}>
      {['分享','收藏','设置为壁纸','详细信息'].map(label=><div key={label} style={{padding:'10px 8px',borderRadius:9,background:label==='设置为壁纸'?C.pale:'transparent',display:'flex',alignItems:'center',gap:6}}>{label==='设置为壁纸'&&<Icon name="wallpaper" size={15}/>}<Txt size={11} weight={label==='设置为壁纸'?750:500}>{label}</Txt></div>)}
    </div>)}
    <Click frame={frame} x={ios?42:201} y={ios?382:22}/>
    <Click frame={frame} x={ios?113:133} y={ios?377:152} delay={125}/>
  </>;
}

function StaticPlacementScreen({step,frame}:{step:Step,frame:number}) {
  const harmony = step.device==='harmony';
  const showChoices = !harmony || frame>=75;
  const complete = frame>=(harmony?190:110);
  return <>
    <div style={{position:'absolute',inset:0}}><Landscape uid={`static-placement-${step.device}`} frame={0} effect="static"/></div>
    <div style={{position:'absolute',top:58,left:0,right:0,textAlign:'center',color:'#28473B'}}><Txt size={12} color="#28473B">星期日</Txt><Txt size={54} color="#28473B" weight={650}>09:41</Txt></div>
    <div style={{position:'absolute',top:5,left:12,right:12,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Txt size={11}>取消</Txt><Txt size={11} weight={700} style={{padding:'6px 13px',borderRadius:15,background:C.gold}}>{harmony?'应用':'预览'}</Txt></div>
    {showChoices&&!complete&&<div style={{position:'absolute',bottom:14,left:8,right:8,padding:13,borderRadius:20,background:'#fffffff2'}}>
      <Txt size={13} weight={750} style={{marginBottom:9}}>选择设置位置</Txt>
      {['设为锁屏','设为桌面','同时设置'].map((label,index)=><div key={label} style={{padding:'10px 9px',borderRadius:12,background:index===0?C.pale:'#F4F3F0',marginTop:6,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Txt size={12} weight={index===0?700:500}>{label}</Txt>{index===0&&<Icon name="check" size={15} color={C.green}/>}</div>)}
    </div>}
    {complete&&<div style={{position:'absolute',bottom:54,left:27,right:27,padding:'12px 8px',borderRadius:18,background:'#ffffffed',display:'flex',justifyContent:'center',alignItems:'center',gap:7}}><Icon name="check" size={18} color={C.green}/><Txt size={12} weight={700}>锁屏壁纸已设置</Txt></div>}
    {harmony&&<Click frame={frame} x={185} y={18}/>}<Click frame={frame} x={109} y={285} delay={harmony?140:55}/>
  </>;
}

function StaticAndroidScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const preview = lerp(frame,85,97,0,1);
  return <>
    <AppScreen tutorial={tutorial} step={step} frame={frame}/>
    {preview>0&&<div style={{position:'absolute',inset:0,opacity:preview}}><StaticPlacementScreen step={step} frame={frame-85}/></div>}
  </>;
}

function StaticIosPreview({frame}:{frame:number}) {
  const pair = lerp(frame,75,87,0,1);
  const complete = frame>=210;
  return <>
    <div style={{position:'absolute',inset:0}}><Landscape uid="static-ios-lock" frame={0} effect="static"/></div>
    <div style={{position:'absolute',top:55,left:0,right:0,textAlign:'center',color:'#28473B'}}><Txt size={12} color="#28473B">星期日</Txt><Txt size={57} color="#28473B" weight={650}>09:41</Txt></div>
    <div style={{position:'absolute',top:4,left:12,right:12,display:'flex',alignItems:'center',justifyContent:'space-between'}}><Txt size={11}>取消</Txt><Txt size={11} weight={700} style={{background:C.gold,padding:'6px 12px',borderRadius:15}}>添加</Txt></div>
    {pair>0&&!complete&&<div style={{position:'absolute',bottom:15,left:7,right:7,padding:12,borderRadius:20,background:'#ffffffed',opacity:pair}}>
      <Txt size={10} weight={650} style={{textAlign:'center'}}>在锁屏和主屏幕上使用这张墙纸</Txt>
      <div style={{display:'flex',justifyContent:'center',gap:14,margin:'10px 0'}}>{['锁屏','主屏幕'].map((label,index)=><div key={label} style={{width:50,textAlign:'center'}}><div style={{height:67,borderRadius:8,overflow:'hidden',position:'relative'}}><Landscape uid={`static-ios-pair-${index}`} frame={0} effect="static"/>{index===0?<Txt size={12} weight={700} style={{position:'absolute',top:11,left:0,right:0}}>09:41</Txt>:<div style={{position:'absolute',top:12,left:7,right:7,display:'grid',gridTemplateColumns:'repeat(3,1fr)',gap:5}}>{[0,1,2,3,4,5].map(n=><div key={n} style={{height:8,borderRadius:2,background:'#ffffffe0'}}/>)}</div>}</div><Txt size={8} color={C.muted} style={{marginTop:3}}>{label}</Txt></div>)}</div>
      <div style={{height:36,borderRadius:18,background:C.gold,display:'grid',placeItems:'center'}}><Txt size={12} weight={750}>设为墙纸组合</Txt></div><Txt size={11} color={C.muted} style={{marginTop:9,textAlign:'center'}}>自定义主屏幕</Txt>
    </div>}
    {complete&&<div style={{position:'absolute',bottom:57,left:17,right:17,padding:'12px 9px',borderRadius:20,background:'#ffffffed',display:'flex',alignItems:'center',justifyContent:'center',gap:6}}><Icon name="check" color={C.green} size={18}/><Txt size={11} weight={700}>锁屏与主屏幕已设置</Txt></div>}
    <Click frame={frame} x={185} y={17}/><Click frame={frame} x={112} y={356} delay={145}/>
  </>;
}

function PhotoScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  const ios = tutorial.id.includes('ios');
  const mixed = tutorial.effect==='static';
  return <>
    <div style={{padding:'8px 13px',display:'flex',alignItems:'center',justifyContent:'space-between'}}><Icon name="back" size={18}/><Txt size={12} weight={650}>刚刚保存</Txt><Icon name="more" size={19}/></div>
    <div style={{height:253,margin:'2px 12px',borderRadius:12,overflow:'hidden',position:'relative'}}><Landscape uid="photo" frame={frame} effect={tutorial.effect}/>{tutorial.effect!=='static'&&<div style={{position:'absolute',left:10,top:10,background:'#ffffffdd',fontSize:10,borderRadius:10,padding:'4px 7px'}}>◎ {ios?'LIVE':'动态照片'}</div>}</div>
    <div style={{display:'flex',justifyContent:'space-between',padding:'9px 20px',alignItems:'center'}}><Icon name="share" size={19}/><Txt size={10} color={C.muted}>{ios?'分享':'更多'}</Txt><Icon name="more" size={22}/></div>
    <div style={{margin:'0 10px',borderRadius:14,background:'white',boxShadow:'0 4px 12px #1918170a',border:`1px solid ${C.line}`,overflow:'hidden'}}>
      <div style={{padding:'9px 12px',fontSize:10,color:C.muted}}>{mixed?'分享 / 更多菜单':ios?'分享菜单':'更多菜单'}</div>
      <div style={{background:C.pale,padding:'12px',display:'flex',gap:9,alignItems:'center',fontSize:14,fontWeight:700}}><Icon name="wallpaper" size={18}/>{step.action}</div>
    </div>
    <Click frame={frame} x={161} y={390}/>
  </>;
}

function PermissionScreen({frame,tutorial}:{frame:number,tutorial:Tutorial}) {
  const enabled = frame > 105;
  return <>
    <div style={{padding:'10px 12px',display:'flex',gap:10,alignItems:'center'}}><Icon name="back" size={16}/><Txt size={15} weight={700}>其他权限</Txt></div>
    <div style={{margin:'14px 14px 20px',display:'flex',gap:10,alignItems:'center'}}><div style={{width:34,height:34,background:C.gold,borderRadius:11,display:'grid',placeItems:'center'}}><Icon name="wallpaper" size={24}/></div><div><Txt size={12} weight={700}>{tutorial.effect==='parallax'?'当前倾境 4D App':'当前倾境 App'}</Txt><Txt size={9} color={C.muted}>应用权限设置</Txt></div></div>
    <div style={{padding:'0 12px'}}>{['后台弹出界面','显示悬浮窗','动态壁纸服务'].map((label,n)=><div key={label} style={{marginBottom:8,padding:n===2?'18px 10px':'13px 10px',borderRadius:12,background:n===2?C.pale:'white',border:n===2?`1px solid ${C.gold}`:`1px solid ${C.line}`,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Txt size={12} weight={n===2?700:500}>{label}</Txt>{n===2?<div style={{width:35,height:21,borderRadius:20,background:enabled?C.green:'#D8D2CA',padding:3}}><div style={{width:15,height:15,borderRadius:'50%',background:'white',transform:`translateX(${enabled?14:0}px)`}}/></div>:<Txt size={10} color={C.muted}>询问</Txt>}</div>)}</div>
    <div style={{margin:'20px 15px 0',padding:12,borderRadius:12,background:enabled?'#E7F4EC':'#F1EFEA'}}><Txt size={11} color={enabled?C.green:C.muted} weight={600}>{enabled?'已允许动态壁纸服务':'请允许动态壁纸服务'}</Txt><Txt size={10} color={C.muted} style={{marginTop:5}}>随后返回 App，重新检测并设置</Txt></div>
    <Click frame={frame} x={186} y={260}/>
  </>;
}

function PreviewScreen({tutorial,step,frame,success=false}:{tutorial:Tutorial,step:Step,frame:number,success?:boolean}) {
  const ios = tutorial.id.includes('ios');
  const harmony = tutorial.id.includes('harmony');
  const lock = ios || harmony || tutorial.effect==='static';
  return <>
    <div style={{position:'absolute',inset:0}}><Landscape uid="full" frame={frame} effect={tutorial.effect}/></div>
    {lock?<div style={{position:'absolute',top:54,left:0,right:0,textAlign:'center',color:'#28473B'}}><div style={{fontSize:12,fontWeight:600}}>10月3日　星期六</div><div style={{fontSize:57,lineHeight:1.25,fontWeight:650,letterSpacing:-2}}>09:41</div></div>:<div style={{position:'absolute',top:51,left:16,right:16,display:'grid',gridTemplateColumns:'repeat(4,1fr)',gap:16}}>{[0,1,2,3,4,5,6,7].map(n=><div key={n} style={{height:35,width:35,borderRadius:11,background:['#ffffffc8','#ddb589dd','#789988e6','#4c7563e6'][n%4],boxShadow:'0 2px 6px #0000000b'}}/>)}</div>}
    {!success&&<>
      <div style={{position:'absolute',top:4,left:10,right:10,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Txt size={11}>取消</Txt><div style={{fontSize:11,background:'#fff8',padding:'6px 12px',borderRadius:20,fontWeight:600}}>{ios?'添加':harmony?'应用':'预览'}</div></div>
      <div style={{position:'absolute',bottom:16,left:12,right:12,padding:12,borderRadius:16,background:'#ffffffed'}}>
        {(ios||harmony)&&<div style={{display:'flex',alignItems:'center',gap:8,paddingBottom:10}}><Icon name="live" color={C.green} size={22}/><Txt size={11} color={C.green} weight={700}>{ios?'实况播放已开启':'动态效果已开启'}</Txt></div>}
        <Button label={step.action} yellow/>
      </div>
      <Click frame={frame} x={112} y={385}/>
    </>}
    {success&&<><div style={{position:'absolute',bottom:49,left:0,right:0,display:'flex',justifyContent:'center'}}><div style={{display:'flex',gap:7,padding:'9px 15px',background:'#ffffffeb',borderRadius:22,alignItems:'center'}}><Icon name="check" color={C.green} size={20}/><Txt size={12} weight={700}>设置完成</Txt></div></div>{!lock&&<div style={{position:'absolute',bottom:13,left:14,right:14,height:39,borderRadius:17,background:'#ffffff70',display:'flex',alignItems:'center',justifyContent:'space-evenly'}}>{[1,2,3,4].map(n=><div key={n} style={{width:24,height:24,borderRadius:8,background:'#ffffffa8'}}/>)}</div>}</>}
  </>;
}

function PhoneScreen({tutorial,step,frame}:{tutorial:Tutorial,step:Step,frame:number}) {
  if (tutorial.effect==='static') {
    if (step.screen==='android-set') return <StaticAndroidScreen tutorial={tutorial} step={step} frame={frame}/>;
    if (step.screen==='save') return <StaticSaveScreen tutorial={tutorial} step={step} frame={frame}/>;
    if (step.screen==='photo') return <StaticPhotoScreen step={step} frame={frame}/>;
    if (step.screen==='placement') return <StaticPlacementScreen step={step} frame={frame}/>;
    if (step.screen==='preview'&&step.device==='ios') return <StaticIosPreview frame={frame}/>;
  }
  return <>
    {step.screen==='app'&&<AppScreen tutorial={tutorial} step={step} frame={frame}/>}
    {step.screen==='home'&&<HomeScreen tutorial={tutorial} frame={frame}/>}
    {step.screen==='support'&&<SupportScreen tutorial={tutorial} frame={frame}/>}
    {step.screen==='redeem'&&<RedemptionScreen tutorial={tutorial} step={step} frame={frame}/>}
    {step.screen==='gallery'&&<GalleryScreen tutorial={tutorial} step={step} frame={frame}/>}
    {step.screen==='photo'&&<PhotoScreen tutorial={tutorial} step={step} frame={frame}/>}
    {step.screen==='permission'&&<PermissionScreen tutorial={tutorial} frame={frame}/>}
    {(step.screen==='preview'||step.screen==='success')&&<PreviewScreen tutorial={tutorial} step={step} frame={frame} success={step.screen==='success'}/>}
  </>;
}

function Phone({tutorial,step,previous,frame}:{tutorial:Tutorial,step:Step,previous?:Step,frame:number}) {
  const tilt = tutorial.effect==='parallax' && step.screen==='success' ? Math.sin(frame/35)*5 : 0;
  const mix = previous ? lerp(frame,0,12,0,1) : 1;
  return <div style={{position:'absolute',top:347,left:148,width:244,height:463,padding:9,borderRadius:39,background:C.ink,boxShadow:'0 20px 38px #31281f22',transform:`rotate(${tilt}deg)`}}>
    <div style={{height:'100%',background:'#F8F7F4',borderRadius:30,overflow:'hidden',position:'relative'}}>
      <div style={{height:24,position:'relative',zIndex:2,padding:'7px 13px 0',display:'flex',justifyContent:'space-between',fontSize:8,fontWeight:600}}><span>9:41</span><span style={{width:57,height:11,borderRadius:8,background:C.ink,position:'absolute',top:6,left:84}}/><span>▮▮▮ ▰</span></div>
      <div style={{position:'absolute',inset:'24px 0 0'}}>
        {previous && mix<1 && <div style={{position:'absolute',inset:0}}><PhoneScreen tutorial={tutorial} step={previous} frame={previous.seconds*30-1}/></div>}
        <div style={{position:'absolute',inset:0,background:'#F8F7F4',opacity:mix}}><PhoneScreen tutorial={tutorial} step={step} frame={frame}/></div>
      </div>
      <div style={{position:'absolute',bottom:5,left:80,width:67,height:3,background:'#19181780',borderRadius:4}}/>
    </div>
  </div>;
}

export function SettingTutorial({tutorial}:{tutorial:Tutorial}) {
  const f = useCurrentFrame();
  let start = 0, index=0;
  for (let i=0;i<tutorial.steps.length;i++) {
    if (f < (start+tutorial.steps[i].seconds*30)) { index=i; break; }
    start += tutorial.steps[i].seconds*30;
  }
  const step = tutorial.steps[index], local=f-start;
  return <AbsoluteFill style={{background:C.bg,fontFamily:font}}>
    <div style={{width:540,height:960,position:'absolute',transform:'scale(2)',transformOrigin:'top left',overflow:'hidden',color:C.ink}}>
      <div style={{position:'absolute',top:42,left:34,right:34,display:'flex',alignItems:'center',justifyContent:'space-between'}}>
        <div style={{display:'flex',alignItems:'center',gap:9}}><div style={{width:30,height:30,borderRadius:11,background:C.gold,display:'grid',placeItems:'center'}}><Icon name="wallpaper" size={22}/></div><Txt size={17} weight={750}>倾境壁纸</Txt></div>
        <Txt size={10} weight={600} color={C.muted} style={{letterSpacing:1.4}}>设置教程 / {tutorial.id.slice(0,2)}</Txt>
      </div>
      <div style={{position:'absolute',top:109,left:34,right:34}}><Txt size={12} weight={650} color={C.muted} style={{letterSpacing:.5}}>{tutorial.platform}</Txt><Txt size={41} weight={800} style={{letterSpacing:-1,marginTop:8}}>{tutorial.title}</Txt></div>
      <div style={{position:'absolute',top:203,left:34,right:34,display:'flex',gap:6}}>{tutorial.steps.map((s,i)=><div key={i} style={{height:4,flex:1,background:i<index?C.ink:C.line,borderRadius:2,overflow:'hidden'}}>{i===index&&<div style={{height:'100%',width:`${(local+1)/(s.seconds*30)*100}%`,background:C.gold}}/>}</div>)}</div>
      <div style={{position:'absolute',top:228,left:34,right:34,transform:`translateY(${lerp(local,0,15,8,0)}px)`}}>
        <div style={{display:'flex',alignItems:'center',gap:8,marginBottom:9}}><Txt size={11} weight={800} style={{background:C.ink,color:'white',borderRadius:20,padding:'4px 9px'}}>STEP {String(index+1).padStart(2,'0')}</Txt>{step.tag&&<Txt size={11} weight={650} style={{background:C.pale,padding:'4px 9px',borderRadius:20}}>{step.tag}</Txt>}</div>
        <Txt size={24} weight={750}>{step.title}</Txt><Txt size={14} color={C.muted} style={{marginTop:7}}>{step.description}</Txt>
      </div>
      <Phone tutorial={tutorial} step={step} previous={tutorial.steps[index-1]} frame={local}/>
      <div style={{position:'absolute',top:836,left:26,right:26,textAlign:'center'}}><Txt size={13} weight={550} style={{whiteSpace:'pre-line',lineHeight:1.7}}>{step.hint}</Txt></div>
      <div style={{position:'absolute',bottom:25,left:34,right:34,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Txt size={10} color={C.muted}>操作示意 · 请以手机实际界面为准</Txt><Txt size={10} color={C.muted}>{index+1} / {tutorial.steps.length}</Txt></div>
      <div style={{position:'absolute',top:390,left:44,fontSize:96,fontWeight:800,color:'#E7E3DD',letterSpacing:-5,zIndex:-1}}>{String(index+1).padStart(2,'0')}</div>
    </div>
  </AbsoluteFill>;
}

const Root = () => <>{tutorials.map(item=>{
  const tutorial={...item,steps:item.steps.map((step,index)=>({...step,narration:narration[item.id][index]}))};
  return <Composition key={tutorial.id} id={tutorial.id} component={SettingTutorial} defaultProps={{tutorial}} width={1080} height={1920} fps={30} durationInFrames={duration(tutorial)*30} calculateMetadata={({props})=>({durationInFrames:duration(props.tutorial)*30})}/>;
})}</>;
registerRoot(Root);
