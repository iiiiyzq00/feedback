import type { InAppNotification } from '@/api/notification'

export interface NotificationGroup {
  key:string
  kind:'ALERT'|'BUSINESS'|'SYSTEM'
  incidentId?:number
  incidentTitle:string
  incidentStatus?:string|null
  relatedAlertCount:number
  latestAt:string
  items:InAppNotification[]
  recoveredAlert?:{
    id:number
    title:string
    firedAt?:string|null
    status?:string|null
    severity:string
    targetPath:string
  }
}

export function groupNotifications(records:InAppNotification[]):NotificationGroup[]{
  const grouped=new Map<string,NotificationGroup>()
  for(const item of records){
    const alertLifecycle=isAlertLifecycleNotification(item)
    const feedbackWorkflow=isFeedbackWorkflowNotification(item)
    const key=alertLifecycle
      ?item.incidentId?`incident-${item.incidentId}`:item.alertInstanceId?`alert-${item.alertInstanceId}`:`notification-${item.id}`
      :`${feedbackWorkflow?'business':'system'}-${item.id}`
    const existing=grouped.get(key)
    if(existing){
      existing.items.push(item)
      if(new Date(item.createdAt).getTime()>new Date(existing.latestAt).getTime())existing.latestAt=item.createdAt
      existing.relatedAlertCount=Math.max(existing.relatedAlertCount,Number(item.relatedAlertCount)||0)
    }else grouped.set(key,{
      key,kind:alertLifecycle?'ALERT':feedbackWorkflow?'BUSINESS':'SYSTEM',incidentId:alertLifecycle?(item.incidentId??undefined):undefined,
      incidentTitle:alertLifecycle?(item.incidentTitle||(`告警 #${item.alertInstanceId||item.id}`)):feedbackWorkflow?'反馈业务通知':'系统通知',
      incidentStatus:alertLifecycle?item.incidentStatus:undefined,
      relatedAlertCount:alertLifecycle?(Number(item.relatedAlertCount)||Number(Boolean(item.alertInstanceId))):0,
      latestAt:item.createdAt,items:[item],
    })
  }
  return [...grouped.values()].map(group=>{
    const source=group.items.find(item=>isOrphanQuickRca(item)&&item.sourceAlertId)
    return {...group,
      recoveredAlert:source?.sourceAlertId?{id:source.sourceAlertId,title:source.sourceAlertTitle||`告警 #${source.sourceAlertId}`,
        firedAt:source.sourceAlertFiredAt,status:source.sourceAlertStatus,severity:source.sourceAlertSeverity||source.severity,
        targetPath:`/alert/center?alertId=${source.sourceAlertId}`}:undefined,
      items:group.items.sort(compareTimelineItems)}
  })
    .sort((a,b)=>new Date(b.latestAt).getTime()-new Date(a.latestAt).getTime())
}

const alertLifecycleEvents=new Set(['ALERT_FIRED','ALERT_ESCALATION','QUICK_RCA_READY'])
const feedbackWorkflowEvents=new Set(['FEEDBACK_DISTRIBUTED','FEEDBACK_TRANSFERRED','FEEDBACK_REMINDER_ACCEPTANCE','FEEDBACK_REMINDER_PROGRESS'])

export function isAlertLifecycleNotification(item:InAppNotification){
  return alertLifecycleEvents.has(item.eventType)
}

export function isFeedbackWorkflowNotification(item:InAppNotification){
  return feedbackWorkflowEvents.has(item.eventType)
}

const lifecycleOrder:Record<string,number>={ALERT_FIRED:0,ALERT_ESCALATION:1,QUICK_RCA_READY:2,RISK_BRIEF_PUBLISHED:3}

function compareTimelineItems(a:InAppNotification,b:InAppNotification){
  const timeDiff=new Date(a.createdAt).getTime()-new Date(b.createdAt).getTime()
  if(timeDiff!==0)return timeDiff
  const lifecycleDiff=(lifecycleOrder[a.eventType]??99)-(lifecycleOrder[b.eventType]??99)
  return lifecycleDiff!==0?lifecycleDiff:a.id-b.id
}

export function isOrphanQuickRca(item:InAppNotification){
  return item.eventType==='QUICK_RCA_READY'&&!item.hasVisibleAlertNotification
}
