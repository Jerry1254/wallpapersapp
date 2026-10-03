import {bundle} from '@remotion/bundler';
import {getCompositions, openBrowser, renderMedia, renderStill} from '@remotion/renderer';
import {mkdir, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'out');
const requested = process.argv.slice(2).filter(arg => !arg.startsWith('--'));
const stillsOnly = process.argv.includes('--stills');
const qa = process.argv.includes('--qa');
await mkdir(out, {recursive:true});
const serveUrl = await bundle({entryPoint:path.join(root,'src/index.tsx')});
const chromePath = process.env.TUTORIAL_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const browser = await openBrowser('chrome', {...(existsSync(chromePath)?{browserExecutable:chromePath}:{}), logLevel:'error'});
try {
  const compositions = await getCompositions(serveUrl,{puppeteerInstance:browser});
  const selected = compositions.filter(c=>requested.length===0||requested.includes(c.id));
  if (!selected.length) throw new Error(`No tutorials matched: ${requested.join(', ')}`);
  for (const composition of selected) {
    await renderStill({serveUrl,composition,puppeteerInstance:browser,frame:75,output:path.join(out,`${composition.id}.png`),scale:0.5,logLevel:'error'});
    if (qa) {
      await mkdir(path.join(out,'qa'),{recursive:true});
      let stepStart = 0;
      for (const [index,step] of composition.props.tutorial.steps.entries()) {
        await renderStill({serveUrl,composition,puppeteerInstance:browser,frame:stepStart+Math.min(120,step.seconds*composition.fps-15),output:path.join(out,'qa',`${composition.id}-step-${index+1}.png`),scale:0.5,logLevel:'error'});
        stepStart += step.seconds*composition.fps;
      }
    }
    if (stillsOnly) continue;
    console.log(`Rendering ${composition.id}: ${composition.durationInFrames/composition.fps}s`);
    let reported = -1;
    await renderMedia({serveUrl,composition,puppeteerInstance:browser,codec:'h264',pixelFormat:'yuv420p',crf:20,concurrency:2,outputLocation:path.join(out,`${composition.id}.mp4`),logLevel:'error',onProgress:({progress})=>{
      const bucket=Math.floor(progress*4);
      if(bucket>reported){reported=bucket;console.log(`${composition.id}: ${Math.min(bucket*25,100)}%`);}
    }});
    console.log(`Saved ${composition.id}.mp4`);
  }
  await writeFile(path.join(out,'manifest.json'),JSON.stringify(compositions.map(c=>({id:c.id,title:c.props.tutorial.title,width:c.width,height:c.height,fps:c.fps,seconds:c.durationInFrames/c.fps,file:`${c.id}.mp4`,status:'visual-draft',interface:'illustrated',audio:false})),null,2)+'\n');
  const cards=compositions.map(c=>`<article><div class="heading"><span>${c.id.slice(0,2)}</span><h2>${c.props.tutorial.title}</h2><small>${c.durationInFrames/c.fps} 秒</small></div><video controls playsinline preload="metadata" poster="${c.id}.png" src="${c.id}.mp4"></video><a href="${c.id}.mp4" download>下载 MP4</a></article>`).join('');
  await writeFile(path.join(out,'preview.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>倾境壁纸 · 五支设置教程</title><style>*{box-sizing:border-box}body{margin:0;background:#f4f3f0;color:#191817;font-family:'PingFang SC',sans-serif;padding:44px 5vw}header{max-width:1280px;margin:auto auto 32px}.eyebrow{font-size:12px;letter-spacing:3px;color:#6f6a64}h1{font-size:34px;margin:14px 0}p{color:#6f6a64;line-height:1.7}.badge{display:inline-block;background:#fff3d8;padding:6px 12px;border-radius:16px;font-size:12px}.grid{max-width:1280px;margin:auto;display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:22px}article{background:white;border:1px solid #e7e3dd;border-radius:22px;padding:16px}.heading{display:flex;gap:10px;align-items:center;margin-bottom:12px}.heading span{font-size:12px;background:#f4a91e;border-radius:8px;padding:5px}h2{font-size:17px;margin:0;flex:1}small{color:#6f6a64}video{width:100%;border-radius:14px;display:block;background:#f4f3f0}a{display:block;color:#191817;text-align:center;font-size:13px;margin-top:14px;padding:10px;border:1px solid #e7e3dd;border-radius:12px;text-decoration:none}</style><header><div class="eyebrow">QINGJING · SETTING GUIDES</div><h1>壁纸设置，看一遍就会。</h1><p>五支轻量 MG 教程样片 · 1080 × 1920 · 30 fps · 无配音<br>手机画面为操作示意，正式使用前按对应机型核对系统菜单。</p><span class="badge">风格样片 · 未发布到 App</span></header><div class="grid">${cards}</div></html>`);
} finally {
  await browser.close({silent:true});
}
