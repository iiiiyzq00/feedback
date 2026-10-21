import { get, post } from './request'
export function getChannelGovernanceOverview(){return get('/channel-governance/overview')}
export function submitChannelAcceptance(channel:string,status:string,comment:string){return post(`/channel-governance/${channel}/acceptance`,{status,comment})}
export function getChannelViewConfig(channel:string){return get(`/channel-governance/${channel}/view-config`)}
export function saveChannelViewDraft(channel:string,fields:Array<Record<string,unknown>>,reason:string){return post(`/channel-governance/${channel}/view-config/drafts`,{fields,reason})}
export function publishChannelViewConfig(channel:string,version:number){return post(`/channel-governance/${channel}/view-config/${version}/publish`,{})}
export function getChannelViewHistory(channel:string){return get(`/channel-governance/${channel}/view-config/history`)}
