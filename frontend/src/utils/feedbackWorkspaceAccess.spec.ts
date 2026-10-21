import { describe, expect, it, vi } from 'vitest'
import {
  FEEDBACK_WORKSPACE_PERMISSION,
  hasFeedbackWorkspaceAccess,
  isFeedbackWorkspaceOnlyUser,
} from './feedbackWorkspaceAccess'

const permitted = vi.fn((permission: string) => permission === FEEDBACK_WORKSPACE_PERMISSION)
const denied = vi.fn(() => false)

describe('feedback access policy', () => {
  it.each(['PHASE_ONE_TECHNICAL', 'PHASE_ONE_PRODUCT'] as const)(
    'treats %s as a feedback-only role',
    (role) => {
      expect(isFeedbackWorkspaceOnlyUser({ role, authProvider: 'SSO' }, denied)).toBe(true)
    },
  )

  it('requires both Feishu identity and workspace permission for non-dedicated roles', () => {
    expect(hasFeedbackWorkspaceAccess({ role: 'AUDITOR', authProvider: 'SSO' }, permitted)).toBe(true)
    expect(hasFeedbackWorkspaceAccess({ role: 'AUDITOR', authProvider: 'LOCAL' }, permitted)).toBe(false)
    expect(hasFeedbackWorkspaceAccess({ role: 'AUDITOR', authProvider: 'SSO' }, denied)).toBe(false)
  })

  it('never collapses an administrator into the feedback-only shell', () => {
    expect(isFeedbackWorkspaceOnlyUser({ role: 'ADMIN', authProvider: 'SSO' }, permitted)).toBe(false)
  })

  it('keeps an authorized non-admin Feishu account inside the feedback shell', () => {
    expect(isFeedbackWorkspaceOnlyUser({ role: 'AUDITOR', authProvider: 'SSO' }, permitted)).toBe(true)
  })
})
