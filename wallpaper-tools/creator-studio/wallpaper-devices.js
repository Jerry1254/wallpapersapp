/* Screen presets reuse the 4D workbench's verified physical pixel dimensions. */
(function(root){
  'use strict';
  const devices=[
    {id:'iphone17pro',name:'iPhone 17 Pro',kind:'phone',group:'iPhone · @3×',screens:[{id:'main',name:'主屏',width:1206,height:2622}]},
    {id:'iphone17promax',name:'iPhone 17 Pro Max',kind:'phone',group:'iPhone · @3×',screens:[{id:'main',name:'主屏',width:1320,height:2868}]},
    {id:'xiaomi15',name:'小米 15',kind:'phone',group:'安卓直板',screens:[{id:'main',name:'主屏',width:1200,height:2670}]},
    {id:'xiaomi15ultra',name:'小米 15 Ultra',kind:'phone',group:'安卓直板',screens:[{id:'main',name:'主屏',width:1440,height:3200}]},
    {id:'oneplus13',name:'一加 13',kind:'phone',group:'安卓直板',screens:[{id:'main',name:'主屏',width:1440,height:3168}]},
    {id:'fold7',name:'Galaxy Z Fold7',kind:'fold',group:'大折叠',screens:[{id:'cover',name:'外屏 · 折叠',width:1080,height:2520},{id:'inner',name:'内屏 · 展开',width:1968,height:2184}]},
    {id:'findn5',name:'OPPO Find N5',kind:'fold',group:'大折叠',screens:[{id:'cover',name:'外屏 · 折叠',width:1140,height:2616},{id:'inner',name:'内屏 · 展开',width:2248,height:2480}]},
    {id:'xfold5',name:'vivo X Fold5',kind:'fold',group:'大折叠',screens:[{id:'cover',name:'外屏 · 折叠',width:1172,height:2748},{id:'inner',name:'内屏 · 展开',width:2200,height:2480}]},
    {id:'flip7',name:'Galaxy Z Flip7',kind:'flip',group:'小折叠',screens:[{id:'main',name:'主屏 · 展开',width:1080,height:2520},{id:'cover',name:'外屏 · 画幅参考',width:948,height:1048,referenceOnly:true}]},
    {id:'generic169',name:'通用安卓 · 9:16',kind:'phone',group:'通用画幅',screens:[{id:'main',name:'竖屏',width:1080,height:1920}]},
    {id:'generic1440',name:'通用高清 · 9:16',kind:'phone',group:'通用画幅',screens:[{id:'main',name:'竖屏',width:1440,height:2560}]},
    {id:'legacyfull',name:'通用全面屏 · 1242 × 2688',kind:'phone',group:'通用画幅',screens:[{id:'main',name:'竖屏',width:1242,height:2688}]},
    {id:'generic209',name:'通用安卓 · 9:20',kind:'phone',group:'通用画幅',screens:[{id:'main',name:'竖屏',width:1080,height:2400}]}
  ];
  const device=id=>devices.find(d=>d.id===id)||devices[0];
  const screen=(id,screenId)=>device(id).screens.find(s=>s.id===screenId)||device(id).screens[0];
  const key=(assetId,deviceId,screenId)=>`${assetId}:${deviceId}:${screenId}`;
  const fresh=()=>({assetId:'demo-image',deviceId:'iphone17pro',screenId:'main',phoneId:'iphone17pro',phoneScreen:'main',foldId:'fold7',mode:'both',crops:{},times:{}});
  function crop(editor,assetId=editor.assetId,deviceId=editor.deviceId,screenId=editor.screenId){
    const spec=screen(deviceId,screenId),id=key(assetId,deviceId,spec.id);
    return editor.crops[id] ||= {width:spec.width,height:spec.height,scale:1,x:0,y:0};
  }
  function choose(editor,id,screenId){
    const d=device(id),s=screen(id,screenId);editor.deviceId=d.id;editor.screenId=s.id;
    if(d.kind==='fold'&&s.id==='inner')editor.foldId=d.id;
    else{editor.phoneId=d.id;editor.phoneScreen=s.id;}
    return crop(editor);
  }
  function choosePackagePreview(editor,id,screenId){
    choose(editor,id,screenId);
    if(editor.mode!=='both')editor.mode=device(editor.deviceId).kind==='fold'&&editor.screenId==='inner'?'fold':'phone';
  }
  function setPackagePreviewMode(editor,mode){
    editor.mode=mode;
    if(mode==='fold')choose(editor,editor.foldId,'inner');
    else if(mode==='phone')choose(editor,editor.phoneId,editor.phoneScreen);
  }
  function normalize(value){
    const e={...fresh(),...value,crops:Object.fromEntries(Object.entries(value?.crops||{}).map(([k,v])=>[k,{...v}])),times:{...(value?.times||{})}};
    e.deviceId=device(e.deviceId).id;e.screenId=screen(e.deviceId,e.screenId).id;
    e.phoneId=device(e.phoneId).id;e.phoneScreen=screen(e.phoneId,e.phoneScreen).id;
    if(device(e.foldId).kind!=='fold')e.foldId='fold7';
    if(!['phone','fold','both'].includes(e.mode))e.mode='both';return e;
  }
  const api={devices,device,screen,key,fresh,crop,choose,choosePackagePreview,setPackagePreviewMode,normalize};
  root.WallpaperDevices=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
