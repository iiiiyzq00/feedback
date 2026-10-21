const DATE_TIME_WITH_FRACTION =
  /^(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})\.\d+(Z|[+-]\d{2}:?\d{2})?$/;

/** 去掉回给 Web 展示的时间戳里的亚秒精度。 */
export function truncateDateTimeToSeconds<T>(value: T): T {
  if (typeof value === "string") {
    const match = value.match(DATE_TIME_WITH_FRACTION);
    return (match ? `${match[1]}${match[2] ?? ""}` : value) as T;
  }
  if (Array.isArray(value)) return value.map(truncateDateTimeToSeconds) as T;
  if (
    value &&
    typeof value === "object" &&
    Object.getPrototypeOf(value) === Object.prototype
  ) {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        truncateDateTimeToSeconds(item),
      ]),
    ) as T;
  }
  return value;
}

/** 把浏览器 Date 序列化成后端按本地业务时间存储的字段。 */
export function toLocalDateTimeString(value: Date): string {
  const pad = (number: number, width = 2) =>
    String(number).padStart(width, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}.${pad(value.getMilliseconds(), 3)}`;
}
