(() => {
  'use strict';

  // A browser-only interaction model. No API calls or production credentials.
  const STORAGE_KEY = 'qingjing-support-prototype-v1';
  const CUSTOMER_ID = 'visitor-0291';
  const surface = document.querySelector('#surface');
  const demoPanel = document.querySelector('#demo-panel');
  const imageInput = document.querySelector('#image-input');
  const imageDialog = document.querySelector('#image-dialog');
  const resetDialog = document.querySelector('#reset-dialog');
  const deleteDialog = document.querySelector('#delete-dialog');
  const videoDialog = document.querySelector('#video-dialog');
  let pendingDeletion = null;
  const paths = {
    message: '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8z"/>',
    headset: '<path d="M3 14v-3a9 9 0 0 1 18 0v3M21 16v1a4 4 0 0 1-4 4h-3"/><rect x="2" y="12" width="5" height="7" rx="2"/><rect x="17" y="12" width="5" height="7" rx="2"/>',
    search: '<circle cx="10.5" cy="10.5" r="7.5"/><path d="m16 16 5 5"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="1.5"/><path d="m21 15-5-5L5 21"/>',
    send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    back: '<path d="m15 18-6-6 6-6"/>',
    chevron: '<path d="m9 18 6-6-6-6"/>',
    arrowDown: '<path d="M12 4v16m-6-6 6 6 6-6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    reset: '<path d="M3 11a9 9 0 1 1 2.7 7M3 4v7h7"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 4h.01"/>',
    wifi: '<path d="M2 8.8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-11 3a6 6 0 0 1 8 0m-4 4h.01"/>',
    wifiOff: '<path d="m2 2 20 20M2 8.8a16 16 0 0 1 3.2-2m4.1-1.5A16 16 0 0 1 22 8.8M5 12a11 11 0 0 1 3-1.6m4-1.3A11 11 0 0 1 19 12m-11 3a6 6 0 0 1 8 0m-4 4h.01"/>',
    volume: '<path d="m11 5-6 4H2v6h3l6 4zM16 9a5 5 0 0 1 0 6m3-9a9 9 0 0 1 0 12"/>',
    mute: '<path d="m11 5-6 4H2v6h3l6 4zM17 9l5 6m0-6-5 6"/>',
    sliders: '<path d="M4 21v-7m0-4V3m8 18v-9m0-4V3m8 18v-5m0-4V3M1 14h6m2-6h6m2 8h6"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    folder: '<path d="M20 20H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h5l2 2h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2Z"/>',
    shield: '<path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z"/><path d="m9 12 2 2 4-4"/>',
    play: '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="m10 8 6 4-6 4z"/>',
    key: '<circle cx="8" cy="15" r="5"/><path d="m11.5 11.5 9-9L23 5l-3 3-2-2-2 2 2 2"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/>',
    upload: '<path d="M12 16V3m-5 5 5-5 5 5M3 16v4a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-4"/>',
    home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z"/><path d="M9 21v-8h6v8"/>',
    heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    replies: '<path d="M3 3h18v14H8l-5 4z"/><path d="M7 7h10M7 11h7"/>',
    signal: '<path d="M4 18v2m5-7v7m5-12v12m5-17v17"/>',
    lock: '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4"/>',
    trash: '<path d="M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    edit: '<path d="m16 3 5 5-12 12-6 1 1-6ZM14 5l5 5"/>',
  };
  const icon = (name) => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.message}</svg>`;
  const escape = (value = '') => String(value).replace(/[&<>"']/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const uid = () => window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const time = (timestamp) => new Date(timestamp).toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit',hour12:false});
  const seed = () => {
    const now = Date.now();
    const message = (id, role, text, minutes, extra = {}) => ({id,role,text,createdAt:now - minutes * 60000,status:'sent',delivered:true,readByAgent:true,readByCustomer:true,attempts:1,...extra});
    return {
      version:1, sessionId:uid(), sound:true, network:true, drafts:{}, library:SupportLibrary.seed(),
      conversations:[
        {id:CUSTOMER_ID,name:'用户 0291',avatar:'29',tone:'gold',platform:'Android',version:'1.0.1',online:true,messages:[
          message('a1','customer','你好，刚兑换的壁纸设置后没有动，请问怎么处理？',14),
          message('a2','agent','您好，我帮您看一下。方便发一张当前壁纸页面的截图吗？',13),
          message('a3','customer','是这张，已经下载完成了。',11),
          message('a4','customer','',10,{image:'assets/mountain.svg',imageName:'壁纸截图.svg'}),
          message('a5','agent','可以先进入壁纸详情，点击「设置壁纸」，选择动态壁纸后再确认一次。',8,{status:'failed',delivered:false,error:'网络连接中断，消息尚未发送。'}),
        ]},
        {id:'visitor-0866',name:'用户 0866',avatar:'86',tone:'rose',platform:'iOS',version:'1.0.1',online:true,messages:[
          message('b1','customer','请问 iPhone 怎么设置动态壁纸？',12),
          message('b2','agent','您好，在壁纸详情保存实况照片后，可以到系统设置中选择。',11),
          message('b3','customer','已经保存了，但找不到设置的位置。',4,{readByAgent:false}),
          message('b4','customer','可以帮我看一下吗？',3,{readByAgent:false}),
        ]},
        {id:'visitor-1058',name:'用户 1058',avatar:'10',tone:'sage',platform:'HarmonyOS',version:'1.0.1',online:true,messages:[
          message('c1','customer','鸿蒙下载完成后在哪里设置？',6,{readByAgent:false}),
        ]},
        {id:'visitor-0327',name:'用户 0327',avatar:'03',tone:'slate',platform:'Android',version:'1.0.0',online:false,messages:[
          message('d1','customer','换了手机以后，之前兑换的壁纸还能用吗？',31),
          message('d2','agent','您好，您可以先提供兑换记录截图，我帮您核对。',28),
          message('d3','customer','好的，我稍后找一下截图。',26),
        ]},
        {id:'visitor-0619',name:'用户 0619',avatar:'06',tone:'gold',platform:'iOS',version:'1.0.1',online:false,messages:[
          message('e1','customer','购买的壁纸已恢复，谢谢！',58),
          message('e2','agent','不客气，使用中有问题可以再联系我。',57),
        ]},
      ],
    };
  };
  let cache;
  let storageWarning = false;
  function read() {
    try {
      const value = JSON.parse(localStorage.getItem(STORAGE_KEY));
      if (value?.version === 1 && Array.isArray(value.conversations) && value.drafts && value.sessionId) {
        value.library ||= SupportLibrary.seed();
        return value;
      }
    } catch (_) { /* Missing, corrupt or unavailable browser storage starts a fresh demo. */ }
    return cache || seed();
  }
  function write(value) {
    cache = value;
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(value)); }
    catch (_) {
      if (!storageWarning) {
        storageWarning = true;
        toast('本地存储空间不足，本次修改仅保留在当前页面。', 'error');
      }
    }
  }
  let model = read();
  write(model);
  const initial = new URLSearchParams(location.search);
  const ui = {
    view:['admin','customer','agent'].includes(initial.get('view')) ? initial.get('view') : 'admin',
    active:CUSTOMER_ID,
    customerPage:initial.get('screen') === 'home' ? 'home' : 'chat',
    agentPage:'list', adminPage:'chat', adminChat:false, search:'', filter:'all', quick:false,
    nextOutcome:'success', jump:false,
  };
  function mutate(change, refresh = true) {
    model = read();
    change(model);
    write(model);
    if (refresh) refreshScreen();
  }
  const currentRole = () => ui.view === 'customer' ? 'customer' : 'agent';
  const currentId = () => ui.view === 'customer' ? CUSTOMER_ID : ui.active;
  const conversation = (id = currentId()) => model.conversations.find((item) => item.id === id);
  const agentConversations = () => model.conversations.filter(item => !item.hiddenForAgent);
  const isChatOpen = () => ui.view === 'customer' ? ui.customerPage === 'chat' : ui.view === 'agent' ? ui.agentPage === 'chat' && !!ui.active : ui.adminPage === 'chat' && !!ui.active && (window.innerWidth > 650 || ui.adminChat);
  const unread = (item, role = 'agent') => item.messages.filter((message) => message.delivered && message.role !== role && !message[role === 'agent' ? 'readByAgent' : 'readByCustomer']).length;
  const totalUnread = () => agentConversations().reduce((sum, item) => sum + unread(item), 0);
  const draftKey = () => `${ui.view}:${currentId()}`;
  const draft = () => model.drafts[draftKey()] || {text:'',image:null};
  const visibleMessages = (item) => item.messages.filter((message) => message.delivered || message.role === currentRole());
  function ensureActive() {
    if (ui.view === 'customer' || conversation(ui.active) && !conversation(ui.active).hiddenForAgent) return false;
    const next = agentConversations().sort((a,b) => b.messages.at(-1).createdAt - a.messages.at(-1).createdAt)[0]?.id || null;
    if (next === ui.active) return false;
    ui.active = next; ui.agentPage = 'list'; ui.adminChat = false; ui.quick = false;
    return true;
  }
  function markRead() {
    if (!isChatOpen()) return;
    const item = conversation();
    const field = currentRole() === 'agent' ? 'readByAgent' : 'readByCustomer';
    if (item?.messages.some((message) => message.delivered && !message[field])) {
      item.messages.forEach((message) => { if (message.delivered) message[field] = true; });
      write(model);
    }
  }
  const avatar = (item, agent = false, showPresence = true) => agent
    ? `<span class="avatar dark">${icon('headset')}</span>`
    : `<span class="avatar ${escape(item.tone)}">${escape(item.avatar)}${item.online && showPresence ? '<span class="presence"></span>' : ''}</span>`;
  const soundControl = () => `<button type="button" class="sound-button" data-action="sound" aria-pressed="${model.sound}" aria-label="${model.sound ? '关闭' : '开启'}声音提醒">${icon(model.sound ? 'volume':'mute')}<span>声音${model.sound ? '开启':'关闭'}</span></button>`;
  const networkBanner = () => !model.network ? `<div class="network-banner" role="status">${icon('wifiOff')}<span>网络已断开，恢复后可重试发送。</span></div>` : '';
  function sidebar() {
    const items = [['grid','工作台'],['folder','分类管理'],['image','壁纸管理'],['shield','协议管理'],['play','设置教程'],['key','兑换码'],['list','兑换记录'],['phone','设备权益'],['upload','App 版本管理'],['headset','在线客服']];
    return `<aside class="admin-sidebar"><div class="admin-brand"><span class="brand-mark">倾</span><div><strong>倾境壁纸</strong><small>管理后台</small></div></div><nav class="admin-menu" aria-label="管理后台导航">${items.map(([name,label]) => `<button type="button" ${name === 'headset' ? 'class="current" aria-current="page" data-action="admin-home"':'data-action="context-menu"'} title="${label}">${icon(name)}<span class="menu-label">${label}</span>${name === 'headset' ? `<span class="sidebar-count" data-total-unread>${totalUnread() || ''}</span>`:''}</button>`).join('')}</nav><div class="sidebar-footer"><span class="status-dot"></span><span>倾境运营管理</span></div></aside>`;
  }
  function listPane(mobile = false) {
    return `<section class="conversation-pane"><div class="list-top"><div class="list-title"><h2>${mobile ? '会话':'用户会话'}</h2>${mobile ? '<span class="online-pill"><span class="status-dot"></span>接待中</span>':`<span><span data-conversation-count>${agentConversations().length}</span> 位用户</span>`}</div><label class="search-box">${icon('search')}<span class="sr-only">搜索用户或消息</span><input id="conversation-search" type="search" placeholder="搜索用户或消息" value="${escape(ui.search)}" autocomplete="off"></label></div><div class="list-filters" aria-label="会话筛选"><button type="button" data-filter="all" class="${ui.filter === 'all' ? 'active':''}" aria-pressed="${ui.filter === 'all'}">全部<span class="filter-count" data-conversation-count>${agentConversations().length}</span></button><button type="button" data-filter="unread" class="${ui.filter === 'unread' ? 'active':''}" aria-pressed="${ui.filter === 'unread'}">未读<span class="filter-count" data-unread-conversations>${agentConversations().filter((item) => unread(item)).length}</span></button></div><div id="conversation-list" class="conversation-list"></div>${mobile ? `<footer class="agent-list-footer"><div><span class="avatar dark">A</span><span>管理员 · 正在接待</span></div>${soundControl()}</footer>`:''}</section>`;
  }
  function listItems() {
    const query = ui.search.trim().toLowerCase();
    const items = agentConversations().filter((item) => {
      const matches = !query || `${item.name} ${item.platform} ${item.messages.filter((message) => message.delivered || message.role === 'agent').map((message) => message.text).join(' ')}`.toLowerCase().includes(query);
      return matches && (ui.filter !== 'unread' || unread(item) > 0);
    }).sort((a,b) => b.messages.at(-1).createdAt - a.messages.at(-1).createdAt);
    if (!items.length) return `<div class="list-empty">${icon(query ? 'search':ui.filter === 'unread' ? 'check':'message')}<p><strong>${query ? '没有找到相关会话':ui.filter === 'unread' ? '暂时没有未读消息':'暂无会话'}</strong>${query ? '试试其他用户编号或关键词':'用户发来新消息时，会显示在这里'}</p></div>`;
    return items.map((item) => {
      const last = item.messages.filter((message) => message.delivered || message.role === 'agent').at(-1);
      const count = unread(item);
      const prefix = last?.role === 'agent' ? last.status === 'failed' ? '[发送失败] ' : last.status === 'uncertain' ? '[发送未确认] ' : '我：' : '';
      const preview = prefix + (last?.text || (last?.image ? '[图片]':last?.video ? '[视频]':'暂无消息'));
      return `<div class="conversation-row"><button type="button" class="conversation-item ${ui.active === item.id ? 'active':''}" data-conversation="${escape(item.id)}" aria-label="打开${escape(item.name)}的会话${count ? `，${count}条未读`:''}" aria-current="${ui.active === item.id}">${avatar(item)}<div class="conversation-copy"><div class="conversation-name"><strong>${escape(item.name)}</strong><time>${time(last?.createdAt || Date.now())}</time></div><div class="conversation-preview"><p>${escape(preview)}</p>${count ? `<span class="unread-badge">${count > 99 ? '99+':count}</span>`:''}</div></div></button><button type="button" class="conversation-delete icon-button" data-delete-conversation="${escape(item.id)}" aria-label="删除${escape(item.name)}的会话" title="删除会话">${icon('trash')}</button></div>`;
    }).join('');
  }
  function chatPane() {
    const item = conversation();
    if (!item) return `<section class="chat-pane"><div class="empty-chat">${icon('message')}选择一位用户开始回复</div></section>`;
    const customer = currentRole() === 'customer';
    const name = customer ? '在线客服' : item.name;
    const meta = customer || item.online ? '<span class="status-dot"></span>在线':'<span class="status-dot offline"></span>离线';
    return `<section class="chat-pane"><header class="chat-heading"><button type="button" class="icon-button mobile-back" data-action="back" aria-label="${customer ? '返回我的页面':'返回会话列表'}">${icon('back')}</button><div class="chat-person">${avatar(item,customer,false)}<div><h2>${escape(name)}</h2><p>${meta}</p></div></div>${customer ? soundControl():`<button type="button" class="delete-conversation-button" data-delete-conversation="${escape(item.id)}" aria-label="删除${escape(item.name)}的会话">${icon('trash')}<span>删除会话</span></button>`}</header><div id="network-banner-slot">${networkBanner()}</div><div id="chat-feed" class="chat-feed" role="log" aria-label="${escape(name)}聊天记录" aria-live="polite"></div><button type="button" id="new-messages" class="new-messages-button" data-action="scroll-bottom" hidden>${icon('arrowDown')}查看新消息</button>${composer()}</section>`;
  }
  function composer() {
    const value = draft();
    return `<form id="composer" class="composer" aria-label="发送消息"><div class="composer-toolbar"><button type="button" class="icon-button" data-action="choose-image" aria-label="添加图片" title="添加图片">${icon('image')}</button>${currentRole() === 'agent' ? `<button type="button" class="composer-library-button" data-action="open-library" aria-label="选择客服素材" title="素材库">${icon('folder')}<span>素材库</span></button><button type="button" class="composer-library-button" data-action="quick" aria-label="选择快捷短语" title="快捷短语">${icon('replies')}<span>快捷短语</span></button>`:''}<span class="composer-hint">Enter 发送 · Shift + Enter 换行</span></div><div id="attachment-slot">${attachmentMarkup(value)}</div><label class="sr-only" for="message-input">消息内容</label><textarea id="message-input" placeholder="${currentRole() === 'customer' ? '描述您的问题，也可以发送截图…':'输入回复内容…'}" maxlength="2000" rows="2">${escape(value.text)}</textarea><div class="composer-bottom"><p>发送结果会显示在每条消息下方</p><button type="submit" id="send-button" class="send-button" ${!value.text.trim() && !value.image && !value.media ? 'disabled':''}>发送${icon('send')}</button></div></form>`;
  }
  function attachmentMarkup(value) {
    const media = value.media || (value.image ? {...value.image,type:'image'} : null);
    if (!media) return '';
    const art = media.type === 'video' ? `<video muted playsinline preload="auto" data-media-source="${escape(media.source)}" ${media.poster ? `data-media-poster="${escape(media.poster)}"`:''}></video>` : `<img data-media-source="${escape(media.source)}" alt="待发送图片">`;
    return `<div class="attachment-preview">${art}<div><strong>${escape(media.name)}</strong><small>点击发送后，${media.type === 'video' ? '视频':'图片'}才会发出</small></div><button type="button" class="icon-button" data-action="remove-image" aria-label="移除待发送素材">${icon('close')}</button></div>`;
  }
  function messageMarkup(message, item) {
    const mine = message.role === currentRole();
    const labels = {sending:'发送中',sent:'已发送',failed:'发送失败',uncertain:'发送未确认'};
    const statusIcons = {sending:'clock',sent:'check',failed:'alert',uncertain:'alert'};
    const retry = mine && ['failed','uncertain'].includes(message.status);
    const media = message.video
      ? `<button type="button" class="message-image media-preview-button" data-video="${escape(message.video)}" aria-label="播放聊天视频"><video muted playsinline preload="auto" data-media-source="${escape(message.video)}" ${message.videoPoster ? `data-media-poster="${escape(message.videoPoster)}"`:''}></video><span class="video-play">${icon('play')}</span></button><div class="video-caption">${escape(message.videoName || '视频')}</div>`
      : message.image ? `<button type="button" class="message-image media-preview-button" data-image="${escape(message.image)}" aria-label="查看聊天图片"><img data-media-source="${escape(message.image)}" alt="${escape(message.imageName || '聊天截图')}"></button>` : '';
    const content = media ? `${media}${message.text ? `<div class="image-caption">${escape(message.text)}</div>`:''}` : escape(message.text);
    return `<article class="message-row ${mine ? 'mine':''}" data-message-id="${escape(message.id)}">${avatar(item,message.role === 'agent',false)}<div class="message-content"><div class="message-bubble ${mine ? escape(message.status):''} ${media ? 'image-bubble':''}">${content}</div><div class="message-meta"><time>${time(message.createdAt)}</time>${mine ? `<span class="message-state ${escape(message.status)} ${message.status === 'sending' ? 'spinning':''}">${icon(statusIcons[message.status])}${labels[message.status] || '发送未确认'}</span>`:''}${retry ? `<button type="button" class="retry-button" data-retry="${escape(message.id)}" aria-label="重发这条消息">${icon('reset')}重发</button>`:''}</div>${retry ? `<div class="failure-reason">${escape(message.error || '未收到发送确认，请重试。')}</div>`:''}</div></article>`;
  }
  function chatMessages() {
    const item = conversation();
    if (!item) return '';
    const messages = visibleMessages(item);
    return `<div class="day-divider">今天 ${messages.length ? time(messages[0].createdAt):time(Date.now())}</div>${currentRole() === 'customer' ? '<div class="welcome-note">您好！请描述您的问题，也可以发送截图。<br>回复记录会保留，您可以稍后回来查看。</div>':''}${messages.map((message) => messageMarkup(message,item)).join('')}`;
  }
  const phoneTop = () => `<div class="phone-statusbar"><span>${time(Date.now())}</span><div class="statusbar-icons">${icon('signal')}${icon('wifi')}<span class="statusbar-battery"></span></div></div>`;
  function customerHome() {
    const count = unread(conversation(CUSTOMER_ID),'customer');
    return `<section class="customer-home"><div class="app-brand-heading"><h1>我的</h1><button type="button" class="icon-button" data-action="open-customer-chat" aria-label="联系客服">${icon('headset')}</button></div><div class="app-card tutorial-card">${icon('play')}<div><strong>壁纸设置教程</strong><p>几步设置，让屏幕动起来</p></div>${icon('chevron')}</div><button type="button" class="app-card customer-service-card" data-action="open-customer-chat"><span class="avatar dark">${icon('headset')}</span><div><strong>在线客服</strong><p>设置、兑换遇到问题？<br>发消息给我们，帮您一起解决。</p></div>${icon('chevron')}${count ? `<span class="unread-badge service-unread">${count}</span>`:''}</button><div class="app-card tutorial-card">${icon('shield')}<div><strong>用户协议与隐私政策</strong><p>倾境服务规则与信息处理说明</p></div>${icon('chevron')}</div><div class="owned-header"><h2>已获得壁纸</h2><span>刷新权益</span></div><p class="owned-hint">权益属于当前安装身份。<br>您获得的壁纸会显示在这里。</p><div class="owned-card"><img src="assets/mountain.svg" alt="落日山峦壁纸"><div><strong>落日山峦</strong><p>动态壁纸 · 已获得</p><span>已下载</span></div></div><div class="owned-card"><img src="assets/coast.svg" alt="海岸微光壁纸"><div><strong>海岸微光</strong><p>动态壁纸 · 已获得</p><span>已下载</span></div></div></section><nav class="bottom-nav" aria-label="App 页面示意"><div>${icon('home')}<span>首页</span></div><div>${icon('grid')}<span>分类</span></div><div class="selected" aria-current="page">${icon('user')}<span>我的</span></div></nav>`;
  }
  function render() {
    model = read();
    ensureActive();
    markRead();
    ui.jump = false;
    document.querySelectorAll('[data-view]').forEach((button) => {
      const active = button.dataset.view === ui.view;
      button.classList.toggle('active',active);
      button.setAttribute('aria-pressed',String(active));
    });
    if (ui.view === 'admin') {
      const libraryPage = ui.adminPage === 'library';
      const heading = libraryPage ? '<h1>客服素材库</h1><p>统一维护图片、视频和快捷短语，手机客服同步使用。</p>' : '<h1>客服工作台</h1><p>查看用户咨询，在这里直接回复。</p>';
      const actions = libraryPage ? `<button type="button" class="button secondary" data-action="return-workbench">${icon('back')}返回会话</button>` : `<button type="button" class="button secondary" data-action="manage-library">${icon('folder')}<span>客服素材库</span></button><span class="online-pill"><span class="status-dot"></span>正在接待</span>${soundControl()}`;
      const body = libraryPage ? SupportLibrary.managerMarkup() : `<div class="workspace ${ui.adminChat ? 'mobile-chat':''}">${listPane()}${chatPane()}</div>`;
      surface.innerHTML = `<div class="admin-shell">${sidebar()}<section class="admin-main"><header class="admin-topbar"><div class="breadcrumb">${icon('menu')}<span>内容运营</span><i>/</i><strong>在线客服</strong></div><div class="admin-identity"><span>A</span><div><strong>管理员</strong><small>倾境管理后台</small></div></div></header><div class="admin-content ${libraryPage ? 'library-admin-content':''}"><div class="page-heading"><div>${heading}</div><div class="work-status">${actions}</div></div>${body}</div></section></div>`;
    } else {
      const inner = ui.view === 'customer' ? ui.customerPage === 'home' ? customerHome():chatPane() : ui.agentPage === 'list' ? listPane(true):chatPane();
      surface.innerHTML = `<div class="mobile-stage"><div class="phone">${phoneTop()}<div class="phone-content">${inner}</div><div class="phone-home-indicator"></div></div></div>`;
    }
    const list = document.querySelector('#conversation-list');
    if (list) list.innerHTML = listItems();
    const feed = document.querySelector('#chat-feed');
    if (feed) { feed.innerHTML = chatMessages(); requestAnimationFrame(() => { feed.scrollTop = feed.scrollHeight; }); }
    updateChrome();
    listenToFeed();
    SupportLibrary.refresh();
    SupportMediaStore.hydrate(surface);
  }
  function refreshScreen() {
    if (ensureActive()) { render(); return; }
    markRead();
    const list = document.querySelector('#conversation-list');
    if (list) list.innerHTML = listItems();
    const feed = document.querySelector('#chat-feed');
    if (feed) {
      const atBottom = feed.scrollHeight - feed.scrollTop - feed.clientHeight < 90;
      const oldTop = feed.scrollTop;
      const oldCount = feed.querySelectorAll('.message-row').length;
      feed.innerHTML = chatMessages();
      const newCount = feed.querySelectorAll('.message-row').length;
      if (atBottom || ui.jump) {
        feed.scrollTop = feed.scrollHeight;
        ui.jump = false;
        document.querySelector('#new-messages').hidden = true;
      } else {
        feed.scrollTop = oldTop;
        if (newCount > oldCount) document.querySelector('#new-messages').hidden = false;
      }
    }
    const banner = document.querySelector('#network-banner-slot');
    if (banner) banner.innerHTML = networkBanner();
    if (ui.view === 'customer' && ui.customerPage === 'home') {
      document.querySelector('.phone-content').innerHTML = customerHome();
    }
    updateChrome();
    SupportLibrary.refresh();
    SupportMediaStore.hydrate(surface);
  }
  function updateChrome() {
    const count = totalUnread();
    document.querySelectorAll('[data-total-unread]').forEach((element) => { element.textContent = count || ''; });
    document.querySelectorAll('[data-unread-conversations]').forEach((element) => { element.textContent = agentConversations().filter((item) => unread(item)).length; });
    document.querySelectorAll('[data-conversation-count]').forEach(element => { element.textContent = agentConversations().length; });
    document.querySelectorAll('[data-action="sound"]').forEach((button) => {
      button.setAttribute('aria-pressed',String(model.sound));
      button.setAttribute('aria-label',`${model.sound ? '关闭':'开启'}声音提醒`);
      button.innerHTML = `${icon(model.sound ? 'volume':'mute')}<span>声音${model.sound ? '开启':'关闭'}</span>`;
    });
    const customerCount = unread(conversation(CUSTOMER_ID),'customer');
    const titleCount = ui.view === 'customer' ? customerCount : count;
    document.title = `${titleCount ? `(${titleCount}) ` : ''}倾境 · 在线客服交互原型`;
    document.querySelector('#network-label').textContent = model.network ? '模拟断网':'恢复网络';
  }
  function listenToFeed() {
    const feed = document.querySelector('#chat-feed');
    feed?.addEventListener('scroll',() => {
      if (feed.scrollHeight - feed.scrollTop - feed.clientHeight < 70) document.querySelector('#new-messages').hidden = true;
    },{passive:true});
  }
  function updateDraft(patch) {
    const key = draftKey();
    mutate((value) => { value.drafts[key] = {...(value.drafts[key] || {text:'',image:null}),...patch}; },false);
    updateComposer();
  }
  function updateComposer() {
    const value = draft();
    const slot = document.querySelector('#attachment-slot');
    if (slot) { slot.innerHTML = attachmentMarkup(value); SupportMediaStore.hydrate(slot); }
    const button = document.querySelector('#send-button');
    if (button) button.disabled = !value.text.trim() && !value.image && !value.media;
  }
  function changeView(view) {
    ui.view = view; ui.quick = false; ui.search = ''; ui.filter = 'all';
    closeDemo();
    const query = new URLSearchParams(location.search);
    query.set('view',view);
    query.delete('screen');
    history.replaceState(null,'',`${location.pathname}?${query.toString()}`);
    render();
  }
  function openConversation(id) {
    if (!conversation(id) || conversation(id).hiddenForAgent) return;
    ui.active = id; ui.agentPage = 'chat'; ui.adminChat = true; ui.quick = false;
    if (ui.view === 'admin') ui.adminPage = 'chat';
    render();
  }
  function beginSend(retryId) {
    const id = currentId();
    const role = currentRole();
    if (!conversation(id)) return;
    const value = {...draft(),text:document.querySelector('#message-input')?.value ?? draft().text};
    const key = draftKey();
    if (!retryId && !value.text.trim() && !value.image && !value.media) return;
    const messageId = retryId || uid();
    const outcome = model.network ? ui.nextOutcome : 'failure';
    const session = model.sessionId;
    const disconnected = !model.network;
    ui.nextOutcome = 'success';
    document.querySelector('#send-outcome').value = 'success';
    mutate((state) => {
      const target = state.conversations.find((item) => item.id === id);
      let message = target.messages.find((entry) => entry.id === messageId);
      if (message) {
        if (!['failed','uncertain'].includes(message.status)) return;
        message.status = 'sending'; message.error = ''; message.attempts += 1; message.pendingAt = Date.now();
      } else {
        const media = value.media || (value.image ? {...value.image,type:'image'} : null);
        message = {id:messageId,role,text:value.text.trim(),image:media?.type === 'image' ? media.source : null,imageName:media?.type === 'image' ? media.name : '',video:media?.type === 'video' ? media.source : null,videoName:media?.type === 'video' ? media.name : '',createdAt:Date.now(),pendingAt:Date.now(),status:'sending',delivered:false,readByAgent:role === 'agent',readByCustomer:role === 'customer',attempts:1};
        if (media?.poster) message.videoPoster = media.poster;
        target.messages.push(message);
        state.drafts[key] = {text:'',image:null,media:null};
      }
      ui.jump = true;
    });
    if (!retryId) {
      const input = document.querySelector('#message-input');
      if (input) input.value = '';
      updateComposer();
    }
    setTimeout(() => {
      let result;
      mutate((state) => {
        if (state.sessionId !== session) return;
        const target = state.conversations.find((item) => item.id === id);
        const message = target?.messages.find((entry) => entry.id === messageId);
        if (!message || message.status !== 'sending') return;
        if (!state.network || disconnected || outcome === 'failure') {
          message.status = 'failed'; message.error = '网络连接中断，发送失败。请点击重发。';
        } else if (outcome === 'uncertain') {
          // Delivery and sender acknowledgement are deliberately separate demo states.
          message.delivered = true; message.status = 'uncertain'; message.error = '未收到发送确认，请重试；重发不会重复发送。';
        } else {
          message.delivered = true; message.status = 'sent'; message.error = '';
        }
        if (message.delivered && message.role === 'customer') { target.hiddenForAgent = false; target.online = true; }
        result = message.status;
      });
      if (result === 'failed') toast('消息发送失败，内容已保留，可点击重发。','error');
      if (result === 'uncertain') toast('发送结果未确认，请在消息下方点击重发。','error');
      if (result === 'sent' && retryId) toast('消息已重新发送。');
    },disconnected ? 350 : 950);
  }
  let audio;
  function beep() {
    if (!model.sound || document.visibilityState !== 'visible') return;
    try {
      const Audio = window.AudioContext || window.webkitAudioContext;
      if (!Audio) return;
      audio ||= new Audio();
      audio.resume().catch(() => {});
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      oscillator.type = 'sine'; oscillator.frequency.value = 740;
      gain.gain.setValueAtTime(.045,audio.currentTime);
      gain.gain.exponentialRampToValueAtTime(.001,audio.currentTime + .24);
      oscillator.connect(gain); gain.connect(audio.destination);
      oscillator.start(); oscillator.stop(audio.currentTime + .25);
    } catch (_) { /* Visual unread indicators remain available if audio is blocked. */ }
  }
  function simulateIncoming(role) {
    const id = role === 'customer' ? model.conversations.find((item) => item.id !== ui.active && item.id !== CUSTOMER_ID)?.id || 'visitor-0866' : currentId() || CUSTOMER_ID;
    const text = role === 'customer' ? ['你好，想咨询一下壁纸设置的问题。','刚才补了一张截图，麻烦看一下。','请问现在方便帮我处理吗？'][Math.floor(Math.random()*3)] : '您好，消息已收到。我帮您核对一下，请稍等。';
    mutate((state) => {
      const item = state.conversations.find((entry) => entry.id === id);
      item.online = true;
      if (role === 'customer') item.hiddenForAgent = false;
      item.messages.push({id:uid(),role,text,createdAt:Date.now(),status:'sent',delivered:true,readByAgent:role === 'agent',readByCustomer:role === 'customer',attempts:1});
    });
    closeDemo();
    if (role !== currentRole()) {
      beep();
      const item = conversation(id);
      toast(`${role === 'customer' ? item.name:'倾境客服'}发来新消息。`,'success',role === 'customer' && id !== ui.active ? id:null);
    } else toast(role === 'customer' ? '已模拟另一位用户的新咨询。':'已模拟客服回复，可切换到用户 App 查看。');
  }
  function toast(message, kind = 'success', target = null) {
    const region = document.querySelector('#toast-region');
    if (!region) return;
    const element = document.createElement('div');
    element.className = `toast ${kind === 'error' ? 'error':''}`;
    element.innerHTML = `${icon(kind === 'error' ? 'alert':'check')}<span>${escape(message)}</span>${target ? `<button type="button" data-toast-conversation="${escape(target)}">查看</button>`:''}`;
    region.append(element);
    while (region.children.length > 2) region.firstElementChild.remove();
    setTimeout(() => element.remove(),4500);
  }
  function closeDemo() {
    demoPanel.hidden = true;
    document.querySelector('#demo-toggle').setAttribute('aria-expanded','false');
  }
  function attachExample() {
    if (ui.view !== 'customer' && !ui.active) return toast('请先选择要回复的用户。','error');
    if (!isChatOpen()) {
      if (ui.view === 'customer') ui.customerPage = 'chat';
      else { ui.agentPage = 'chat'; ui.adminChat = true; }
      render();
    }
    updateDraft({image:null,media:{type:'image',source:'assets/mountain.svg',name:'壁纸问题截图.svg'}});
    closeDemo();
    toast('示例图片已添加，点击发送即可。');
  }
  async function previewMedia(source, type = 'image') {
    try {
      const url = await SupportMediaStore.url(source);
      if (type === 'video') {
        const video = videoDialog.querySelector('video');
        video.src = url;
        videoDialog.showModal();
        video.play().catch(() => {});
      } else {
        imageDialog.querySelector('img').src = url;
        imageDialog.showModal();
      }
    } catch (error) { toast(error.message || '素材无法预览，请重试。','error'); }
  }
  function askDelete(target) {
    pendingDeletion = {...target,session:model.sessionId};
    deleteDialog.querySelector('#delete-title').textContent = target.title;
    deleteDialog.querySelector('#delete-description').textContent = target.description;
    deleteDialog.showModal();
  }
  function askDeleteConversation(id) {
    const item = conversation(id);
    if (!item || item.hiddenForAgent || currentRole() !== 'agent') return;
    askDelete({kind:'conversation',id,title:'删除会话？',description:`将「${item.name}」从客服列表中移除。聊天记录会保留，用户再次发来消息时，会话会重新出现。`});
  }
  function confirmDelete() {
    const target = pendingDeletion;
    pendingDeletion = null; deleteDialog.close();
    if (!target || target.session !== read().sessionId) return toast('演示数据已变更，请重新操作。','error');
    if (target.kind === 'library') return SupportLibrary.remove(target.id);
    mutate(state => {
      const item = state.conversations.find(row => row.id === target.id);
      if (!item) return;
      item.hiddenForAgent = true;
      item.messages.forEach(message => { if (message.delivered) message.readByAgent = true; });
    });
    toast('会话已从客服列表中删除。');
  }
  function useLibraryItem(item) {
    if (currentRole() !== 'agent' || !ui.active) { toast('请先选择要回复的用户。','error'); return false; }
    if (ui.view === 'admin' && ui.adminPage === 'library') { ui.adminPage = 'chat'; ui.adminChat = true; render(); }
    const input = document.querySelector('#message-input');
    if (!input) return false;
    if (item.type === 'phrase') {
      const start = input.selectionStart, end = input.selectionEnd;
      const text = input.value.slice(0,start) + item.text + input.value.slice(end);
      if (text.length > 2000) { toast('消息最多 2000 字，请删减后再插入。','error'); return false; }
      updateDraft({text}); input.value = text; input.focus(); input.setSelectionRange(start + item.text.length,start + item.text.length);
    } else updateDraft({image:null,media:{type:item.type,source:item.source,poster:item.poster || null,name:item.title}});
    toast(`${item.type === 'phrase' ? '短语已填入':'素材已添加'}，点击发送即可。`);
    return true;
  }
  document.addEventListener('click',(event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.view) return changeView(button.dataset.view);
    if (button.dataset.deleteConversation) return askDeleteConversation(button.dataset.deleteConversation);
    if (button.dataset.conversation) return openConversation(button.dataset.conversation);
    if (button.dataset.toastConversation) { openConversation(button.dataset.toastConversation); button.closest('.toast').remove(); return; }
    if (button.dataset.filter) {
      ui.filter = button.dataset.filter;
      document.querySelectorAll('[data-filter]').forEach((item) => { item.classList.toggle('active',item.dataset.filter === ui.filter); item.setAttribute('aria-pressed',String(item.dataset.filter === ui.filter)); });
      refreshScreen(); return;
    }
    if (button.dataset.retry) return beginSend(button.dataset.retry);
    if (button.dataset.image) return previewMedia(button.dataset.image);
    if (button.dataset.video) return previewMedia(button.dataset.video,'video');
    if (button.id === 'demo-toggle') {
      demoPanel.hidden = !demoPanel.hidden;
      button.setAttribute('aria-expanded',String(!demoPanel.hidden)); return;
    }
    if (button.classList.contains('image-close') && imageDialog.contains(button)) { imageDialog.close(); return; }
    switch (button.dataset.action) {
      case 'back': if (ui.view === 'customer') ui.customerPage = 'home'; else { ui.agentPage = 'list'; ui.adminChat = false; } render(); break;
      case 'open-customer-chat': ui.customerPage = 'chat'; render(); break;
      case 'sound': mutate((state) => { state.sound = !state.sound; }); if (model.sound) beep(); toast(model.sound ? '声音提醒已开启。':'声音提醒已关闭，未读消息仍会显示。'); break;
      case 'choose-image': imageInput.value = ''; imageInput.click(); break;
      case 'remove-image': updateDraft({image:null,media:null}); break;
      case 'quick': SupportLibrary.openPicker('phrase'); break;
      case 'open-library': SupportLibrary.openPicker('image'); break;
      case 'manage-library': ui.adminPage = 'library'; render(); break;
      case 'return-workbench': ui.adminPage = 'chat'; render(); break;
      case 'cancel-delete': pendingDeletion = null; deleteDialog.close(); break;
      case 'confirm-delete': confirmDelete(); break;
      case 'close-video': videoDialog.close(); break;
      case 'scroll-bottom': { const feed = document.querySelector('#chat-feed'); feed.scrollTop = feed.scrollHeight; button.hidden = true; break; }
      case 'close-demo': closeDemo(); break;
      case 'incoming-user': simulateIncoming('customer'); break;
      case 'incoming-agent': simulateIncoming('agent'); break;
      case 'example-image': attachExample(); break;
      case 'network': mutate((state) => { state.network = !state.network; }); toast(model.network ? '网络已恢复，失败消息可以点击重发。':'已模拟断网；消息内容会保留。',model.network ? 'success':'error'); closeDemo(); break;
      case 'reset': closeDemo(); resetDialog.showModal(); break;
      case 'cancel-reset': resetDialog.close(); break;
      case 'confirm-reset': model = seed(); write(model); ui.active = CUSTOMER_ID; ui.search = ''; ui.filter = 'all'; ui.quick = false; ui.nextOutcome = 'success'; document.querySelector('#send-outcome').value = 'success'; resetDialog.close(); render(); toast('演示数据已重置。'); break;
      case 'admin-home': ui.adminChat = false; ui.adminPage = 'chat'; render(); break;
      case 'context-menu': toast('当前原型展示在线客服模块。'); break;
    }
  });
  document.addEventListener('input',(event) => {
    if (event.target.id === 'conversation-search') { ui.search = event.target.value; const list = document.querySelector('#conversation-list'); if (list) list.innerHTML = listItems(); }
    if (event.target.id === 'message-input') updateDraft({text:event.target.value});
  });
  document.addEventListener('submit',(event) => { if (event.target.id === 'composer') { event.preventDefault(); beginSend(); } });
  document.addEventListener('keydown',(event) => {
    if (event.key === 'Escape') closeDemo();
    if (event.target.id === 'message-input' && event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); beginSend(); }
  });
  document.querySelector('#send-outcome').addEventListener('change',(event) => { ui.nextOutcome = event.target.value; toast(`已设置下一条消息：${event.target.selectedOptions[0].textContent}。`); closeDemo(); });
  document.addEventListener('pointerdown',(event) => { if (!demoPanel.hidden && !demoPanel.contains(event.target) && !event.target.closest('#demo-toggle')) closeDemo(); });
  imageDialog.addEventListener('click',(event) => { if (event.target === imageDialog) imageDialog.close(); });
  videoDialog.addEventListener('close',() => { const video = videoDialog.querySelector('video'); video.pause(); video.removeAttribute('src'); video.load(); });
  videoDialog.addEventListener('click',event => { if (event.target === videoDialog) videoDialog.close(); });
  imageInput.addEventListener('change',async () => {
    const file = imageInput.files?.[0];
    if (!file) return;
    if (!['image/png','image/jpeg','image/webp'].includes(file.type)) return toast('请选择 PNG、JPG 或 WebP 图片。','error');
    if (file.size > 8 * 1024 * 1024) return toast('图片不能超过 8 MB，请选择较小的图片。','error');
    const key = draftKey();
    const session = model.sessionId;
    try {
      const source = await SupportMediaStore.save(file,'image');
      if (read().sessionId !== session) return;
      mutate((state) => { if (state.sessionId === session) state.drafts[key] = {...(state.drafts[key] || {text:''}),image:null,media:{type:'image',source,name:file.name}}; },false);
      if (draftKey() === key) updateComposer();
      toast('图片已添加，点击发送即可。');
    } catch (_) { toast('图片读取失败，请重新选择。','error'); }
  });
  window.addEventListener('storage',(event) => {
    if (event.key !== STORAGE_KEY) return;
    const previousSession = model.sessionId;
    const deliveredIds = new Set(model.conversations.flatMap((item) => item.messages.filter((message) => message.delivered).map((message) => message.id)));
    model = read();
    if (previousSession !== model.sessionId) { render(); return; }
    const incoming = model.conversations.find((item) => (currentRole() === 'agent' || item.id === CUSTOMER_ID) && item.messages.some((message) => message.delivered && message.role !== currentRole() && !deliveredIds.has(message.id)));
    refreshScreen();
    if (incoming) {
      beep();
      toast(`${currentRole() === 'agent' ? incoming.name:'倾境客服'}发来新消息。`,'success',currentRole() === 'agent' && incoming.id !== ui.active ? incoming.id:null);
    }
  });
  document.addEventListener('visibilitychange',() => { if (document.visibilityState === 'visible') { model = read(); refreshScreen(); } });
  // A refresh can interrupt a simulated acknowledgement. Never silently mark it sent.
  setInterval(() => {
    const latest = read();
    const stale = latest.conversations.some((item) => item.messages.some((message) => message.status === 'sending' && Date.now() - message.pendingAt > 5000));
    if (stale) mutate((state) => { state.conversations.forEach((item) => item.messages.forEach((message) => { if (message.status === 'sending' && Date.now() - message.pendingAt > 5000) { message.status = 'uncertain'; message.error = '上次发送未确认，内容已保留，请重试。'; } })); });
  },2000);
  SupportLibrary.mount({
    icon, escape, uid, toast, mutate, preview:previewMedia, askDelete, use:useLibraryItem,
    state:() => { model = read(); return model; },
    isAdmin:() => ui.view === 'admin',
    hasConversation:() => currentRole() === 'agent' && !!ui.active && !!conversation(),
    renderManager:render,
  });
  document.querySelectorAll('[data-icon]').forEach((element) => { element.outerHTML = icon(element.dataset.icon); });
  render();
})();
