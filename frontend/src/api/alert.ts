import { get, post } from './request'

export interface AlertQuery {
  level?: string
  status?: string
  page?: number
  size?: number
  alertId?: number
  clusterId?: number
}

export function getAlertList(params: AlertQuery) {
  return get('/alerts', { params })
}

export function getAlertDetail(id: number) {
  return get(`/alerts/${id}`)
}
export function getAlertEvidence(id:number){return get(`/alerts/${id}/evidence`)}

export function getAlertStatistics() {
  return get('/alerts/statistics')
}
export function getP3Digest(){ return get('/alerts/p3-digest') }
export function getAlertAssignees(){ return get('/alerts/assignees') }
export function transferAlert(id:number,data:{assigneeId:number;reason:string;expectedIncidentVersion:number}){return post(`/alerts/${id}/transfer`,data)}
export function silenceAlert(id:number,data:{minutes:number;reason:string;expectedVersion:number}){return post(`/alerts/${id}/silence`,data)}

export function ackAlert(id: number, expectedVersion: number) {
  return post(`/alerts/${id}/ack`, { expectedVersion })
}

export function resolveAlert(id: number, data: { resolution: string; expectedVersion: number }) {
  return post(`/alerts/${id}/resolve`, data)
}

export function getIncidentList(params?: { page?: number; size?: number }) {
  return get('/alerts/incidents', { params })
}

export function getIncidentDetail(id: number) {
  return get(`/alerts/incidents/${id}`)
}
