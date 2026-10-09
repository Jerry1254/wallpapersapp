(() => {
  'use strict';
  const names = {image:'图片库', video:'视频库', phrase:'快捷短语'};
  const labels = {image:'图片', video:'视频', phrase:'短语'};
  const icons = {image:'image', video:'play', phrase:'replies'};
  const picker = document.querySelector('#picker-dialog');
  const editor = document.querySelector('#editor-dialog');
  const phraseDialog = document.querySelector('#phrase-dialog');
  let H, tab = 'image', query = '', pickerTab = 'image', pickerQuery = '', editSession, saving = false;
  const seed = () => ({items:[
    {id:'library-mountain',type:'image',title:'落日山峦壁纸示例',notes:'用户咨询壁纸效果时，可以发送这张图片。',source:'assets/mountain.svg',fileName:'落日山峦.svg',updatedAt:Date.now()},
    {id:'library-coast',type:'image',title:'海岸微光壁纸示例',notes:'用于说明壁纸预览效果。',source:'assets/coast.svg',fileName:'海岸微光.svg',updatedAt:Date.now()},
    {id:'library-video',type:'video',title:'动态壁纸效果演示',notes:'一段短视频，展示壁纸画面的动态效果。',source:'assets/support-demo.mp4',poster:'assets/mountain.svg',fileName:'动态效果演示.mp4',updatedAt:Date.now()},
    {id:'phrase-hello',type:'phrase',title:'问候与接待',text:'您好，我帮您看一下。',updatedAt:Date.now()},
    {id:'phrase-screenshot',type:'phrase',title:'请用户补充截图',text:'方便发一张截图吗？',updatedAt:Date.now()},
    {id:'phrase-thanks',type:'phrase',title:'结束与致谢',text:'不客气，有问题随时联系。',updatedAt:Date.now()},
  ]});
  const items = () => H.state().library.items;
  const itemById = id => items().find(item => item.id === id);
  function tabs(kind, selected) {
    return `<nav class="library-tabs" aria-label="素材分类">${Object.keys(names).map(type => `<button type="button" data-library-tab="${type}" data-library-surface="${kind}" class="${selected === type ? 'active':''}" aria-pressed="${selected === type}">${H.icon(icons[type])}<span>${names[type]}</span></button>`).join('')}</nav>`;
  }
  function search(kind, value) {
    return `<label class="library-search">${H.icon('search')}<input type="search" data-library-search="${kind}" value="${H.escape(value)}" placeholder="搜索名称、说明或内容" aria-label="${kind === 'manager' ? '搜索后台素材':'搜索可用素材'}"><span data-library-count="${kind}"></span></label>`;
  }
  function managerMarkup() {
    return `<section class="library-panel">${tabs('manager',tab)}<div class="library-toolbar"><span class="library-sync">${H.icon('phone')}管理后台维护 · 手机客服同步使用</span><button type="button" class="button primary" data-library-new="${tab}">${H.icon('plus')}新增${labels[tab]}</button></div>${search('manager',query)}<div id="managed-library-items" class="library-items"></div></section>`;
  }
  function mediaArt(item) {
    return item.type === 'video'
      ? `<video muted playsinline preload="auto" data-media-source="${H.escape(item.source)}" ${item.poster ? `data-media-poster="${H.escape(item.poster)}"`:''}></video><span class="video-play">${H.icon('play')}</span>`
      : `<img alt="${H.escape(item.title)}" data-media-source="${H.escape(item.source)}">`;
  }
  function card(item, mode) {
    const title = H.escape(item.title), id = H.escape(item.id);
    const art = item.type === 'phrase'
      ? `<div class="library-art phrase-art">${H.icon('replies')}</div>`
      : `<button type="button" class="library-art media-preview-button" data-library-preview="${id}" aria-label="预览${title}">${mediaArt(item)}</button>`;
    return `<article class="library-card ${item.type === 'phrase' ? 'phrase-card':''}" data-library-item="${id}">${art}<div class="library-copy"><strong title="${title}">${title}</strong><p>${H.escape(item.type === 'phrase' ? item.text : item.notes || item.fileName)}</p><div class="library-card-actions"><button type="button" class="button secondary" data-library-preview="${id}">${item.type === 'phrase' ? '查看':'预览'}</button><button type="button" class="button ${mode === 'picker' ? 'primary':'secondary'}" data-library-use="${id}">${mode === 'picker' ? '选用':'使用'}</button>${mode === 'manager' ? `<button type="button" class="button secondary" data-library-edit="${id}" aria-label="编辑${title}">${H.icon('edit')}编辑</button><button type="button" class="icon-button library-remove" data-library-remove="${id}" aria-label="删除${title}" title="删除">${H.icon('trash')}</button>`:''}</div></div></article>`;
  }
  function renderItems(kind) {
    const container = document.querySelector(kind === 'manager' ? '#managed-library-items':'#picker-library-items');
    if (!container) return;
    const selected = kind === 'manager' ? tab : pickerTab;
    const term = (kind === 'manager' ? query : pickerQuery).trim().toLowerCase();
    const rows = items().filter(item => item.type === selected && (!term || `${item.title} ${item.notes || ''} ${item.text || ''} ${item.fileName || ''}`.toLowerCase().includes(term))).sort((a,b) => b.updatedAt - a.updatedAt);
    const count = document.querySelector(`[data-library-count="${kind}"]`);
    if (count) count.textContent = `${rows.length} 项`;
    container.innerHTML = rows.length ? `<div class="library-grid">${rows.map(item => card(item,kind)).join('')}</div>` : `<div class="library-empty">${H.icon(term ? 'search':icons[selected])}<strong>${term ? '没有找到匹配内容':`暂无${labels[selected]}`}</strong><p>${term ? '试试其他名称或关键词。':kind === 'manager' ? `点击「新增${labels[selected]}」开始添加。`:'由管理后台添加后，会同步显示在这里。'}</p></div>`;
    SupportMediaStore.hydrate(container);
  }
  function refresh() {
    renderItems('manager');
    if (picker.open) renderItems('picker');
  }
  function openPicker(type = 'image') {
    if (!H.hasConversation()) return H.toast('请先选择要回复的用户。','error');
    pickerTab = names[type] ? type : 'image'; pickerQuery = '';
    picker.innerHTML = `<header class="library-dialog-heading"><div><h2 id="picker-title">客服素材库</h2><p>由管理后台维护，选用后点击发送。</p></div><button type="button" class="icon-button" data-library-close="picker" aria-label="关闭素材库">${H.icon('close')}</button></header>${tabs('picker',pickerTab)}${search('picker',pickerQuery)}<div id="picker-library-items" class="library-items"></div>`;
    if (!picker.open) picker.showModal();
    renderItems('picker');
  }
  function openEditor(type, id = null) {
    if (!H.isAdmin()) return;
    const item = id ? itemById(id) : null;
    if (id && !item) return H.toast('该内容已被删除，请刷新列表。','error');
    editSession = H.state().sessionId;
    saving = false;
    editor.innerHTML = `<form id="library-editor-form" data-edit-id="${H.escape(id || '')}" data-edit-type="${type}"><header class="library-dialog-heading"><div><h2 id="editor-title">${item ? '编辑':'新增'}${labels[type]}</h2><p>保存后，手机客服可以同步选用。</p></div><button type="button" class="icon-button" data-library-close="editor" aria-label="关闭编辑">${H.icon('close')}</button></header><label class="editor-field">${labels[type]}名称<input id="library-title" name="title" required maxlength="80" value="${H.escape(item?.title || '')}" placeholder="起一个方便搜索的名称"></label>${type === 'phrase' ? `<label class="editor-field">短语内容<textarea id="library-text" name="text" required maxlength="2000" rows="5" placeholder="输入常用回复内容">${H.escape(item?.text || '')}</textarea></label>`:`<label class="editor-field">${labels[type]}文件<input id="library-file" name="file" type="file" accept="${type === 'image' ? 'image/png,image/jpeg,image/webp':'video/mp4,video/webm'}" ${item ? '':'required'}><small>${item ? `当前文件：${H.escape(item.fileName)}。选择新文件可替换。`:type === 'image' ? '支持 JPG、PNG、WebP 图片。':'支持 MP4、WebM 视频。'}</small></label>${item ? `<div class="editor-current-media"><button type="button" class="library-art media-preview-button" data-library-preview="${H.escape(item.id)}" aria-label="预览当前素材">${mediaArt(item)}</button></div>`:''}<label class="editor-field">说明<textarea id="library-notes" name="notes" maxlength="200" rows="2" placeholder="例如：适合什么时候发送给用户">${H.escape(item?.notes || '')}</textarea></label>`}<p id="editor-error" class="editor-error" role="status"></p><footer class="editor-actions"><button type="button" class="button secondary" data-library-close="editor">取消</button><button type="submit" class="button primary" id="library-save">保存</button></footer></form>`;
    if (!editor.open) editor.showModal();
    SupportMediaStore.hydrate(editor);
    editor.querySelector('#library-title').focus();
  }
  async function saveEditor(event) {
    if (event.target.id !== 'library-editor-form') return;
    event.preventDefault();
    if (saving || !H.isAdmin()) return;
    const form = event.target, type = form.dataset.editType, id = form.dataset.editId;
    const title = form.elements.title.value.trim();
    const text = type === 'phrase' ? form.elements.text.value.trim() : '';
    const file = type === 'phrase' ? null : form.elements.file.files[0];
    const error = editor.querySelector('#editor-error');
    const button = editor.querySelector('#library-save');
    if (!title || type === 'phrase' && !text) { error.textContent = '请填写名称和内容，不能只输入空格。'; return; }
    const previous = id ? itemById(id) : null;
    if (id && !previous) { error.textContent = '该内容已被删除，请关闭后重新添加。'; return; }
    if (type !== 'phrase' && !previous && !file) { error.textContent = '请选择素材文件。'; return; }
    saving = true; button.disabled = true; button.textContent = '保存中…'; error.textContent = '';
    try {
      const source = file ? await SupportMediaStore.save(file,type) : previous?.source;
      if (!editor.open || !form.isConnected) return;
      if (editSession !== H.state().sessionId) throw new Error('演示数据已重置，请关闭后重新编辑。');
      if (id && !itemById(id)) throw new Error('该内容已被删除，请关闭后重新添加。');
      const item = {id:id || H.uid(), type, title, updatedAt:Date.now(), ...(type === 'phrase' ? {text} : {source, poster:file ? null : previous?.poster || null, fileName:file?.name || previous?.fileName, notes:form.elements.notes.value.trim()})};
      H.mutate(state => { state.library.items = state.library.items.filter(row => row.id !== item.id).concat(item); });
      editor.close();
      H.toast(`${labels[type]}已${id ? '更新':'添加'}，手机客服同步使用。`);
    } catch (exception) { if (error.isConnected) error.textContent = exception.message || '保存失败，请重试。'; }
    finally { if (form.isConnected) { saving = false; button.disabled = false; button.textContent = '保存'; } }
  }
  function remove(id) {
    const item = itemById(id);
    if (!item) return;
    H.mutate(state => { state.library.items = state.library.items.filter(row => row.id !== id); });
    H.toast(`${labels[item.type]}已删除，手机客服列表同步更新。`);
  }
  function preview(item) {
    if (item.type !== 'phrase') return H.preview(item.source,item.type);
    phraseDialog.innerHTML = `<header class="library-dialog-heading"><h2 id="phrase-preview-title">${H.escape(item.title)}</h2><button type="button" class="icon-button" data-library-close="phrase" aria-label="关闭短语预览">${H.icon('close')}</button></header><div class="phrase-preview-content">${H.escape(item.text)}</div>`;
    phraseDialog.showModal();
  }
  function mount(bridge) {
    H = bridge;
    document.addEventListener('click', event => {
      const button = event.target.closest('button');
      if (!button) return;
      if (button.dataset.libraryTab) {
        if (button.dataset.librarySurface === 'manager') { tab = button.dataset.libraryTab; query = ''; H.renderManager(); }
        else openPicker(button.dataset.libraryTab);
      } else if (button.dataset.libraryNew) openEditor(button.dataset.libraryNew);
      else if (button.dataset.libraryEdit) { const item = itemById(button.dataset.libraryEdit); if (item) openEditor(item.type,item.id); }
      else if (button.dataset.libraryRemove && H.isAdmin()) { const item = itemById(button.dataset.libraryRemove); if (item) H.askDelete({kind:'library',id:item.id,title:`删除${labels[item.type]}？`,description:`确定删除「${item.title}」？手机客服将不再显示这项内容，已经发出的消息会保留。`}); }
      else if (button.dataset.libraryPreview) { const item = itemById(button.dataset.libraryPreview); if (item) preview(item); else H.toast('该素材已被删除。','error'); }
      else if (button.dataset.libraryUse) { const item = itemById(button.dataset.libraryUse); if (item && H.use(item) && picker.open) picker.close(); }
      else if (button.dataset.libraryClose) ({picker,editor,phrase:phraseDialog}[button.dataset.libraryClose])?.close();
    });
    document.addEventListener('input', event => {
      const kind = event.target.dataset.librarySearch;
      if (!kind) return;
      if (kind === 'manager') query = event.target.value; else pickerQuery = event.target.value;
      renderItems(kind);
    });
    document.addEventListener('submit', saveEditor);
  }
  window.SupportLibrary = {seed, mount, managerMarkup, refresh, openPicker, remove};
})();
