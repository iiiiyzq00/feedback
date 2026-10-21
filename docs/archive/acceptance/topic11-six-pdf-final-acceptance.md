# 专题 11：六类 PDF 最终验收

## 闭环结论

专题 11 已达到实习生项目最终汇报程度。每日接入、每周趋势、版本观察、质量关联、红黑榜分析、风险简报六类报告均沿用同一结构化 PDF 生成器，并完成特殊字符、超宽表格、极端行数、字体配置和页面渲染门禁。

## 本次补齐

- 字体加载后校验中文、ASCII 和常用中文标点字形；缺少关键字形时阻断生成。
- `prod` 环境必须显式配置 `report.pdf.font-path` 或 `REPORT_PDF_FONT`，应用启动时即检查文件存在，不允许依赖开发机字体回退。
- 控制字符、零宽字符和 BOM 不进入 PDF；各种 Unicode 长横线统一为 ASCII 连字符。
- 字体不支持的补充平面符号以 `[符号]` 可读文本替代，不再输出缺字方框。
- 表格超过 6 列时自动分为多个字段组，避免列宽过窄导致不可阅读。
- 单节最多展示 2000 行，超过时明确披露截断和受控导出路径，不静默丢失。
- 单元格高度设置安全上限，超长内容由既有换行与裁切策略保护页面布局。

## 六类报告

| 编码 | 中文名称 |
|---|---|
| `DAILY_INGEST` | 每日数据接入报告 |
| `WEEKLY_TREND` | 每周多渠道趋势报告 |
| `VERSION_RELEASE` | 版本上线观察报告 |
| `QUALITY_LINKAGE` | 质量关联报告 |
| `REDBLACK_ANALYSIS` | 红黑榜分析报告 |
| `RISK_BRIEF` | 风险与舆情简报 |

## 验证证据

- `ReportPdfServiceTest` 验证治理快照、特殊字符、260 行 11 列大表、字段分组、页数、尾行和生产字体门禁。
- 六类既有验收 PDF 使用 `pdfinfo` 检查结构，并通过 Poppler 渲染为 PNG。
- 特殊字符大报告输出到 `output/pdf/topic11-special-large-acceptance.pdf`，重新打开并提取文本后确认无缺字方框。
- 最终 PNG 进行人工视觉检查，关注封面、表头续页、页码、长表格和中文字符。

## 发布环境字体门禁

发布环境必须提供可读的中文字体文件，并设置以下任一项：

```text
report.pdf.font-path=/opt/feedback/fonts/NotoSansCJK-Regular.ttc
REPORT_PDF_FONT=/opt/feedback/fonts/NotoSansCJK-Regular.ttc
```

缺少显式路径时报 `REPORT_PDF_FONT_EXPLICIT_CONFIG_REQUIRED`；文件不存在时报 `REPORT_PDF_FONT_NOT_FOUND`；关键中文字形缺失时报 `REPORT_PDF_FONT_GLYPH_MISSING`。

## 汇报边界

本结论覆盖项目模拟数据、结构正确性和本机 Poppler 渲染；上线前仍需在最终容器镜像内复跑字体路径与六类真实样本，但配置缺失已由启动后首次导出门禁明确阻断，不会生成乱码 PDF。
