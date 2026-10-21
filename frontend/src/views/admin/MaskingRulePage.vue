<template>
  <div class="page-container">
    <div class="page-header"><div><h2>脱敏策略与版本管理</h2><p>统一管理受控正则、NER 白名单和发布版本，系统内置安全门禁不可绕过。</p></div><el-tag :type="canEdit?'danger':'info'">{{ canEdit ? '管理员可提交变更' : '只读查看' }}</el-tag></div>
    <el-card shadow="never" class="pipeline-overview">
      <template #header><div class="section-heading"><div><b>完整脱敏处理链路</b><span>所有渠道数据进入业务分析和 AI 前均需通过</span></div><el-tag type="success" effect="plain">纵深防护</el-tag></div></template>
      <div class="pipeline-steps"><template v-for="(step,index) in pipelineSteps" :key="step.name"><div class="pipeline-step"><span class="step-index">{{ index+1 }}</span><div><b>{{ step.name }}</b><small>{{ step.description }}</small></div></div><span v-if="index<pipelineSteps.length-1" class="step-arrow">→</span></template></div>
      <el-alert :title="`当前共 ${rules.length} 条可版本化受控正则；上下文规则、NER 与残留扫描作为内置安全门禁单独运行。`" type="info" :closable="false" show-icon />
    </el-card>
    <el-tabs v-model="activeTab" class="masking-tabs">
      <el-tab-pane :label="`受控正则规则（${rules.length}条）`" name="rules">
        <el-card shadow="never" class="online-test">
          <template #header><b>在线脱敏测试</b><span>输入内容仅在本次请求内处理，不保存原文</span></template>
          <el-input v-model="testText" type="textarea" :rows="3" maxlength="5000" show-word-limit placeholder="输入测试文本，即时预览脱敏结果" />
          <div class="test-actions"><el-button type="primary" :loading="testing" :disabled="!testText.trim()" @click="runTest">执行测试</el-button><el-button @click="clearTest">清空</el-button></div>
          <el-descriptions v-if="testResult" :column="1" border>
            <el-descriptions-item label="结果状态"><el-tag :type="testResult.maskingStatus==='PASS'?'success':'warning'">{{ testResult.maskingStatus }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="命中类型">{{ testResult.findingTypes?.join('、') || '无' }}</el-descriptions-item>
            <el-descriptions-item label="规则版本">{{ testResult.ruleVersion }}</el-descriptions-item>
            <el-descriptions-item label="脱敏结果"><pre>{{ testResult.maskedText }}</pre></el-descriptions-item>
          </el-descriptions>
        </el-card>
        <el-card shadow="never" class="rule-catalog">
          <template #header><div class="section-heading"><div><b>可版本化正则目录</b><span>目录与运行时确定性规则保持一致；高风险规则必须启用，低风险变更生成候选版本后经评估发布</span></div><el-tag type="primary" effect="plain">{{ rules.length }} 条</el-tag></div></template>
          <el-table :data="rules" border stripe>
            <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="name" label="规则名称" width="160" />
            <el-table-column prop="type" label="类型" width="100"><template #default="{row}"><el-tag size="small">{{ row.type }}</el-tag></template></el-table-column>
            <el-table-column prop="pattern" label="匹配模式" min-width="220"><template #default="{row}"><code>{{ row.pattern }}</code></template></el-table-column>
            <el-table-column prop="replacement" label="处理方式" width="170"><template #default="{row}"><code>{{ row.replacement }}</code></template></el-table-column>
            <el-table-column prop="priority" label="优先级" width="80" /><el-table-column prop="enabled" label="状态" width="90"><template #default="{row}"><el-switch v-model="row.enabled" size="small" :disabled="!canEdit || row.critical" @change="(v:boolean)=>toggleRule(row,v)" /></template></el-table-column>
            <el-table-column label="说明" width="160"><template #default="{row}">{{ row.critical ? '高风险规则，不可停用' : '变更后生成候选版本' }}</template></el-table-column>
          </el-table>
        </el-card>
        <el-card shadow="never" class="builtin-rules">
          <template #header><div class="section-heading"><div><b>系统内置安全规则</b><span>位于正则目录之后执行，属于强制安全门禁，不提供单独停用</span></div><el-tag type="warning" effect="plain">始终启用</el-tag></div></template>
          <div class="builtin-grid"><div v-for="rule in builtinRules" :key="rule.name" class="builtin-item"><div class="builtin-icon">{{ rule.short }}</div><div><b>{{ rule.name }}</b><p>{{ rule.description }}</p><el-tag size="small" effect="plain">{{ rule.engine }}</el-tag></div></div></div>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="NER 识别与白名单" name="allowlist">
        <el-card shadow="never"><template #header><div class="page-header"><div><b>NER 白名单（精确词条）</b><p>用于保护产品名、功能名和领域词，避免被识别为个人信息。</p></div><el-button v-if="canEdit" type="primary" @click="openAllowlist">新增词条</el-button></div></template>
          <el-alert title="白名单只保护完整的产品/功能/领域词，不会放行词条周围的手机号、地址、姓名或凭证；NER 不可用或发现高风险残留时，数据将进入人工复核。" type="warning" :closable="false" show-icon />
          <el-table :data="allowlist" border><el-table-column prop="term" label="词条" /><el-table-column prop="type" label="类型" width="130"/><el-table-column prop="listVersion" label="版本" width="130"/><el-table-column prop="status" label="状态" width="100"/><el-table-column prop="reason" label="原因"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="canEdit&&row.status==='ACTIVE'" text type="danger" @click="retire(row)">退役</el-button></template></el-table-column></el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="版本历史" name="versions">
        <el-alert title="规则变更必须完成标注集评估和人工审批后才能发布；历史待处理数据由自动恢复任务按当前规则重跑。" type="info" :closable="false" show-icon />
        <el-card shadow="never"><el-table :data="versions" border><el-table-column prop="version" label="规则版本"/><el-table-column prop="allowlistVersion" label="白名单版本"/><el-table-column prop="status" label="状态"/><el-table-column prop="totalSamples" label="样本数"/><el-table-column prop="endToEndF1" label="端到端F1"/><el-table-column prop="highRiskRecall" label="高风险召回"/><el-table-column prop="createdAt" label="创建时间" width="180"><template #default="{row}">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="操作" width="180"><template #default="{row}"><el-tooltip v-if="row.status==='DRAFT'" content="需先通过可追溯标注集评估和双人审批"><el-button text disabled>等待证据评估</el-button></el-tooltip><el-button v-if="canEdit&&row.status==='EVALUATED'" text type="success" @click="publish(row)">发布</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="allowlistVisible" title="新增 NER 白名单词条" width="520px"><el-form :model="allowlistForm" label-width="90px"><el-form-item label="词条"><el-input v-model="allowlistForm.term" maxlength="100" /></el-form-item><el-form-item label="类型"><el-select v-model="allowlistForm.type"><el-option label="产品名" value="PRODUCT"/><el-option label="功能名" value="FUNCTION"/><el-option label="领域词" value="DOMAIN_TERM"/></el-select></el-form-item><el-form-item label="加入原因"><el-input v-model="allowlistForm.reason" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="allowlistVisible=false">取消</el-button><el-button type="primary" @click="saveAllowlist">保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed,onMounted,reactive,ref } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { addNerAllowlist,getMaskingRules,getMaskingVersions,getNerAllowlist,publishMaskingVersion,retireNerAllowlist,testMaskingText,updateMaskingRule } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'
const auth=useAuthStore(),canEdit=computed(()=>auth.hasPermission('access:masking_edit'))
const activeTab=ref('rules'),rules=ref<any[]>([]),allowlist=ref<any[]>([]),versions=ref<any[]>([])
const testText=ref(''),testResult=ref<any>(null),testing=ref(false)
const allowlistVisible=ref(false),allowlistForm=reactive({term:'',type:'PRODUCT',reason:''})
const pipelineSteps=[
  {name:'文本标准化',description:'统一字符并清理不可见符号'},
  {name:'受控正则',description:'版本化确定性格式规则'},
  {name:'上下文规则',description:'姓名、学校、班级和详细地址'},
  {name:'本地 NER',description:'补充识别弱结构化实体'},
  {name:'残留扫描',description:'高风险残留按兜底规则自动替换'},
]
const builtinRules=[
  {short:'名',name:'上下文姓名',description:'结合“姓名、学生、家长、老师”等上下文识别中文姓名。',engine:'上下文规则'},
  {short:'校',name:'学校与班级',description:'识别学校、学院、幼儿园及年级班级等组织信息。',engine:'上下文规则'},
  {short:'址',name:'详细地址',description:'识别路、街、巷、号、栋、单元、室等精确地址片段。',engine:'上下文规则'},
  {short:'NER',name:'弱结构化实体',description:'由本地 HanLP NER 补充识别姓名、地址等非固定格式实体。',engine:'本地 NER'},
  {short:'检',name:'高风险残留',description:'在所有规则后扫描长数字等残留，命中后使用兜底占位符自动替换。',engine:'安全门禁'},
  {short:'纹',name:'结构化身份保护',description:'手机、邮箱、设备 SN 等按用途规范化、指纹化或受控加密。',engine:'字段级保护'},
]
async function loadRules(){const[r,a,v]=await Promise.all([getMaskingRules(),getNerAllowlist(),getMaskingVersions()]);rules.value=r;allowlist.value=a;versions.value=v}
async function runTest(){testing.value=true;try{testResult.value=await testMaskingText(testText.value)}finally{testing.value=false}} function clearTest(){testText.value='';testResult.value=null}
async function toggleRule(rule:any,enabled:boolean){try{await updateMaskingRule(rule.id,{enabled});ElMessage.success('已生成候选版本，评估并发布后生效');await loadRules()}catch{rule.enabled=!enabled}}
function openAllowlist(){Object.assign(allowlistForm,{term:'',type:'PRODUCT',reason:''});allowlistVisible.value=true}
async function saveAllowlist(){if(allowlistForm.term.trim().length<2||allowlistForm.reason.trim().length<2){ElMessage.warning('请填写至少2个字符的词条和原因');return}await addNerAllowlist(allowlistForm);allowlistVisible.value=false;await loadRules()}
async function retire(row:any){const{value}=await ElMessageBox.prompt('请输入退役原因','退役白名单',{inputValidator:v=>v.trim().length>=2||'至少2个字符'});await retireNerAllowlist(row.id,{expectedVersion:row.lifecycleVersion,reason:value});await loadRules()}
async function publish(row:any){const{value}=await ElMessageBox.prompt('请输入发布审批原因','发布脱敏版本',{inputValidator:v=>v.trim().length>=2||'至少2个字符'});await publishMaskingVersion(row.version,value);await loadRules()}
function formatDateTime(value?:string){if(!value)return '-';const date=new Date(value);return Number.isNaN(date.getTime())?value:new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'medium',hour12:false}).format(date)}
onMounted(loadRules)
</script>
<style scoped>
.page-header,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.page-header h2{margin:0}.page-header p,.section-heading span{margin:6px 0 0;color:var(--text-secondary);font-size:13px;font-weight:400}.section-heading>div{display:flex;flex-direction:column}.pipeline-overview{margin:16px 0}.pipeline-overview :deep(.el-card__body){padding-bottom:12px}.pipeline-steps{display:flex;align-items:stretch;justify-content:space-between;gap:8px;margin-bottom:16px}.pipeline-step{display:flex;align-items:center;flex:1;min-width:0;gap:9px;padding:12px;border:1px solid #e4e7ed;border-radius:8px;background:#fafcff}.pipeline-step>div{display:flex;min-width:0;flex-direction:column}.pipeline-step b{font-size:13px}.pipeline-step small{margin-top:3px;color:var(--text-secondary);font-size:11px;line-height:1.4}.step-index{display:grid;flex:0 0 26px;height:26px;place-items:center;border-radius:50%;background:#409eff;color:#fff;font-size:12px;font-weight:700}.step-arrow{align-self:center;color:#a8abb2;font-size:18px}.pipeline-overview .el-alert{margin-bottom:0}.masking-tabs{background:#fff;padding:0 16px 16px;border-radius:var(--radius-card)}.online-test,.rule-catalog{margin-bottom:16px}.online-test :deep(.el-card__header){display:flex;gap:12px;align-items:center}.online-test :deep(.el-card__header)>span{color:var(--text-secondary);font-size:13px}.test-actions{display:flex;gap:10px;margin:12px 0}.online-test pre{white-space:pre-wrap;word-break:break-word;margin:0}.builtin-rules{margin-top:16px}.builtin-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.builtin-item{display:flex;gap:12px;padding:14px;border:1px solid #e8ebf0;border-radius:8px;background:#fafafa}.builtin-item>div:last-child{min-width:0}.builtin-icon{display:grid;flex:0 0 38px;height:38px;place-items:center;border-radius:10px;background:#ecf5ff;color:#409eff;font-size:12px;font-weight:700}.builtin-item b{font-size:14px}.builtin-item p{min-height:38px;margin:5px 0 9px;color:var(--text-secondary);font-size:12px;line-height:1.55}.el-alert{margin-bottom:16px}@media(max-width:1100px){.pipeline-steps{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.step-arrow{display:none}.builtin-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:680px){.page-header{align-items:flex-start}.pipeline-steps,.builtin-grid{grid-template-columns:1fr}.section-heading{align-items:flex-start}.pipeline-overview{margin-top:12px}}
</style>
