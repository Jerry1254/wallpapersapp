const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto');

async function fixture(){
  const storedHash='b'.repeat(64),calls=[];
  let asset={id:'17',sha256:storedHash,validationStatus:'READY',mimeType:'image/webp',widthPx:320,heightPx:640};
  const B={connected:true,requireConnection:async()=>{},get:async()=>null,put:async()=>{},canonical:JSON.stringify,
    hash:async blob=>'file-'+crypto.createHash('sha256').update(Buffer.from(await blob.arrayBuffer())).digest('hex'),
    request:async(path,options={})=>{
      calls.push({path,method:options.method||'GET'});
      if(path==='/wallpaper/capabilities')return {environment:{id:'LOCAL_DEV'},supportedOperations:{wallpaperPublication:true},coverMaximumBytes:1048576,coverMimeTypes:['image/png']};
      if(path.startsWith('/wallpaper/publications?'))return {items:[]};
      if(path==='/wallpaper/assets'||path==='/wallpaper/assets/17')return {...asset};
      throw Error('Unexpected request: '+path);
    }};
  const context=vm.createContext({window:{CreatorBackend:B},Blob,FormData,crypto:crypto.webcrypto,setTimeout,clearTimeout});
  vm.runInContext(fs.readFileSync(__dirname+'/wallpaper-publication.js','utf8'),context);
  return {session:await context.window.WallpaperPublication.open('qa'),B,calls,setAsset:value=>{asset={...asset,...value};}};
}

test('accepts backend-optimized WebP covers and reuses their stored hash',async()=>{
  const f=await fixture(),file=new Blob(['original PNG bytes'],{type:'image/png'});
  const first=await f.session.upload(file,'WALLPAPER_COVER');
  assert.equal(first.sha256,'b'.repeat(64));
  assert.equal((await f.session.upload(file,'WALLPAPER_COVER')).id,first.id);
  assert.equal(f.calls.filter(c=>c.method==='POST').length,1);
});

test('rejects a cached cover whose stored file hash changes',async()=>{
  const f=await fixture(),file=new Blob(['PNG'],{type:'image/png'});
  await f.session.upload(file,'WALLPAPER_COVER');
  f.setAsset({sha256:'c'.repeat(64)});
  await assert.rejects(f.session.upload(file,'WALLPAPER_COVER'),/已上传的文件未就绪或已失效/);
});

test('keeps exact original hash validation for formal image resources',async()=>{
  const f=await fixture(),file=new Blob(['PNG'],{type:'image/png'});
  const rule={maximumBytes:1048576,acceptedMimeTypes:['image/png']};
  await assert.rejects(f.session.upload(file,'WALLPAPER_STATIC_IMAGE',rule),/后台未返回可用的正式文件/);
  f.setAsset({sha256:crypto.createHash('sha256').update('PNG').digest('hex'),mimeType:'image/png'});
  assert.equal((await f.session.upload(file,'WALLPAPER_STATIC_IMAGE',rule)).id,'17');
});

test('rejects unready or malformed optimized cover responses',async()=>{
  for(const changed of [{validationStatus:'FAILED'},{sha256:'invalid'},{mimeType:'image/png'},{widthPx:0}]){
    const f=await fixture();f.setAsset(changed);
    await assert.rejects(f.session.upload(new Blob(['PNG'],{type:'image/png'}),'WALLPAPER_COVER'),/后台未返回可用的正式文件/);
  }
});

test('requires persisted duration on uploaded and cached Android video assets',async()=>{
  const f=await fixture(),file=new Blob(['video bytes'],{type:'video/mp4'}),rule={maximumBytes:1048576,acceptedMimeTypes:['video/mp4']};
  f.setAsset({sha256:crypto.createHash('sha256').update('video bytes').digest('hex'),mimeType:'video/mp4'});
  await assert.rejects(f.session.upload(file,'VIDEO',rule),/后台未返回可用的正式文件/);
  f.setAsset({durationMs:5000});
  assert.equal((await f.session.upload(file,'VIDEO',rule)).durationMs,5000);
  f.setAsset({durationMs:null});
  await assert.rejects(f.session.upload(file,'VIDEO',rule),/已上传的文件未就绪或已失效/);
});

test('re-uploads legacy video cache entries without deleting their original assets',async()=>{
  const f=await fixture(),file=new Blob(['video bytes'],{type:'video/mp4'});
  const sha256=(await f.B.hash(file)).slice(5);
  const oldKey=await f.B.hash(new Blob([f.B.canonical({environment:'LOCAL_DEV',purpose:'VIDEO',sha256,filename:'wallpaper.zip',mime:'video/mp4'})]));
  f.session.record.uploads[oldKey]={key:crypto.randomUUID(),result:{id:'9',sha256,validationStatus:'READY',durationMs:null}};
  f.setAsset({sha256,mimeType:'video/mp4',durationMs:5000});
  const result=await f.session.upload(file,'VIDEO',{maximumBytes:1048576,acceptedMimeTypes:['video/mp4']});
  assert.equal(result.id,'17');
  assert.equal(f.calls.filter(c=>c.method==='POST').length,1);
  assert.equal(f.session.record.uploads[oldKey].result.id,'9');
});
