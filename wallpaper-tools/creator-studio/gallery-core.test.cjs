const test=require('node:test'),assert=require('node:assert/strict');
const C=require('./content-core.js'),G=require('./gallery-core.js'),H=require('./project-history.js');
const near=(a,b)=>assert(Math.abs(a-b)<1e-7,a+' != '+b);
test('instance edits retain unmodified master properties after serialization and resizing',()=>{const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);f.nodes=[G.node('image',{width:300,height:400,assetId:'first'})];g.selection=[f.nodes[0].id];const def=G.makeComponent(g),a=f.nodes[0],b=G.addInstance(g,def.id);G.refreshInstances(g);a.children[0].panX=20;G.captureOverrides(g);def.nodes[0].assetId='new-source';def.nodes[0].panX=8;G.refreshInstances(g);assert.equal(a.children[0].panX,20);assert.equal(b.children[0].panX,8);assert.equal(a.children[0].assetId,'new-source');assert.ok(!JSON.stringify(a).includes('children'));const restored=C.copy(g);G.refreshInstances(restored);const saved=restored.frames[0].nodes[0];assert.equal(saved.children[0].panX,20);saved.width*=2;G.refreshInstances(restored);assert.equal(saved.children[0].width,600);});
test('editing an instance adds, deletes and overrides children locally while retaining resource references',()=>{const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);f.nodes=[G.node('image',{width:300,height:400,assetId:'original'})];g.selection=[f.nodes[0].id];const def=G.makeComponent(g),a=f.nodes[0],b=G.addInstance(g,def.id);G.refreshInstances(g);a.children[0].assetId='local-image';a.children.push(G.node('rect',{x:20,y:30,width:40,height:50}));G.captureOverrides(g);G.refreshInstances(g);assert.equal(a.children.length,2);assert.equal(b.children.length,1);assert.equal(def.nodes[0].assetId,'original');assert.ok(G.assets(C.copy(w)).includes('local-image'));a.children.splice(0,1);G.captureOverrides(g);G.refreshInstances(g);assert.equal(a.children.length,1);assert.equal(b.children.length,1);});
test('multi-selection spans independent frames without collecting descendants twice',()=>{const w=C.create('gallery'),g=G.ensure(w),a=g.frames[0],b=g.frames[1];a.nodes=[G.node('rect')];b.nodes=[G.node('rect')];g.selection=[a.nodes[0].id,b.nodes[0].id];assert.equal(G.roots(g).length,2);assert.equal(G.roots(g)[1].surface.id,b.id);g.surfaceSelection=[a.id,b.id];assert.equal(G.selectedSurfaces(g).length,2);});
test('duplicated instance children stay selected and editable after proxy refresh and restore',()=>{
  const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);
  f.nodes=[G.node('image',{assetId:'original',width:300,height:400})];g.selection=[f.nodes[0].id];
  const def=G.makeComponent(g),a=f.nodes[0],b=G.addInstance(g,def.id);G.refreshInstances(g);
  g.selection=[a.children[0].id];G.duplicate(g);G.captureOverrides(g);G.refreshInstances(g);
  const selected=G.selected(g);assert.equal(selected.length,1);assert.equal(selected[0].node.assetId,'original');
  selected[0].node.panX=25;G.captureOverrides(g);G.refreshInstances(g);
  const restored=C.copy(g);G.refreshInstances(restored);assert.equal(G.selected(restored)[0].node.panX,25);
  assert.equal(a.children.length,2);assert.equal(b.children.length,1);assert.equal(def.nodes.length,1);
  G.remove(g);G.captureOverrides(g);G.refreshInstances(g);assert.equal(a.children.length,1);assert.equal(b.children.length,1);
});
test('legacy pages migrate once into independent frames with one shared original',()=>{
  const w=C.create('gallery'),g=G.ensure(w),key=Object.keys(g.components)[0];
  assert.equal(g.frames.length,4);assert.equal(G.instances(g,key).length,4);
  g.frames[1].width=1920;assert.equal(g.frames[0].width,1080);
  assert.equal(G.ensure(w),g);assert.equal(g.components[key].nodes[0].slot,'wallpaper');
});
test('group and rotated ungroup preserve each child world position and rotation',()=>{
  const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);
  f.nodes=[G.node('rect',{x:20,y:30,width:50,height:80,rotation:15}),G.node('rect',{x:180,y:170,width:90,height:100})];g.selection=f.nodes.map(n=>n.id);
  const before=f.nodes.map(n=>G.point(G.transform(n),{x:0,y:0}));const group=G.group(g);
  group.children.forEach((n,i)=>{const p=G.point(G.multiply(G.transform(group),G.transform(n)),{x:0,y:0});near(p.x,before[i].x);near(p.y,before[i].y);});
  group.rotation=45;group.opacity=.5;const rotated=group.children.map(n=>G.point(G.multiply(G.transform(group),G.transform(n)),{x:0,y:0}));
  G.ungroup(g);f.nodes.forEach((n,i)=>{const p=G.point(G.transform(n),{x:0,y:0});near(p.x,rotated[i].x);near(p.y,rotated[i].y);near(n.opacity,.5);});
});
test('component edits are shared while instances retain independent geometry',()=>{
  const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);f.nodes=[G.node('image',{assetId:'first',x:12,y:32,width:300,height:400})];g.selection=[f.nodes[0].id];
  const def=G.makeComponent(g,'主图'),a=f.nodes[0],b=G.addInstance(g,def.id);b.x=550;b.width=200;
  def.nodes[0].assetId='replacement';assert.equal(g.components[a.componentId].nodes[0].assetId,'replacement');assert.equal(g.components[b.componentId].nodes[0].assetId,'replacement');assert.equal(a.x,12);assert.equal(b.x,550);assert.equal(b.width,200);
  g.selectedSurface=def.id;assert.equal(G.addInstance(g,def.id),null);
});
test('copy makes independent nested nodes and shared component references',()=>{
  const w=C.create('gallery'),g=G.ensure(w),f=G.surface(g);g.selection=f.nodes.map(n=>n.id);G.group(g);const group=f.nodes[0];G.duplicate(g);
  assert.notEqual(f.nodes[1].id,group.id);assert.notEqual(f.nodes[1].children[0].id,group.children[0].id);assert.equal(f.nodes[1].children[0].componentId,group.children[0].componentId);
  f.nodes[1].children[0].x=5;assert.notEqual(group.children[0].x,5);
});
test('drafts do not create content and legacy generated files migrate only once',()=>{
  const w=C.create('gallery');let d=C.normalize({works:[w],activeId:w.id});assert.equal(d.items.length,0);assert.equal(d.drafts.length,1);
  w.generated={assetIds:['png-a','png-b'],at:'2026-10-05'};d=C.normalize({works:[w],activeId:w.id});assert.equal(d.items.length,1);assert.equal(C.normalize(d).items.length,1);assert.equal(d.drafts[0].generated,undefined);
});
test('gallery viewport and selection do not consume history; master and frame edits do',()=>{
  const w=C.create('gallery');G.ensure(w);const initial={content:{drafts:[w],items:[],activeId:w.id}},h=H.create(initial),data=C.copy(initial),g=data.content.drafts[0].gallery;
  g.view={x:400,y:20,zoom:2};g.selection=['any'];g.selectedSurface=g.frames[1].id;assert.equal(H.commit(h,data,'查看画布'),false);
  g.frames[1].width=800;Object.values(g.components)[0].nodes[0].assetId='new';assert.equal(H.commit(h,data,'调整模板'),true);assert.equal(H.restore(h,0).content.drafts[0].gallery.frames[1].width,1080);
});
