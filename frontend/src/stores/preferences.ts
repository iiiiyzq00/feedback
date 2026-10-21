import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'

export type TableDensity = 'compact' | 'standard' | 'comfortable'
const STORAGE_KEY = 'feedback-platform-preferences'

export const usePreferencesStore = defineStore('preferences', () => {
  let initial: { tableDensity?: TableDensity; tableDensities?: Record<string, TableDensity> } = {}
  try { initial = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') } catch { /* 使用默认值 */ }
  const state = reactive({ tableDensity: initial.tableDensity || 'standard' as TableDensity, tableDensities: initial.tableDensities || {} })
  function densityFor(key?: string): TableDensity { return key && state.tableDensities[key] ? state.tableDensities[key] : state.tableDensity }
  function setDensity(value: TableDensity, key?: string) { if (key) state.tableDensities[key] = value; else state.tableDensity = value }
  function reset() { state.tableDensity = 'standard'; state.tableDensities = {} }
  watch(state, value => { try { localStorage.setItem(STORAGE_KEY, JSON.stringify(value)) } catch { /* 不影响业务 */ } }, { deep: true })
  return { state, densityFor, setDensity, reset }
})

