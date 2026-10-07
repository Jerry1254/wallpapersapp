const test=require('node:test');
const assert=require('node:assert/strict');
const D=require('./wallpaper-devices.js');

test('default export uses iPhone physical pixels, without multiplying them again',()=>{
  const e=D.fresh(),c=D.crop(e);
  assert.equal(c.width,1206);assert.equal(c.height,2622);
});
test('ordinary and unfolded screens retain independent crops for each source image',()=>{
  const e=D.fresh();D.crop(e).x=12;D.crop(e).scale=1.4;
  const fold=D.choose(e,'fold7','inner');fold.x=-20;
  assert.equal(fold.width,1968);assert.equal(fold.height,2184);
  assert.equal(e.phoneId,'iphone17pro');assert.equal(e.foldId,'fold7');
  const ordinary=D.choose(e,'iphone17pro','main');assert.equal(ordinary.x,12);assert.equal(ordinary.scale,1.4);
  e.assetId='another-image';assert.equal(D.crop(e).x,0);
  e.assetId='demo-image';assert.equal(D.crop(e).x,12);
});
test('switching fold cover and inner screens never overwrites their saved framing',()=>{
  const e=D.fresh();D.choose(e,'findn5','inner').y=10;
  D.choose(e,'findn5','cover').y=-5;
  assert.equal(D.choose(e,'findn5','inner').y,10);
  assert.equal(D.choose(e,'findn5','cover').y,-5);
});
test('restoring editor state isolates the saved history from subsequent edits',()=>{
  const original=D.fresh();D.crop(original).x=7;
  const restored=D.normalize(original);D.crop(restored).x=25;
  assert.equal(D.crop(original).x,7);
  assert.equal(D.normalize({deviceId:'missing',mode:'unknown'}).mode,'both');
});
test('4D screen selection and single-view mode describe the same visible screen',()=>{
  const e=D.fresh();D.setPackagePreviewMode(e,'fold');
  assert.equal(e.deviceId,'fold7');assert.equal(e.screenId,'inner');
  D.choosePackagePreview(e,'fold7','cover');
  assert.equal(e.mode,'phone');assert.equal(e.phoneScreen,'cover');
  D.choosePackagePreview(e,'fold7','inner');assert.equal(e.mode,'fold');
  D.choosePackagePreview(e,'iphone17pro','main');assert.equal(e.mode,'phone');
  D.setPackagePreviewMode(e,'both');D.choosePackagePreview(e,'findn5','inner');assert.equal(e.mode,'both');
  D.setPackagePreviewMode(e,'phone');assert.equal(e.deviceId,'iphone17pro');assert.equal(e.screenId,'main');
  D.setPackagePreviewMode(e,'fold');assert.equal(e.deviceId,'findn5');assert.equal(e.screenId,'inner');
});
