import { describe, expect, it } from "vitest";
import { toLocalDateTimeString, truncateDateTimeToSeconds } from "./datetime";

describe("truncateDateTimeToSeconds", () => {
  it("removes fractional seconds and preserves timezone information", () => {
    expect(truncateDateTimeToSeconds("2026-07-31T14:46:00.393216")).toBe(
      "2026-07-31T14:46:00",
    );
    expect(truncateDateTimeToSeconds("2026-07-31T06:46:00.393Z")).toBe(
      "2026-07-31T06:46:00Z",
    );
    expect(truncateDateTimeToSeconds("2026-07-31 14:46:00.1+08:00")).toBe(
      "2026-07-31 14:46:00+08:00",
    );
  });

  it("normalizes timestamps recursively without changing ordinary text", () => {
    expect(
      truncateDateTimeToSeconds({
        createdAt: "2026-07-31T14:46:00.123456",
        title: "版本 1.2.3",
      }),
    ).toEqual({ createdAt: "2026-07-31T14:46:00", title: "版本 1.2.3" });
  });

  it("preserves binary download responses", () => {
    const blob = new Blob(["template"], { type: "text/csv" });
    expect(truncateDateTimeToSeconds(blob)).toBe(blob);
  });
});

describe("toLocalDateTimeString", () => {
  it("preserves browser local clock fields without appending a timezone", () => {
    const value = new Date(2026, 7, 1, 19, 8, 7, 6);
    expect(toLocalDateTimeString(value)).toBe("2026-08-01T19:08:07.006");
  });
});
