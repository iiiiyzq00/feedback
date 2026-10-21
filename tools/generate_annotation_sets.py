#!/usr/bin/env python3
"""生成标注集骨架 v1（分类/归簇/脱敏），数据来自脱敏真实样本。

用法: venv/bin/python tools/generate_annotation_sets.py
输出: data/annotation-sets/{classify,cluster,masking}/*.json

标注说明:
- 类别枚举: BUG / HARDWARE / CONTENT_ERROR / DEMAND / CONSULT / PRAISE / COMPLAINT
- 严重度（severity-policy-v2，2026-08-06 用户视角修订；代码落地待下一步）:
  S0=人身安全/隐私泄露/不可恢复数据丢失/大范围核心不可用;
  S1=学习被完全阻断（无法开机/上课闪退/自动关机/无法登录）或硬件需送修换机;
  S2=明显降级但有替代路径（断网可热点/内容错误/数据不同步/卡顿）;
  S3=非阻断体验、一般咨询/需求建议/无证据投诉; S4=纯表扬。
- 归簇文件中的 predicted* 字段为占位值，正式评测前必须替换为系统真实预测。
"""

import json
import hashlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "data" / "annotation-sets"

# (caseId, channel, title, description, category, severity, clusterId)
# 来源: SDK=demo 应用真实上报(DB t_feedback_content, PASS 脱敏);
#       JIRA=已过滤400问题(2026-07-15); QUALITY=质量数据.xlsx; REDBLACK=红黑榜数据.csv
CASES = [
    # ---------- SDK: 绘画/健康 demo 应用真实上报（DB 33413~33432） ----------
    ("sdk-01", "SDK", "反馈弹窗打开慢",
     "点击反馈按钮后弹窗出现速度较慢", "BUG", "S3", "C17"),
    ("sdk-02", "SDK", "横屏画布排版偏移",
     "横屏画布右侧边缘排版出现偏移", "BUG", "S2", "C08"),
    ("sdk-03", "SDK", "画笔切换后预览错误",
     "粗细画笔切换后预览内容显示错误", "BUG", "S2", "C08"),
    ("sdk-04", "SDK", "笔迹数量提示不更新",
     "返回绘画页面后笔迹数量提示没有变化", "BUG", "S2", "C08"),
    ("sdk-05", "SDK", "切换画笔颜色卡顿",
     "快速切换画笔颜色时画布出现卡顿", "BUG", "S2", "C17"),
    ("sdk-06", "SDK", "清空按钮不显眼",
     "清空画布按钮的位置不够容易发现", "DEMAND", "S3", "C16"),
    ("sdk-07", "SDK", "连续绘制响应慢",
     "连续绘制多条线时页面响应较慢", "BUG", "S2", "C17"),
    ("sdk-08", "SDK", "颜色名称提示不直观",
     "选择天空蓝后状态提示颜色名称不直观", "DEMAND", "S3", "C16"),
    ("sdk-09", "SDK", "粗画笔绘制卡顿",
     "切换粗画笔后绘制偶尔出现短暂卡顿", "BUG", "S3", "C17"),
    ("sdk-10", "SDK", "健康页反馈弹窗慢",
     "健康总览点击反馈按钮后弹窗出现速度较慢", "BUG", "S3", "C17"),
    ("sdk-11", "SDK", "总览截图排版偏移",
     "总览页面截图区域排版出现偏移", "BUG", "S2", "C08"),
    ("sdk-12", "SDK", "健康建议卡片内容错误",
     "健康建议卡片偶尔显示内容错误", "CONTENT_ERROR", "S2", "C08"),
    ("sdk-13", "SDK", "饮水数据提示不更新",
     "饮水数据刷新后提示没有及时变化", "BUG", "S2", "C17"),
    ("sdk-14", "SDK", "切换健康模块卡顿",
     "连续切换健康模块时页面出现卡顿", "BUG", "S2", "C17"),
    ("sdk-15", "SDK", "身体指标说明不清晰",
     "身体指标模块的说明文字不够清晰", "DEMAND", "S3", "C16"),
    ("sdk-16", "SDK", "返回总览响应慢",
     "睡眠分析页面返回总览时响应较慢", "BUG", "S3", "C17"),
    ("sdk-17", "SDK", "步数卡片与趋势图不一致",
     "今日步数卡片与趋势图展示不一致", "CONTENT_ERROR", "S2", "C08"),
    ("sdk-18", "SDK", "运动记录切换卡顿",
     "切换到运动记录后偶尔出现短暂卡顿", "BUG", "S3", "C17"),
    ("sdk-19", "SDK", "健康页首次加载慢",
     "健康总览首次打开时卡片加载较慢", "BUG", "S3", "C17"),
    ("sdk-20", "SDK", "画布工具栏加载慢",
     "画布首次打开时工具栏加载较慢", "BUG", "S3", "C17"),

    # ---------- JIRA: 400 渠道真实工单（2026-04~05 市场反馈） ----------
    ("jira-01", "JIRA", "VIPKID优学上课闪退",
     "使用VIPKID优学上课经常出现闪退，软件方反馈是设备 WebView 版本过低，应用商店无该软件可升级，不定时录制不到视频", "BUG", "S1", "C05"),
    ("jira-02", "JIRA", "关机状态充电界面反复",
     "设备开机状态下可稳定充电，关机状态下充电会反复出现充电界面", "HARDWARE", "S2", "C03"),
    ("jira-03", "JIRA", "PollyClass课件播放不全",
     "使用PollyClass APP课件内容播放不全，一句英文掐头去尾只播放中间部分，家长反馈上网课始终不成功", "CONTENT_ERROR", "S2", "C08"),
    ("jira-04", "JIRA", "未使用自动亮屏消耗时长",
     "不使用的情况下设备自动亮屏消耗今日可用时间，小程序出现亮屏20分钟的报告，关闭皮套唤醒后仍出现", "BUG", "S2", "C12"),
    ("jira-05", "JIRA", "使用软件自动跳转设置",
     "使用软件会自动跳转到设置界面，无提示无征兆，不同软件都出现，开启DNS后正常一段时间又出现", "BUG", "S2", "C17"),
    ("jira-06", "JIRA", "佳音英语自动关机白屏",
     "使用佳音英语时设备自动关机，软件打开白屏，重启仍复现", "HARDWARE", "S1", "C05"),
    ("jira-07", "JIRA", "临时延长使用时间失效",
     "临时延长设备使用30分钟，设定单次15分钟休息20分钟，实际仅使用5-6分钟就提示需要休息", "BUG", "S2", "C12"),
    ("jira-08", "JIRA", "正常充电开机后自动关机",
     "设备可正常充电和开机，但过几分钟会自动关机，无法准确捕捉关机时间，原装适配器仍复现", "HARDWARE", "S1", "C04"),
    ("jira-09", "JIRA", "多次自动关机短按无法亮屏",
     "多次出现自动关机，短按无法亮屏，长按电源键才能重新开机，检测无DDR问题", "HARDWARE", "S1", "C04"),
    ("jira-10", "JIRA", "W3黑屏无法亮屏",
     "设备多次出现黑屏，短按电源键无法亮屏，需等待几分钟重新按动才会亮屏", "HARDWARE", "S1", "C01"),
    ("jira-11", "JIRA", "AI辅导作业批改一直加载",
     "AI辅导位置的作业批改无法使用，拍摄题目后一直处于加载状态，清理缓存切换热点无效", "BUG", "S1", "C17"),
    ("jira-12", "JIRA", "连接家里WiFi经常断网",
     "学习机连接家里WiFi经常性断网，其他设备正常，连接手机热点正常，开启DNS优化和网络自愈无效", "BUG", "S2", "C06"),
    ("jira-13", "JIRA", "弈小象一直加载无法登录",
     "登录弈小象APP一直卡在玩命加载中界面无法登录，切换热点、重启、清缓存、重装均无效，手机登录正常", "BUG", "S1", "C05"),
    ("jira-14", "JIRA", "检查更新不到新系统版本",
     "检查更新不到新系统版本，重启和交叉测试热点无效，守护平台显示设备在线", "BUG", "S3", "C17"),
    ("jira-15", "JIRA", "咿啦绘本模块消失",
     "T2咿啦绘本模块不见了，桌面的咿啦看书软件显示未注册，系统软件版本均最新", "BUG", "S2", "C05"),
    ("jira-16", "JIRA", "设备耗电快",
     "反馈设备耗电快，待机状态从12点到15点多接近满电掉到66%，非首次反馈", "HARDWARE", "S2", "C13"),
    ("jira-17", "JIRA", "微信通话发出蜂鸣声",
     "使用微信视频通话以及自带音频通话时设备发出蜂鸣声，调整距离关闭麦克风后仍异常", "HARDWARE", "S2", "C09"),
    ("jira-18", "JIRA", "相机屡次停止运行",
     "学习机相机显示屡次停止运行，重启、清理数据缓存、恢复出厂设置均无效", "BUG", "S2", "C05"),
    ("jira-19", "JIRA", "充电很慢电流异常",
     "充电很慢，重启后电流掉到-971mA，耗电曲线正常", "HARDWARE", "S2", "C03"),
    ("jira-20", "JIRA", "游戏时长未被记录",
     "孩子玩游戏两个小时，但电池耗电和APP时长管理中都没有显示，家长询问原因", "BUG", "S2", "C12"),
    ("jira-21", "JIRA", "打卡奖励碎片未到账",
     "计划岛任务孩子打卡14天后对应的奖励碎片没有到账，重启和重新登录无效，观察24小时仍未到账", "BUG", "S2", "C11"),
    ("jira-22", "JIRA", "文件传输记录无法删除",
     "文件传输助手传输的文件在学习机端无法删除，删除本地文件后传输记录还在，打开会自行重新下载", "BUG", "S2", "C17"),
    ("jira-23", "JIRA", "赠送大圣套装未收到",
     "活动说明3月31日前下载使用希沃家长圈APP可在4月1日收到大圣套装，到期打开空间站未收到，重新登录一样", "CONSULT", "S3", "C11"),

    # ---------- QUALITY: 质量平台商返数据（2026-05） ----------
    ("q-01", "QUALITY", "XP34A-E 碎屏",
     "学生平板碎屏，可开机触摸使用，申请返厂维修", "HARDWARE", "S1", "C10"),
    ("q-02", "QUALITY", "XP34A-E 无法充电",
     "无法充电且无法开机，指示灯不亮，测试其他充电配件故障依旧", "HARDWARE", "S1", "C03"),
    ("q-03", "QUALITY", "XP34A-E 花屏",
     "设备花屏故障，重启无效，返厂维修", "HARDWARE", "S1", "C01"),
    ("q-04", "QUALITY", "XP34A-E 屏幕偏暗",
     "屏幕偏暗，根据视频确认属实，寄修处理", "HARDWARE", "S2", "C01"),
    ("q-05", "QUALITY", "XP34-JC-E 开机卡logo",
     "学习机开机卡在logo界面，返厂维修", "HARDWARE", "S1", "C02"),
    ("q-06", "QUALITY", "XP34-JC-E 无法开机",
     "学习机无法开机，返厂维修", "HARDWARE", "S1", "C02"),
    ("q-07", "QUALITY", "XPV21-A-E 机身变形",
     "机身弯曲变形，申请检测与返厂", "HARDWARE", "S2", "C10"),
    ("q-08", "QUALITY", "XP34-JC-E 触屏无法使用",
     "触屏坏掉，无法触摸使用，设备无库存优先返厂维修", "HARDWARE", "S1", "C07"),
    ("q-09", "QUALITY", "XPV21-C-E 黑屏有背光",
     "黑屏但有背光，重启无效，返厂维修", "HARDWARE", "S1", "C01"),
    ("q-10", "QUALITY", "H55EA 触摸跳点",
     "触摸跳点，线上无法解决，返厂检测", "HARDWARE", "S2", "C07"),
    ("q-11", "QUALITY", "XPV21-A-E 外放杂音",
     "外放杂音，重启无效，反馈无磕碰进水，返厂", "HARDWARE", "S2", "C09"),
    ("q-12", "QUALITY", "XPV21-A-E 音量键无反应",
     "学习机音量键减键没有反应，去掉保护壳测试依旧有问题", "HARDWARE", "S2", "C17"),
    ("q-13", "QUALITY", "XPV21-A-E 充电慢",
     "网课学习机充电慢，交叉测试电源线和插座无效，疑似接口问题", "HARDWARE", "S2", "C03"),
    ("q-14", "QUALITY", "XPV21-C-E 多界面亮线",
     "屏幕多界面出现亮线，返厂维修", "HARDWARE", "S2", "C01"),
    ("q-15", "QUALITY", "XPK31-A-E 音频无声充电慢",
     "播放音频没有声音且充电慢，客情考虑商务换新", "HARDWARE", "S1", "C09"),
    ("q-16", "QUALITY", "XP34-JC-E 屏幕发红",
     "屏幕显示发红，返厂维修", "HARDWARE", "S2", "C01"),
    ("q-17", "QUALITY", "H55EA 开箱漏光",
     "开箱不良漏光，返厂自建单处理", "HARDWARE", "S2", "C01"),
    ("q-18", "QUALITY", "XP34A-E 自动开关机重启",
     "设备自动开关机重启，无法开机，交叉适配器测试后返厂", "HARDWARE", "S1", "C04"),

    # ---------- REDBLACK: 全网舆情（2026-04） ----------
    ("r-01", "REDBLACK", "希沃断头台U盘损坏",
     "希沃断头台果然名不虚传，我的U盘坏了", "HARDWARE", "S3", "C10"),
    ("r-02", "REDBLACK", "HDMI接口设计不合理投屏失败",
     "白板HDMI接口在侧边和黑板紧挨着根本插不上，转换插口也不行，希沃传屏显示网络差投不上", "COMPLAINT", "S2", "C06"),
    ("r-03", "REDBLACK", "W3花屏摔坏维修",
     "学习机W3花屏，拆机发现重摔导致屏幕中间漏液，维修视频记录", "HARDWARE", "S1", "C01"),
    ("r-04", "REDBLACK", "课件打不开磁空间不足",
     "希沃白板课件打不开，显示磁空间不足，询问怎么解决", "BUG", "S2", "C08"),
    ("r-05", "REDBLACK", "白板竖条维修频繁",
     "白板一天之内莫名其妙起竖条，周末花钱维修没过星期三又坏，上课用不了", "HARDWARE", "S1", "C01"),
    ("r-06", "REDBLACK", "希沃漏电",
     "被希沃电出幻觉了，漏电存在安全隐患", "HARDWARE", "S0", "C14"),
    ("r-07", "REDBLACK", "U盘无法识别",
     "优盘无法识别，插到电脑和希沃上都识别不出来，询问哪里能修", "CONSULT", "S3", "C10"),
    ("r-08", "REDBLACK", "设备故障影响课堂",
     "上课时希沃突然出现问题，学生在起哄，整堂课可以用糟糕来形容", "COMPLAINT", "S3", "C17"),
    ("r-09", "REDBLACK", "系统死机桌面乱",
     "教室里动不动就死机的希沃桌面上装了龙虾，无力吐槽", "BUG", "S2", "C17"),
    ("r-10", "REDBLACK", "PPT导入排版全乱",
     "每次PPT导入希沃文字排版和PPT背景全部乱掉", "BUG", "S2", "C08"),
    ("r-11", "REDBLACK", "白板自动关机求助",
     "希沃白板总是自动关机，询问怎么办", "HARDWARE", "S1", "C04"),
    ("r-12", "REDBLACK", "京东店骚扰电话与卡顿",
     "买完学习机后收到骚扰电话要送直播课，疑似售卖顾客信息，且学习机卡顿严重", "COMPLAINT", "S1", "C15"),
    ("r-13", "REDBLACK", "课件少内容大屏不显示",
     "课件电脑上看有内容，一到教室大屏幕就不显示了", "BUG", "S2", "C08"),
    ("r-14", "REDBLACK", "识字任务枯燥缺图形",
     "识字打卡第二阶段没有字形图案配合显得枯燥难记，建议增加图形辅助", "DEMAND", "S3", "C16"),
    ("r-15", "REDBLACK", "白板漏电电傻",
     "希沃白板漏电电傻了，白天被希沃电晚上被数位板电", "HARDWARE", "S0", "C14"),
    ("r-16", "REDBLACK", "白板功能老旧操作麻烦",
     "希沃白板功能老旧，改课件操作麻烦，手机和平板也没办法改课件，兼容性差", "COMPLAINT", "S3", "C16"),
    ("r-17", "REDBLACK", "亲子屏声音异常",
     "看电视中突然声音巨大语速巨快，屏幕上只有一只苍蝇却触发声音变大三倍速", "BUG", "S2", "C09"),
    ("r-18", "REDBLACK", "设备离线得不到解决",
     "学习机设备总离线，客服只会让断网络试热点，过保后问题一直得不到解决", "COMPLAINT", "S2", "C06"),
    ("r-19", "REDBLACK", "物流代签拒绝全额退款",
     "购买学习机物流被代签，退货时商家以签收为由拒绝全额退款", "COMPLAINT", "S2", "C15"),
    ("r-20", "REDBLACK", "触屏没反应求助",
     "白板PC端触屏没反应，强制重启后第三天仍无法使用，只能临时用安卓端", "HARDWARE", "S1", "C07"),

    # ---------- 归簇扩展样本（提高样本量，来源同上） ----------
    ("q-19", "QUALITY", "XP34A-E 碎屏",
     "碎屏，收取图片确认后返厂维修", "HARDWARE", "S1", "C10"),
    ("q-20", "QUALITY", "XPV21-A-E 碎屏",
     "网课学习机碎屏，使用碎屏险返厂维修", "HARDWARE", "S1", "C10"),
    ("q-21", "QUALITY", "XPV21-C-E 碎屏",
     "网课学习机碎屏，使用碎屏险返厂维修", "HARDWARE", "S1", "C10"),
    ("q-22", "QUALITY", "XPK31-A-E 碎屏",
     "网课学习机碎屏，使用碎屏险返厂维修", "HARDWARE", "S1", "C10"),
    ("q-23", "QUALITY", "XP34A-E 无法充电",
     "无法充电，指示灯不亮，测试其他充电配件故障依旧", "HARDWARE", "S1", "C03"),
    ("q-24", "QUALITY", "XPV21-A-E 无法充电",
     "无法充电，交叉测试电源线和插座无效", "HARDWARE", "S1", "C03"),
    ("q-25", "QUALITY", "XPK31-A-E 无法充电",
     "无法充电，交叉测试无效", "HARDWARE", "S1", "C03"),
    ("q-26", "QUALITY", "XP34-JC-E 开机卡logo",
     "设备开机卡logo，返厂维修", "HARDWARE", "S1", "C02"),
    ("q-27", "QUALITY", "XP34-JC-E 无法开机",
     "无法开机，强制重启充电无效，交叉测试电源线插座不行", "HARDWARE", "S1", "C02"),
    ("q-28", "QUALITY", "XP34A-E 外壳变形",
     "外观变形，收取图片后返厂检测维修", "HARDWARE", "S2", "C10"),
    ("q-29", "QUALITY", "XPV21-C-E 脱胶缝隙",
     "屏幕边缘和外壳之间缝隙很大，脱胶，安排换新处理", "HARDWARE", "S2", "C10"),
    ("q-30", "QUALITY", "XP34A-E 黑屏无背光",
     "黑屏无背光有画面，重启无效", "HARDWARE", "S1", "C01"),
    ("q-31", "QUALITY", "XPV21-A-E 触摸断线",
     "触摸异常，提供的故障视频跳屏，TP复位后仍多次出现", "HARDWARE", "S2", "C07"),
    ("q-32", "QUALITY", "XP34A-E 无触摸",
     "设备无触摸，点击屏幕无反应，长按电源键强制重启均无反应", "HARDWARE", "S1", "C07"),
    ("q-33", "QUALITY", "XPV21-C-E 横竖条纹亮暗线",
     "多界面出现亮线，返厂维修", "HARDWARE", "S2", "C01"),
    ("jira-24", "JIRA", "WiFi经常性断网",
     "学习机连接家里WiFi经常性断网，其他设备正常，热点正常，DNS优化网络自愈重启均无效", "BUG", "S2", "C06"),
    ("jira-25", "JIRA", "W20网络问题",
     "学习机连接家里WiFi经常断网，连接热点正常，开启DNS优化和网络自愈无效", "BUG", "S2", "C06"),
    ("jira-26", "JIRA", "W2网络问题",
     "连接家里WiFi无法正常上网，连接热点正常，家里WiFi其他设备正常", "BUG", "S2", "C06"),
    ("jira-27", "JIRA", "设备多次自动开机",
     "设备多次自动开机，按键无挤压情况", "HARDWARE", "S2", "C04"),
    ("jira-28", "JIRA", "V2自动关机",
     "设备自动关机多次出现，学习机还有电，系统软件版本最新", "HARDWARE", "S1", "C04"),
    ("jira-29", "JIRA", "自动关机提示温度过低",
     "设备自动关机并提示环境温度过低，实际环境温度正常，确认是关机不是休眠", "HARDWARE", "S1", "C04"),
    ("jira-30", "JIRA", "V2耗电快",
     "单个软件使用情况下耗电很快，一小时掉电18%，需要确认是否硬件问题", "BUG", "S3", "C13"),
    ("jira-31", "JIRA", "K30耗电异常",
     "设备耗电异常，使用一小时关机，关机前电量91，后设备低电量无法开机", "HARDWARE", "S1", "C13"),
    ("jira-32", "JIRA", "V2Pro充电九小时",
     "电量11充电九个多小时才满电，充电慢", "HARDWARE", "S3", "C03"),
    ("jira-33", "JIRA", "T2充电慢电流异常",
     "充电很慢，重启后电流掉到负值，耗电曲线正常", "HARDWARE", "S2", "C03"),
    ("r-21", "REDBLACK", "数位板漏电",
     "新数位板会漏电，白天被希沃电晚上被数位板电", "HARDWARE", "S0", "C14"),
    ("r-22", "REDBLACK", "希沃漏电了",
     "希沃漏电了", "HARDWARE", "S0", "C14"),
    ("r-23", "REDBLACK", "白板漏电",
     "我的希沃白板漏电了", "HARDWARE", "S0", "C14"),
    ("r-24", "REDBLACK", "维修频繁高拍仪损坏",
     "白板故障维修频繁，学生弄坏高拍仪，上课无法正常使用", "HARDWARE", "S2", "C15"),
    ("r-25", "REDBLACK", "售后评价不佳",
     "6大教学一体机测评中重点提到售后服务不佳", "COMPLAINT", "S3", "C15"),
    ("r-26", "REDBLACK", "设备离线无法解决",
     "学习机设备离线一直得不到解决，客服只会让试热点", "COMPLAINT", "S2", "C06"),
    ("r-27", "REDBLACK", "打不开新课计划",
     "希沃打不开新课计划被迫打断，课堂节奏受影响", "BUG", "S1", "C17"),
    ("r-28", "REDBLACK", "登录不上希沃白板",
     "一段时间不登录就登录不上希沃白板，反馈系统稳定性问题", "BUG", "S1", "C05"),
]

CLUSTERS = {
    "C01": "屏幕显示异常（花屏/黑屏/亮线/偏暗/发红/漏光）",
    "C02": "无法开机与卡Logo",
    "C03": "充电异常（无法充电/充电慢/充电界面反复）",
    "C04": "自动关机与重启",
    "C05": "应用崩溃、白屏与无法登录",
    "C06": "网络连接不稳定",
    "C07": "触摸交互失效",
    "C08": "课件与内容展示问题",
    "C09": "音频与录音异常",
    "C10": "外壳与物理损坏（碎屏/变形/外设损坏）",
    "C11": "账号权益与活动奖励",
    "C12": "使用时长与家长管控",
    "C13": "耗电异常",
    "C14": "漏电与安全隐患",
    "C15": "服务与售后投诉",
    "C16": "体验与建议",
    "C17": "系统功能异常与性能问题",
}


def build_classify(cases):
    body = []
    for cid, ch, title, desc, cat, sev, _ in cases:
        body.append({
            "name": cid,
            "channel": ch,
            "title": title,
            "description": desc,
            "expectedCategory": cat,
            "expectedSeverity": sev,
        })
    return {
        "minimumAccuracy": 0.74,
        "minimumS1Recall": 0.90,
        "minimumSecurityPassRate": 1.0,
        "cases": body,
    }


def build_cluster(cases):
    body = []
    for cid, ch, title, desc, cat, sev, cl in cases:
        body.append({
            "caseId": cid,
            "channel": ch,
            "predictedSame": True,          # 占位值：正式评测前替换为系统真实预测
            "expectedSame": True,           # 金标准：该样本归属某个问题簇
            "decisionType": "AUTO_MATCH",   # 占位值
            "humanOverturned": False,
            "containsIdentitySignal": False,
            "predictedClusterId": cl,       # 占位值：=金标准，需替换
            "expectedClusterId": cl,        # 金标准簇
            "similarity": None,
            "evidence": None,
        })
    return {
        "datasetVersion": "cluster-annot-v1",
        "decisionVersion": "cluster-decision-v1",
        "model": "OPENAI_MODEL_REQUIRED",  # 正式评测前替换为部署批准的 OpenAI 模型 ID
        "shadow": True,
        "evidenceKind": "FIXTURE",
        "annotatedBy": "待填写-标注人",
        "reviewedBy": "待填写-复核人",
        "thresholds": {
            "f1Min": 0.75,
            "errorMergeRateMax": 0.05,
            "humanOverturnRateMax": 0.15,
            "purityMin": 0.80,
            "falseSplitRateMax": 0.15,
            "unclusteredRateMax": 0.15,
        },
        "cases": body,
        "clusterAnnotations": [
            {"clusterId": cid, "verdict": "GOOD", "reason": f"初版金标准簇：{name}"}
            for cid, name in CLUSTERS.items()
        ],
    }


def build_masking():
    # 脱敏标注集以"已知含 PII 的输入 + 期望脱敏结果"为一行；真实文件已是脱敏后输出，
    # 无法反推金标准，因此本文件为格式模板（示例行为合成数据，标注 synthetic=true）。
    return {
        "datasetVersion": "masking-annot-v1",
        "datasetSha256": "待生成：对数据集文件整体计算 SHA-256",
        "evidenceKind": "FIXTURE",
        "storageReference": "data/annotation-sets/masking/masking-dataset-v1.json",
        "sampleCount": 0,
        "positiveCount": 0,
        "negativeCount": 0,
        "boundaryCount": 0,
        "piiApprovalReference": None,
        "expiresAt": "2026-12-31T23:59:59",
        "rowFormat": {
            "caseId": "唯一用例 ID",
            "synthetic": "true=合成示例 / false=真实脱敏样本",
            "category": "PII 类别: CREDENTIAL/ID_CARD/PHONE/LANDLINE/EMAIL/ACCOUNT/...",
            "inputText": "含 PII 的原文（真实样本必须已获授权并脱敏后存储）",
            "expectedMasked": "期望脱敏后的文本",
            "highRisk": "是否高风险类别",
        },
        "exampleRows": [
            {
                "caseId": "mask-format-001", "synthetic": True,
                "category": "PHONE", "highRisk": True,
                "inputText": "联系人张三 电话 13800138000",
                "expectedMasked": "联系人[姓名] 电话[手机号]",
            },
            {
                "caseId": "mask-format-002", "synthetic": True,
                "category": "ID_CARD", "highRisk": True,
                "inputText": "身份证号 110101199003071234",
                "expectedMasked": "身份证号[身份证]",
            },
            {
                "caseId": "mask-format-003", "synthetic": True,
                "category": "LANDLINE", "highRisk": False,
                "inputText": "办公电话 010-88886666",
                "expectedMasked": "办公电话[座机]",
            },
        ],
    }


def main():
    (OUT / "classify").mkdir(parents=True, exist_ok=True)
    (OUT / "cluster").mkdir(parents=True, exist_ok=True)
    (OUT / "masking").mkdir(parents=True, exist_ok=True)

    classify = build_classify(CASES)
    cluster = build_cluster(CASES)
    masking = build_masking()

    for name, obj in [
        ("classify/classify-v1.json", classify),
        ("cluster/cluster-v1.json", cluster),
        ("masking/masking-dataset-v1.json", masking),
    ]:
        path = OUT / name
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {path} ({len(obj['cases']) if 'cases' in obj else 'metadata'})")

    # 覆盖矩阵统计
    from collections import Counter
    by_channel = Counter(c[1] for c in CASES)
    by_cat = Counter(c[4] for c in CASES)
    by_sev = Counter(c[5] for c in CASES)
    by_cluster = Counter(c[6] for c in CASES)
    print("\nchannel:", dict(by_channel))
    print("category:", dict(by_cat))
    print("severity:", dict(by_sev))
    print("clusters:", len(by_cluster), dict(by_cluster))


if __name__ == "__main__":
    main()
