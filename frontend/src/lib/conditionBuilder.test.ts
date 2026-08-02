import { describe, expect, it } from "vitest";
import { generateAviator, parseAviator } from "./conditionBuilder";

describe("conditionBuilder", () => {
  it("round trips nested generated expressions", () => {
    const script = "(data.amount >= 1000 && (data.country == 'PH' || data.country == 'SG'))";
    const parsed = parseAviator(script);
    expect(parsed).not.toBeNull();
    expect(generateAviator(parsed!)).toBe(script);
  });

  it("preserves escaped apostrophes and backslashes", () => {
    const script = "(data.name == 'O\\'Brien' && data.path == 'C:\\\\temp')";
    const parsed = parseAviator(script);
    expect(parsed).not.toBeNull();
    expect(generateAviator(parsed!)).toBe(script);
  });

  it("does not claim unsupported raw syntax", () => {
    expect(parseAviator("data.amount + 1 > 10")).toBeNull();
  });

  it("round trips contains and not-contains operators", () => {
    const script = "(string.contains(data.message, 'failed') && !string.contains(data.message, 'test'))";
    const parsed = parseAviator(script);
    expect(parsed).not.toBeNull();
    expect(generateAviator(parsed!)).toBe(script);
  });
});
