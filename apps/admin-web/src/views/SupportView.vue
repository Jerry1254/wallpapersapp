<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ChatDotRound, Delete, Picture, VideoPlay, Collection, Refresh } from '@element-plus/icons-vue';
import SupportMedia from '@/components/SupportMedia.vue';
import SupportLibrary from '@/components/SupportLibrary.vue';
import { BrowserSupportCache, createSupportChat } from '@/domain/supportChat';
import { compareSupportIds, supportPreview, type LibraryKind, type PendingSupportMessage, type SupportConversation, type SupportLibraryItem } from '@/domain/support';
import { supportRepository as repository } from '@/repositories/http/supportRepository';
import { readableApiError } from '@/repositories/http/apiClient';
const chat = createSupportChat(repository, new BrowserSupportCache()); const state = chat.state;
const users = ref<SupportConversation[]>([]); const search = ref(''); const unread = ref(false); const page = ref(1); const total = ref(0);
const listError = ref(''); const connected = ref(true); const sound = ref(false); const loadingList = ref(false);
const library = ref(false); const manage = ref(false); const libraryKind = ref<LibraryKind>('IMAGE');
const scroll = ref<HTMLElement>(); const input = ref<{ textarea: HTMLTextAreaElement }>(); const sending = ref(false); const olderBusy = ref(false);
const selected = computed(() => state.selected); const draft = computed(() => selected.value ? chat.draft(selected.value.id) : null);
const messages = computed(() => selected.value ? chat.feed(selected.value.id).messages : []);
const pending = computed(() => state.pending.filter(item => item.conversationId === selected.value?.id));
let running = false; let stopped = false; let timer: ReturnType<typeof setTimeout>; let delay = 2000; let listGeneration = 0;
const observed = new Map<string, string>(); let initialPoll = true; let audio: AudioContext | undefined;
function enableSound() { if (sound.value) { audio ??= new AudioContext(); void audio.resume(); } }
function beep() { if (!sound.value || !audio || audio.state !== 'running') return; const oscillator = audio.createOscillator(); const gain = audio.createGain(); oscillator.connect(gain); gain.connect(audio.destination); gain.gain.value = .06; oscillator.frequency.value = 740; oscillator.start(); oscillator.stop(audio.currentTime + .15); }
function noteNew(list: SupportConversation[]) {
  let fresh = false;
  for (const user of list) {
    const id = user.lastMessage?.id || '0'; const prior = observed.get(user.id);
    if (!initialPoll && user.unreadCount > 0 && user.lastMessage?.sender === 'CUSTOMER' && (!prior || compareSupportIds(id, prior) > 0)) fresh = true;
    observed.set(user.id, id);
  }
  if (fresh) { ElMessage({ message: '收到新的用户消息', type: 'info', grouping: true }); beep(); }
  initialPoll = false;
}
async function loadList() {
  const token = ++listGeneration; loadingList.value = !users.value.length;
  try {
    const result = await repository.conversations(search.value.trim(), unread.value, page.value);
    if (token === listGeneration) { users.value = result.items; total.value = result.total; listError.value = ''; }
    const notice = search.value || unread.value || page.value !== 1 ? await repository.conversations() : result;
    noteNew(notice.items);
  } catch (cause) { if (token === listGeneration) listError.value = readableApiError(cause, '会话暂时无法加载'); throw cause; }
  finally { if (token === listGeneration) loadingList.value = false; }
}
const atBottom = () => !scroll.value || scroll.value.scrollHeight - scroll.value.scrollTop - scroll.value.clientHeight < 70;
async function scrollAndRead(id: string, stick: boolean) {
  await nextTick(); if (selected.value?.id !== id || document.hidden) return;
  if (stick && scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight;
  if (atBottom()) { const last = chat.feed(id).messages.at(-1); if (last) await chat.markRead(id, last.id); }
}
async function select(user: SupportConversation) { await chat.select(user); await scrollAndRead(user.id, true).catch(() => {}); }
async function poll() {
  clearTimeout(timer); if (stopped || document.hidden || running) return; running = true;
  try {
    await loadList(); const id = selected.value?.id;
    if (id) {
      const current = await repository.conversation(id);
      if (selected.value?.id === id) {
        if (current.hidden) state.selected = null;
        else { state.selected = current; const stick = atBottom(); await chat.sync(id); await chat.recover(id); await scrollAndRead(id, stick); }
      }
    }
    connected.value = true; delay = 2000;
  } catch { connected.value = false; delay = Math.min(delay * 2, 30_000); }
  finally { running = false; if (!stopped && !document.hidden) timer = setTimeout(() => void poll(), delay); }
}
function visibility() { clearTimeout(timer); if (!document.hidden) { delay = 2000; void poll(); } }
async function hide(user: SupportConversation) {
  try { await ElMessageBox.confirm('会话将移出列表，聊天记录保留。用户再次发送消息后会重新出现。', `移出 ${user.name}`, { confirmButtonText: '移出列表', cancelButtonText: '取消', type: 'warning' }); }
  catch { return; }
  try { await repository.hide(user.id); if (selected.value?.id === user.id) state.selected = null; await loadList(); }
  catch (cause) { ElMessage.error(readableApiError(cause, '移出失败，请重试')); }
}
function openLibrary(kind: LibraryKind, editing = false) { libraryKind.value = kind; manage.value = editing; library.value = true; }
async function choose(item: SupportLibraryItem) {
  const current = draft.value; if (!current) return;
  if (item.kind === 'PHRASE') {
    const element = input.value?.textarea; const start = element?.selectionStart ?? current.text.length; const end = element?.selectionEnd ?? start;
    const value = current.text.slice(0, start) + (item.text || '') + current.text.slice(end);
    if (value.length > 2000) { ElMessage.warning('插入后超过 2000 字，请先缩短内容'); return; }
    current.text = value; chat.saveDraft(); await nextTick(); element?.focus(); element?.setSelectionRange(start + (item.text?.length || 0), start + (item.text?.length || 0));
  } else { current.attachment = item.attachment; current.libraryItemId = item.id; chat.saveDraft(); }
}
async function send() {
  const id = selected.value?.id; if (!id || sending.value) return; sending.value = true;
  try { await chat.sendDraft(id); await scrollAndRead(id, true); }
  catch (cause) { ElMessage.error(readableApiError(cause, '发送失败')); }
  finally { sending.value = false; }
}
function enter(event: KeyboardEvent) { if (!event.shiftKey && !event.isComposing) { event.preventDefault(); void send(); } }
async function retry(item: PendingSupportMessage) { await chat.retry(item); await scrollAndRead(item.conversationId, true).catch(() => {}); }
async function older() {
  const id = selected.value?.id; if (!id || olderBusy.value) return; olderBusy.value = true; const height = scroll.value?.scrollHeight || 0;
  try { await chat.older(id); await nextTick(); if (selected.value?.id === id && scroll.value) scroll.value.scrollTop += scroll.value.scrollHeight - height; }
  catch (cause) { ElMessage.error(readableApiError(cause, '历史记录加载失败')); } finally { olderBusy.value = false; }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
watch([unread, page], () => void loadList().catch(() => {}));
onMounted(() => { chat.restore(); void poll(); document.addEventListener('visibilitychange', visibility); });
onBeforeUnmount(() => { stopped = true; listGeneration++; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility); void audio?.close(); });
</script>
<template>
  <section class="support-page">
    <div class="support-page-heading"><div><h1>在线客服</h1><p>每一条消息，都有回应</p></div><div><ElSwitch v-model="sound" aria-label="声音提醒" active-text="声音提醒" @change="enableSound" /><ElButton :icon="Collection" @click="openLibrary('IMAGE', true)">素材与快捷短语</ElButton></div></div>
    <ElAlert v-if="state.cacheError" :title="state.cacheError" type="error" :closable="false"><ElButton text @click="chat.restore()">重新读取</ElButton></ElAlert>
    <ElAlert v-if="!connected" title="连接暂时中断，恢复后将继续同步。发送结果未确认的消息可核对或重试。" type="warning" :closable="false"><ElButton text :icon="Refresh" @click="poll()">重新连接</ElButton></ElAlert>
    <div class="support-workspace">
      <aside class="support-users">
        <div class="support-users-toolbar"><strong>会话</strong><span>{{ total }}</span><ElCheckbox v-model="unread" @change="page = 1">仅看未读</ElCheckbox></div>
        <ElInput v-model="search" placeholder="搜索用户" clearable @keyup.enter="page = 1; loadList().catch(() => {})" @clear="page = 1; loadList().catch(() => {})" />
        <ElAlert v-if="listError" :title="listError" type="error" :closable="false" />
        <div v-loading="loadingList" class="support-user-list">
          <div v-for="user in users" :key="user.id" class="support-user" :class="{ active: selected?.id === user.id }">
            <button class="support-user-select" @click="select(user)"><span class="support-avatar">{{ user.name.slice(-2) }}</span><span class="support-user-copy"><strong>{{ user.name }}</strong><small>{{ supportPreview(user.lastMessage) }}</small></span><b v-if="user.unreadCount" class="support-unread">{{ user.unreadCount > 99 ? '99+' : user.unreadCount }}</b></button>
            <ElButton class="support-user-delete" text circle :icon="Delete" :aria-label="`移出${user.name}`" @click="hide(user)" />
          </div>
          <ElEmpty v-if="!loadingList && !users.length && !listError" :image-size="64" description="暂无会话" />
        </div>
        <ElPagination v-if="total > 50" v-model:current-page="page" :total="total" :page-size="50" layout="prev, next" small />
      </aside>
      <main v-if="selected" class="support-chat">
        <header class="support-chat-header"><span class="support-avatar">{{ selected.name.slice(-2) }}</span><div><strong>{{ selected.name }}</strong><small><i class="support-presence" :class="{ online: selected.online }"></i>{{ selected.online ? '在线' : '离线' }}</small></div><ElButton text :icon="Delete" @click="hide(selected)">移出列表</ElButton></header>
        <ElAlert v-if="state.error" :title="state.error" type="error" :closable="false"><ElButton text @click="select(selected)">重试</ElButton></ElAlert>
        <div ref="scroll" v-loading="state.loading" class="support-messages" @scroll="scrollAndRead(selected.id, false).catch(() => {})">
          <ElButton v-if="chat.feed(selected.id).hasOlder" class="support-history" text :loading="olderBusy" @click="older">加载更早消息</ElButton>
          <p v-if="!state.loading && !messages.length && !pending.length" class="support-empty">暂时没有消息，发送一句问候吧</p>
          <article v-for="message in messages" :key="message.id" class="support-message" :class="{ mine: message.sender === 'ADMIN' }">
            <small>{{ message.sender === 'ADMIN' ? '客服' : selected.name }} · {{ time(message.createdAt) }}</small>
            <div class="support-bubble"><SupportMedia v-if="message.attachment" :attachment="message.attachment" /><span v-else>{{ message.text }}</span></div>
            <small v-if="message.sender === 'ADMIN'" class="support-sent">已发送</small>
          </article>
          <article v-for="item in pending" :key="item.request.clientId" class="support-message mine"><small>客服 · {{ time(item.createdAt) }}</small>
            <div class="support-bubble"><SupportMedia v-if="item.attachment" :attachment="item.attachment" /><span v-else>{{ item.request.text }}</span></div>
            <div class="support-send-state" :class="item.state"><span>{{ item.state === 'sending' ? '发送中…' : item.state === 'failed' ? '发送失败' : '发送结果未确认' }}</span><ElButton v-if="item.state !== 'sending'" text size="small" @click="retry(item)">核对并重试</ElButton></div>
            <small v-if="item.error" class="support-failure">{{ item.error }}</small>
          </article>
        </div>
        <footer v-if="draft" class="support-composer">
          <div class="support-composer-tools"><ElButton text :icon="Picture" @click="openLibrary('IMAGE')">图片</ElButton><ElButton text :icon="VideoPlay" @click="openLibrary('VIDEO')">视频</ElButton><ElButton text :icon="ChatDotRound" @click="openLibrary('PHRASE')">快捷短语</ElButton></div>
          <div v-if="draft.attachment" class="support-attachment-draft"><span>{{ draft.attachment.kind === 'IMAGE' ? '图片' : '视频' }} · {{ draft.attachment.filename }}</span><ElButton text @click="draft.attachment = null; draft.libraryItemId = null; chat.saveDraft()">取消选择</ElButton><small>点击发送后发送此素材；输入区文字会保留。</small></div>
          <ElInput ref="input" v-model="draft.text" type="textarea" :rows="3" resize="none" maxlength="2000" placeholder="输入回复，Enter 发送，Shift + Enter 换行" @input="chat.saveDraft" @keydown.enter="enter" />
          <div class="support-composer-bottom"><small>已发送表示服务器已保存</small><span>{{ draft.text.length }} / 2000</span><ElButton type="primary" :loading="sending" :disabled="!!state.cacheError || (!draft.text.trim() && !draft.attachment)" @click="send">发送</ElButton></div>
        </footer>
      </main>
      <main v-else class="support-select-empty"><ElIcon :size="54"><ChatDotRound /></ElIcon><h2>选择会话，开始回复</h2><p>用户消息和聊天记录会显示在这里</p></main>
    </div>
    <SupportLibrary v-model="library" :manage="manage" :initial-kind="libraryKind" @choose="choose" />
  </section>
</template>
