const REGION_NAMES: Record<string, string> = {
  "110000": "北京市",
  "110100": "北京市",
  "110105": "朝阳区",
  "310000": "上海市",
  "310100": "上海市",
  "310115": "浦东新区",
  "330000": "浙江省",
  "330100": "杭州市",
  "330106": "西湖区",
  "440000": "广东省",
  "440100": "广州市",
  "440106": "天河区",
  "510000": "四川省",
  "510100": "成都市",
  "510107": "武侯区",
};

/** 行政区划代码用于查询和权限判断，名称只用于界面展示。 */
export function regionName(code: unknown, fallback = "-") {
  if (code == null || code === "") return fallback;
  const value = String(code);
  if (value === "UNKNOWN") return fallback;
  return REGION_NAMES[value] ?? value;
}
