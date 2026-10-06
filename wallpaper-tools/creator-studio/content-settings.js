/* Canvas and export settings for the video composition, shown when nothing is selected. */
(() => {
  'use strict';
  const C=window.ContentCore,$=id=>document.getElementById(id);
  let H;
  const work=()=>H.work();
  function mount(bridge){
    H=bridge;
    $('content-project-settings').innerHTML=`
      <section id="content-video-size">
        <div class="section-title"><strong>画布设置</strong><button id="content-size-swap" title="交换画布宽高" aria-label="交换画布宽高">⇄</button></div>
        <div class="field"><label for="content-work-name">作品名称</label><input id="content-work-name" maxlength="60"></div>
        <div class="field"><label for="content-size-preset">尺寸预设</label><select id="content-size-preset"><option value="">自定义</option><option value="720x1280">9:16 · 720 × 1280</option><option value="1080x1920">9:16 · 1080 × 1920</option><option value="1080x1440">3:4 · 1080 × 1440</option><option value="1080x1080">1:1 · 1080 × 1080</option><option value="1920x1080">16:9 · 1920 × 1080</option><option value="1280x720">16:9 · 1280 × 720</option></select></div>
        <div class="two-fields"><label>宽度 px<input id="content-width" type="number" min="64" max="4096" step="2"></label><label>高度 px<input id="content-height" type="number" min="64" max="4096" step="2"></label></div>
        <label class="content-setting-check"><input id="content-size-lock" type="checkbox">锁定画布比例</label>
        <div class="field"><label for="content-background">背景颜色</label><div class="content-setting-color"><input id="content-background" type="color"><span id="content-background-value"></span></div></div>
        <div class="field"><label for="content-fps">帧率 · 每秒帧数</label><select id="content-fps">${C.frameRates.map(rate=>`<option value="${rate}">${rate} fps${rate===30?' · 默认':''}</option>`).join('')}</select></div>
        <div class="field"><label for="content-duration-mode">视频时长</label><select id="content-duration-mode"><option value="auto">跟随最后一个片段</option><option value="manual">手动设置</option></select></div>
        <div class="field"><label for="content-duration">时长 · 秒</label><input id="content-duration" type="number" min="1" max="30"></div>
        <p id="content-duration-note" class="content-slot-help"></p>
      </section>
      <section id="content-output-settings">
        <div class="section-title"><strong>输出设置</strong></div>
        <div class="field"><label for="content-output-name">文件名称</label><div class="content-setting-suffix"><input id="content-output-name" maxlength="80"><span>.mp4</span></div></div>
        <div class="two-fields content-setting-readonly"><label>格式<span>MP4</span></label><label>编码<span>H.264</span></label></div>
        <div class="field"><label for="content-output-size">输出尺寸</label><select id="content-output-size"><option value="canvas">与画布一致</option><option value="custom">自定义尺寸</option></select></div>
        <div id="content-output-custom"><div class="two-fields"><label>宽度 px<input id="content-output-width" type="number" min="64" max="4096" step="2"></label><label>高度 px<input id="content-output-height" type="number" min="64" max="4096" step="2"></label></div><label class="content-setting-check"><input id="content-output-lock" type="checkbox">锁定输出比例</label></div>
        <div class="content-setting-row"><span>输出帧率</span><strong id="content-output-fps"></strong></div>
        <div class="field"><label for="content-output-quality">画质</label><select id="content-output-quality" aria-describedby="content-quality-help"><option value="standard">标准 · 日常发布</option><option value="high">高画质 · 保留更多细节</option><option value="custom">自定义码率</option></select><p id="content-quality-help" class="content-slot-help"></p></div>
        <div class="field" id="content-output-bitrate-field"><label for="content-output-bitrate">视频码率 · Mbps</label><input id="content-output-bitrate" type="number" min="0.5" max="100" step="0.5"></div>
        <p class="content-slot-help">画质影响压缩程度和文件大小，尺寸与帧率保持当前设置。</p>
      </section>
      <section class="content-export-action"><div id="content-output-summary"></div><button id="content-export-video" class="primary">导出视频</button></section>`;
    $('content-work-name').onchange=e=>{const name=e.target.value.trim();if(!name){render();return;}work().name=name;H.change('修改作品名称');};
    $('content-background').oninput=e=>{$('content-background-value').textContent=e.target.value.toUpperCase();};
    $('content-background').onchange=e=>{work().background=e.target.value;H.change('调整画布背景');};
    for(const [id,axis] of [['content-width','width'],['content-height','height']])$(id).onchange=e=>resize('canvas',axis,e.target.valueAsNumber);
    $('content-size-preset').onchange=e=>{if(!e.target.value)return;const [width,height]=e.target.value.split('x').map(Number);setSize('canvas',width,height);};
    $('content-size-swap').onclick=()=>setSize('canvas',work().height,work().width);
    $('content-size-lock').onchange=e=>{work().lockAspect=e.target.checked;H.change('调整画布比例锁定');};
    $('content-fps').onchange=e=>{H.stop();work().fps=Number(e.target.value);H.change('调整项目帧率');};
    $('content-duration-mode').onchange=e=>{H.stop();work().durationMode=e.target.value;H.change('调整视频时长模式');};
    $('content-duration').onchange=e=>{const value=e.target.valueAsNumber;if(!Number.isFinite(value)||value<1||value>C.maxDuration)return invalid('视频时长请输入 1–30 秒');H.stop();const w=work();w.durationMode='manual';w.duration=Math.min(C.maxDuration,Math.ceil(value*C.fps(w)-1e-7)/C.fps(w));H.change('调整视频时长');};
    $('content-output-name').onchange=e=>{work().output.name=e.target.value.trim().replace(/\.mp4$/i,'');H.change('调整输出文件名称');};
    $('content-output-size').onchange=e=>{const w=work(),o=w.output;o.followCanvas=e.target.value==='canvas';if(!o.followCanvas){o.width=w.width;o.height=w.height;}H.change('调整输出尺寸');};
    for(const [id,axis] of [['content-output-width','width'],['content-output-height','height']])$(id).onchange=e=>resize('output',axis,e.target.valueAsNumber);
    $('content-output-lock').onchange=e=>{work().output.lockAspect=e.target.checked;H.change('调整输出比例锁定');};
    $('content-output-quality').onchange=e=>{work().output.quality=e.target.value;H.change('调整输出画质');};
    $('content-output-bitrate').onchange=e=>{const value=e.target.valueAsNumber;if(!Number.isFinite(value)||value<.5||value>100)return invalid('视频码率请输入 0.5–100 Mbps');work().output.bitrate=value;H.change('调整输出码率');};
    $('content-export-video').onclick=()=>H.generate();
  }
  function invalid(message){H.toast(message);render();}
  function resize(kind,axis,value){
    const w=work(),settings=kind==='canvas'?w:w.output;let width=axis==='width'?value:settings.width,height=axis==='height'?value:settings.height;
    if(settings.lockAspect){const ratio=settings.width/settings.height;if(axis==='width')height=width/ratio;else width=height*ratio;}
    setSize(kind,width,height);
  }
  function setSize(kind,width,height){
    if(!Number.isFinite(width)||!Number.isFinite(height)||width<64||height<64||width>4096||height>4096)return invalid('宽高请输入 64–4096 px');
    H.stop();const settings=kind==='canvas'?work():work().output;settings.width=Math.round(width/2)*2;settings.height=Math.round(height/2)*2;H.change(kind==='canvas'?'调整视频画布尺寸':'调整视频输出尺寸');
  }
  function render(){
    const w=work();if(w?.type!=='video')return;const o=w.output,size=C.outputSize(w),rate=C.fps(w),frames=C.outputFrames(w);
    $('content-work-name').value=w.name;$('content-width').value=w.width;$('content-height').value=w.height;$('content-size-lock').checked=w.lockAspect;
    const preset=w.width+'x'+w.height;$('content-size-preset').value=[...$('content-size-preset').options].some(option=>option.value===preset)?preset:'';
    $('content-background').value=w.background;$('content-background-value').textContent=w.background.toUpperCase();$('content-fps').value=rate;
    $('content-duration-mode').value=w.durationMode;$('content-duration').value=Number(w.duration.toFixed(4));$('content-duration').step=C.frame(w);$('content-duration').disabled=w.durationMode==='auto';
    $('content-duration-note').textContent=w.durationMode==='auto'?'随片段自动更新，最长 30 秒。':'按项目帧率对齐。缩短时长保留后面的片段，延长后可继续编辑。';
    $('content-output-name').value=o.name;$('content-output-name').placeholder=w.name;$('content-output-size').value=o.followCanvas?'canvas':'custom';$('content-output-custom').hidden=o.followCanvas;
    $('content-output-width').value=o.width;$('content-output-height').value=o.height;$('content-output-lock').checked=o.lockAspect;
    $('content-output-fps').textContent=rate+' fps · 跟随项目';$('content-output-quality').value=o.quality;$('content-output-bitrate').value=o.bitrate;$('content-output-bitrate-field').hidden=o.quality!=='custom';
    $('content-quality-help').textContent={standard:'兼顾清晰度和文件大小，适合日常发布与快速分享。',high:'压缩更少，保留更多纹理与渐变细节，文件通常更大。壁纸展示或保存成品建议选这一档。',custom:'按填写的码率编码，数值越大通常文件越大。有明确码率要求时使用。'}[o.quality];
    $('content-output-summary').textContent=`${size.width} × ${size.height} · ${rate} fps · ${Number((frames/rate).toFixed(3))} 秒`;
  }
  window.ContentSettings={mount,render};
})();
