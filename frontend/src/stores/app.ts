import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const globalSearchVisible = ref(false)
  const globalSearchQuery = ref('')
  const pageLoading = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function openGlobalSearch() {
    globalSearchVisible.value = true
  }

  function closeGlobalSearch() {
    globalSearchVisible.value = false
    globalSearchQuery.value = ''
  }

  return {
    sidebarCollapsed,
    globalSearchVisible,
    globalSearchQuery,
    pageLoading,
    toggleSidebar,
    openGlobalSearch,
    closeGlobalSearch,
  }
})
