/* Account operations, paginated task history and statistics from durable API records. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id), Core=window.DistributionCore;
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const names={xhs:'小红书',douyin:'抖音'}, labels={queued:'等待执行',running:'处理中',submitting:'正在提交',submitted:'已提交 · 待确认',published:'已确认发布',failed:'未完成',uncertain:'结果待核对',needs_input:'需要处理',cancelled:'已取消',ready:'已登录',expired:'登录失效',disconnected:'未登录',unverified:'待核对身份'};
  const date=v=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—';
  const num=v=>v==null?'—':Number(v).toLocaleString('zh-CN');
  const accountName=a=>a.nickname||a.name;
  const avatar=a=>`<span class="account-avatar">${a.avatarUrl?.startsWith('https://')?`<img src="${esc(a.avatarUrl)}" alt="" referrerpolicy="no-referrer">`:a.platform==='xhs'?'小':'抖'}</span>`;
  const platformURL=p=>p==='xhs'?'https://creator.xiaohongshu.com/new/note-manager':'https://creator.douyin.com/creator-micro/content/manage';
  const jobURL=j=>j.platform==='xhs'&&j.resultUrl?.startsWith('https://creator.xiaohongshu.com/publish/')?platformURL(j.platform):(j.resultUrl||platformURL(j.platform));
  const query=q=>new URLSearchParams(Object.entries(q).filter(([,v])=>v!==''&&v!=null)).toString();
  const fields={accounts:{keyword:'',platform:'',group:'',status:''},jobs:{keyword:'',platform:'',accountId:'',status:'',type:'',from:'',to:'',batchId:'',page:1,pageSize:20},data:{platform:'',accountId:'',from:'',to:''}};
  const selected=new Set();let H,epoch=0;
  // The API groups days in China time, independently of the browser's timezone.
  const day=v=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(v);
  function shift(value,days){return day(new Date(new Date(value+'T12:00:00+08:00').getTime()+days*86400000));}
  function period(days){const end=day(new Date());fields.data.from=shift(end,1-days);fields.data.to=end;}
  function range(f){const q={...f};if(f.from)q.from=new Date(f.from+'T00:00:00+08:00').toISOString();if(f.to)q.to=new Date(shift(f.to,1)+'T00:00:00+08:00').toISOString();return q;}
  function init(h){H=h;period(30);}
  function safe(fn){return async(...args)=>{try{return await fn(...args);}catch(e){H.toast(e.message);}};}
  function commonFilters(f,includeTasks=false){
    return `<label>平台<select data-filter="platform"><option value="">全部平台</option><option value="xhs">小红书</option><option value="douyin">抖音</option></select></label><label>账号<select data-filter="accountId"><option value="">全部账号（含历史任务）</option>${H.accounts().filter(a=>!f.platform||a.platform===f.platform).map(a=>`<option value="${a.id}">${esc(accountName(a))}</option>`).join('')}</select></label>${includeTasks?`<label>状态<select data-filter="status"><option value="">全部状态</option>${Object.entries(labels).filter(([key])=>!['ready','expired','disconnected','unverified'].includes(key)).map(([key,label])=>`<option value="${key}">${label}</option>`).join('')}</select></label><label>形式<select data-filter="type"><option value="">图文和视频</option><option value="image">图文</option><option value="video">视频</option></select></label>`:''}<label>开始日期<input type="date" data-filter="from" value="${esc(f.from)}"></label><label>结束日期<input type="date" data-filter="to" value="${esc(f.to)}"></label>`;
  }
  function bindFilters(pane,f,apply){
    pane.querySelectorAll('[data-filter]').forEach(el=>{
      el.value=f[el.dataset.filter]||'';
      el.onchange=()=>{f[el.dataset.filter]=el.value;if(el.dataset.filter==='platform'){f.accountId='';const account=pane.querySelector('[data-filter="accountId"]');if(account)account.value='';}};
      if(el.tagName==='INPUT')el.oninput=()=>{f[el.dataset.filter]=el.value;};
    });
    pane.querySelector('form').onsubmit=e=>{e.preventDefault();apply();};
  }
  function error(pane,id,e){const target=$(id);if(target&&pane.contains(target))target.innerHTML=`<p class="distribution-error" role="alert">${esc(e.message)}</p>`;}
  function accounts(pane){
    const f=fields.accounts,all=H.accounts(),status=H.status();
    pane.innerHTML=`<div class="distribution-heading"><div><h2>账号管理</h2><p>每个账号独立登录。本机账号可批量检查和同步数据。</p></div><button id="console-add" class="primary">＋ 添加账号</button></div><div class="distribution-filters console-filters"><label>搜索<input id="console-account-search" placeholder="账号、备注或分组" value="${esc(f.keyword)}"></label><label>平台<select id="console-account-platform"><option value="">全部平台</option><option value="xhs">小红书</option><option value="douyin">抖音</option></select></label><label>分组<select id="console-account-group"><option value="">全部分组</option><option value="@ungrouped">未分组</option>${[...new Set(all.map(a=>a.group).filter(Boolean))].sort().map(g=>`<option value="${esc(g)}">${esc(g)}</option>`).join('')}</select></label><label>状态<select id="console-account-status"><option value="">全部状态</option>${['ready','unverified','expired','disconnected'].map(s=>`<option value="${s}">${labels[s]}</option>`).join('')}</select></label></div><div class="console-toolbar"><label><input type="checkbox" id="console-account-all"> 选择当前结果中的本机账号</label><span id="console-account-count"></span><button id="console-check">检查所选账号</button><button id="console-sync">同步所选账号数据</button></div><div id="distribution-account-grid" class="distribution-account-grid"></div>`;
    $('console-add').onclick=()=>H.newAccount();
    for(const key of ['platform','group','status'])$('console-account-'+key).value=f[key];
    function visible(){return all.filter(a=>(!f.platform||a.platform===f.platform)&&(!f.status||a.status===f.status)&&(!f.group||(f.group==='@ungrouped'?!a.group:a.group===f.group))&&(!f.keyword||`${a.name} ${a.nickname||''} ${a.platformUserId||''} ${a.group}`.toLowerCase().includes(f.keyword.toLowerCase())));}
    function update(){
      const ids=visible().filter(a=>a.runnerId===status.runnerId).map(a=>a.id),picked=all.filter(a=>selected.has(a.id)&&a.runnerId===status.runnerId);
      $('console-account-all').checked=ids.length>0&&ids.every(id=>selected.has(id));$('console-account-all').indeterminate=ids.some(id=>selected.has(id))&&!$('console-account-all').checked;
      $('console-account-count').textContent=`已选 ${picked.length} 个 · 共 ${visible().length} 个结果`;
      $('console-check').disabled=!picked.length||!!status.active||!status.ready;
      $('console-sync').disabled=!picked.length||picked.some(a=>a.status!=='ready')||!!status.active||!status.ready;
    }
    function list(){
      $('distribution-account-grid').innerHTML=visible().map(a=>`<article class="distribution-account-card"><div><label><input type="checkbox" data-account-select="${a.id}" ${selected.has(a.id)?'checked':''} ${a.runnerId!==status.runnerId?'disabled':''}> <span class="distribution-platform ${a.platform}">${names[a.platform]}</span></label><span class="distribution-state ${a.status}">${labels[a.status]||esc(a.status)}</span></div><h3>${avatar(a)} ${esc(accountName(a))}</h3><p>备注：${esc(a.name)}<br>${esc(a.platformUserId||'尚未读取平台身份')}<br>${esc(a.group||'未分组')} · ${a.runnerId===status.runnerId?'本机账号':'其他电脑'}</p><small>最近检查：${date(a.checkedAt)}<br>身份核对：${date(a.identityCheckedAt)}</small><div class="distribution-card-actions"><button data-edit="${a.id}">编辑</button><button data-login="${a.id}" ${a.runnerId!==status.runnerId||status.active?'disabled':''}>${a.status==='ready'?'重新登录':'登录'}</button><button data-check="${a.id}" ${a.runnerId!==status.runnerId||status.active?'disabled':''}>检查状态</button><button data-remove="${a.id}" ${status.active?'disabled':''}>移除</button></div></article>`).join('')||'<p class="distribution-empty">没有匹配账号。可调整筛选，或添加新账号。</p>';
      pane.querySelectorAll('[data-account-select]').forEach(el=>el.onchange=()=>{el.checked?selected.add(el.dataset.accountSelect):selected.delete(el.dataset.accountSelect);update();});
      pane.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>H.newAccount(all.find(a=>a.id===b.dataset.edit)));
      for(const mode of ['login','check'])pane.querySelectorAll('[data-'+mode+']').forEach(b=>b.onclick=safe(()=>H.login(all.find(a=>a.id===b.dataset[mode]),mode)));
      pane.querySelectorAll('[data-remove]').forEach(b=>b.onclick=()=>{const a=all.find(a=>a.id===b.dataset.remove);H.modal('移除账号',`<p>移除 ${esc(a.name)} 的本机登录关联，并取消尚未执行的任务。历史发布记录会保留。</p>`,[{label:'取消',run:H.close},{label:'移除',primary:true,run:safe(async()=>{await H.request('/accounts/'+a.id,{method:'DELETE'});selected.delete(a.id);H.close();await H.refresh();})}]);});update();
    }
    $('console-account-search').oninput=e=>{f.keyword=e.target.value.trim();list();};
    for(const key of ['platform','group','status'])$('console-account-'+key).onchange=e=>{f[key]=e.target.value;list();};
    $('console-account-all').onchange=e=>{for(const a of visible().filter(a=>a.runnerId===status.runnerId))e.target.checked?selected.add(a.id):selected.delete(a.id);list();};
    $('console-check').onclick=safe(()=>H.bulk(all.filter(a=>selected.has(a.id)&&a.runnerId===status.runnerId).map(a=>a.id),'check'));
    $('console-sync').onclick=safe(()=>H.bulk(all.filter(a=>selected.has(a.id)&&a.runnerId===status.runnerId).map(a=>a.id),'analytics'));list();
  }
  async function jobs(pane){
    const f=fields.jobs,ticket=++epoch;
    pane.innerHTML=`<div class="distribution-heading"><div><h2>发布任务</h2><p>完整历史按任务创建时间筛选。提交后结果待核对的任务不会自动重发。</p></div><button id="console-jobs-refresh">刷新</button></div><form class="distribution-filters console-filters"><label>搜索<input data-filter="keyword" placeholder="标题、账号或处理提示" value="${esc(f.keyword)}"></label>${commonFilters(f,true)}<button class="primary">查询</button><button type="button" id="console-jobs-reset">清除筛选</button></form><div id="console-jobs-results" aria-live="polite">正在读取任务…</div>`;
    bindFilters(pane,f,()=>{f.page=1;jobs(pane);});$('console-jobs-refresh').onclick=()=>H.refresh();$('console-jobs-reset').onclick=()=>{Object.assign(f,{keyword:'',platform:'',accountId:'',status:'',type:'',from:'',to:'',batchId:'',page:1});jobs(pane);};
    try{
      const result=await H.request('/jobs/page?'+query(range(f)));if(ticket!==epoch||!H.isView('jobs'))return;
      const rows=result.items,total=result.total,pages=Math.max(1,Math.ceil(total/f.pageSize));
      if(f.page>pages){f.page=pages;return jobs(pane);}
      $('console-jobs-results').innerHTML=`<p class="hint">${f.batchId?'当前批次 · ':''}共 ${num(total)} 条任务</p><div class="distribution-job-list">${rows.map(j=>`<article class="distribution-job"><div><span class="distribution-state ${j.status}">${labels[j.status]||esc(j.status)}</span><strong>${esc(j.post.title||'未命名内容')}</strong><small>${names[j.platform]} · ${esc(j.accountName)} · ${j.post.type==='video'?'视频':'图文'}</small><p>${esc(j.message||'等待助手到点开始上传')}</p>${j.post.source?`<p>来源：${esc(j.post.source.projectName)} · ${[...new Set((j.post.source.assets||[]).map(a=>a.workName||a.name))].map(esc).join('、')}</p>`:''}<small>创建 ${date(j.createdAt)} · 计划开始 ${date(j.dueAt)}<br>最近更新 ${date(j.updatedAt)} · 批次内第 ${j.batchPosition==null?'—':j.batchPosition+1} 条</small></div><div class="distribution-job-actions"><a href="${esc(jobURL(j))}" target="_blank" rel="noopener noreferrer">${j.resultUrl?'查看平台作品':'打开平台管理'}</a>${j.status==='queued'?`<button data-job="${j.id}" data-action="cancel">取消</button>`:''}${['failed','needs_input'].includes(j.status)?`<button data-job="${j.id}" data-action="retry">重试</button>`:''}${['submitted','uncertain'].includes(j.status)?`<button data-job="${j.id}" data-action="resolve">核对结果</button>`:''}${j.post.source?`<button data-source="${j.id}">查看来源作品</button>`:''}<button data-details="${j.id}">查看内容</button><button data-batch="${j.batchId}">同批任务</button></div></article>`).join('')||'<p class="distribution-empty">没有匹配的发布任务。</p>'}</div><div class="console-pagination"><button id="console-previous" ${f.page<=1?'disabled':''}>上一页</button><span>第 ${f.page} / ${pages} 页</span><button id="console-next" ${f.page>=pages?'disabled':''}>下一页</button></div>`;
      $('console-previous').onclick=()=>{f.page--;jobs(pane);};$('console-next').onclick=()=>{f.page++;jobs(pane);};
      pane.querySelectorAll('[data-source]').forEach(b=>b.onclick=safe(()=>H.openSource(rows.find(j=>j.id===b.dataset.source).post.source)));
      pane.querySelectorAll('[data-batch]').forEach(b=>b.onclick=()=>{f.batchId=b.dataset.batch;f.page=1;jobs(pane);});
      pane.querySelectorAll('[data-details]').forEach(b=>b.onclick=()=>{const j=rows.find(r=>r.id===b.dataset.details);H.modal('任务内容快照',`<p>${names[j.platform]} · ${esc(j.accountName)}</p><h3>${esc(j.post.title)}</h3><p class="distribution-body">${esc(j.post.body)}</p><p>${(j.post.tags||[]).map(t=>'#'+esc(t)).join(' ')}</p><p>${(j.post.mediaIds||[]).length} 个素材 · ${esc(Core.settingsSummary(j.platform,j.post))}</p><p class="hint">此处展示创建任务时固定的内容，后续修改作品不会改变它。</p>`,[{label:'关闭',run:H.close}]);});
      pane.querySelectorAll('[data-job]').forEach(b=>b.onclick=()=>action(rows.find(j=>j.id===b.dataset.job),b.dataset.action));
    }catch(e){if(ticket===epoch)error(pane,'console-jobs-results',e);}
  }
  function action(job,mode){
    const run=body=>safe(async()=>{await H.request('/jobs/'+job.id+'/action',{method:'POST',body});H.close();await H.refresh();});
    if(mode==='resolve')H.modal('核对平台结果',`<h3>${esc(job.post.title)}</h3><p>${names[job.platform]} · ${esc(job.accountName)}</p><p>请在平台作品管理中确认这条内容的结果后选择状态。</p><a href="${platformURL(job.platform)}" target="_blank" rel="noopener noreferrer">打开平台作品管理</a>`,[{label:'稍后核对',run:H.close},{label:'确认未发布',run:run({action:'resolve',result:'failed'})},{label:'确认已发布',primary:true,run:run({action:'resolve',result:'published'})}]);
    else H.modal(mode==='retry'?'重试发布':'取消任务',`<p>${esc(job.post.title)} · ${esc(job.accountName)}</p><p>${mode==='retry'?'将使用原任务的内容重新上传和发布。':'只取消尚未开始执行的这条任务。'}</p>`,[{label:'返回',run:H.close},{label:mode==='retry'?'确认重试':'确认取消',primary:true,run:run({action:mode})}]);
  }
  async function data(pane){
    const f=fields.data,ticket=++epoch;
    pane.innerHTML=`<div class="distribution-heading"><div><h2>数据概览</h2><p>任务统计覆盖完整历史，按创建日期汇总；已发布以平台核对结果为准。</p></div><button id="console-data-refresh">刷新</button></div><div class="console-periods">${[7,30,90].map(n=>`<button data-period="${n}">近 ${n} 天</button>`).join('')}</div><form class="distribution-filters console-filters">${commonFilters(f)}<button class="primary">查询</button></form><div id="console-data-results" aria-live="polite">正在读取统计…</div>`;
    bindFilters(pane,f,()=>data(pane));$('console-data-refresh').onclick=()=>H.refresh();pane.querySelectorAll('[data-period]').forEach(b=>b.onclick=()=>{period(Number(b.dataset.period));data(pane);});
    try{
      if(!f.from||!f.to)throw new Error('请选择开始和结束日期');
      const result=await H.request('/overview?'+query(range(f)));if(ticket!==epoch||!H.isView('data'))return;
      const s=result.statuses,count=keys=>keys.reduce((n,key)=>n+(s[key]||0),0),metrics=result.metrics;
      const days=[],byDay=new Map(result.daily.map(row=>[String(row.date).slice(0,10),row]));for(let value=f.from;value<=f.to&&days.length<366;value=shift(value,1))days.push({date:value,total:0,published:0,...byDay.get(value)});
      const maximum=Math.max(1,...days.map(d=>Number(d.total)));
      $('console-data-results').innerHTML=`<div class="distribution-metrics">${[['已确认发布',s.published||0],['待平台确认',count(['submitted','uncertain'])],['等待 / 执行中',count(['queued','running','submitting'])],['需要处理',count(['failed','needs_input'])]].map(([label,value])=>`<article><span>${label}</span><strong>${num(value)}</strong></article>`).join('')}</div><p class="hint">共 ${num(result.total)} 条任务 · 已取消 ${num(s.cancelled||0)} 条 · 日期按北京时间统计</p><section class="distribution-table"><h3>每日任务</h3><p class="hint">柱形表示当天创建的任务数，其中已确认发布的数量单独标注。</p><div class="console-chart" role="img" aria-label="每日创建任务数量，明细见下方表格">${days.map(d=>`<div title="${esc(d.date)}：创建 ${d.total} 条，已确认发布 ${d.published} 条"><span>${d.total}</span><i style="height:${Math.max(2,Number(d.total)/maximum*100)}px"></i><small>${esc(d.date.slice(5))}</small></div>`).join('')}</div><details><summary>查看每日明细</summary><table><thead><tr><th>日期</th><th>创建任务</th><th>其中已确认发布</th><th>其中需要处理或核对</th></tr></thead><tbody>${days.map(d=>`<tr><td>${esc(d.date)}</td><td>${num(d.total)}</td><td>${num(d.published)}</td><td>${num(d.needsAttention||0)}</td></tr>`).join('')}</tbody></table></details></section><section class="distribution-table"><h3>账号发布统计</h3><table><thead><tr><th>账号</th><th>任务总数</th><th>已确认发布</th><th>待平台确认</th><th>需要处理</th></tr></thead><tbody>${result.accounts.map(a=>`<tr><td>${esc(a.name)}${a.archived?' · 已移除':''}<br><small>${names[a.platform]}</small></td><td>${num(a.total)}</td><td>${num(a.published)}</td><td>${num(a.awaiting)}</td><td>${num(a.needsAttention)}</td></tr>`).join('')||'<tr><td colspan="5">当前日期内暂无任务。</td></tr>'}</tbody></table></section><section class="distribution-table"><div class="console-toolbar"><h3>账号累计指标</h3><button id="console-sync-all">同步当前账号</button><button id="console-export-data">导出统计</button></div><p class="hint">累计值取结束日期前最后一次采集；下方变化与开始日期前的采集比较。— 表示缺少可比较的数据，不按 0 统计。历史数据按实际采集时间保留。</p><table><thead><tr><th>账号</th>${['粉丝','播放 / 阅读','点赞','评论','收藏'].map(t=>`<th>${t}</th>`).join('')}<th>本次 / 基准采集</th><th></th></tr></thead><tbody>${metrics.map(a=>`<tr><td>${esc(a.name)}<br><small>${names[a.platform]}</small></td>${['followers','plays','likes','comments','favorites'].map(key=>`<td>${num(a.metrics[key])}<br><small>变化 ${a.delta[key]==null?'—':(a.delta[key]>0?'+':'')+num(a.delta[key])}</small></td>`).join('')}<td>${date(a.collectedAt)}<br><small>${date(a.baselineAt)}</small></td><td><a href="${a.platform==='xhs'?'https://creator.xiaohongshu.com/new/home':'https://creator.douyin.com/creator-micro/home'}" target="_blank" rel="noopener noreferrer">平台数据</a></td></tr>`).join('')||'<tr><td colspan="8">暂无匹配账号。</td></tr>'}</tbody></table></section>`;
      const ids=H.accounts().filter(a=>(!f.platform||a.platform===f.platform)&&(!f.accountId||a.id===f.accountId)&&a.status==='ready'&&a.runnerId===H.status().runnerId).map(a=>a.id);
      $('console-sync-all').disabled=!ids.length||!H.status().ready||!!H.status().active;$('console-sync-all').onclick=safe(()=>H.bulk(ids,'analytics'));
      $('console-export-data').onclick=()=>exportData({...result,daily:days});
    }catch(e){if(ticket===epoch)error(pane,'console-data-results',e);}
  }
  function exportData(result){
    const csvCell=value=>{let text=String(value??'');if(typeof value==='string'&&/^[\s]*[=+@-]/.test(text))text="'"+text;return '"'+text.replace(/"/g,'""')+'"';};
    const rows=[['任务创建日期','任务数','已确认发布','需要处理或核对'],...result.daily.map(d=>[d.date,d.total,d.published,d.needsAttention]),[],['账号','平台','累计粉丝','累计播放或阅读','累计点赞','累计评论','累计收藏','粉丝变化','播放或阅读变化','点赞变化','评论变化','收藏变化','采集时间','基准采集时间'],...result.metrics.map(a=>[a.name,names[a.platform],...['followers','plays','likes','comments','favorites'].map(k=>a.metrics[k]),...['followers','plays','likes','comments','favorites'].map(k=>a.delta[k]),a.collectedAt,a.baselineAt])];
    const blob=new Blob(['\ufeff'+rows.map(row=>row.map(csvCell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='分发统计-'+fields.data.from+'-'+fields.data.to+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  function render(pane,view){if(view==='accounts')accounts(pane);else if(view==='jobs')jobs(pane);else data(pane);}
  window.DistributionConsole={init,render};
})();
