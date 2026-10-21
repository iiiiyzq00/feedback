<template>
  <div class="page-container">
    <div class="page-header">
      <div><h2>站内通知</h2><p>告警按 Incident 汇总，反馈分发、转派和催办等业务通知独立展示</p></div>
      <div class="actions">
        <el-switch v-model="unreadOnly" active-text="仅看未读" @change="reload" />
        <el-button :disabled="!unreadCount" @click="readAll">全部已读</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert title="快速 RCA 按 Incident 生成，一个 RCA 可能关联多个告警，不代表与单条告警一一对应。" type="info" :closable="false" show-icon />
    <div v-if="groups.length" class="incident-list">
      <el-card v-for="group in groups" :key="group.key" shadow="never" class="incident-card">
        <template #header>
          <div class="incident-header">
            <div>
              <b v-if="group.kind==='ALERT'">{{ group.incidentId ? `Incident #${group.incidentId}` : '未关联 Incident' }} · {{ group.incidentTitle }}</b>
              <b v-else>{{ group.kind==='BUSINESS'?'反馈业务通知':'系统通知' }}</b>
              <small v-if="group.kind==='ALERT'">{{ group.relatedAlertCount ? `关联 ${group.relatedAlertCount} 个告警` : '暂无关联告警' }}</small>
              <small v-else-if="group.kind==='BUSINESS'">分发、转派、接收提醒与处理催办</small>
              <small v-else>平台报告与其他系统消息</small>
            </div>
            <el-tag v-if="group.incidentStatus" size="small" effect="plain">{{ incidentStatusLabel(group.incidentStatus) }}</el-tag>
          </div>
        </template>
        <el-timeline>
          <el-timeline-item v-if="group.recoveredAlert" :timestamp="formatTime(group.recoveredAlert.firedAt)" placement="top" type="danger">
            <button class="notification-item recovered-alert" @click="router.push(group.recoveredAlert.targetPath)">
              <span class="unread-dot" aria-hidden="true" />
              <span class="copy">
                <span class="title-row"><el-tag :type="levelType(group.recoveredAlert.severity)" size="small">{{group.recoveredAlert.severity}}</el-tag><el-tag size="small" effect="plain" type="danger">告警事实</el-tag><b>{{group.recoveredAlert.title}}</b></span>
                <span class="summary">告警实体真实存在；该历史数据的原渠道通知记录已缺失，系统根据告警与 Incident 关联恢复本节点。</span>
                <small>告警实例 #{{group.recoveredAlert.id}} · {{alertStatusLabel(group.recoveredAlert.status)}}</small>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </el-timeline-item>
          <el-timeline-item v-for="item in group.items" :key="item.id" :timestamp="formatTime(item.createdAt)" placement="top" :type="timelineType(item.eventType)">
            <button class="notification-item" :class="{unread:!item.read}" @click="open(item)">
              <span class="unread-dot" aria-hidden="true" />
              <span class="copy">
                <span class="title-row">
                  <el-tag :type="levelType(item.severity)" size="small">{{ item.severity }}</el-tag>
                  <el-tag size="small" effect="plain">{{ eventLabel(item.eventType) }}</el-tag>
                  <b>{{ item.title }}</b>
                </span>
                <span class="summary">{{ item.summary||'暂无摘要' }}</span>
                <el-alert v-if="isOrphanQuickRca(item)" class="orphan-hint" type="warning" :closable="false" show-icon title="原渠道告警通知记录已缺失；上方告警事实来自真实告警实体，并非补造通知。" />
                <small>
                  <template v-if="item.eventType==='QUICK_RCA_READY'">该 RCA 关联 {{ item.relatedAlertCount||1 }} 个告警</template>
                  <template v-else-if="group.kind==='ALERT'&&item.alertInstanceId">告警实例 #{{ item.alertInstanceId }}</template>
                  <template v-if="item.ruleCode"> · {{ item.ruleCode }}</template>
                </small>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </el-timeline-item>
        </el-timeline>
      </el-card>
    </div>
    <el-empty v-else :description="unreadOnly?'暂无未读通知':'暂无站内通知'" />
    <el-pagination v-if="total>size" background layout="prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="changePage" />
  </div>
</template>

<script setup lang="ts">
import{computed,onMounted,ref}from'vue'
import{useRouter}from'vue-router'
import{ArrowRight,Refresh}from'@element-plus/icons-vue'
import{getInAppNotifications,markAllInAppNotificationsRead,markInAppNotificationRead,type InAppNotification}from'@/api/notification'
import{groupNotifications,isOrphanQuickRca}from'@/utils/notificationGroups'

const router=useRouter(),loading=ref(false),records=ref<InAppNotification[]>([]),total=ref(0),unreadCount=ref(0),unreadOnly=ref(false),page=ref(1),size=20
const groups=computed(()=>groupNotifications(records.value))
async function load(){loading.value=true;try{const data=await getInAppNotifications({page:page.value,size,unreadOnly:unreadOnly.value});records.value=data.records||[];total.value=data.total||0;unreadCount.value=data.unreadCount||0;window.dispatchEvent(new CustomEvent('in-app-notifications-updated',{detail:{count:unreadCount.value}}))}finally{loading.value=false}}
function reload(){page.value=1;void load()}
function changePage(value:number){page.value=value;void load()}
async function open(item:InAppNotification){if(!item.read){await markInAppNotificationRead(item.id);item.read=true;unreadCount.value=Math.max(0,unreadCount.value-1);window.dispatchEvent(new CustomEvent('in-app-notifications-updated',{detail:{count:unreadCount.value}}))}if(item.targetPath)await router.push(item.targetPath);else await load()}
async function readAll(){await markAllInAppNotificationsRead();await load()}
function levelType(value:string){return value==='P0'?'danger':value==='P1'?'warning':value==='P2'?'primary':'info'}
function timelineType(value:string){return value==='QUICK_RCA_READY'?'success':value==='ALERT_ESCALATION'?'warning':'primary'}
function eventLabel(value:string){return({ALERT_FIRED:'告警触发',ALERT_ESCALATION:'告警升级',QUICK_RCA_READY:'快速 RCA',RISK_BRIEF_PUBLISHED:'风险简报',FEEDBACK_DISTRIBUTED:'反馈分发',FEEDBACK_TRANSFERRED:'反馈转派',FEEDBACK_REMINDER_ACCEPTANCE:'接收提醒',FEEDBACK_REMINDER_PROGRESS:'处理催办'}as Record<string,string>)[value]||'业务通知'}
function incidentStatusLabel(value:string){return({OPEN:'处理中',MITIGATING:'缓解中',RESOLVED:'已解决',CLOSED:'已关闭'}as Record<string,string>)[value]||value}
function alertStatusLabel(value?:string|null){return({FIRING:'告警中',ACKNOWLEDGED:'已确认',RESOLVED:'已解决',ARCHIVED:'已归档'}as Record<string,string>)[value||'']||value||'状态未知'}
function formatTime(value:any){return value?new Date(value).toLocaleString('zh-CN'):'-'}
onMounted(load)
</script>

<style scoped>
.page-container{padding:20px}.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.page-header h2{margin:0 0 5px}.page-header p{margin:0;color:var(--text-secondary)}.actions{display:flex;align-items:center;gap:10px}.incident-list{display:grid;gap:14px;margin-top:14px}.incident-card{border-radius:10px}.incident-header{display:flex;align-items:center;justify-content:space-between;gap:12px}.incident-header>div{display:grid;gap:5px}.incident-header small,.copy small{color:var(--text-secondary)}.incident-card :deep(.el-timeline){padding:4px 0 0 4px}.incident-card :deep(.el-timeline-item:last-child){padding-bottom:0}.notification-item{appearance:none;width:100%;display:grid;grid-template-columns:10px 1fr 24px;align-items:center;gap:10px;padding:13px;border:1px solid #ebeef5;border-radius:8px;background:#fff;text-align:left;cursor:pointer}.notification-item:hover{border-color:#a0cfff;background:#f7faff}.notification-item.unread{background:#f0f7ff}.unread-dot{width:7px;height:7px;border-radius:50%;background:transparent}.unread .unread-dot{background:#409eff}.copy{display:grid;gap:7px}.title-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.summary{color:#606266;line-height:1.55}.orphan-hint{margin:2px 0}.el-pagination{justify-content:flex-end;margin-top:16px}@media(max-width:700px){.page-header{align-items:flex-start;flex-direction:column;gap:12px}.actions{flex-wrap:wrap}.incident-header{align-items:flex-start;flex-direction:column}.notification-item{grid-template-columns:8px 1fr 20px}}
</style>
