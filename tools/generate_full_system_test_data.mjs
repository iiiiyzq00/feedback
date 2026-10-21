import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { Workbook, SpreadsheetFile } from "@oai/artifact-tool";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const root = path.resolve(process.env.FULL_SYSTEM_FIXTURE_DIR || path.join(projectRoot, "data/test-fixtures/full-system-20260805"));
const qaDir = path.resolve(process.env.FULL_SYSTEM_FIXTURE_QA_DIR || path.join(process.env.TMPDIR || "/tmp", "feedback-platform-full-system-20260805-qa"));
await fs.mkdir(root, { recursive: true });
await fs.mkdir(qaDir, { recursive: true });

let state = 20260805;
const rnd = () => ((state = (state * 1664525 + 1013904223) >>> 0) / 2 ** 32);
const pick = (a, i) => a[i % a.length];
const iso = (i, step = 37) => new Date(Date.UTC(2026, 7, 4, 2, 0) - i * step * 60000).toISOString();
const clusters = Array.from({ length: 16 }, (_, i) => `XC-${String(i + 1).padStart(2, "0")}`);
const modules = ["AI_TUTOR", "SYNC_COURSE", "WRONG_QUESTION_BOOK", "CAMERA_MIC", "VIDEO_PLAYBACK", "LOGIN_ACCOUNT"];
const apps = ["com.mock.english", "com.mock.math", "com.mock.reading", "com.mock.parent"];
const versions = ["3.1.9", "3.2.0", "3.2.1", "3.2.2", "4.0.0", "4.0.1-beta"];
const devices = ["XT11A-MOCK", "XT12-MOCK", "PAD-A-MOCK", "PAD-B-MOCK", "PHONE-C-MOCK"];
const symptoms = ["AI助教打开后闪退", "同步课程页面卡死", "错题本答案判定错误", "麦克风无法录音", "视频播放明显卡顿", "登录后白屏", "离线内容同步失败", "按钮点击无响应"];
const clusterSymptom = (c) => symptoms[(Number(c.slice(3)) - 1) % symptoms.length];
const syntheticSensitive = [
  "虚构姓名测试用户甲，模拟手机号13800138000",
  "虚构家长测试监护人乙，邮箱qa.user@example.test",
  "虚构身份证110101199001011234（校验位故意无效）",
  "虚构银行卡6222020000000001（故意无效）",
  "模拟地址测试省测试市测试区未来路88号",
  "文档保留IP 192.0.2.42，局部管理MAC 02:00:00:00:00:42",
  "模拟设备序列号MOCK-SN-000042，虚构座机010-00000000",
  "混合敏感串：测试用户丙 13900000000 qa.parent@example.test MOCK-SN-009999",
  "虚构学生测试同学丁，就读测试未来学校三年级二班"
];

const scenarios = [];
function expected(i) {
  if (i % 97 === 0) return "MISSING_REQUIRED_FIELD";
  if (i % 89 === 0) return "INVALID_ENUM";
  if (i % 83 === 0) return "INVALID_DATE";
  if (i % 79 === 0) return "DUPLICATE_RECORD";
  if (i % 73 === 0) return "STALE_UPDATE";
  if (i % 67 === 0) return "MASKING_REVIEW";
  if (i % 61 === 0) return "FORMAT_ERROR";
  return "IMPORTED";
}
function issueText(i, channel) {
  const cluster = i < 64 ? clusters[i % clusters.length] : "NONE";
  const symptom = cluster === "NONE" ? pick(symptoms, i) : clusterSymptom(cluster);
  const pii = i % 11 === 0 || expected(i) === "MASKING_REVIEW" ? `；脱敏样本：${pick(syntheticSensitive, i)}` : "；不含身份信息";
  return { cluster, symptom, title: `${symptom}${cluster === "NONE" ? "" : `-${cluster}`}`, description: `[全量模拟/${channel}] ${symptom}${pii}；变体${Math.floor(rnd() * 10000)}` };
}

function sdkRows(count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const t = issueText(i, "SDK");
    const outcome = expected(i);
    let id = `FS-SDK-${String(i).padStart(5, "0")}`;
    if (outcome === "DUPLICATE_RECORD" && i) id = `FS-SDK-${String(i - 1).padStart(5, "0")}`;
    const row = {
      client_feedback_id: id, app_id: `demo-${pick(apps, i).split('.').at(-1)}`, package_name: pick(apps, i), app_version: pick(versions, i), function_module: pick(modules, i),
      issue_type: pick(["CRASH", "FREEZE", "MIC_FAIL", "WRONG_RESULT", "NO_RESPONSE", "LAG"], i),
      severity_level: pick(["S0", "S1", "S2", "S3"], i), sentiment: pick(["NEGATIVE", "NEGATIVE", "NEUTRAL", "POSITIVE"], i),
      reproducibility: pick(["ALWAYS", "FREQUENT", "OCCASIONAL", "ONCE"], i), source_event_time: iso(i),
      province_code: pick(["440000", "110000", "310000", "330000", "510000"], i), city_code: pick(["440100", "110100", "310100", "330100", "510100"], i),
      district_code: pick(["440106", "110105", "310115", "330106", "510107"], i), device_model: pick(devices, i),
      device_sn: `MOCK-SN-${String(i).padStart(6, "0")}`, os_version: pick(["12-MOCK","13-MOCK","14-MOCK","15-MOCK"],i), network_type: pick(["WIFI", "CELLULAR", "ETHERNET", "UNKNOWN"], i),
      screen_orientation: pick(["PORTRAIT","LANDSCAPE"],i), app_session_id:`MOCK-SESSION-${String(Math.floor(i/5)).padStart(5,"0")}`,
      battery_level: 15+(i%86), storage_available_mb: 512+(i%32)*256, trigger_type:pick(["IN_APP_BUTTON","CODE","FLOAT_WINDOW"],i),
      input_methods:pick(["TEXT,LOG","TEXT,SCREENSHOT,LOG","TEXT,SCREEN_RECORDING,LOG","VOICE,SCREENSHOT,LOG"],i), title: t.title,
      description: t.description, contact_allowed: i % 3 === 0, cross_channel_cluster: t.cluster, expected_outcome: outcome,
      diagnostic_context:{app_internal_logs:`[SIMULATED] event=seed_operation index=${i}`,crash_stack:i%17===0?"com.mock.DemoException: synthetic stack only":null,page_route:`/mock/${pick(modules,i).toLowerCase()}`,state_machine:`STATE_${i%4}`,private_file_manifest:"NO_REAL_FILES"},
      client_privacy_filter_expected:i%11===0,environment_collection_expected:true,progress_query_expected:true,
      attachment: i % 25 === 0 ? { type: "INVALID_TYPE", size_bytes: 1024, expected_outcome: "REJECT" } : (i % 10 === 0 ? { type: pick(["SCREENSHOT","SCREEN_RECORDING","LOG"],i), size_bytes: 4096 + i, expected_outcome: "RETRY_THEN_PASS" } : null),
      transport: { needs_encryption: true, signature_mode: i % 43 === 0 ? "INVALID" : "VALID", nonce_mode: i % 47 === 0 ? "REUSED" : "UNIQUE", fixture_token: Buffer.from(`20260805:${i}`).toString("base64url") }
    };
    if (outcome === "MISSING_REQUIRED_FIELD") row.description = "";
    if (outcome === "INVALID_DATE") row.source_event_time = "2026-99-99 25:61";
    if (outcome === "INVALID_ENUM") row.severity_level = "SEV-UNKNOWN";
    if (outcome === "FORMAT_ERROR") row.province_code = "广东省";
    rows.push(row); scenarios.push(["SDK", id, outcome, t.cluster]);
  }
  return rows;
}

const jiraHeaders = ["问题编号", "问题标题", "问题描述", "创建时间", "更新时间", "状态", "优先级", "项目Key", "经办人", "报告人", "功能模块", "问题类型", "可重现性", "应用包名", "应用版本", "设备型号"];
function jiraRows(count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const t = issueText(i, "JIRA"); const outcome = expected(i);
    let id = `FS-${1000 + i}`; if (outcome === "DUPLICATE_RECORD" && i) id = `FS-${999 + i}`;
    const time = outcome === "INVALID_DATE" ? "not-a-date" : iso(i, 53);
    const row = [id, t.title, outcome === "MISSING_REQUIRED_FIELD" ? "" : t.description, time, outcome === "STALE_UPDATE" ? "2025-01-01T00:00:00Z" : time,
      outcome === "INVALID_ENUM" ? "Mystery" : pick(["Open", "处理中", "Resolved", "Reopened"], i), pick(["Highest", "High", "Medium", "Low"], i), "MOCK", `模拟经办人${i % 7}`, `模拟报告人${i % 9}`,
      pick(modules, i), pick(["Bug", "缺陷", "内容错误", "硬件"], i), pick(["ALWAYS", "FREQUENT", "OCCASIONAL", "ONCE"], i), pick(apps, i), pick(versions, i), pick(devices, i), outcome, t.cluster];
    rows.push(row.slice(0,-2)); scenarios.push(["JIRA", id, outcome, t.cluster]);
  }
  return rows;
}

const qualityHeaders = ["问题编号", "问题标题", "问题描述", "发生时间", "维修区域", "省份", "城市", "区县", "区域", "申请类型", "换货申请", "退货原因", "发货日期", "功能模块", "可重现性", "应用包名", "应用版本", "设备型号"];
function qualityRows(start, count) {
  return Array.from({ length: count }, (_, j) => { const i = start + j, t = issueText(i, "QUALITY"), outcome = expected(i); let id = `Q-FS-${String(i).padStart(5,"0")}`; if (outcome === "DUPLICATE_RECORD" && i) id = `Q-FS-${String(i-1).padStart(5,"0")}`;
    const time = outcome === "INVALID_DATE" ? "31/31/2026" : iso(i, 61); const row = [id, t.title, outcome === "MISSING_REQUIRED_FIELD" ? "" : t.description, time, pick(["华东","华南","华北"],i), pick(["440000","110000","310000","330000","510000"],i), pick(["440100","110100","310100","330100","510100"],i), pick(["440106","110105","310115","330106","510107"],i), pick(["天河区","朝阳区","浦东新区","西湖区","武侯区"],i), outcome === "INVALID_ENUM" ? "未知申请" : pick(["换机","维修","投诉","咨询"],i), i%4===0?"是":"否", i%5===0?"模拟退货原因":"", time.slice(0,10), pick(modules,i), pick(["ALWAYS","FREQUENT","OCCASIONAL","ONCE"],i), pick(apps,i), pick(versions,i), pick(devices,i), outcome, t.cluster];
    scenarios.push(["QUALITY", id, outcome, t.cluster]); return row.slice(0,-2); });
}

const rbHeaders = ["问题编号", "问题标题", "问题描述", "发布时间", "原始ID", "UID", "作者", "链接", "来源平台", "发布类型", "板块", "红黑榜类型", "是否删除", "功能模块", "问题分类", "可重现性", "应用包名", "应用版本", "设备型号"];
function rbRows(start, count) {
  return Array.from({ length: count }, (_, j) => { const i=start+j,t=issueText(i,"REDBLACK"),outcome=expected(i),praise=i%3!==0; let id=`RB-FS-${String(i).padStart(5,"0")}`; if(outcome==="DUPLICATE_RECORD"&&i)id=`RB-FS-${String(i-1).padStart(5,"0")}`;
    const time=outcome==="INVALID_DATE"?"昨天晚上":iso(i,47); const row=[id,praise?`红榜好评：${pick(modules,i)}体验良好`:t.title,outcome==="MISSING_REQUIRED_FIELD"?"":(praise?`[全量模拟] 操作顺畅；${i%11===0?pick(syntheticSensitive,i):"无身份信息"}`:t.description),time,`ORIG-${10000+i}`,`MOCK-UID-${String(i).padStart(5,"0")}`,`虚构作者${i%11}`,`https://example.test/mock/${i}`,"模拟平台","公开评价",pick(["产品","课程","服务"],i),outcome==="INVALID_ENUM"?"灰榜":(praise?"红榜":"黑榜"),"否",pick(modules,i),praise?"PRAISE":pick(["BUG","CONTENT_ERROR","HARDWARE"],i),pick(["ALWAYS","FREQUENT","OCCASIONAL","ONCE"],i),pick(apps,i),pick(versions,i),pick(devices,i),outcome,t.cluster]; scenarios.push(["REDBLACK",id,outcome,t.cluster]); return row.slice(0,-2); });
}

async function writeWorkbook(filename, sheetName, headers, rows) {
  const wb = Workbook.create(); const sh = wb.worksheets.add(sheetName); sh.showGridLines = false;
  sh.getRangeByIndexes(0,0,rows.length+1,headers.length).values = [headers,...rows];
  sh.getRangeByIndexes(0,0,1,headers.length).format = { fill:"#155E75", font:{bold:true,color:"#FFFFFF"}, rowHeight:28, wrapText:true, verticalAlignment:"center" };
  sh.getRangeByIndexes(1,0,rows.length,headers.length).format = { font:{color:"#172033"}, verticalAlignment:"top" };
  sh.getRangeByIndexes(0,0,rows.length+1,headers.length).format.borders = { insideHorizontal:{style:"thin",color:"#E2E8F0"}, bottom:{style:"thin",color:"#94A3B8"} };
  sh.getRangeByIndexes(0,0,rows.length+1,headers.length).format.autofitColumns();
  for (let c=0;c<headers.length;c++) { const r=sh.getRangeByIndexes(0,c,rows.length+1,1); r.format.columnWidth = Math.min(Math.max(r.format.columnWidth || 10, 11), headers[c].includes("描述")?48:headers[c].includes("标题")?32:22); }
  sh.getRangeByIndexes(1,1,rows.length,2).format.wrapText = true;
  headers.forEach((header,c) => { if (header.includes("时间") || header.includes("日期")) { const dateRange=sh.getRangeByIndexes(0,c,rows.length+1,1); dateRange.format.numberFormat = "yyyy-mm-dd hh:mm:ss"; dateRange.format.columnWidth = 28; } });
  sh.freezePanes.freezeRows(1); sh.freezePanes.freezeColumns(1);
  sh.tables.add(`A1:${String.fromCharCode(64+headers.length)}${rows.length+1}`, true, `${sheetName.replace(/[^A-Za-z0-9]/g,"")}Table`).style = "TableStyleMedium2";
  const out=path.join(root,filename); const x=await SpreadsheetFile.exportXlsx(wb); await x.save(out);
  const inspect=await wb.inspect({kind:"table",range:`${sheetName}!A1:F8`,include:"values,formulas",tableMaxRows:8,tableMaxCols:6,maxChars:3000});
  const errors=await wb.inspect({kind:"match",searchTerm:"#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",options:{useRegex:true,maxResults:50},summary:"formula errors"});
  const preview=await wb.render({sheetName,range:`A1:H18`,scale:1,format:"png"}); await fs.writeFile(path.join(qaDir,`${filename}.png`),new Uint8Array(await preview.arrayBuffer()));
  return {out, inspect:inspect.ndjson, errors:errors.ndjson};
}

const sdk=sdkRows(360); await fs.writeFile(path.join(root,"sdk-feedback-360.ndjson"),sdk.map(x=>JSON.stringify(x)).join("\n")+"\n");
const results=[];
results.push(await writeWorkbook("jira-issues-320.xlsx","JIRA",jiraHeaders,jiraRows(320)));
results.push(await writeWorkbook("quality-platform-a-140.xlsx","QUALITY_A",qualityHeaders,qualityRows(0,140)));
results.push(await writeWorkbook("quality-platform-b-120.xlsx","QUALITY_B",qualityHeaders,qualityRows(140,120)));
results.push(await writeWorkbook("redblack-a-130.xlsx","REDBLACK_A",rbHeaders,rbRows(0,130)));
results.push(await writeWorkbook("redblack-b-110.xlsx","REDBLACK_B",rbHeaders,rbRows(130,110)));

const csv = (rows) => rows.map(r=>r.map(v=>`"${String(v??"").replaceAll('"','""')}"`).join(",")).join("\r\n")+"\r\n";
await fs.writeFile(path.join(root,"expected-results.csv"),"\uFEFF"+csv([["渠道","来源记录ID","预期结果","跨渠道簇"],...scenarios]));
const clusterMap=[["跨渠道簇","标准症状","SDK记录数","Jira记录数","质量记录数","红黑榜记录数"],...clusters.map(c=>[c,clusterSymptom(c),4,4,4,4])];
await fs.writeFile(path.join(root,"cross-channel-clusters.csv"),"\uFEFF"+csv(clusterMap));
await fs.writeFile(path.join(root,"privacy-masking-cases.csv"),"\uFEFF"+csv([["样本编号","虚构敏感信息","类型","预期"],...syntheticSensitive.map((v,i)=>[`PII-${String(i+1).padStart(2,"0")}`,v,pick(["姓名+手机","邮箱","身份证","银行卡","地址","IP+MAC","设备SN+座机","混合","学校+班级+学生姓名"],i),"MASK_OR_REVIEW"])]));
const privacyRegistry = syntheticSensitive.map((value,index)=>({
  fixture_id:`PII-${String(index+1).padStart(2,"0")}`,
  sha256:crypto.createHash("sha256").update(value).digest("hex"),
  exact_value:value,
  purpose:"MASKING_RULE_VALIDATION_ONLY",
  synthetic:true,
  prohibited_for_demo_identity:true
}));
await fs.writeFile(path.join(root,"privacy-fixture-registry.json"),JSON.stringify({schema_version:"privacy-fixture-registry-v1",entries:privacyRegistry},null,2)+"\n");

for (const f of await fs.readdir(root)) if (f.endsWith(".inspect.ndjson")) await fs.unlink(path.join(root,f));
const files=(await fs.readdir(root)).filter(f=>!f.startsWith("manifest")&&f!=="README.md"); const hashes={}; for(const f of files){hashes[f]=crypto.createHash("sha256").update(await fs.readFile(path.join(root,f))).digest("hex");}
const counts=Object.fromEntries(scenarios.reduce((m,r)=>m.set(r[2],(m.get(r[2])||0)+1),new Map()));
const manifest={schema_version:"full-system-fixture-v2",aligned_to:"workspace-latest-2026-08-05",seed:20260805,generated_at:"2026-08-05T00:00:00+08:00",synthetic_data_only:true,contains_real_personal_data:false,database_write_performed:false,counts:{sdk_feedback:360,jira_issues:320,quality_files:2,quality_rows:260,redblack_files:2,redblack_rows:240,cross_channel_clusters:16,masking_cases:9,registered_privacy_fixtures:9},latest_capabilities:["CLIENT_PRIVACY_FILTER","ENVIRONMENT_COLLECTOR","CONFIGURABLE_LOG_BUFFER","FEEDBACK_PROGRESS_QUERY","OUTBOUND_PRIVACY_GATE","UNIFIED_DATA_QUALITY"],expected_outcome_distribution:counts,applications:apps,modules,versions,device_models:devices,files_sha256:hashes};
await fs.writeFile(path.join(root,"manifest.json"),JSON.stringify(manifest,null,2)+"\n");
await fs.writeFile(path.join(root,"README.md"),`# 全系统模拟测试数据（最新版 v2）\n\n本目录仅包含确定性生成的模拟数据，不含真实学生、家长、设备、客服或员工身份信息，也未写入后端数据库。\n\n- SDK：360 条 NDJSON\n- Jira：320 条 XLSX\n- 质量平台：2 份 XLSX，共 260 条\n- 红黑榜：2 份 XLSX，共 240 条\n- 跨渠道重复问题簇：16 个，每簇在四个渠道各 4 条\n- 异常覆盖：缺必填、非法枚举、非法日期、格式错误、重复记录、陈旧更新、脱敏复核、附件拒绝/重试、签名失败、nonce 重放\n- 最新能力：客户端隐私过滤、设备环境采集、可配置日志窗口、反馈进度查询、出站隐私门禁、统一数据质量快照\n\n导入时请使用各文件首个工作表。业务导入文件只包含渠道字段；测试预期和跨渠道金标准保存在 expected-results.csv 与 cross-channel-clusters.csv，不混入生产字段映射。格式逼真的敏感样例必须登记在 privacy-fixture-registry.json，且仅用于脱敏门禁验证。\n`);
console.log(JSON.stringify({root,qaDir,files:(await fs.readdir(root)).sort(),checks:results.map(r=>({out:r.out,errors:r.errors}))},null,2));
