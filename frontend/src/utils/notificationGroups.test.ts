import{describe,expect,it}from'vitest'
import{groupNotifications,isAlertLifecycleNotification,isFeedbackWorkflowNotification,isOrphanQuickRca}from'./notificationGroups'
import type{InAppNotification}from'@/api/notification'

const row=(values:Partial<InAppNotification>):InAppNotification=>({id:1,eventType:'ALERT_FIRED',title:'告警',severity:'P1',createdAt:'2026-08-03T10:00:00Z',read:false,relatedAlertCount:1,hasVisibleAlertNotification:true,...values})

describe('notification incident grouping',()=>{
  it('groups one incident timeline and orders lifecycle events chronologically',()=>{
    const groups=groupNotifications([
      row({id:2,incidentId:9,eventType:'QUICK_RCA_READY',createdAt:'2026-08-03T10:02:00Z',relatedAlertCount:2}),
      row({id:1,incidentId:9,eventType:'ALERT_ESCALATION',createdAt:'2026-08-03T10:01:00Z',relatedAlertCount:2}),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].relatedAlertCount).toBe(2)
    expect(groups[0].items.map(x=>x.eventType)).toEqual(['ALERT_ESCALATION','QUICK_RCA_READY'])
  })

  it('keeps the source alert before quick RCA when both share one timestamp',()=>{
    const groups=groupNotifications([
      row({id:2,incidentId:9,eventType:'QUICK_RCA_READY'}),
      row({id:1,incidentId:9,eventType:'ALERT_FIRED'}),
    ])
    expect(groups[0].items.map(x=>x.eventType)).toEqual(['ALERT_FIRED','QUICK_RCA_READY'])
  })

  it('does not falsely pair an RCA whose original alert notification is unavailable',()=>{
    expect(isOrphanQuickRca(row({eventType:'QUICK_RCA_READY',hasVisibleAlertNotification:false}))).toBe(true)
  })

  it('recovers the real source alert without pretending a historical notification was delivered',()=>{
    const [group]=groupNotifications([row({eventType:'QUICK_RCA_READY',hasVisibleAlertNotification:false,sourceAlertId:31,
      sourceAlertTitle:'历史告警',sourceAlertFiredAt:'2026-08-02T10:00:00Z',sourceAlertSeverity:'P1'})])
    expect(group.recoveredAlert).toEqual(expect.objectContaining({id:31,title:'历史告警',targetPath:'/alert/center?alertId=31'}))
  })

  it('keeps feedback workflow notifications outside alert and incident groups',()=>{
    const groups=groupNotifications([
      row({id:11,eventType:'FEEDBACK_DISTRIBUTED',alertInstanceId:117,incidentId:9,title:'分发通知'}),
      row({id:12,eventType:'FEEDBACK_TRANSFERRED',alertInstanceId:116,incidentId:9,title:'转派通知'}),
    ])
    expect(groups).toHaveLength(2)
    expect(groups.every(group=>group.kind==='BUSINESS')).toBe(true)
    expect(groups.every(group=>group.incidentId===undefined&&group.relatedAlertCount===0)).toBe(true)
    expect(groups.map(group=>group.incidentTitle)).toEqual(['反馈业务通知','反馈业务通知'])
  })

  it('recognizes only alert lifecycle events as alert notifications',()=>{
    expect(isAlertLifecycleNotification(row({eventType:'ALERT_FIRED'}))).toBe(true)
    expect(isAlertLifecycleNotification(row({eventType:'FEEDBACK_REMINDER_PROGRESS'}))).toBe(false)
    expect(isFeedbackWorkflowNotification(row({eventType:'FEEDBACK_REMINDER_PROGRESS'}))).toBe(true)
    expect(groupNotifications([row({eventType:'RISK_BRIEF_PUBLISHED'})])[0].kind).toBe('SYSTEM')
  })
})
