import { describe, expect, it } from 'vitest'
import pageSource from './UserPermissionPage.vue?raw'

describe('UserPermissionPage feedback authorization', () => {
  it('offers the feedback role with the Chinese authorization description', () => {
    expect(pageSource).toContain('value="PHASE_ONE_TECHNICAL"')
    expect(pageSource).toContain('value="PHASE_ONE_PRODUCT"')
    expect(pageSource).toContain('技术：直接查看原文与分析证据')
    expect(pageSource).toContain('产品：查看局部隐藏原文与分析证据')
    for (const role of ['ADMIN', 'OPERATOR', 'AUDITOR', 'USER']) {
      expect(pageSource).toContain(`value="${role}"`)
    }
  })

  it('identifies the selected account by display name and shows Feishu binding state', () => {
    expect(pageSource).toContain('{{ userDisplayName(roleTarget) }}')
    expect(pageSource).toContain('user?.realName || user?.username || \'-\'')
    expect(pageSource).toContain('user?.authProvider === \'SSO\' && user?.feishuBound === true')
    expect(pageSource).toContain(':disabled="!canAssign(roleTarget)"')
    expect(pageSource).toContain('飞书账号')
  })

  it('keeps both feedback roles exclusive and sends a normalized role set', () => {
    expect(pageSource).toContain('function handleRoleSelection(nextRoles: string[])')
    expect(pageSource).toContain('const FEEDBACK_ROLES')
    expect(pageSource).toContain('selectedRoles.value = [addedPhaseRole]')
    expect(pageSource).toContain('const roles = fixedRole ? [fixedRole] : selectedRoles.value')
    expect(pageSource).toContain('await assignRole(roleTarget.value.id, roles)')
  })

  it('keeps the feedback permission package fixed in the role matrix', () => {
    expect(pageSource).toContain('isFixedRole(role.roleCode)')
    expect(pageSource).toContain('反馈技术/产品角色权限包固定')
  })
})
