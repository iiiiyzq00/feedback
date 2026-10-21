import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { usePreferencesStore } from './preferences'

describe('preferences store', () => {
  beforeEach(() => {
    const values = new Map<string, string>()
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    } })
    setActivePinia(createPinia())
  })

  it('支持全局和页面级表格密度', async () => {
    const store = usePreferencesStore()
    store.setDensity('compact')
    store.setDensity('comfortable', 'import-batches')
    await nextTick()
    expect(store.densityFor()).toBe('compact')
    expect(store.densityFor('import-batches')).toBe('comfortable')
    expect(localStorage.getItem('feedback-platform-preferences')).toContain('import-batches')
  })
})
