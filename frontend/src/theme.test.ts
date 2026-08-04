import { describe, expect, it } from "vitest";
import { accentMainColor, createAppTheme } from "./theme";

describe("app theme", () => {
  it("uses the selected accent for the MUI primary palette", () => {
    expect(accentMainColor("blue")).toBe("#1976d2");
    expect(createAppTheme("light", "teal").palette.primary.main).toBe("#00796b");
    expect(createAppTheme("light", "orange").palette.primary.main).toBe("#ed6c02");
    expect(createAppTheme("light", "yellow").palette.primary.main).toBe("#fbc02d");
    expect(createAppTheme("light", "red").palette.primary.main).toBe("#d32f2f");
    expect(createAppTheme("light", "pink").palette.primary.main).toBe("#c2185b");
    expect(createAppTheme("light", "grey").palette.primary.main).toBe("#616161");
    expect(createAppTheme("light", "black").palette.primary.main).toBe("#000000");
    expect(createAppTheme("light", "neon").palette.primary.main).toBe("#39ff14");
  });

  it("creates distinct light and dark surfaces", () => {
    expect(createAppTheme("light", "blue").palette.background.default).toBe("#eceff1");
    expect(createAppTheme("dark", "blue").palette.background.default).toBe("#121212");
    expect(createAppTheme("dark", "blue").palette.background.paper).toBe("#1e1e1e");
  });
});
