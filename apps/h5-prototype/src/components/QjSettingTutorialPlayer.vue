<script setup lang="ts">
import { Maximize2, Pause, Play, X } from '@lucide/vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  src?: string;
  title?: string;
}>(), {
  src: '/media/setting-tutorial.mp4',
  title: '设置教程'
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

const player = ref<HTMLVideoElement>();
const playerShell = ref<HTMLElement>();
const duration = ref(0);
const currentTime = ref(0);
const playing = ref(false);

const timeText = computed(() => `${formatTime(currentTime.value)} / ${formatTime(duration.value)}`);

function formatTime(value: number) {
  if (!Number.isFinite(value)) return '00:00';
  const minutes = Math.floor(value / 60).toString().padStart(2, '0');
  const seconds = Math.floor(value % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
}

const syncDuration = () => {
  duration.value = player.value?.duration || 0;
};

const syncTime = () => {
  currentTime.value = player.value?.currentTime || 0;
};

const togglePlayback = async () => {
  const video = player.value;
  if (!video) return;
  if (video.paused) {
    try {
      await video.play();
    } catch {
      playing.value = false;
    }
    return;
  }
  video.pause();
};

const seek = (event: Event) => {
  const video = player.value;
  if (!video) return;
  const value = Number((event.target as HTMLInputElement).value);
  video.currentTime = value;
  currentTime.value = value;
};

const requestFullscreen = async () => {
  const shell = playerShell.value;
  const video = player.value as (HTMLVideoElement & { webkitEnterFullscreen?: () => void }) | undefined;
  if (shell?.requestFullscreen) {
    try {
      await shell.requestFullscreen();
      return;
    } catch {
      // iOS WebView uses the native video fullscreen API below.
    }
  }
  video?.webkitEnterFullscreen?.();
};

const close = () => {
  player.value?.pause();
  if (document.fullscreenElement && document.exitFullscreen) {
    void document.exitFullscreen().catch(() => undefined);
  }
  emit('update:modelValue', false);
};

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') close();
};

watch(() => props.modelValue, async (visible) => {
  document.body.style.overflow = visible ? 'hidden' : '';
  if (!visible) {
    player.value?.pause();
    return;
  }
  await nextTick();
  player.value?.focus();
});

watch(() => props.src, () => {
  player.value?.pause();
  currentTime.value = 0;
  duration.value = 0;
  playing.value = false;
  player.value?.load();
});

onBeforeUnmount(() => {
  document.body.style.overflow = '';
  window.removeEventListener('keydown', handleKeydown);
});

window.addEventListener('keydown', handleKeydown);
</script>

<template>
  <Teleport to="body">
    <Transition name="qj-player-fade">
      <section
        v-if="modelValue"
        ref="playerShell"
        class="qj-setting-player"
        role="dialog"
        aria-modal="true"
        aria-label="壁纸设置教程"
      >
        <header>
          <div>
            <strong>{{ title }}</strong>
            <small>跟随视频完成壁纸设置</small>
          </div>
          <button type="button" aria-label="关闭教程" @click="close">
            <X :size="24" aria-hidden="true" />
          </button>
        </header>

        <div class="qj-setting-player__stage">
          <video
            :key="src"
            ref="player"
            :src="src"
            playsinline
            preload="metadata"
            aria-label="壁纸设置教程视频"
            @loadedmetadata="syncDuration"
            @durationchange="syncDuration"
            @timeupdate="syncTime"
            @play="playing = true"
            @pause="playing = false"
            @ended="playing = false"
            @click="togglePlayback"
          ></video>
          <button
            v-if="!playing"
            type="button"
            class="qj-setting-player__center-play"
            aria-label="播放教程"
            @click="togglePlayback"
          >
            <Play :size="30" fill="currentColor" aria-hidden="true" />
          </button>
        </div>

        <div class="qj-setting-player__controls">
          <button type="button" :aria-label="playing ? '暂停教程' : '播放教程'" @click="togglePlayback">
            <Pause v-if="playing" :size="21" fill="currentColor" aria-hidden="true" />
            <Play v-else :size="21" fill="currentColor" aria-hidden="true" />
          </button>
          <input
            type="range"
            min="0"
            :max="duration || 0"
            step="0.05"
            :value="currentTime"
            aria-label="视频进度"
            @input="seek"
          />
          <span>{{ timeText }}</span>
          <button type="button" aria-label="全屏播放" @click="requestFullscreen">
            <Maximize2 :size="20" aria-hidden="true" />
          </button>
        </div>
      </section>
    </Transition>
  </Teleport>
</template>

<style scoped>
.qj-setting-player {
  position: fixed;
  z-index: 4000;
  inset: 0;
  display: grid;
  min-width: 320px;
  padding: max(16px, env(safe-area-inset-top)) 16px max(20px, env(safe-area-inset-bottom));
  grid-template-rows: auto minmax(0, 1fr) auto;
  color: #fff;
  background: #0f0f0f;
}

.qj-setting-player header {
  display: flex;
  width: min(100%, 720px);
  min-height: 54px;
  margin: 0 auto 14px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.qj-setting-player header div {
  display: grid;
  gap: 3px;
}

.qj-setting-player header strong {
  font-size: 18px;
}

.qj-setting-player header small {
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
}

.qj-setting-player button {
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
  cursor: pointer;
}

.qj-setting-player__stage {
  position: relative;
  display: grid;
  width: auto;
  max-width: min(100%, 520px);
  height: auto;
  max-height: 100%;
  min-height: 0;
  margin: 0 auto;
  align-self: stretch;
  justify-self: center;
  aspect-ratio: 9 / 16;
  overflow: hidden;
  place-items: center;
  border-radius: 24px;
  background: #191817;
}

.qj-setting-player video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  outline: 0;
}

.qj-setting-player__center-play {
  position: absolute;
  width: 64px !important;
  height: 64px !important;
  padding-left: 4px !important;
  color: var(--qj-color-ink) !important;
  background: var(--qj-color-accent) !important;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.32);
}

.qj-setting-player__controls {
  display: grid;
  width: min(100%, 720px);
  min-height: 64px;
  margin: 14px auto 0;
  grid-template-columns: 44px minmax(80px, 1fr) auto 44px;
  align-items: center;
  gap: 10px;
}

.qj-setting-player__controls input {
  width: 100%;
  accent-color: var(--qj-color-accent);
  cursor: pointer;
}

.qj-setting-player__controls span {
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.qj-player-fade-enter-active,
.qj-player-fade-leave-active {
  transition: opacity var(--qj-duration-base) var(--qj-ease-standard);
}

.qj-player-fade-enter-from,
.qj-player-fade-leave-to {
  opacity: 0;
}
</style>
