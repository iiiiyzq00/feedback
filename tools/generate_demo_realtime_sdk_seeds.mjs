import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";

const out="/Users/yang/projects/feedback-platform/data/test-fixtures/sdk-realtime-demo-seeds-20260805";
await fs.mkdir(out,{recursive:true});
const attachmentModes=["SCREENSHOT","SCREEN_RECORDING","SCREENSHOT_AND_RECORDING","SCREENSHOT","SCREEN_RECORDING"];
const commonTypes=["功能异常","卡顿闪退","内容错误","显示异常","交互问题","网络异常","数据同步","权限异常","兼容性","性能问题"];
const health=[
 ["HEALTH_OVERVIEW","总览首次打开白屏","冷启动后总览区域短暂白屏，卡片延迟出现"],
 ["HEALTH_OVERVIEW","步数卡片与趋势不一致","今日步数与七日趋势最后一列数值不一致"],
 ["ACTIVITY_RECORDS","运动记录列表卡顿","快速滚动运动记录时出现明显掉帧"],
 ["ACTIVITY_RECORDS","新增记录按钮无响应","连续点击新增运动记录后页面没有响应"],
 ["HEALTH_VITALS","身体指标刷新失败","切换网络后身体指标仍显示旧状态"],
 ["HEALTH_VITALS","心率卡片排版溢出","大字体模式下单位文字覆盖指标数值"],
 ["SLEEP_ANALYSIS","睡眠分段显示错误","睡眠阶段颜色图例与时间轴不一致"],
 ["SLEEP_ANALYSIS","睡眠页面返回缓慢","从睡眠详情返回总览需要等待数秒"],
 ["HYDRATION","饮水提醒重复弹出","同一次饮水记录触发了两次提醒"],
 ["HEALTH_OVERVIEW","横竖屏切换错位","旋转设备后健康建议卡片位置偏移"],
 ["HEALTH_OVERVIEW","弱网加载一直转圈","弱网恢复后页面仍停留在加载状态"],
 ["ACTIVITY_RECORDS","离线记录未同步","离线新增的模拟运动记录联网后未出现"],
 ["HEALTH_VITALS","权限拒绝提示不清晰","拒绝传感器权限后没有明确恢复入口"],
 ["SLEEP_ANALYSIS","深色模式文字不可见","深色模式下睡眠图例文字对比度过低"],
 ["HEALTH_OVERVIEW","连续切换模块闪退","快速切换四个模块后应用异常退出"],
 ["ACTIVITY_RECORDS","筛选条件被重置","返回列表后之前选择的日期筛选丢失"],
 ["HEALTH_VITALS","小数精度显示异常","体重模拟值显示过多小数位"],
 ["SLEEP_ANALYSIS","时间轴跨日错误","跨午夜的模拟睡眠记录被拆分到错误日期"],
 ["HEALTH_OVERVIEW","反馈入口响应慢","点击反馈按钮后弹窗延迟出现"],
 ["HEALTH_OVERVIEW","截图含敏感区域验证","验证截图涂抹、删除和重新截图流程"]
];
const art=[
 ["DRAWING_CANVAS","画布首次打开空白","进入绘画页后画布与工具栏延迟显示"],
 ["DRAWING_CANVAS","快速绘制明显掉帧","连续快速画线时笔迹跟手性变差"],
 ["DRAWING_CANVAS","粗细画笔切换无效","切换粗画笔后实际线宽没有变化"],
 ["DRAWING_CANVAS","颜色状态不同步","选择天空蓝后工具栏仍显示旧颜色"],
 ["DRAWING_CANVAS","清空后笔迹残留","点击清空画布后边缘仍残留部分线条"],
 ["DRAWING_CANVAS","撤销恢复顺序错误","多次撤销与恢复后笔迹顺序发生变化"],
 ["DRAWING_CANVAS","多点触控产生断线","双指触摸后继续绘制出现断裂"],
 ["DRAWING_CANVAS","横屏工具栏遮挡","横屏时右侧工具栏覆盖画布区域"],
 ["DRAWING_CANVAS","大字体按钮截断","系统大字体下按钮文字显示不完整"],
 ["DRAWING_CANVAS","深色模式对比度低","深色模式下颜色名称难以辨认"],
 ["DRAWING_CANVAS","保存作品无响应","点击保存后长时间没有状态提示"],
 ["DRAWING_CANVAS","弱网保存重复提交","网络抖动时同一作品产生两次保存动作"],
 ["DRAWING_CANVAS","离线反馈待补传","离线提交反馈后恢复网络验证历史补传"],
 ["DRAWING_CANVAS","录屏过程旋转屏幕","录屏期间旋转设备验证会话恢复"],
 ["DRAWING_CANVAS","录屏权限被拒绝","拒绝系统录屏授权后验证错误提示"],
 ["DRAWING_CANVAS","后台返回画布重置","切到后台再返回后模拟笔迹丢失"],
 ["DRAWING_CANVAS","超长绘画性能下降","大量笔迹后缩放和继续绘制明显变慢"],
 ["DRAWING_CANVAS","边缘绘制坐标偏移","靠近画布右下角时笔迹与触点不一致"],
 ["DRAWING_CANVAS","反馈入口响应慢","点击反馈按钮后弹窗延迟出现"],
 ["DRAWING_CANVAS","截图隐私标注验证","验证截图圈选、涂抹、重拍与取消上传"]
];

function build(app,packageName,appVersion,items){return items.map((x,i)=>({
 seed_id:`${app}-${String(i+1).padStart(3,"0")}`,synthetic_data_only:true,package_name:packageName,app_version:appVersion,
 function_module:x[0],issue_type:commonTypes[i%commonTypes.length],title:x[1],description:`[SDK实时模拟种子] ${x[2]}；不含真实用户或设备信息。`,
 attachment_mode:attachmentModes[i%attachmentModes.length],capture_current_activity:true,recording_max_seconds:i%3===0?10:i%3===1?20:30,
 network_profile:["WIFI","CELLULAR","OFFLINE_THEN_RECOVER","WEAK_NETWORK","WIFI"][i%5],device_profile:["XT11A-MOCK","XT12-MOCK","PAD-A-MOCK","PAD-B-MOCK"][i%4],
 operation_steps:[`打开${x[0]}对应页面`,`执行场景：${x[1]}`,"打开 SDK 反馈弹窗",attachmentModes[i%attachmentModes.length].includes("SCREEN_RECORDING")?"授权系统录屏并复现操作":"确认自动截图并按需标注","提交并观察 queued/accepted 回调"],
 log_upload:{automatic:true,type:"LOG",source:"SDK_AND_HOST_RING_BUFFER",window_minutes_from_remote_config:"1-60",reads_logcat:false,client_privacy_filter:true},
 environment_collection:{automatic:true,fields:["device_model","network_type","battery_level","storage_available_mb"]},
 expected_pipeline:["CLIENT_PRIVACY_FILTER_APPLIED","ENVIRONMENT_COLLECTED","HOST_LOG_CAPTURED","BODY_ENCRYPTED_AND_QUEUED","ATTACHMENT_ENCRYPTED",attachmentModes[i%attachmentModes.length].includes("SCREEN_RECORDING")?"REAL_SCREEN_RECORDING_UPLOADED":"REAL_SCREENSHOT_UPLOADED","LOG_UPLOADED","ACK_OR_RETRY_OBSERVED","PROGRESS_QUERY_VERIFIED"],
 business_context:{data_classification:`SIMULATED_${app}_DATA`,seed_id:`${app}-${String(i+1).padStart(3,"0")}`,realtime_seed_run:"20260805",contains_real_identity:"false"}
}));}
const datasets={"health-demo-realtime-seeds.json":build("HEALTH","com.feedback.healthdemo","1.0.0-seed",health),"child-art-demo-realtime-seeds.json":build("CHILD_ART","com.feedback.childartdemo","1.0.0-seed",art)};
for(const [name,data] of Object.entries(datasets))await fs.writeFile(path.join(out,name),JSON.stringify(data,null,2)+"\n");
const index=Object.entries(datasets).flatMap(([file,rows])=>rows.map(r=>({seed_id:r.seed_id,file,title:r.title,module:r.function_module,issue_type:r.issue_type,attachment_mode:r.attachment_mode,network_profile:r.network_profile})));
await fs.writeFile(path.join(out,"seed-index.ndjson"),index.map(x=>JSON.stringify(x)).join("\n")+"\n");
const hashes={};for(const name of Object.keys(datasets).concat("seed-index.ndjson"))hashes[name]=crypto.createHash("sha256").update(await fs.readFile(path.join(out,name))).digest("hex");
await fs.writeFile(path.join(out,"manifest.json"),JSON.stringify({schema_version:"sdk-demo-realtime-seed-v2",aligned_to:"workspace-latest-2026-08-05",seed:20260805,synthetic_data_only:true,contains_real_personal_data:false,database_write_performed:false,demo_apps:2,total_scenarios:40,health_scenarios:20,child_art_scenarios:20,latest_capabilities:["CLIENT_PRIVACY_FILTER","ENVIRONMENT_COLLECTOR","CONFIGURABLE_LOG_BUFFER","FEEDBACK_PROGRESS_QUERY"],attachment_distribution:Object.values(datasets).flat().reduce((m,x)=>(m[x.attachment_mode]=(m[x.attachment_mode]||0)+1,m),{}),files_sha256:hashes},null,2)+"\n");
await fs.writeFile(path.join(out,"README.md"),`# 两个 Demo App 的 SDK 实时采集种子（最新版 v2）\n\n全部场景均为模拟数据，不含真实学生、家长、设备、客服或员工身份信息，也不会自动写数据库。\n\n## 内容\n\n- 健康 Demo：20 个场景\n- 儿童绘画 Demo：20 个场景\n- 覆盖自动截图、截图标注、真实系统录屏、自动 LOG 附件、离线补传、弱网重试、权限拒绝、旋转恢复、性能、兼容性、显示、交互、同步及内容错误。\n- 对齐最新 SDK：客户端隐私过滤、设备型号/网络/电量/可用存储自动采集、服务端配置的 1–60 分钟日志窗口、反馈处理进度查询。\n\n## 使用方式\n\n1. 安装并启动对应 Debug Demo，确保 SDK 指向测试环境。\n2. 从对应 JSON 选择 seed，按 operation_steps 在 Demo 中复现。\n3. 点击“反馈问题”：SDK 会在弹窗出现前实时截取当前 Activity。\n4. SCREEN_RECORDING / SCREENSHOT_AND_RECORDING 场景必须点击录屏并同意 Android 系统授权；录屏必须来自真机/模拟器实际操作，不使用伪造媒体。\n5. 提交后检查 queued、accepted 或 retry 回调，查询 progress，并核对截图/录屏/LOG 三类附件。\n\n这些文件是场景种子和预期，不是服务端入库数据。\n`);
console.log(JSON.stringify({out,total:index.length,files:await fs.readdir(out)},null,2));
