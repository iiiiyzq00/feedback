<template>
  <section class="channel-hero" :class="`channel-hero--${channel || 'all'}`">
    <div class="channel-hero__main">
      <div class="channel-hero__eyebrow">
        <span class="channel-dot" />{{ eyebrow }}
      </div>
      <h2>{{ title }}</h2>
      <p>{{ intro }}</p>
    </div>
    <div class="channel-hero__facts">
      <div v-for="item in facts" :key="item.label">
        <span>{{ item.label }}</span
        ><b>{{ item.value }}</b>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { FeedbackSourceChannel } from '@/api/feedback';

defineProps<{
  channel?: FeedbackSourceChannel;
  title: string;
  intro: string;
  eyebrow: string;
  facts: { label: string; value: string }[];
}>();
</script>

<style scoped>
.channel-hero {
  --hero-accent: #409eff;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 18px;
  padding: 22px 24px;
  border: 1px solid #d9e5f3;
  border-radius: 16px;
  background: linear-gradient(
    125deg,
    color-mix(in srgb, var(--hero-accent) 9%, #fff),
    #fff 62%
  );
  box-shadow: 0 8px 26px rgba(41, 72, 110, 0.05);
}
.channel-hero--sdk {
  --hero-accent: #409eff;
}
.channel-hero--station_feedback {
  --hero-accent: #8b6bd8;
}
.channel-hero--jira {
  --hero-accent: #1d7af3;
}
.channel-hero--quality {
  --hero-accent: #28a77a;
}
.channel-hero--redblack {
  --hero-accent: #d65b63;
}
.channel-hero--all {
  --hero-accent: #5578a5;
}
.channel-hero__main {
  min-width: 0;
}
.channel-hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--hero-accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}
.channel-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--hero-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--hero-accent) 13%, transparent);
}
h2 {
  margin: 8px 0 7px;
  color: #1c3150;
  font-size: 25px;
  line-height: 1.3;
}
p {
  max-width: 720px;
  margin: 0;
  color: #687b92;
  font-size: 13px;
  line-height: 1.65;
}
.channel-hero__facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(92px, 1fr));
  min-width: 360px;
  overflow: hidden;
  border: 1px solid rgba(211, 223, 238, 0.9);
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.78);
}
.channel-hero__facts > div {
  padding: 11px 13px;
  border-right: 1px solid #e5edf6;
}
.channel-hero__facts > div:last-child {
  border-right: 0;
}
.channel-hero__facts span {
  display: block;
  margin-bottom: 5px;
  color: #8291a4;
  font-size: 10px;
}
.channel-hero__facts b {
  display: block;
  color: #334b69;
  font-size: 12px;
  line-height: 1.35;
}
@media (max-width: 980px) {
  .channel-hero {
    align-items: stretch;
    flex-direction: column;
  }
  .channel-hero__facts {
    min-width: 0;
    width: 100%;
  }
}
@media (max-width: 560px) {
  .channel-hero {
    padding: 18px;
  }
  .channel-hero__facts {
    grid-template-columns: 1fr;
  }
  .channel-hero__facts > div {
    border-right: 0;
    border-bottom: 1px solid #e5edf6;
  }
  .channel-hero__facts > div:last-child {
    border-bottom: 0;
  }
}
</style>
