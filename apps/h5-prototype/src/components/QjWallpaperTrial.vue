<script setup lang="ts">
import { Clock3, X } from '@lucide/vue';
import { computed, onBeforeUnmount, watch } from 'vue';
import { ref } from 'vue';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  src: string;
  title: string;
  durationSeconds?: number;
}>(), {
  durationSeconds: 120
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  expired: [];
}>();

const remainingSeconds = ref(props.durationSeconds);
let countdownTimer: number | undefined;

const remainingText = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60).toString().padStart(2, '0');
  const seconds = (remainingSeconds.value % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
});

const clearCountdown = () => {
  if (countdownTimer) window.clearInterval(countdownTimer);
  countdownTimer = undefined;
};

const close = () => {
  clearCountdown();
  emit('update:modelValue', false);
};

const startCountdown = () => {
  clearCountdown();
  remainingSeconds.value = props.durationSeconds;
  countdownTimer = window.setInterval(() => {
    remainingSeconds.value -= 1;
    if (remainingSeconds.value > 0) return;
    clearCountdown();
    emit('update:modelValue', false);
    emit('expired');
  }, 1000);
};

const handleKeydown = (event: KeyboardEvent) => {
  if (props.modelValue && event.key === 'Escape') close();
};

watch(() => props.modelValue, (visible) => {
  document.body.style.overflow = visible ? 'hidden' : '';
  if (visible) startCountdown();
  else clearCountdown();
});

window.addEventListener('keydown', handleKeydown);

onBeforeUnmount(() => {
  clearCountdown();
  document.body.style.overflow = '';
  window.removeEventListener('keydown', handleKeydown);
});
</script>

<template>
  <Teleport to="body">
    <Transition name="qj-trial-fade">
      <section v-if="modelValue" class="qj-wallpaper-trial" role="dialog" aria-modal="true" aria-label="壁纸试用">
        <div class="qj-wallpaper-trial__motion">
          <img :src="src" :alt="title + '试用预览'" />
        </div>
        <div class="qj-wallpaper-trial__shade"></div>

        <header>
          <div class="qj-wallpaper-trial__timer" role="timer" aria-live="polite">
            <Clock3 :size="18" aria-hidden="true" />
            <span>试用剩余</span>
            <strong>{{ remainingText }}</strong>
          </div>
          <button type="button" aria-label="结束试用" @click="close">
            <X :size="24" aria-hidden="true" />
          </button>
        </header>

        <footer>
          <div>
            <small>2 分钟完整效果试用</small>
            <h2>{{ title }}</h2>
            <p>轻轻转动手机，体验壁纸动态与景深效果</p>
          </div>
          <button type="button" @click="close">结束试用</button>
        </footer>
      </section>
    </Transition>
  </Teleport>
</template>

<style scoped>
.qj-wallpaper-trial {
  position: fixed;
  z-index: 3900;
  inset: 0;
  min-width: 320px;
  overflow: hidden;
  color: #fff;
  background: #111;
}

.qj-wallpaper-trial__motion,
.qj-wallpaper-trial__shade {
  position: absolute;
  inset: 0;
}

.qj-wallpaper-trial__motion {
  overflow: hidden;
}

.qj-wallpaper-trial__motion img {
  width: 112%;
  max-width: none;
  height: 112%;
  margin: -6%;
  object-fit: cover;
  animation: qj-trial-depth 8s ease-in-out infinite alternate;
}

.qj-wallpaper-trial__shade {
  background: linear-gradient(180deg, rgba(10, 10, 10, 0.48) 0%, transparent 28%, transparent 56%, rgba(10, 10, 10, 0.78) 100%);
}

.qj-wallpaper-trial header,
.qj-wallpaper-trial footer {
  position: absolute;
  z-index: 1;
  right: max(20px, env(safe-area-inset-right));
  left: max(20px, env(safe-area-inset-left));
}

.qj-wallpaper-trial header {
  top: max(20px, env(safe-area-inset-top));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.qj-wallpaper-trial__timer {
  display: inline-flex;
  min-height: 44px;
  padding: 0 15px;
  align-items: center;
  gap: 7px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: var(--qj-radius-pill);
  background: rgba(17, 17, 17, 0.58);
  backdrop-filter: blur(12px);
}

.qj-wallpaper-trial__timer span {
  color: rgba(255, 255, 255, 0.72);
  font-size: var(--qj-font-size-caption);
}

.qj-wallpaper-trial__timer strong {
  font-size: var(--qj-font-size-body);
  font-variant-numeric: tabular-nums;
}

.qj-wallpaper-trial header > button {
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 50%;
  color: #fff;
  background: rgba(17, 17, 17, 0.58);
  backdrop-filter: blur(12px);
}

.qj-wallpaper-trial footer {
  bottom: max(24px, env(safe-area-inset-bottom));
  display: grid;
  gap: 20px;
}

.qj-wallpaper-trial footer small {
  color: var(--qj-color-accent);
  font-size: var(--qj-font-size-caption-large);
  font-weight: var(--qj-font-weight-bold);
}

.qj-wallpaper-trial footer h2 {
  margin: 6px 0 4px;
  font-size: 28px;
}

.qj-wallpaper-trial footer p {
  margin: 0;
  color: rgba(255, 255, 255, 0.72);
  font-size: var(--qj-font-size-caption-large);
}

.qj-wallpaper-trial footer > button {
  min-height: 52px;
  border: 1px solid rgba(255, 255, 255, 0.44);
  border-radius: var(--qj-radius-pill);
  color: #fff;
  font-size: var(--qj-font-size-body);
  font-weight: var(--qj-font-weight-bold);
  background: rgba(17, 17, 17, 0.62);
  backdrop-filter: blur(12px);
}

.qj-trial-fade-enter-active,
.qj-trial-fade-leave-active {
  transition: opacity var(--qj-duration-base) var(--qj-ease-standard);
}

.qj-trial-fade-enter-from,
.qj-trial-fade-leave-to {
  opacity: 0;
}

@keyframes qj-trial-depth {
  from { transform: translate3d(-1.8%, -0.8%, 0) scale(1.02); }
  to { transform: translate3d(1.8%, 0.8%, 0) scale(1.06); }
}
</style>
