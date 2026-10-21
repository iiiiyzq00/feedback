import { download, get, post, put, del } from './request'

// 用户管理
export function getUserList(params?: { page?: number; size?: number; keyword?: string }) {
  return get('/admin/users', { params })
}

export function getRolePermissionMatrix() { return get('/admin/roles/permissions') }
export function updateRolePermissions(roleCode: string, permissions: string[]) {
  return put(`/admin/roles/${roleCode}/permissions`, { permissions })
}

export function createUser(data: any) {
  return post('/admin/users', data)
}

export function updateUser(id: number, data: any) {
  return put(`/admin/users/${id}`, data)
}

export function disableUser(id: number) {
  return put(`/admin/users/${id}/disable`, {})
}

export function assignRole(id: number, roles: string[]) {
  return put(`/admin/users/${id}/roles`, { roles })
}

export function updateDataPermissions(id: number, scopes: Array<{ type: string; value: string; accessLevel?: string; effect?: string }>) {
  return put(`/admin/users/${id}/data-permissions`, { scopes })
}

export type FeishuRoleMappingInput = {
  ruleType: 'USER' | 'USER_GROUP' | 'DEPARTMENT' | 'DEFAULT'
  subjectId: string
  subjectName: string
  grantKind: 'ROLE' | 'DATA_SCOPE'
  roleCode?: string
  scopeType?: 'CHANNEL' | 'MODULE' | 'REGION'
  scopeValue?: string
  priority: number
  description?: string
  expectedVersion?: number
}
export function getFeishuRoleMappings(){return get('/admin/feishu-role-mappings')}
export function createFeishuRoleMapping(data:FeishuRoleMappingInput){return post('/admin/feishu-role-mappings',data)}
export function updateFeishuRoleMapping(id:number,data:FeishuRoleMappingInput){return put(`/admin/feishu-role-mappings/${id}`,data)}
export function decideFeishuRoleMapping(id:number,status:'APPROVED'|'REJECTED'|'RETIRED',reason:string,expectedVersion:number){return post(`/admin/feishu-role-mappings/${id}/decision`,{status,reason,expectedVersion})}
export function getFeishuPolicySyncStatus(){return get('/admin/feishu-role-mappings/sync-status')}
export function previewFeishuPolicy(data:{openId?:string;groupIds:string[];departmentIds:string[]}){return post('/admin/feishu-role-mappings/preview',data)}
export function syncFeishuPolicies(){return post('/admin/feishu-role-mappings/sync',{})}

// 告警规则
export function getAlertRules() {
  return get('/admin/alert-rules')
}
export function getAlertGovernanceCatalog() { return get('/admin/alert-rules/governance-catalog') }
export function getAlertLoopHealth(){return get('/admin/alert-rules/loop-health',{silent:true} as any)}
export function getAlertRuleReadiness(id:number){return get(`/admin/alert-rules/${id}/activation-gaps`)}
export function publishAlertRule(id:number,data:{reason:string;enable:boolean}){return post(`/admin/alert-rules/${id}/publish`,data)}
export function getAlertContracts(){return get('/admin/alert-rules/contracts')}
export function validateAlertContract(code:string,data:any){return post(`/admin/alert-rules/contracts/${encodeURIComponent(code)}/validate`,data)}
export function approveAlertContract(code:string){return post(`/admin/alert-rules/contracts/${encodeURIComponent(code)}/approve`,{})}
export function getAlertProviders(){return get('/admin/alert-rules/providers')}
export function validateAlertProvider(data:any){return post('/admin/alert-rules/providers/validate',data)}
export function approveAlertProvider(id:number){return post(`/admin/alert-rules/providers/${id}/approve`,{})}
export function markAlertRuleImplemented(code:string,replayEvidenceSha256:string){return post(`/admin/alert-rules/governance-catalog/${encodeURIComponent(code)}/implemented`,{replayEvidenceSha256})}
export function getAlertAcceptance(ruleId:number){return get('/admin/alert-rules/acceptance',{params:{ruleId}})}
export function validateAlertAcceptance(data:any){return post('/admin/alert-rules/acceptance/validate',data)}
export function approveAlertAcceptance(id:number){return post(`/admin/alert-rules/acceptance/${id}/approve`,{})}

export function createAlertRule(data: any) {
  return post('/admin/alert-rules', data)
}

export function updateAlertRule(id: number, data: any) {
  return put(`/admin/alert-rules/${id}`, data)
}

export function toggleAlertRule(id: number, enabled: boolean) {
  return put(`/admin/alert-rules/${id}/toggle`, { enabled })
}
export function getAlertSilences() { return get('/admin/alert-rules/silences') }
export function createAlertSilence(data: any) { return post('/admin/alert-rules/silences', data) }
export function deleteAlertSilence(id: number) { return del(`/admin/alert-rules/silences/${id}`) }
export function reorderAlertRules(ruleIds:number[]){return put('/admin/alert-rules/reorder',{ruleIds})}
export function testAlertRule(rule:any,metricValues:Record<string,number>){const {name,level,metric,threshold,window,channels,escalation,cooldownMinutes,recoveryThreshold,conditions,combinator,ownerTeam,recipients,effectiveDays,effectiveStart,effectiveEnd,timezone}=rule;return post('/admin/alert-rules/test',{rule:{name,level,metric,threshold,window,channels,escalation,cooldownMinutes,recoveryThreshold,conditions,combinator,ownerTeam,recipients,effectiveDays,effectiveStart,effectiveEnd,timezone},ruleId:rule.id,mode:'SIMULATION',metricValues})}
export function getAlertRuleTestRuns(limit=50){return get('/admin/alert-rules/test-runs',{params:{limit}})}
export function replayAlertRule(data:any){return post('/admin/alert-rules/test-runs/replay',data)}
export function shadowAlertRule(ruleId:number){return post('/admin/alert-rules/test-runs/shadow',{ruleId})}
export function drillAlertRule(data:any){return post('/admin/alert-rules/test-runs/drill',data)}
export function getAlertDrills(limit=50){return get('/admin/alert-rules/test-runs/drills',{params:{limit}})}
export function acknowledgeAlertDrill(id:number,reason:string){return post(`/admin/alert-rules/test-runs/drills/${id}/acknowledge`,{reason})}
export function resolveAlertDrill(id:number,reason:string){return post(`/admin/alert-rules/test-runs/drills/${id}/resolve`,{reason})}
export function getIdempotencyConflicts(status?:string){return get('/admin/alert-rules/idempotency-conflicts',{params:{status}})}
export function resolveIdempotencyConflict(id:number,data:any){return post(`/admin/alert-rules/idempotency-conflicts/${id}/resolve`,data)}
export function getIncorrectDedupEvents(){return get('/admin/alert-rules/incorrect-dedup')}
export function confirmIncorrectDedup(data:any){return post('/admin/alert-rules/incorrect-dedup',data)}
export function repairIncorrectDedup(id:number,data:any){return post(`/admin/alert-rules/incorrect-dedup/${id}/repair`,data)}
export function getSecuritySignals(){return get('/admin/alert-rules/security-signals')}
export function reportSecuritySignal(data:any){return post('/admin/alert-rules/security-signals',data)}
export function concludeSecuritySignal(id:number,data:any){return post(`/admin/alert-rules/security-signals/${id}/conclude`,data)}
export function getVersionReleaseFacts(){return get('/admin/alert-rules/version-releases')}
export function saveVersionReleaseFact(data:any){return post('/admin/alert-rules/version-releases',data)}
export function getOncallTeams(){return get('/admin/alert-rules/oncall/teams')}
export function getOncallMembers(team?:string){return get('/admin/alert-rules/oncall/members',{params:{team}})}
export function getOncallEligibleUsers(){return get('/admin/alert-rules/oncall/eligible-users')}
export function saveOncallMember(data:any){return post('/admin/alert-rules/oncall/members',data)}
export function removeOncallMember(id:number){return del(`/admin/alert-rules/oncall/members/${id}`)}
export function getOncallCalendar(params:any){return get('/admin/alert-rules/oncall/calendar',{params})}
export function createOncall(data:any){return post('/admin/alert-rules/oncall',data)}
export function cancelOncall(id:number,version:number){return del(`/admin/alert-rules/oncall/${id}?version=${version}`)}

// 脱敏规则
export function getMaskingRules() {
  return get('/admin/masking-rules')
}
export function testMaskingText(text:string){return post('/admin/masking-rules/test',{text})}

export function updateMaskingRule(id: number, data: any) {
  return put(`/admin/masking-rules/${id}`, data)
}
export function getNerAllowlist(){return get('/admin/masking-rules/ner-allowlist')}
export function addNerAllowlist(data:any){return post('/admin/masking-rules/ner-allowlist',data)}
export function retireNerAllowlist(id:number,data:any){return post(`/admin/masking-rules/ner-allowlist/${id}/retire`,data)}
export function getMaskingVersions(){return get('/admin/masking-rules/versions')}
export function evaluateMaskingVersion(version:string,data:any){return post(`/admin/masking-rules/versions/${version}/evaluation`,data)}
export function publishMaskingVersion(version:string,reason:string){return post(`/admin/masking-rules/versions/${version}/publish`,{reason})}

// 已迁移到单表 MyBatis 版本库的动态配置治理。
export function getSystemConfigs() {
  return get('/admin/configs')
}
export function updateSystemConfigBatch(data:any){return post('/admin/configs/change-set',data)}
export function getSystemConfigHistory(key:string,params?:any){return get(`/admin/configs/${key}/history`,{params})}
export function rollbackSystemConfig(key:string,data:any){return post(`/admin/configs/${key}/rollback`,data)}

// 第 4 天的生产级聚类评估闸门
export function getLatestClusterEvaluation() {
  return get('/admin/cluster-evaluation/latest')
}

export function submitClusterEvaluation(data: any) {
  return post('/admin/cluster-evaluation', data)
}
export function exportLatestClusterEvaluation() {
  return download('/admin/cluster-evaluation/latest/export')
}
