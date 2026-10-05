const test=require('node:test');
const assert=require('node:assert/strict');
const Export=require('./resource-export.js');
test('keeps duplicate names without overwriting files, including case-insensitive collisions',()=>{
  const rows=['same.png','SAME.png','same.png'].map(name=>({group:'原始素材',file:{name}}));
  const entries=Export.archiveEntries(rows);assert.deepEqual(entries.map(e=>e.path),['原始素材/same.png','原始素材/SAME (2).png','原始素材/same (3).png']);
  entries.forEach((entry,i)=>assert.equal(entry.file,rows[i].file));
});
test('prevents resource names escaping the archive folder',()=>{
  const entries=Export.archiveEntries([{group:'../资源',file:{name:'../../picture.png'}}]);
  assert.equal(entries[0].path,'__资源/__.._picture.png');assert.equal(entries[0].path.split('/').length,2);
});
test('does not change original names unnecessarily and separates source/exported resources',()=>{
  const file={name:'壁纸原图.png'};
  assert.deepEqual(Export.archiveEntries([{group:'原始素材',file},{group:'壁纸资源',file}]).map(e=>e.path),['原始素材/壁纸原图.png','壁纸资源/壁纸原图.png']);
  assert.equal(Export.filename('壁纸原图.png'),'壁纸原图.png');
});
