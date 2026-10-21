import { download, get, post, put } from './request'

export type ProductFeedbackInput={requestType:string;title:string;description:string;sourceChannel?:string;feedbackSourceId?:number;pagePath:string;pageContext?:Record<string,unknown>;attachments?:Array<{name:string;size:number;type:string}>}
export function createProductFeedback(input:ProductFeedbackInput){return post('/product-feedback',input)}
export function getProductFeedback(params?:Record<string,unknown>){return get('/product-feedback',{params})}
export function updateProductFeedback(id:number,input:{status:string;reason:string;handlerUserId?:number;priorityCode?:string;plannedVersion?:string;resolutionNote?:string}){return put(`/product-feedback/${id}`,input)}
export function getProductFeedbackDetail(id:number){return get(`/product-feedback/${id}`)}
export function commentProductFeedback(id:number,comment:string){return post(`/product-feedback/${id}/comments`,{comment})}
export function exportProductFeedback(){return download('/product-feedback/export')}
