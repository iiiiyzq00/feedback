<template>
  <DataTable class="cluster-review-list" :data="items" :loading="loading" :error="error" empty-text="当前筛选条件下没有待审核任务" :pagination="pagination" @retry="$emit('retry')" @row-click="$emit('select',$event)" @page-change="$emit('page-change',$event)" @size-change="$emit('size-change',$event)">
    <el-table-column prop="sourceRecordId" label="待归簇记录" min-width="160" show-overflow-tooltip/>
    <el-table-column prop="title" label="问题摘要" min-width="140" show-overflow-tooltip/>
    <el-table-column prop="priority" label="优先级" width="96"><template #default="{row}"><el-tag :type="priorityType(row.priority)" size="small">{{priorityLabel(row.priority)}}</el-tag></template></el-table-column>
    <el-table-column label="审核时效" width="140"><template #default="{row}"><el-tag :type="row.slaEnabled ? (row.overdue?'danger':'success') : 'info'" size="small">{{slaText(row)}}</el-tag></template></el-table-column>
    <el-table-column prop="candidateCount" label="候选" width="65"/>
    <el-table-column label="建议问题簇" min-width="150" show-overflow-tooltip><template #default="{row}">{{row.candidates?.[0]?.clusterName||'-'}}</template></el-table-column>
    <el-table-column prop="decision" label="决策" width="120"><template #default="{row}"><StatusBadge :status="row.decision"/></template></el-table-column>
    <el-table-column prop="similarity" label="最高相似度" width="100"><template #default="{row}">{{Math.round(row.similarity*100)}}%</template></el-table-column>
    <el-table-column label="操作" width="104" align="center" header-align="center" class-name="cell-action"><template #default="{row}"><div class="compare-action"><el-button text type="primary" @click.stop="$emit('select',row)">查看对比</el-button></div></template></el-table-column>
  </DataTable>
</template>
<script setup lang="ts">
import DataTable from '@/components/common/DataTable.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import type { ClusterReviewItem, ClusterReviewPriority } from '@/api/cluster';
defineProps<{items:ClusterReviewItem[];loading:boolean;error?:string;pagination:{page:number;size:number;total:number}}>();
defineEmits<{retry:[];select:[ClusterReviewItem];'page-change':[number];'size-change':[number]}>();
function priorityType(p:ClusterReviewPriority){return p==='P1'?'danger':p==='P2'?'warning':'info'}
function priorityLabel(p:ClusterReviewPriority){return p==='P1'?'P1 紧急':p==='P2'?'P2 较高':'P3 普通'}
function slaText(row:ClusterReviewItem){if(!row.slaEnabled)return'开发阶段不计时';const s=Math.abs(row.slaRemainingSeconds||0),t=s>=86400?`${Math.ceil(s/86400)}天`:s>=3600?`${Math.ceil(s/3600)}小时`:`${Math.ceil(s/60)}分钟`;return row.overdue?`超时 ${t}`:`剩余 ${t}`}
</script>
<style scoped>
.compare-action{display:flex;width:100%;align-items:center;justify-content:center}
.compare-action :deep(.el-button){margin:0;padding-inline:8px}
.cluster-review-list :deep(.table-scroll){overflow-x:hidden}
.cluster-review-list :deep(.el-table){min-width:0;width:100%!important}
</style>
