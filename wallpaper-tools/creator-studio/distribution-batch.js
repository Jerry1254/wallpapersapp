/* Build a reviewable list from complete local works; no platform requests here. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id),Core=window.DistributionCore;
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={douyin:'抖音',xhs:'小红书'};
  let H;
  const plan=()=>H.state().batchPlan||=( {mode:'all',rows:[]} );
  const name=a=>a?.nickname||a?.name||'账号已移除';
  const account=id=>H.accounts().find(a=>a.id===id);
  const ready=a=>a&&a.status==='ready'&&a.platformUserId&&a.runnerId===H.status().runnerId;
  const locked=()=>!!H.state().submissionPending;
  function init(h){H=h;}
  function error(row){
    const a=account(row.accountId);if(!ready(a))return '账号需要在本机重新登录或检查身份';
    try{H.validate(a.platform,row.post,H.state());return '';}catch(e){return e.message;}
  }
  function render(pane){
    const p=plan(),pending=locked(),count=p.rows.length;
    pane.innerHTML=`<div class="distribution-heading"><div><h2>批量发布</h2><p>先选择作品与账号，生成清单后逐条检查。</p></div><button id="batch-select" class="primary" ${pending?'disabled':''}>选择作品和账号</button></div>${pending?'<p class="distribution-error" role="status">上次确认的清单尚未完成创建。继续确认会复用同一批次，不会重复创建任务。</p>':''}<div class="distribution-batch-tools"><span>${count} 条任务 · ${new Set(p.rows.map(r=>r.accountId)).size} 个账号</span><button id="batch-clear" ${!count||pending?'disabled':''}>清空清单</button></div><ol class="distribution-batch-list">${p.rows.map((r,i)=>{const a=account(r.accountId),problem=error(r);return `<li class="distribution-batch-row"><span class="distribution-batch-number">${i+1}</span><div><strong>${esc(r.post.title||'尚未填写标题')}</strong><p>${esc(r.contentName)} · ${r.post.type==='video'?'视频':r.post.assetIds.length+' 张图片'}</p><small>${a?names[a.platform]+' · '+esc(name(a)):'账号已移除'}</small>${problem?`<p class="distribution-error">${esc(problem)}</p>`:`<p class="hint">${esc(Core.settingsSummary(a.platform,{...r.post,...Core.settings(a.platform,r.post)}))}</p>`}</div><div><button data-batch-edit="${esc(r.id)}" ${pending?'disabled':''}>编辑</button><button data-batch-remove="${esc(r.id)}" ${pending?'disabled':''}>移除</button></div></li>`;}).join('')||'<li class="distribution-empty">选择作品和账号后，在这里检查每条发布内容。</li>'}</ol><div class="distribution-batch-footer"><div class="field"><label for="batch-timing">发布时间</label><select id="batch-timing" ${pending?'disabled':''}><option value="now">立即开始上传</option><option value="scheduled">定时开始上传</option></select></div><div class="field" id="batch-date-field"><label for="batch-date">计划开始时间</label><input id="batch-date" type="datetime-local" ${pending?'disabled':''}></div><button id="batch-preview" class="primary" ${!count&&!pending?'disabled':''}>${pending?'继续确认上次清单':'预览发布清单'}</button></div>`;
    $('batch-select').onclick=picker;
    $('batch-clear').onclick=()=>{p.rows=[];H.save();render(pane);};
    pane.querySelectorAll('[data-batch-remove]').forEach(b=>b.onclick=()=>{p.rows=p.rows.filter(r=>r.id!==b.dataset.batchRemove);H.save();render(pane);});
    pane.querySelectorAll('[data-batch-edit]').forEach(b=>b.onclick=()=>edit(p.rows.find(r=>r.id===b.dataset.batchEdit),pane));
    $('batch-timing').value=H.state().timing;$('batch-date').value=H.state().scheduledAt;
    $('batch-date-field').hidden=H.state().timing!=='scheduled';
    $('batch-timing').onchange=e=>{H.state().timing=e.target.value;$('publish-timing').value=e.target.value;$('publish-date').hidden=e.target.value!=='scheduled';$('batch-date-field').hidden=e.target.value!=='scheduled';H.save();};
    $('batch-date').onchange=e=>{H.state().scheduledAt=e.target.value;$('publish-date').value=e.target.value;H.save();};
    $('batch-preview').onclick=()=>H.preview(p.rows);
  }
  function picker(){
    if(locked())return H.toast('请先继续确认上次清单');
    const projectId=H.projectId(),state=H.state(),choices=Core.contentChoices(state),accounts=H.accounts(),p=plan();
    const selectedWorks=(p.contentIds||[]).filter(id=>choices.some(w=>w.id===id&&w.available));
    const selectedAccounts=(p.accountIds||[...state.selectedAccounts]).filter(id=>ready(account(id)));
    H.modal('选择批量发布内容',`<p class="hint">文案和设置从当前平台、账号的发布设置复制；生成清单后可逐条修改。单批最多 50 条任务。</p><div class="distribution-batch-picker"><section><h3>作品与素材</h3><div class="distribution-batch-choices">${choices.map(w=>`<label><input type="checkbox" data-batch-content="${esc(w.id)}" ${selectedWorks.includes(w.id)?'checked':''} ${w.available?'':'disabled'}><span><strong>${esc(w.name)}</strong><small>${w.type==='video'?'视频':w.assetIds.length+' 张图片'}${w.available?'':' · 文件未完整读取'}</small></span></label>`).join('')||'<p class="hint">当前项目还没有可发布的作品或素材。</p>'}</div><h3>作品顺序</h3><ol id="batch-content-order" class="distribution-order"></ol></section><section><h3>账号</h3><div class="distribution-batch-choices">${accounts.map(a=>`<label><input type="checkbox" data-batch-account="${a.id}" ${selectedAccounts.includes(a.id)?'checked':''} ${ready(a)?'':'disabled'}><span><strong>${esc(name(a))}</strong><small>${names[a.platform]} · ${ready(a)?'可发布':'需在本机登录并核对身份'}</small></span></label>`).join('')||'<p class="hint">请先到账号管理添加账号。</p>'}</div><p id="batch-account-order" class="hint"></p></section></div><div class="field"><label for="batch-mode">分配方式</label><select id="batch-mode"><option value="all">每份作品发布到全部选中账号</option><option value="sequential">作品按顺序分配给账号</option></select></div><p class="hint">按顺序分配时，作品多于账号会循环分配；只有一份作品时，发到所有选中账号。</p><div class="field"><label for="batch-build-mode">生成方式</label><select id="batch-build-mode"><option value="replace">重新生成清单</option><option value="append">追加到现有清单</option></select></div><p id="batch-build-count" role="status"></p><p id="batch-build-error" class="distribution-error" role="alert"></p>`,[{label:'取消',run:H.close},{label:'生成清单',primary:true,run:()=>{
      try{if(H.projectId()!==projectId||locked())throw new Error('项目或待确认清单已变化，请重新打开');
        const works=selectedWorks.map(id=>choices.find(w=>w.id===id)),targets=selectedAccounts.map(account);
        if(targets.some(a=>!ready(a)))throw new Error('所选账号需要在本机重新登录');
        const rows=Core.buildBatch(state,works,targets,$('batch-mode').value,()=>crypto.randomUUID());
        p.rows=Core.mergeBatch(p.rows,rows,$('batch-build-mode').value==='append');p.mode=$('batch-mode').value;p.contentIds=[...selectedWorks];p.accountIds=[...selectedAccounts];state.selectedAccounts=new Set(selectedAccounts);
        H.save();H.close();H.render();H.toast(`已生成 ${p.rows.length} 条待发布内容`);
      }catch(e){$('batch-build-error').textContent=e.message;}
    }}]);
    $('batch-mode').value=p.mode||'all';
    const refresh=()=>{
      $('batch-content-order').innerHTML=selectedWorks.map((id,i)=>`<li><span>${i+1}. ${esc(choices.find(w=>w.id===id).name)}</span><button data-batch-order="${i}" data-step="-1" ${i===0?'disabled':''} aria-label="前移作品">↑</button><button data-batch-order="${i}" data-step="1" ${i===selectedWorks.length-1?'disabled':''} aria-label="后移作品">↓</button></li>`).join('');
      $('batch-content-order').querySelectorAll('[data-batch-order]').forEach(b=>b.onclick=()=>{const i=Number(b.dataset.batchOrder),j=i+Number(b.dataset.step);[selectedWorks[i],selectedWorks[j]]=[selectedWorks[j],selectedWorks[i]];refresh();});
      $('batch-account-order').textContent='账号顺序：'+selectedAccounts.map(id=>name(account(id))).join(' → ');
      const count=$('batch-mode').value==='all'||selectedWorks.length===1?selectedWorks.length*selectedAccounts.length:selectedAccounts.length?selectedWorks.length:0;
      $('batch-build-count').textContent=`将生成 ${count} 条任务${$('batch-build-mode').value==='append'?'，现有 '+p.rows.length+' 条':''}`;
      $('batch-build-error').textContent=count>50?'超过单批 50 条，请减少作品或账号':'';
    };
    for(const [selector,attribute,ids] of [['[data-batch-content]','batchContent',selectedWorks],['[data-batch-account]','batchAccount',selectedAccounts]])document.querySelectorAll(selector).forEach(el=>el.onchange=()=>{const id=el.dataset[attribute];if(el.checked)ids.push(id);else ids.splice(ids.indexOf(id),1);refresh();});
    $('batch-mode').onchange=refresh;$('batch-build-mode').onchange=refresh;refresh();
  }
  function edit(row,pane){
    if(!row||locked())return;
    const a=account(row.accountId);if(!a)return H.toast('账号已移除，请移除此条任务');
    const projectId=H.projectId(),post=JSON.parse(JSON.stringify(row.post)),fields=Core.settingFields(a.platform,post.type);
    const images=H.state().postAssets.filter(a=>a.type==='image'&&a.file&&!a.demo).map(a=>`<option value="${esc(a.id)}">${esc(a.name)}</option>`).join('');
    const covers=post.type==='video'?[['coverAssetId',a.platform==='douyin'?'竖版封面':'视频封面'],...(a.platform==='douyin'?[['landscapeCoverAssetId','横版封面']]:[])]:[];
    H.modal('编辑这条发布内容',`<p>${names[a.platform]} · ${esc(name(a))}<br><small>${esc(row.contentName)}</small></p><div class="field"><label for="batch-row-title">标题</label><input id="batch-row-title" value="${esc(post.title)}"></div><div class="field"><label for="batch-row-body">正文</label><textarea id="batch-row-body" rows="7">${esc(post.body)}</textarea></div><div class="field"><label for="batch-row-tags">话题标签</label><input id="batch-row-tags" value="${esc(post.tags)}"></div>${post.type==='image'?'<label>图片顺序 · 第一张作为封面</label><ol id="batch-row-order" class="distribution-order"></ol>':''}${covers.map(([key,label])=>`<div class="field"><label for="batch-row-${key}">${label}</label><select id="batch-row-${key}"><option value="">由平台选取</option>${images}</select></div>`).join('')}${fields.map(f=>`<div class="field"><label for="batch-row-${f.key}">${f.label}</label><select id="batch-row-${f.key}">${f.options.map(([v,label])=>`<option value="${esc(v)}">${esc(label)}</option>`).join('')}${post[f.key]!=null&&!f.options.some(([v])=>v===post[f.key])?`<option value="${esc(post[f.key])}">请重新选择：${esc(post[f.key])}</option>`:''}</select></div>`).join('')}<p id="batch-row-error" class="distribution-error" role="alert"></p>`,[{label:'取消',run:H.close},{label:'保存修改',primary:true,run:()=>{
      try{if(projectId!==H.projectId()||locked()||!plan().rows.some(r=>r===row))throw new Error('清单已变化，请重新打开');
        post.title=$('batch-row-title').value;post.body=$('batch-row-body').value;post.tags=$('batch-row-tags').value;
        for(const [key] of covers)post[key]=$('batch-row-'+key).value;
        for(const f of fields)post[f.key]=$('batch-row-'+f.key).value;
        H.validate(a.platform,post,H.state());Core.mergeBatch([],plan().rows.map(r=>r===row?{...r,post}:r),false);row.post=post;H.save();H.close();render(pane);
      }catch(e){$('batch-row-error').textContent=e.message;}
    }}]);
    for(const [key] of covers)$('batch-row-'+key).value=post[key]||'';
    for(const f of fields)$('batch-row-'+f.key).value=post[f.key]??f.fallback;
    if(post.type==='image'){
      const order=()=>{
        $('batch-row-order').innerHTML=post.assetIds.map((id,i)=>`<li><span>${i+1}. ${esc(H.state().postAssets.find(a=>a.id===id)?.name||'素材已缺失')}</span><button data-image-order="${i}" data-step="-1" ${i===0?'disabled':''} aria-label="前移图片">↑</button><button data-image-order="${i}" data-step="1" ${i===post.assetIds.length-1?'disabled':''} aria-label="后移图片">↓</button></li>`).join('');
        $('batch-row-order').querySelectorAll('[data-image-order]').forEach(b=>b.onclick=()=>{const i=Number(b.dataset.imageOrder),j=i+Number(b.dataset.step);[post.assetIds[i],post.assetIds[j]]=[post.assetIds[j],post.assetIds[i]];order();});
      };order();
    }
  }
  window.DistributionBatch={init,render};
})();
