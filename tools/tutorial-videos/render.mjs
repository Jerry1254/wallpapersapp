import {bundle} from '@remotion/bundler';
import {getCompositions, openBrowser, renderMedia, renderStill} from '@remotion/renderer';
import {mkdir, readFile, rename, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const execute = promisify(execFile);
const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'out', 'current');
const voices = path.join(root, 'out', 'narration');
const requested = process.argv.slice(2).filter(arg => !arg.startsWith('--'));
const stillsOnly = process.argv.includes('--stills');
const qa = process.argv.includes('--qa');
const pythonRuntime = path.join(root, 'out', 'tts-runtime', 'bin', 'python');
const python = process.env.TUTORIAL_TTS_PYTHON || (existsSync(pythonRuntime) ? pythonRuntime : 'python3');
const runPython = args => new Promise((resolve, reject) => {
  const child = spawn(python, args, {stdio:['ignore', 'inherit', 'inherit']});
  child.on('error', reject);
  child.on('exit', code => code === 0 ? resolve() : reject(new Error(`Voice/validation process exited with ${code}`)));
});

await mkdir(out, {recursive:true});
const serveUrl = await bundle({entryPoint:path.join(root, 'src/index.tsx')});
// Dedicated headless Chrome avoids corrupted/tiled frames from system Chrome.
const browser = await openBrowser('chrome', {chromeMode:'headless-shell', logLevel:'error'});
try {
  const base = await getCompositions(serveUrl, {puppeteerInstance:browser});
  let compositions = base.filter(composition => requested.length === 0 || requested.includes(composition.id));
  if (!compositions.length) throw new Error(`No tutorials matched: ${requested.join(', ')}`);
  if (!stillsOnly) {
    await mkdir(voices, {recursive:true});
    const plan = compositions.map(composition => ({id:composition.id, steps:composition.props.tutorial.steps}));
    const planFile = path.join(voices, 'plan.json');
    await writeFile(planFile, JSON.stringify(plan, null, 2));
    await runPython([path.join(root, 'narrate.py'), planFile, voices]);
    const timeline = JSON.parse(await readFile(path.join(voices, 'timeline.json'), 'utf8'));
    compositions = compositions.map(composition => {
      const narration = timeline.find(item => item.id === composition.id);
      return {...composition,
        durationInFrames:narration.steps.reduce((sum, step) => sum + step.seconds * composition.fps, 0),
        props:{tutorial:{...composition.props.tutorial, steps:narration.steps}},
        audioFile:narration.audioFile,
      };
    });
  }

  for (const composition of compositions) {
    await renderStill({serveUrl, composition, puppeteerInstance:browser, frame:75,
      output:path.join(out, `${composition.id}.png`), scale:0.5, logLevel:'error'});
    if (qa) {
      await mkdir(path.join(out, 'qa'), {recursive:true});
      let stepStart = 0;
      for (const [index, step] of composition.props.tutorial.steps.entries()) {
        await renderStill({serveUrl, composition, puppeteerInstance:browser,
          frame:stepStart + Math.min(120, step.seconds * composition.fps - 15),
          output:path.join(out, 'qa', `${composition.id}-step-${index+1}.png`), scale:0.5, logLevel:'error'});
        stepStart += step.seconds * composition.fps;
      }
    }
    if (stillsOnly) continue;
    const seconds = composition.durationInFrames / composition.fps;
    console.log(`Rendering ${composition.id}: ${seconds}s, Chinese voice`);
    const silentFile = path.join(out, `${composition.id}.silent.mp4`);
    const stagedFile = path.join(out, `${composition.id}.staged.mp4`);
    const finalFile = path.join(out, `${composition.id}.mp4`);
    let reported = -1;
    await renderMedia({serveUrl, composition, puppeteerInstance:browser,
      codec:'h264', pixelFormat:'yuv420p', crf:20, concurrency:1,
      outputLocation:silentFile, logLevel:'error', onProgress:({progress}) => {
        const bucket = Math.floor(progress * 4);
        if (bucket > reported) {reported = bucket; console.log(`${composition.id}: ${Math.min(bucket*25, 100)}%`);}
      }});
    await execute('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-i', silentFile,
      '-i', composition.audioFile, '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy',
      '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', '-t', String(seconds), stagedFile]);
    await runPython([path.join(root, 'verify-video.py'), stagedFile, String(seconds)]);
    await rename(stagedFile, finalFile);
    await rename(path.join(out, `${composition.id}.staged.validation.json`), path.join(out, `${composition.id}.validation.json`));
    console.log(`Saved and verified ${composition.id}.mp4`);
  }
  if (!stillsOnly) {
    const manifest = compositions.map(composition => ({id:composition.id, title:composition.props.tutorial.title,
      width:composition.width, height:composition.height, fps:composition.fps,
      seconds:composition.durationInFrames / composition.fps, file:`${composition.id}.mp4`,
      status:'voiced-draft', interface:'illustrated', audio:true, voice:'Chinese female synthetic narration'}));
    await writeFile(path.join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    const cards = manifest.map(item => `<article><div class="heading"><span>${item.id.slice(0,2)}</span><h2>${item.title}</h2><small>${item.seconds} 秒</small></div><video controls playsinline preload="metadata" poster="${item.id}.png" src="${item.file}"></video><a href="${item.file}" download>下载 MP4</a></article>`).join('');
    await writeFile(path.join(out, 'preview.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>倾境壁纸 · 中文配音版设置教程</title><style>*{box-sizing:border-box}body{margin:0;background:#f4f3f0;color:#191817;font-family:'PingFang SC',sans-serif;padding:44px 5vw}header{max-width:1280px;margin:auto auto 32px}.eyebrow{font-size:12px;letter-spacing:3px;color:#6f6a64}h1{font-size:34px;margin:14px 0}p{color:#6f6a64;line-height:1.7}.badge{display:inline-block;background:#fff3d8;padding:6px 12px;border-radius:16px;font-size:12px}.grid{max-width:${manifest.length === 1 ? 380 : 1280}px;margin:auto;display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:22px}article{background:white;border:1px solid #e7e3dd;border-radius:22px;padding:16px}.heading{display:flex;gap:10px;align-items:center;margin-bottom:12px}.heading span{font-size:12px;background:#f4a91e;border-radius:8px;padding:5px}h2{font-size:17px;margin:0;flex:1}small{color:#6f6a64}video{width:100%;border-radius:14px;display:block;background:#f4f3f0}a{display:block;color:#191817;text-align:center;font-size:13px;margin-top:14px;padding:10px;border:1px solid #e7e3dd;border-radius:12px;text-decoration:none}</style><header><div class="eyebrow">QINGJING · SETTING GUIDES</div><h1>${manifest.length === 1 ? manifest[0].title + '设置教程' : '壁纸设置，看一遍就会。'}</h1><p>中文配音版 · 1080 × 1920 · 30 fps · 每一步配音与画面对齐<br>手机画面为操作示意，正式使用前按对应机型核对系统菜单。</p><span class="badge">中文女声合成配音 · 操作示意</span></header><div class="grid">${cards}</div><script>document.querySelectorAll('video').forEach(video=>video.addEventListener('play',()=>document.querySelectorAll('video').forEach(other=>{if(other!==video)other.pause()})))</script></html>`);
    if (requested.length === 0) {
      await writeFile(path.join(root, 'out', 'preview.html'), '<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=current/preview.html"><a href="current/preview.html">打开中文配音版教程</a>');
    }
  }
} finally {
  await browser.close({silent:true});
}
