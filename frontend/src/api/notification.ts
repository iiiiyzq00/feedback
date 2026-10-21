import { get, post } from './request'

export interface InAppNotification {
  id:number
  alertInstanceId?:number|null
  reportId?:number|null
  rcaId?:number|null
  feedbackId?:number|null
  incidentId?:number|null
  incidentTitle?:string|null
  incidentStatus?:string|null
  relatedAlertCount:number
  hasVisibleAlertNotification:boolean
  sourceAlertId?:number|null
  sourceAlertFiredAt?:string|null
  sourceAlertStatus?:string|null
  sourceAlertSeverity?:string|null
  sourceAlertTitle?:string|null
  ruleCode?:string|null
  eventType:string
  title:string
  summary?:string|null
  severity:string
  createdAt:string
  deliveredAt?:string|null
  readAt?:string|null
  read:boolean
  targetPath?:string|null
}

export function getInAppNotifications(params?:{page?:number;size?:number;unreadOnly?:boolean}){return get('/in-app-notifications',{params})}
export function getInAppUnreadCount(){return get('/in-app-notifications/unread-count')}
export function markInAppNotificationRead(id:number){return post(`/in-app-notifications/${id}/read`)}
export function markAllInAppNotificationsRead(){return post('/in-app-notifications/read-all')}
