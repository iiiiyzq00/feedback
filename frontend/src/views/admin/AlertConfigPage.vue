<template>
  <div class="page-container">
    <div class="page-header">
      <h2>告警规则配置</h2>
      <el-tag type="info">正式规则以当前治理基线目录为准</el-tag>
    </div>
    <el-alert title="生产规则必须通过数据契约、历史回放和供应商验收后才能激活；开发测试请使用测试中心，不会发送生产通知。" type="warning" :closable="false" style="margin-bottom:12px" />
    <el-tabs v-model="activeSection" class="config-tabs">
      <el-tab-pane label="规则治理与运行" name="rules" />
      <el-tab-pane label="规则测试中心" name="testing" />
      <el-tab-pane label="值班排班" name="oncall" />
      <el-tab-pane label="静默策略" name="silences" />
    </el-tabs>

    <el-card v-show="activeSection==='rules'" shadow="never">
      <el-alert v-if="loopHealth" :title="`预警闭环：${loopHealth.status === 'HEALTHY' ? '健康' : loopHealth.status === 'DEGRADED' ? '降级' : '未就绪'}`"
        :type="loopHealth.status === 'HEALTHY' ? 'success' : loopHealth.status === 'DEGRADED' ? 'warning' : 'error'" :closable="false"
        :description="`覆盖 ${loopHealth.categories?.filter((x:any)=>x.covered).length || 0}/${loopHealth.categories?.length || 0} 类；开放告警 ${loopHealth.runtime?.openAlerts || 0}；待处理通知 ${loopHealth.runtime?.pendingNotifications || 0}；死信 ${loopHealth.runtime?.deadLetters || 0}`" style="margin-bottom:12px" />
      <el-alert :title="`正式规则治理目录：${catalog.summary?.total || 0}/${catalog.summary?.expected || 0} 条`"
        :type="catalog.summary?.consistent ? 'success' : 'error'" :closable="false"
        :description="`已实现 ${catalog.summary?.implemented || 0} · 待数据契约 ${catalog.summary?.contractRequired || 0} · 外部系统 ${catalog.summary?.external || 0}。${catalog.activationPolicy || ''}`" />
      <el-alert v-if="loadErrors.length" :title="loadErrors.join('；')" type="error" :closable="false" style="margin-top:12px" />
      <el-collapse style="margin:12px 0"><el-collapse-item :title="`查看全部 ${catalog.summary?.expected || catalog.rules.length || 0} 条治理目录`">
        <el-table :data="catalog.rules" max-height="420"><el-table-column prop="ruleCode" label="规则编码" min-width="210"/><el-table-column prop="ruleName" label="名称" min-width="180"/><el-table-column prop="category" label="分类" width="100"/><el-table-column prop="defaultSeverity" label="级别" width="70"/><el-table-column prop="ownerTeam" label="责任团队" min-width="140"/><el-table-column prop="implementationStatus" label="实现状态" width="150"/><el-table-column prop="governanceStatus" label="治理状态" width="110"/><el-table-column prop="preventionClass" label="预防类别" width="90"/></el-table>
      </el-collapse-item></el-collapse>
      <div class="section-heading"><div><h3>业务告警运行版本</h3><p>这里只展示治理目录中已经创建执行版本的规则，不代表{{ catalog.total || catalog.rules.length }}条目录规则都已上线；拖拽规则卡片可调整优先级。</p></div><el-button type="primary" @click="openRuleDialog(null)">新建规则草稿</el-button></div>
      <el-alert title="这里只展示用户诉求、安全、产品趋势、数据接入、脱敏和数据质量规则；平台运行监控在下方独立展示。" type="info" :closable="false" style="margin-bottom:8px" />
      <div v-for="rule in businessRules" :key="rule.id" class="rule-item" draggable="true" @dragstart="dragRuleId=rule.id" @dragover.prevent @drop="dropRule(rule.id)">
        <div class="rule-main">
          <div class="rule-info">
            <span class="rule-name">{{ rule.name }}</span>
            <div class="rule-tags">
              <el-tag :type="lifecycleTag(rule).type" effect="plain" size="small">{{ lifecycleTag(rule).label }}</el-tag>
              <el-tag :type="rule.level === 'P0' ? 'danger' : rule.level === 'P1' ? 'warning' : 'info'" effect="plain" size="small">{{ rule.level }}</el-tag>
            </div>
            <span class="rule-condition">{{ rule.conditionDesc }}</span>
          </div>
          <div class="rule-meta">
            <span>最后触发: {{ rule.lastTriggeredAt || '从未' }}</span>
            <el-tooltip :content="switchHint(rule)" placement="top"><span><el-switch
              v-model="rule.enabled"
              :disabled="rule.versionStatus==='SHADOW' || (!rule.enabled && !isProductionReady(rule))"
              @change="(v: boolean) => toggleRule(rule, v)"
            /></span></el-tooltip>
          </div>
        </div>
        <div class="rule-actions">
          <el-tooltip :disabled="!isEventRule(rule)" content="事件型治理规则由事件契约定义，不适用阈值/窗口编辑器"><span><el-button size="small" text type="primary" :disabled="rule.versionStatus==='SHADOW'||isEventRule(rule)" @click="openRuleDialog(rule)">编辑</el-button></span></el-tooltip>
          <el-button size="small" text @click="testExistingRule(rule)">测试</el-button>
          <el-button size="small" text type="warning" @click="openReadiness(rule)">准入详情</el-button>
        </div>
      </div>
      <el-empty v-if="!businessRules.length" description="暂无可运行的业务告警规则" :image-size="80" />
      <el-collapse v-if="systemRules.length" style="margin-top:16px"><el-collapse-item title="平台运行监控（由 Prometheus / Alertmanager 治理，不计入业务告警目录）">
        <el-table :data="systemRules"><el-table-column prop="name" label="监控项" min-width="180"/><el-table-column prop="level" label="级别" width="80"/><el-table-column prop="conditionDesc" label="触发条件" min-width="260"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column></el-table>
      </el-collapse-item></el-collapse>
    </el-card>

    <!-- 规则编辑对话框 -->
    <el-dialog v-model="ruleDialogVisible" :title="editingRule ? '编辑规则' : '新建规则'" width="640px">
      <el-form :model="ruleForm" label-width="100px">
        <el-form-item label="规则名称">
          <el-input v-model="ruleForm.name" />
        </el-form-item>
        <el-form-item label="告警级别">
          <el-select v-model="ruleForm.level">
            <el-option label="P0-紧急" value="P0" />
            <el-option label="P1-严重" value="P1" />
            <el-option label="P2-一般" value="P2" />
            <el-option label="P3-日报" value="P3" />
          </el-select>
        </el-form-item>
        <el-form-item label="主责团队"><el-input v-model="ruleForm.ownerTeam" placeholder="如：数据平台" /></el-form-item>
        <el-form-item label="指标">
          <el-select v-model="ruleForm.metric">
            <el-option label="反馈窗口总量（旧版，仅兼容）" value="FEEDBACK_SPIKE" disabled />
            <el-option label="错误率过高" value="ERROR_RATE_HIGH" />
            <el-option label="S0/S1占比" value="CRITICAL_RATIO" />
            <el-option label="数据中断" value="DATA_INTERRUPTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input-number v-model="ruleForm.threshold" :min="1" />
        </el-form-item>
        <el-form-item label="时间窗口">
          <el-select v-model="ruleForm.window">
            <el-option label="5分钟" value="5m" />
            <el-option label="15分钟" value="15m" />
            <el-option label="1小时" value="1h" />
            <el-option label="24小时" value="24h" />
          </el-select>
        </el-form-item>
        <el-form-item label="恢复阈值"><el-input-number v-model="ruleForm.recoveryThreshold" :min="0" :max="ruleForm.threshold" /></el-form-item>
        <el-form-item label="冷却（分钟）"><el-input-number v-model="ruleForm.cooldownMinutes" :min="0" :max="10080" /></el-form-item>
        <el-form-item label="通知渠道">
          <el-checkbox-group v-model="ruleForm.channels">
            <el-checkbox value="feishu_card">飞书卡片</el-checkbox>
            <el-checkbox value="feishu_all">飞书群@all</el-checkbox>
            <el-checkbox value="feishu_dm">飞书私信</el-checkbox>
            <el-checkbox value="feishu_task">飞书任务</el-checkbox>
            <el-checkbox value="email">邮件</el-checkbox>
            <el-checkbox value="sms">短信</el-checkbox>
            <el-checkbox value="voice_call">电话语音</el-checkbox>
            <el-checkbox value="inapp">平台内</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="升级策略">
          <el-select v-model="ruleForm.escalation">
            <el-option label="不升级" value="NONE" />
            <el-option label="30分钟→P1" value="30M_TO_P1" />
            <el-option label="1小时→P1" value="1H_TO_P1" />
            <el-option label="2小时→P0" value="2H_TO_P0" />
          </el-select>
        </el-form-item>
        <el-form-item label="同时通知"><el-select v-model="ruleForm.recipients" multiple allow-create filterable placeholder="团队或用户标识" /></el-form-item>
        <el-form-item label="生效星期"><el-checkbox-group v-model="ruleForm.effectiveDays"><el-checkbox v-for="d in 7" :key="d" :value="d">{{ d }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="生效时段"><el-time-select v-model="ruleForm.effectiveStart" start="00:00" step="00:30" end="23:30"/><span style="margin:0 8px">至</span><el-time-select v-model="ruleForm.effectiveEnd" start="00:29" step="00:30" end="23:59"/></el-form-item>
        <el-form-item label="复合条件"><el-select v-model="ruleForm.combinator" style="width:100px"><el-option label="全部" value="ALL"/><el-option label="任一" value="ANY"/></el-select><el-button style="margin-left:8px" @click="addCondition">增加条件</el-button></el-form-item>
        <div v-for="(c,i) in ruleForm.conditions" :key="i" style="display:flex;gap:6px;margin:0 0 8px 100px"><el-select v-model="c.metric" style="width:180px"><el-option label="反馈窗口总量（旧版，仅兼容）" value="FEEDBACK_SPIKE" disabled/><el-option label="错误率" value="ERROR_RATE_HIGH"/><el-option label="严重问题占比" value="CRITICAL_RATIO"/><el-option label="数据中断" value="DATA_INTERRUPTION"/></el-select><el-select v-model="c.operator" style="width:75px"><el-option v-for="o in ['>','>=','<','<=','==']" :key="o" :value="o" :label="o"/></el-select><el-input-number v-model="c.threshold" :min="0"/><el-select v-model="c.window" style="width:90px"><el-option v-for="w in ['5m','15m','1h','24h']" :key="w" :value="w" :label="w"/></el-select><el-button text type="danger" @click="ruleForm.conditions.splice(i,1)">删除</el-button></div>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
    <el-drawer v-model="readinessVisible" :title="`${readinessRule?.name || ''} · 生产准入`" size="720px">
      <el-alert title="影子规则必须完成历史回放、站内演练、独立验收、数据契约及实现证据，发布后才能进入生产运行。" type="warning" :closable="false" />
      <el-descriptions v-if="readiness" :column="2" border style="margin-top:12px">
        <el-descriptions-item label="治理目录"><el-tag :type="readiness.governanceReady?'success':'danger'">{{readiness.governanceReady?'已批准':'未批准'}}</el-tag></el-descriptions-item>
        <el-descriptions-item label="数据契约"><el-tag :type="!readiness.missingContracts?.length?'success':'danger'">{{readiness.missingContracts?.length?`缺少 ${readiness.missingContracts.length} 项`:'已就绪'}}</el-tag></el-descriptions-item>
        <el-descriptions-item label="独立验收"><el-tag :type="readiness.acceptanceReady?'success':'danger'">{{readiness.acceptanceReady?'已批准':'待完成'}}</el-tag></el-descriptions-item>
        <el-descriptions-item label="实现证据"><el-tag :type="readiness.implementationReady?'success':'danger'">{{readiness.implementationReady?'已登记':'待登记'}}</el-tag></el-descriptions-item>
        <el-descriptions-item label="通知供应商"><el-tag :type="!readiness.missingProviders?.length?'success':'danger'">{{readiness.missingProviders?.length?readiness.missingProviders.join('、'):'已就绪'}}</el-tag></el-descriptions-item><el-descriptions-item label="有效值班人"><el-tag :type="readiness.missingEligibleOncall?'danger':'success'">{{readiness.missingEligibleOncall?`未配置（${readiness.ownerTeam||'责任团队为空'}）`:`${readiness.eligibleOncallCount||0} 人`}}</el-tag></el-descriptions-item>
        <el-descriptions-item label="发布状态"><el-tag :type="readiness.readyToPublish?'success':'warning'">{{readiness.readyToPublish?'允许发布':'门禁未通过'}}</el-tag></el-descriptions-item>
      </el-descriptions>
      <h3>所需数据契约</h3>
      <el-table :data="readiness?.contracts || []" size="small">
        <el-table-column prop="contractCode" label="契约" min-width="190"/><el-table-column prop="status" label="状态" width="100"/>
        <el-table-column prop="expiresAt" label="有效期" min-width="150"/>
        <el-table-column label="操作" width="180"><template #default="{row}"><el-button size="small" text @click="openContractValidation(row)">提交验证</el-button><el-button size="small" text type="primary" :disabled="row.status!=='VALIDATED'" @click="approveContract(row)">独立审批</el-button></template></el-table-column>
      </el-table>
      <h3>验收证据</h3>
      <el-table :data="acceptanceRows" size="small">
        <el-table-column prop="id" label="编号" width="70"/><el-table-column prop="precisionRate" label="精确率"/><el-table-column prop="recallRate" label="召回率"/><el-table-column prop="routingOutcome" label="路由"/><el-table-column prop="status" label="状态"/>
        <el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" text type="primary" :disabled="row.status!=='VALIDATED'" @click="approveAcceptanceRow(row)">独立审批</el-button></template></el-table-column>
      </el-table>
      <div class="section-heading"><h3>通知供应商</h3><el-button size="small" :disabled="!readiness?.missingProviders?.length" @click="openProviderValidation">验证供应商</el-button></div>
      <el-table :data="providerRows" size="small"><el-table-column prop="channel" label="渠道"/><el-table-column prop="providerCode" label="供应商"/><el-table-column prop="status" label="状态"/><el-table-column prop="expiresAt" label="有效期"/><el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" text type="primary" :disabled="row.status!=='VALIDATED'" @click="approveProvider(row)">独立审批</el-button></template></el-table-column></el-table>
      <div class="readiness-actions">
        <el-button @click="acceptanceDialogVisible=true">提交验收结果</el-button>
        <el-button :disabled="readiness?.implementationReady" @click="registerImplementation">登记实现证据</el-button>
        <el-button type="success" :disabled="!readiness?.readyToPublish || readinessRule?.versionStatus!=='SHADOW'" @click="publishSelectedRule">发布并启用</el-button>
      </div>
    </el-drawer>
    <el-dialog v-model="contractDialogVisible" title="提交数据契约验证" width="560px"><el-form label-width="100px">
      <el-form-item label="契约">{{contractForm.code}}</el-form-item><el-form-item label="证据 SHA-256"><el-input v-model="contractForm.evidenceSha256" maxlength="64"/></el-form-item><el-form-item label="验证摘要"><el-input v-model="contractForm.summary" type="textarea"/></el-form-item><el-form-item label="有效期至"><el-date-picker v-model="contractForm.expiresAt" type="datetime"/></el-form-item>
      </el-form><template #footer><el-button @click="contractDialogVisible=false">取消</el-button><el-button type="primary" @click="submitContractValidation">提交验证</el-button></template></el-dialog>
    <el-dialog v-model="acceptanceDialogVisible" title="提交规则验收结果" width="620px"><el-alert title="回放和演练必须属于当前规则，且不会发送外部通知；提交人与审批人必须不同。" type="info" :closable="false"/><el-form label-width="110px" style="margin-top:12px">
      <el-form-item label="历史回放"><el-select v-model="acceptanceForm.replayRunId" style="width:100%"><el-option v-for="r in ruleReplayRuns" :key="r.id" :label="`#${r.id} · 样本 ${r.sampleSize}`" :value="r.id"/></el-select></el-form-item>
      <el-form-item label="站内演练"><el-select v-model="acceptanceForm.drillId" style="width:100%"><el-option v-for="d in ruleDrills" :key="d.id" :label="`#${d.id} · ${d.title}`" :value="d.id"/></el-select></el-form-item>
      <el-form-item label="TP / FP"><el-input-number v-model="acceptanceForm.truePositive" :min="0"/><span style="margin:0 8px">/</span><el-input-number v-model="acceptanceForm.falsePositive" :min="0"/></el-form-item>
      <el-form-item label="FN / TN"><el-input-number v-model="acceptanceForm.falseNegative" :min="0"/><span style="margin:0 8px">/</span><el-input-number v-model="acceptanceForm.trueNegative" :min="0"/></el-form-item>
      <el-form-item label="路由结果"><el-select v-model="acceptanceForm.routingOutcome"><el-option label="通过" value="PASSED"/><el-option label="失败" value="FAILED"/></el-select></el-form-item>
      <el-form-item label="证据 SHA-256"><el-input v-model="acceptanceForm.evidenceSha256" maxlength="64"/></el-form-item>
      </el-form><template #footer><el-button @click="acceptanceDialogVisible=false">取消</el-button><el-button type="primary" @click="submitAcceptance">提交验收</el-button></template></el-dialog>
    <el-dialog v-model="providerDialogVisible" title="验证生产通知供应商" width="620px"><el-alert title="供应商验证只登记能力与证据，不会发送通知；必须由另一名管理员审批后才可用于生产。" type="warning" :closable="false"/><el-form label-width="110px" style="margin-top:12px">
      <el-form-item label="渠道"><el-select v-model="providerForm.channel"><el-option v-for="c in readiness?.missingProviders || []" :key="c" :label="c" :value="c"/></el-select></el-form-item><el-form-item label="供应商编码"><el-input v-model="providerForm.providerCode"/></el-form-item><el-form-item label="每日配额"><el-input-number v-model="providerForm.dailyQuota" :min="1"/></el-form-item><el-form-item label="证据 SHA-256"><el-input v-model="providerForm.evidenceSha256" maxlength="64"/></el-form-item><el-form-item label="证据引用"><el-input v-model="providerForm.evidenceReference" type="textarea"/></el-form-item><el-form-item label="有效期至"><el-date-picker v-model="providerForm.expiresAt" type="datetime"/></el-form-item>
      </el-form><template #footer><el-button @click="providerDialogVisible=false">取消</el-button><el-button type="primary" @click="submitProviderValidation">提交验证</el-button></template></el-dialog>
    <el-card v-show="activeSection==='testing'" shadow="never" class="test-center"><template #header><div class="section-heading"><div><b>规则测试中心</b><p>四种模式共用隔离证据链；均不创建生产告警、不发送外部通知。</p></div></div></template>
      <el-alert title="模拟用于手工边界值；回放用于历史区间验收；影子使用当前真实指标但不通知；演练仅创建平台内非生产实例。" type="info" :closable="false" style="margin-bottom:12px"/>
      <el-form label-width="110px" style="max-width:900px"><el-form-item label="运行规则"><el-select v-model="testRuleId" placeholder="选择已创建的运行版本" style="width:100%"><el-option v-for="r in businessRules" :key="r.id" :label="`${r.name} · ${r.level}`" :value="r.id"/></el-select></el-form-item><el-form-item label="测试指标"><div v-if="selectedTestRule" class="test-metrics"><el-input-number v-for="c in testConditions" :key="c.metric" v-model="testMetricValues[c.metric]" :min="0"><template #prepend>{{c.metric}}</template></el-input-number></div><el-empty v-else description="请先选择运行规则" :image-size="48"/></el-form-item><el-form-item label="回放区间"><el-date-picker v-model="replayRange" type="datetimerange" :disabled-date="disableFutureDate"/></el-form-item><el-form-item><el-button type="primary" :loading="testRunningMode==='SIMULATION'" :disabled="!selectedTestRule||Boolean(testRunningMode)" @click="runSelectedRuleTest">安全模拟</el-button><el-button :loading="testRunningMode==='REPLAY'" :disabled="!selectedTestRule||!replayRange||Boolean(testRunningMode)" @click="runReplay">历史回放</el-button><el-button :loading="testRunningMode==='SHADOW'" :disabled="!selectedTestRule||Boolean(testRunningMode)" @click="runShadow">影子评估</el-button><el-button type="warning" plain :loading="testRunningMode==='DRILL'" :disabled="!selectedTestRule||Boolean(testRunningMode)" @click="runDrill">{{testRunningMode==='DRILL'?'演练分析中':'平台内演练'}}</el-button></el-form-item></el-form>
      <el-descriptions v-if="testResult" :column="2" border><el-descriptions-item label="运行编号">#{{testResult.runId}}</el-descriptions-item><el-descriptions-item label="测试模式">{{testModeLabel(testResult.mode)}}</el-descriptions-item><el-descriptions-item label="是否命中"><el-tag :type="testResult.matched?'danger':'success'">{{testResult.matched?'命中':'未命中'}}</el-tag></el-descriptions-item><el-descriptions-item label="样本量">{{testResult.sampleSize??'-'}}</el-descriptions-item><el-descriptions-item label="主责团队">{{testResult.ownerTeam||'-'}}</el-descriptions-item><el-descriptions-item label="外部通知">{{testResult.externalNotificationsSent===0?'0（已隔离）':testResult.externalNotificationsSent}}</el-descriptions-item></el-descriptions>
      <h3 v-if="drills.length" style="margin-top:20px">非生产演练实例</h3><el-table v-if="drills.length" :data="drills" max-height="260"><el-table-column prop="id" label="演练实例" width="100"><template #default="{row}">#{{row.id}}</template></el-table-column><el-table-column prop="title" label="标题" min-width="220"/><el-table-column prop="severity" label="级别" width="70"/><el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="drillStatusType(row.status)">{{drillStatusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column label="外部通知" width="110"><template #default>0（隔离）</template></el-table-column><el-table-column label="处置" width="190"><template #default="{row}"><el-button v-if="row.status==='OPEN'" size="small" type="primary" @click="acknowledgeDrill(row)">确认</el-button><el-button v-if="row.status==='ACKNOWLEDGED'" size="small" type="success" @click="resolveDrill(row)">解决</el-button><span v-if="row.status==='RESOLVED'">闭环完成</span></template></el-table-column></el-table>
      <h3 style="margin-top:20px">最近测试证据</h3><el-table :data="testRuns" max-height="300"><el-table-column prop="id" label="编号" width="75"/><el-table-column prop="ruleName" label="规则" min-width="180"/><el-table-column label="模式" width="100"><template #default="{row}">{{testModeLabel(row.mode)}}</template></el-table-column><el-table-column label="结果" width="90"><template #default="{row}"><el-tag :type="row.matched?'danger':'success'">{{row.matched?'命中':'未命中'}}</el-tag></template></el-table-column><el-table-column prop="sampleSize" label="样本量" width="90"/><el-table-column prop="externalNotificationsSent" label="外部通知" width="90"/><el-table-column prop="createdByName" label="执行人"/><el-table-column prop="createdAt" label="时间" min-width="170"/></el-table>
      <el-collapse style="margin-top:16px"><el-collapse-item :title="`未处理幂等冲突（${idempotencyConflicts.length}）`"><el-alert title="同一业务键出现不同内容且缺少单调更新时间时会阻断导入，避免静默覆盖；处置只改变冲突状态，原始哈希证据不修改。" type="warning" :closable="false"/><el-table :data="idempotencyConflicts" style="margin-top:8px"><el-table-column prop="sourceChannel" label="渠道" width="90"/><el-table-column prop="importBatchId" label="批次"/><el-table-column prop="importRowId" label="行任务"/><el-table-column prop="reason" label="原因" min-width="180"/><el-table-column prop="occurredAt" label="发生时间" min-width="170"/><el-table-column label="处置" min-width="270"><template #default="{row}"><el-button size="small" @click="handleConflict(row,'KEEP_EXISTING')">保留现有</el-button><el-button size="small" type="primary" plain @click="handleConflict(row,'ACCEPT_INCOMING')">允许新记录重试</el-button><el-button size="small" text @click="handleConflict(row,'IGNORE')">忽略</el-button></template></el-table-column></el-table></el-collapse-item></el-collapse>
    </el-card>
    <el-card v-show="activeSection==='oncall'" shadow="never" style="margin-top:16px"><template #header><div class="section-heading"><div><b>值班排班</b><p class="section-help">告警先路由到当前主值班，其次备值班；空档自动通知团队负责人。</p></div><el-button @click="memberDialog=true">维护团队成员</el-button></div></template>
      <div style="display:flex;gap:8px;margin-bottom:12px"><el-select v-model="oncallTeam" placeholder="选择团队" @change="loadOncall"><el-option v-for="t in teams" :key="t.team" :label="`${t.team}（${t.members}人）`" :value="t.team"/></el-select><el-button type="primary" @click="oncallDialog=true" :disabled="!oncallTeam || !oncallMembers.length">新增排班</el-button></div>
      <el-empty v-if="!teams.length" description="尚未配置团队成员，先建立团队与系统账号的关系"><el-button type="primary" @click="memberDialog=true">创建团队成员关系</el-button></el-empty>
      <el-alert v-for="g in oncall.gaps" :key="`${g.start}-${g.end}`" type="warning" :closable="false" :title="`无排班：${g.start} ～ ${g.end}，将通知团队负责人`" style="margin-bottom:6px"/>
      <el-table v-if="teams.length" :data="oncall.items"><el-table-column prop="team" label="团队"/><el-table-column prop="userName" label="值班人"/><el-table-column label="类型"><template #default="{row}">{{oncallTypeLabel(row.type)}}</template></el-table-column><el-table-column prop="start" label="开始"/><el-table-column prop="end" label="结束"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button text type="danger" :disabled="!isFuture(row.end)" @click="cancelSchedule(row)">取消</el-button></template></el-table-column></el-table>
    </el-card>
    <el-dialog v-model="oncallDialog" title="新增值班排班" width="520px"><el-form label-width="90px"><el-form-item label="值班人员"><el-select v-model="oncallForm.userId" placeholder="选择团队成员" style="width:100%"><el-option v-for="m in oncallMembers" :key="m.userId" :label="`${m.userName}${m.primary?'（负责人）':''}`" :value="m.userId"/></el-select></el-form-item><el-form-item label="类型"><el-select v-model="oncallForm.type"><el-option label="主值班" value="PRIMARY"/><el-option label="备值班" value="BACKUP"/><el-option label="临时" value="TEMPORARY"/></el-select></el-form-item><el-form-item label="时间"><el-date-picker v-model="oncallForm.range" type="datetimerange"/></el-form-item></el-form><template #footer><el-button @click="oncallDialog=false">取消</el-button><el-button type="primary" @click="saveOncall">保存</el-button></template></el-dialog>
    <el-dialog v-model="memberDialog" title="团队成员维护" width="760px"><el-alert title="团队成员决定告警可排班人员；负责人同时作为无排班时的兜底联系人。" type="info" :closable="false"/><el-form inline style="margin-top:12px"><el-form-item label="团队"><el-input v-model="memberForm.team" placeholder="如：数据平台"/></el-form-item><el-form-item label="系统账号"><el-select v-model="memberForm.userId" filterable placeholder="选择有效账号"><el-option v-for="u in eligibleUsers" :key="u.id" :label="`${u.displayName}（${u.username}）`" :value="u.id"/></el-select></el-form-item><el-form-item label="角色"><el-select v-model="memberForm.role"><el-option label="成员" value="MEMBER"/><el-option label="负责人" value="LEADER"/></el-select></el-form-item><el-form-item><el-checkbox v-model="memberForm.primary">设为负责人</el-checkbox></el-form-item><el-form-item><el-button type="primary" @click="saveMember">保存成员</el-button></el-form-item></el-form><el-table :data="allMembers"><el-table-column prop="team" label="团队"/><el-table-column prop="userName" label="成员"/><el-table-column prop="role" label="角色"/><el-table-column label="负责人"><template #default="{row}">{{row.primary?'是':'否'}}</template></el-table-column><el-table-column label="操作" width="80"><template #default="{row}"><el-button text type="danger" @click="deleteMember(row)">移除</el-button></template></el-table-column></el-table></el-dialog>
    <el-card v-show="activeSection==='silences'" shadow="never" style="margin-top:16px"><template #header><div class="section-heading"><div><b>静默策略</b><p>静默只暂停指定时间内的外部通知，规则仍评估并保存证据；P0安全规则不可静默。</p></div><el-button type="primary" @click="silenceDialogVisible=true">新增静默</el-button></div></template>
      <el-table :data="silenceRows"><el-table-column prop="typeLabel" label="类型"/><el-table-column prop="scopeLabel" label="影响范围" min-width="180"/><el-table-column label="状态"><template #default="{row}"><el-tag :type="row.statusType">{{row.statusLabel}}</el-tag></template></el-table-column><el-table-column prop="start" label="开始"/><el-table-column prop="end" label="结束"/><el-table-column prop="remaining" label="剩余时间"/><el-table-column prop="suppressedByName" label="创建人"/><el-table-column prop="reason" label="原因" min-width="150"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button text type="danger" :disabled="row.status!=='PENDING'" @click="cancelSilence(row)">取消</el-button></template></el-table-column></el-table>
    </el-card>
    <el-dialog v-model="silenceDialogVisible" title="新增静默" width="520px"><el-form label-width="90px">
      <el-form-item label="类型"><el-select v-model="silenceForm.type"><el-option label="维护窗口（可覆盖全部非P0规则）" value="MAINTENANCE"/><el-option label="人工静默（必须指定规则）" value="MANUAL"/></el-select></el-form-item>
      <el-form-item label="规则"><el-select v-model="silenceForm.ruleId" clearable placeholder="维护窗口留空表示全部非P0规则"><el-option v-for="r in businessRules" :key="r.id" :label="`${r.name} · ${r.level}`" :value="r.id" :disabled="r.level==='P0'"/></el-select></el-form-item>
      <el-form-item label="时间"><el-date-picker v-model="silenceForm.range" type="datetimerange"/></el-form-item>
      <el-form-item label="原因"><el-input v-model="silenceForm.reason"/></el-form-item></el-form>
      <template #footer><el-button @click="silenceDialogVisible=false">取消</el-button><el-button type="primary" @click="saveSilence">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { createAlertRule, getAlertRules, getAlertGovernanceCatalog, getAlertLoopHealth, toggleAlertRule, updateAlertRule, getAlertSilences, createAlertSilence, deleteAlertSilence, reorderAlertRules, testAlertRule, getAlertRuleTestRuns, replayAlertRule, shadowAlertRule, drillAlertRule, getAlertDrills, acknowledgeAlertDrill, resolveAlertDrill, getIdempotencyConflicts, resolveIdempotencyConflict, getOncallTeams, getOncallCalendar, createOncall, cancelOncall, getOncallMembers, getOncallEligibleUsers, saveOncallMember, removeOncallMember, getAlertRuleReadiness, publishAlertRule, validateAlertContract, approveAlertContract, getAlertProviders, validateAlertProvider, approveAlertProvider, markAlertRuleImplemented, getAlertAcceptance, validateAlertAcceptance, approveAlertAcceptance } from '@/api/admin'
import { acceptanceValidationError, contractValidationError, isRuleProductionReady, providerValidationError, ruleDraftError } from './alertConfigValidation'

const route=useRoute()
const loopHealth=ref<any>(null)
const rules = ref<any[]>([])
const readinessByRule=reactive<Record<number,any>>({})
const activeSection=ref('rules')
const businessRules=computed(()=>rules.value.filter(rule=>rule.category!=='SYSTEM'))
const systemRules=computed(()=>rules.value.filter(rule=>rule.category==='SYSTEM'))
const testRuleId=ref<number|null>(null),testMetricValues=reactive<Record<string,number>>({}),testResult=ref<any>(null),testRuns=ref<any[]>([]),testRunningMode=ref<string|null>(null),replayRange=ref<Date[]|null>([new Date(Date.now()-86400000),new Date()])
const idempotencyConflicts=ref<any[]>([])
const selectedTestRule=computed(()=>businessRules.value.find(r=>r.id===testRuleId.value))
const testConditions=computed(()=>selectedTestRule.value?.conditions?.length?selectedTestRule.value.conditions:[selectedTestRule.value?{metric:selectedTestRule.value.metric,threshold:selectedTestRule.value.threshold}:null].filter(Boolean))
watch(testRuleId,()=>{testResult.value=null;Object.keys(testMetricValues).forEach(k=>delete testMetricValues[k]);testConditions.value.forEach((c:any)=>testMetricValues[c.metric]=Number(c.threshold)||0)})
async function loadTestRuns(){const [runs,drillRows]=await Promise.all([getAlertRuleTestRuns(50),getAlertDrills(50)]);testRuns.value=runs;drills.value=drillRows}
async function loadIdempotencyConflicts(){idempotencyConflicts.value=await getIdempotencyConflicts('OPEN')}
async function loadLoopHealth(){try{loopHealth.value=await getAlertLoopHealth()}catch{loopHealth.value=null}}
async function handleConflict(row:any,action:string){const label=action==='KEEP_EXISTING'?'保留现有记录':action==='ACCEPT_INCOMING'?'允许新记录进入人工重试':'忽略冲突';const result=await ElMessageBox.prompt(`请输入“${label}”的处置依据，操作会进入审计记录。`,'处置幂等冲突',{inputPlaceholder:'至少3个字符',inputValidator:(v:string)=>v.trim().length>=3||'处置依据至少3个字符',confirmButtonText:'确认处置',cancelButtonText:'取消'});await resolveIdempotencyConflict(row.id,{action,note:result.value});ElMessage.success('冲突已处置');await loadIdempotencyConflicts()}
async function withTestMode(mode:string,action:()=>Promise<any>){testRunningMode.value=mode;try{testResult.value=await action();await loadTestRuns()}finally{testRunningMode.value=null}}
async function runSelectedRuleTest(){if(!selectedTestRule.value)return;await withTestMode('SIMULATION',()=>testAlertRule(selectedTestRule.value,{...testMetricValues}))}
async function runReplay(){if(!testRuleId.value||!replayRange.value)return;await withTestMode('REPLAY',()=>replayAlertRule({ruleId:testRuleId.value,start:localDateTime(replayRange.value![0]),end:localDateTime(replayRange.value![1])}))}
async function runShadow(){if(!testRuleId.value)return;await withTestMode('SHADOW',()=>shadowAlertRule(testRuleId.value!))}
async function runDrill(){if(!testRuleId.value)return;await withTestMode('DRILL',()=>drillAlertRule({ruleId:testRuleId.value,metricValues:{...testMetricValues},title:`${selectedTestRule.value?.name}开发验收演练`}))}
function validDrillId(row:any){const id=Number(row?.id);if(!Number.isSafeInteger(id)||id<=0){ElMessage.error('演练实例编号缺失，请刷新后重试');return null}return id}
async function acknowledgeDrill(row:any){const id=validDrillId(row);if(id===null)return;const r=await ElMessageBox.prompt('请输入确认说明（2～300字）','确认非生产演练实例',{inputValue:'已确认演练告警，开始分析处置',inputValidator:(v:string)=>v.trim().length>=2&&v.trim().length<=300||'请输入2～300字'});await acknowledgeAlertDrill(id,r.value);ElMessage.success('演练实例已确认');await loadTestRuns()}
async function resolveDrill(row:any){const id=validDrillId(row);if(id===null)return;const r=await ElMessageBox.prompt('请输入解决结论（2～300字）','解决非生产演练实例',{inputValue:'规则与路由验证完成，演练实例关闭',inputValidator:(v:string)=>v.trim().length>=2&&v.trim().length<=300||'请输入2～300字'});await resolveAlertDrill(id,r.value);ElMessage.success('演练实例已解决，闭环完成');await loadTestRuns()}
const drillStatusLabel=(s:string)=>({OPEN:'待确认',ACKNOWLEDGED:'已确认',RESOLVED:'已解决'} as any)[s]||s
const drillStatusType=(s:string)=>s==='RESOLVED'?'success':s==='ACKNOWLEDGED'?'warning':'danger'
const readinessVisible=ref(false),readinessRule=ref<any>(null),readiness=ref<any>(null),acceptanceRows=ref<any[]>([]),drills=ref<any[]>([]),providerRows=ref<any[]>([])
const acceptanceDialogVisible=ref(false),contractDialogVisible=ref(false),providerDialogVisible=ref(false)
const contractForm=reactive({code:'',evidenceSha256:'',summary:'',expiresAt:null as Date|null})
const acceptanceForm=reactive({replayRunId:null as number|null,drillId:null as number|null,truePositive:0,falsePositive:0,falseNegative:0,trueNegative:0,routingOutcome:'PASSED',evidenceSha256:''})
const providerForm=reactive({channel:'',providerCode:'',dailyQuota:1000,evidenceSha256:'',evidenceReference:'',expiresAt:null as Date|null})
const ruleReplayRuns=computed(()=>testRuns.value.filter(r=>r.ruleId===readinessRule.value?.id&&r.mode==='REPLAY'&&r.externalNotificationsSent===0))
const ruleDrills=computed(()=>drills.value.filter(d=>d.ruleId===readinessRule.value?.id&&!d.productionImpact&&d.notificationChannel==='IN_APP'))
async function refreshReadiness(){if(!readinessRule.value)return;const [gaps,acceptance,drillRows,providers]=await Promise.all([getAlertRuleReadiness(readinessRule.value.id),getAlertAcceptance(readinessRule.value.id),getAlertDrills(100),getAlertProviders()]);readiness.value=gaps;readinessByRule[readinessRule.value.id]=gaps;acceptanceRows.value=acceptance;drills.value=drillRows;providerRows.value=providers.filter((p:any)=>p.environment==='PRODUCTION'&&((gaps.missingProviders||[]).includes(p.channel)||p.status==='APPROVED'))}
async function openReadiness(rule:any){readinessRule.value=rule;readinessVisible.value=true;readiness.value=null;await Promise.all([loadTestRuns(),refreshReadiness()])}
const isEventRule=(rule:any)=>rule.metric==='EVENT_ONLY'
const isProductionReady=(rule:any)=>isRuleProductionReady(rule.id,readinessByRule)
function lifecycleTag(rule:any){
  if(rule.enabled)return {label:'生产运行',type:'success' as const}
  if(rule.versionStatus==='SHADOW')return {label:'影子验证',type:'warning' as const}
  if(!isProductionReady(rule))return {label:'待准入',type:'warning' as const}
  return {label:'待启用',type:'info' as const}
}
function switchHint(rule:any){if(rule.enabled)return '关闭规则会立即停止生产评估';if(rule.versionStatus==='SHADOW')return '影子规则需从准入详情完成验收并发布';if(!isProductionReady(rule))return '生产准入未完成，请查看准入详情';return '生产准入已完成，可以启用'}
async function loadActivationReadiness(rows:any[]){const candidates=rows.filter(r=>r.versionStatus==='ACTIVE');const results=await Promise.allSettled(candidates.map(r=>getAlertRuleReadiness(r.id)));candidates.forEach((rule,index)=>{const result=results[index];if(result.status==='fulfilled')readinessByRule[rule.id]=result.value})}
function openContractValidation(row:any){Object.assign(contractForm,{code:row.contractCode,evidenceSha256:'',summary:'',expiresAt:new Date(Date.now()+30*86400000)});contractDialogVisible.value=true}
async function submitContractValidation(){const error=contractValidationError(contractForm);if(error){ElMessage.warning(error);return}await validateAlertContract(contractForm.code,{evidenceSha256:contractForm.evidenceSha256,summary:contractForm.summary,expiresAt:localDateTime(contractForm.expiresAt!)});contractDialogVisible.value=false;ElMessage.success('契约验证已提交，请由另一名管理员审批');await refreshReadiness()}
async function approveContract(row:any){await approveAlertContract(row.contractCode);ElMessage.success('数据契约已独立审批');await refreshReadiness()}
function openProviderValidation(){Object.assign(providerForm,{channel:readiness.value?.missingProviders?.[0]||'',providerCode:'',dailyQuota:1000,evidenceSha256:'',evidenceReference:'',expiresAt:new Date(Date.now()+30*86400000)});providerDialogVisible.value=true}
async function submitProviderValidation(){const error=providerValidationError(providerForm);if(error){ElMessage.warning(error);return}const common={accepted:true,failureReceipt:true,rateLimitTested:true,credentialRotationTested:true,providerReceipt:true};const specific=providerForm.channel==='VOICE_CALL'?{connectedReceipt:true,noAnswerReceipt:true,dtmf1Ack:true}:providerForm.channel==='EMAIL'?{deliveryReceipt:true,bounceReceipt:true}:{};await validateAlertProvider({...providerForm,environment:'PRODUCTION',idempotencySupported:true,capabilities:{...common,...specific},expiresAt:localDateTime(providerForm.expiresAt!)});providerDialogVisible.value=false;ElMessage.success('供应商验证已提交，请由另一名管理员审批');await refreshReadiness()}
async function approveProvider(row:any){await approveAlertProvider(row.id);ElMessage.success('通知供应商已独立审批');await refreshReadiness()}
async function submitAcceptance(){if(!readinessRule.value)return;const error=acceptanceValidationError(acceptanceForm);if(error){ElMessage.warning(error);return}await validateAlertAcceptance({...acceptanceForm,ruleId:readinessRule.value.id});acceptanceDialogVisible.value=false;ElMessage.success('验收已提交，请由另一名管理员审批');await refreshReadiness()}
async function approveAcceptanceRow(row:any){await approveAlertAcceptance(row.id);ElMessage.success('规则验收已独立审批');await refreshReadiness()}
async function registerImplementation(){if(!readinessRule.value)return;const result=await ElMessageBox.prompt('填写历史回放/实现验证证据的 SHA-256。数据契约必须先全部获批。','登记实现证据',{inputValidator:(v:string)=>/^[a-fA-F0-9]{64}$/.test(v)||'请输入64位十六进制 SHA-256'});await markAlertRuleImplemented(readinessRule.value.ruleCode,result.value);ElMessage.success('实现证据已登记');await refreshReadiness()}
async function publishSelectedRule(){if(!readinessRule.value)return;const result=await ElMessageBox.prompt('发布后规则会立即进入生产评估并启用，请填写发布原因。','发布并启用',{confirmButtonText:'确认发布',inputValidator:(v:string)=>v.trim().length>=3||'发布原因至少3个字符'});await publishAlertRule(readinessRule.value.id,{reason:result.value,enable:true});ElMessage.success('规则已发布并启用');readinessVisible.value=false;await loadRules()}
const disableFutureDate=(d:Date)=>d.getTime()>Date.now()
const testModeLabel=(mode:string)=>({SIMULATION:'安全模拟',REPLAY:'历史回放',SHADOW:'影子评估',DRILL:'平台内演练'} as Record<string,string>)[mode]||mode
const catalog=reactive<any>({rules:[],summary:{},activationPolicy:''})
const loadErrors = ref<string[]>([])
const ruleDialogVisible = ref(false)
const editingRule = ref<any>(null)
const ruleForm = reactive({
  name: '', level: 'P2', metric: 'FEEDBACK_SPIKE', threshold: 10,
  window: '1h', channels: ['feishu_card'], escalation: 'NONE', cooldownMinutes:60, recoveryThreshold:5,ownerTeam:'',recipients:[] as string[],effectiveDays:[1,2,3,4,5,6,7],effectiveStart:'00:00',effectiveEnd:'23:59',timezone:'Asia/Shanghai',combinator:'ALL',conditions:[] as any[],
})

function openRuleDialog(rule: any) {
  editingRule.value = rule
  if (rule) Object.assign(ruleForm, rule)
  else Object.assign(ruleForm, { name: '', level: 'P2', metric: 'FEEDBACK_SPIKE', threshold: 10, window: '1h', channels: ['feishu_card'], escalation: 'NONE', cooldownMinutes:60, recoveryThreshold:5,ownerTeam:'',recipients:[],effectiveDays:[1,2,3,4,5,6,7],effectiveStart:'00:00',effectiveEnd:'23:59',timezone:'Asia/Shanghai',combinator:'ALL',conditions:[] })
  ruleDialogVisible.value = true
}
function addCondition(){ruleForm.conditions.push({metric:ruleForm.metric,operator:'>=',threshold:ruleForm.threshold,window:ruleForm.window})}
const dragRuleId=ref<number|null>(null);async function dropRule(target:number){if(!dragRuleId.value||dragRuleId.value===target)return;const ids=rules.value.map(r=>r.id);const from=ids.indexOf(dragRuleId.value),to=ids.indexOf(target);ids.splice(to,0,ids.splice(from,1)[0]);await reorderAlertRules(ids);dragRuleId.value=null;await loadRules()}
async function testExistingRule(rule:any){const values=Object.fromEntries((rule.conditions?.length?rule.conditions:[{metric:rule.metric,threshold:rule.threshold}]).map((c:any)=>[c.metric,c.threshold]));const result=await testAlertRule(rule,values);ElMessage[result.matched?'success':'warning'](result.matched?'测试数据命中规则':'测试数据未命中')}
const teams=ref<any[]>([]),oncallTeam=ref(''),oncall=reactive<any>({items:[],gaps:[]}),oncallDialog=ref(false),oncallMembers=ref<any[]>([]),allMembers=ref<any[]>([]),eligibleUsers=ref<any[]>([]),memberDialog=ref(false),oncallForm=reactive({userId:null as number|null,type:'PRIMARY',range:null as Date[]|null}),memberForm=reactive({team:'',userId:null as number|null,role:'MEMBER',primary:false})
const localDateTime=(d:Date)=>{const offset=d.getTimezoneOffset()*60000;return new Date(d.getTime()-offset).toISOString().slice(0,19)}
async function loadOncall(){if(!oncallTeam.value){Object.assign(oncall,{items:[],gaps:[]});oncallMembers.value=[];return}const start=new Date(),end=new Date(start.getTime()+7*86400000);const [calendar,members]=await Promise.all([getOncallCalendar({team:oncallTeam.value,start:localDateTime(start),end:localDateTime(end)}),getOncallMembers(oncallTeam.value)]);Object.assign(oncall,calendar);oncallMembers.value=members;if(!oncallMembers.value.some(m=>m.userId===oncallForm.userId))oncallForm.userId=oncallMembers.value[0]?.userId??null}
async function reloadMemberData(){const results=await Promise.allSettled([getOncallTeams(),getOncallMembers(),getOncallEligibleUsers()]);const [teamRows,members,users]=results;if(teamRows.status==='fulfilled')teams.value=teamRows.value;else loadErrors.value.push('值班团队加载失败');if(members.status==='fulfilled')allMembers.value=members.value;else loadErrors.value.push('团队成员加载失败');if(users.status==='fulfilled')eligibleUsers.value=users.value;else loadErrors.value.push('可排班账号加载失败');if(!teams.value.some(t=>t.team===oncallTeam.value))oncallTeam.value=teams.value[0]?.team||'';if(oncallTeam.value)await loadOncall()}
async function saveOncall(){if(!oncallForm.userId){ElMessage.warning('请选择值班人员');return}if(!oncallForm.range){ElMessage.warning('请选择时间');return}await createOncall({team:oncallTeam.value,userId:oncallForm.userId,type:oncallForm.type,start:localDateTime(oncallForm.range[0]),end:localDateTime(oncallForm.range[1])});oncallDialog.value=false;ElMessage.success('排班已保存');await loadOncall()}
async function cancelSchedule(row:any){await cancelOncall(row.id,row.version);ElMessage.success('排班已取消');await loadOncall()}
async function saveMember(){if(!memberForm.team.trim()||!memberForm.userId){ElMessage.warning('请填写团队并选择账号');return}await saveOncallMember({...memberForm,team:memberForm.team.trim(),primary:memberForm.primary||memberForm.role==='LEADER'});ElMessage.success('团队成员已保存');await reloadMemberData()}
async function deleteMember(row:any){await removeOncallMember(row.id);ElMessage.success('成员已移除');await reloadMemberData()}
const isFuture=(value:any)=>new Date(value).getTime()>Date.now()
const oncallTypeLabel=(type:string)=>({PRIMARY:'主值班',BACKUP:'备值班',TEMPORARY:'临时值班'}[type]||type)

const silences=ref<any[]>([]);const silenceDialogVisible=ref(false);const silenceForm=reactive({type:'MAINTENANCE',ruleId:null as number|null,range:null as Date[]|null,reason:''})
const silenceRows=computed(()=>silences.value.map(row=>{let scope:any=row.scope;try{if(typeof scope==='string')scope=JSON.parse(scope)}catch{/* 保留无法解析的原始范围，供页面安全降级展示 */}const now=Date.now(),start=new Date(row.start).getTime(),end=new Date(row.end).getTime();const status=now<start?'PENDING':now<end?'ACTIVE':'EXPIRED';const remainingMs=Math.max(0,end-now),hours=Math.floor(remainingMs/3600000),minutes=Math.floor(remainingMs%3600000/60000);const rule=rules.value.find(r=>r.id===Number(scope?.ruleId));const typeLabels:Record<string,string>={MAINTENANCE:'维护窗口',MANUAL:'人工静默',DUPLICATE:'重复抑制'};return {...row,status,statusLabel:{PENDING:'待生效',ACTIVE:'生效中',EXPIRED:'已过期'}[status],statusType:{PENDING:'warning',ACTIVE:'success',EXPIRED:'info'}[status],typeLabel:typeLabels[String(row.type)]||row.type,scopeLabel:scope?.all?'全部非P0业务规则':rule?.name||`规则 #${scope?.ruleId||'-'}`,reason:scope?.reason||'-',remaining:status==='EXPIRED'?'—':`${hours}小时${minutes}分钟`}}))
async function loadRules() {
  loadErrors.value = []
  const [active, governance, silence] = await Promise.allSettled([
    getAlertRules(), getAlertGovernanceCatalog(), getAlertSilences(),
  ])
  if (active.status === 'fulfilled') {rules.value = active.value;await loadActivationReadiness(active.value)}
  else loadErrors.value.push('启用规则加载失败')
  if (governance.status === 'fulfilled') Object.assign(catalog, governance.value)
  else loadErrors.value.push('正式规则目录加载失败')
  if (silence.status === 'fulfilled') silences.value = silence.value
  else loadErrors.value.push('静默策略加载失败')
}
async function saveSilence(){if(!silenceForm.range){ElMessage.warning('请选择时间');return}await createAlertSilence({type:silenceForm.type,ruleId:silenceForm.ruleId,start:silenceForm.range[0].toISOString(),end:silenceForm.range[1].toISOString(),reason:silenceForm.reason});silenceDialogVisible.value=false;await loadRules()}
async function cancelSilence(row:any){await deleteAlertSilence(row.id);ElMessage.success('待生效静默已取消');await loadRules()}

async function saveRule() {
  const error=ruleDraftError(ruleForm)
  if (error) { ElMessage.warning(error); return }
  if (editingRule.value) await updateAlertRule(editingRule.value.id, ruleForm)
  else await createAlertRule(ruleForm)
  ElMessage.success(editingRule.value ? '规则已更新并生成新版本' : '规则已创建')
  ruleDialogVisible.value = false
  await loadRules()
}

async function toggleRule(rule: any, enabled: boolean) {
  if(enabled&&!isProductionReady(rule)){rule.enabled=false;ElMessage.warning('生产准入未完成，请先查看准入详情');await openReadiness(rule);return}
  try {await toggleAlertRule(rule.id, enabled);ElMessage.success(enabled ? '规则已启用' : '规则已禁用');await loadRules()}
  catch{rule.enabled=!enabled}
}
onMounted(async()=>{const requested=String(route.query.section||'');if(['rules','governance','testing','oncall','silences'].includes(requested))activeSection.value=requested;await Promise.all([loadRules(),loadTestRuns(),loadIdempotencyConflicts(),loadLoopHealth()]);await reloadMemberData()})
</script>

<style scoped>
.rule-item {
  padding: 16px 0;
  border-bottom: 1px solid var(--border-light);
}
.rule-item:last-child { border-bottom: none; }
.rule-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 24px;
  align-items: center;
}
.rule-info {
  display: grid;
  grid-template-columns: minmax(160px, 210px) 150px minmax(260px, 1fr);
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.rule-name { font-weight: 600; font-size: 15px; line-height: 1.45; }
.rule-tags { display:flex; align-items:center; gap:8px; white-space:nowrap; }
.rule-condition { min-width:0; font-size: 13px; line-height:1.55; color: var(--text-secondary); overflow-wrap:anywhere; }
.rule-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-secondary);
}
.rule-actions { margin-top: 8px; padding-left: 0; }
@media (max-width: 1200px) {
  .rule-main { grid-template-columns: 1fr; gap:10px; }
  .rule-info { grid-template-columns:minmax(150px, 200px) 150px minmax(220px, 1fr); }
  .rule-meta { justify-content:flex-end; }
}
.readiness-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:20px; }
.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.section-heading h3,.section-heading p,.section-help{margin:0}.section-heading p,.section-help{font-size:13px;color:var(--text-secondary);margin-top:4px}.test-metrics{display:flex;flex-wrap:wrap;gap:10px}.config-tabs{margin-bottom:12px}
</style>
