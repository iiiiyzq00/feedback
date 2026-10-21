<template><header class="app-page-header"><div class="heading"><div class="eyebrow" v-if="eyebrow">{{eyebrow}}</div><h2>{{title}}</h2><p v-if="description">{{description}}</p><slot name="meta"/></div><div v-if="$slots.actions" class="actions"><slot name="actions"/></div></header></template>
<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>
<style scoped>.app-page-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:22px}.heading{min-width:0}.eyebrow{margin-bottom:5px;color:var(--color-primary);font-size:12px;font-weight:650;letter-spacing:.08em}.heading h2{font-size:24px;line-height:1.35;font-weight:700;color:var(--text-primary)}.heading p{margin-top:6px;color:var(--text-secondary);font-size:13px;line-height:1.6}.actions{display:flex;align-items:center;justify-content:flex-end;gap:10px;flex-wrap:wrap}@media(max-width:760px){.app-page-header{align-items:stretch;flex-direction:column}.actions{justify-content:flex-start}}</style>
