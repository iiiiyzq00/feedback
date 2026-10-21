import { describe, expect, it } from "vitest";
import { privacyStatusLabel } from "./privacyStatus";

describe("privacyStatusLabel", () => {
  it.each([
    ["PENDING", "处理中"],
    ["PASS", "已安全放行"],
    ["REVIEW_NEEDED", "待人工复核"],
    ["FAIL", "处理失败"],
    ["FAILED", "处理失败"],
  ])("translates %s", (status, label) => {
    expect(privacyStatusLabel(status)).toBe(label);
  });

  it("does not expose an unknown raw status", () => {
    expect(privacyStatusLabel("NEW_STATUS")).toBe("未知状态");
  });
});
