const {test}=require('node:test');
const assert=require('node:assert/strict');
const H=require('./project-history.js');
const data=(name,extra={})=>({name,versions:{harmony:{clips:[],cursor:0,zoom:100},ios:{clips:[],cursor:0,zoom:100}},...extra});

test('默认只保留十五个动作及它们之前的还原起点',()=>{
  const h=H.create(data('起点'));
  for(let i=1;i<=25;i++)H.commit(h,data(`状态${i}`),`动作${i}`);
  assert.equal(h.entries.length,16);
  assert.equal(h.index,15);
  assert.equal(h.entries[0].data.name,'状态10');
  assert.equal(H.restore(h,0).name,'状态10');
  assert.equal(H.restore(h,15).name,'状态25');
});

test('选中片段、播放定位和切换工作区不占用动作历史',()=>{
  const original=data('作品'),h=H.create(original),changed=structuredClone(original);
  changed.versions.harmony.cursor=2;changed.versions.harmony.selectedClipId='clip-a';changed.versions.harmony.zoom=200;changed.view={workspace:'publish',platform:'ios'};
  assert.equal(H.commit(h,changed,'切换界面'),false);
  assert.equal(h.entries.length,1);
});

test('还原后可前进；继续编辑后用新动作替换后续历史',()=>{
  const h=H.create(data('初始'));
  H.commit(h,data('A'),'操作A');H.commit(h,data('B'),'操作B');
  assert.equal(H.restore(h,1).name,'A');
  assert.equal(H.restore(h,2).name,'B');
  H.restore(h,1);H.commit(h,data('C'),'操作C');
  assert.deepEqual(h.entries.map(e=>e.data.name),['初始','A','C']);
  assert.equal(H.restore(h,3),null);
});

test('项目历史互不影响，历史快照包含双平台但不共享编辑对象',()=>{
  const a=H.create(data('项目A')),b=H.create(data('项目B'));
  const change=data('项目A');change.versions.ios.clips=[{assetId:'image',kind:'image',frames:10}];
  H.commit(a,change,'iOS加入图片');change.versions.ios.clips[0].frames=20;
  const restored=H.restore(a,1);assert.equal(restored.versions.ios.clips[0].frames,10);
  restored.name='修改';assert.equal(a.entries[1].data.name,'项目A');
  assert.equal(b.entries.length,1);
});

test('普通与折叠预览切换不占历史，实际裁剪修改可以还原',()=>{
  const original=data('静态作品',{staticEditor:{assetId:'image',mode:'both',deviceId:'phone',crops:{image:{x:0,scale:1}}},packageView:{deviceId:'phone'}});
  const h=H.create(original),view=structuredClone(original);
  view.staticEditor.mode='fold';view.staticEditor.deviceId='fold';view.packageView.deviceId='fold';
  assert.equal(H.commit(h,view,'切换预览'),false);
  view.staticEditor.crops.image.x=12;
  assert.equal(H.commit(h,view,'调整裁剪'),true);
  assert.equal(H.restore(h,0).staticEditor.crops.image.x,0);
  assert.equal(H.restore(h,1).staticEditor.crops.image.x,12);
});

test('内容作品切页和定位不占历史，替换素材与排版可以还原',()=>{
  const original=data('内容项目',{content:{activeId:'gallery',works:[{id:'gallery',pageIndex:0,cursor:0,selectedClipId:'phone',slots:{wallpaper:'image-a'},pages:[{clips:[{id:'phone',x:50}]}]}]}});
  const h=H.create(original),view=structuredClone(original);
  view.content.activeId='video';view.content.works[0].pageIndex=2;view.content.works[0].cursor=3;view.content.works[0].selectedClipId='text';
  assert.equal(H.commit(h,view,'切换内容预览'),false);
  view.content.works[0].slots.wallpaper='image-b';view.content.works[0].pages[0].clips[0].x=60;
  assert.equal(H.commit(h,view,'替换素材并调整排版'),true);
  assert.equal(H.restore(h,0).content.works[0].slots.wallpaper,'image-a');
  assert.equal(H.restore(h,1).content.works[0].pages[0].clips[0].x,60);
});
