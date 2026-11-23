// The market map (card M-50): how each state is drawn, search highlighting, the phone list, and the draw time.

import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { StallStatus } from "../api/schema";
import { endSession, startSession } from "../api/client";
import type { Footprint } from "./geometry";
import { contains, GridIndex, pathOf } from "./geometry";
import { MarketMap } from "./MarketMapPage";
import { groupPaths, MarketSvg } from "./MarketSvg";
import { legendFor, outlineFor, styleFor } from "./style";

const C = "11111111-1111-1111-1111-111111111111";
const M = "22222222-2222-2222-2222-222222222222";

function status(state: StallStatus["state"], extra: Partial<StallStatus> = {}): StallStatus {
  return { stallId: "s", code: "SOW-A-1", state, levy: "PAID_UP", depositedTodayNgwee: 0, ...extra };
}

/** A generated plan of n stalls, 30 to a row, 2.5 m square, like the server's generator. */
function generated(n: number): Footprint[] {
  return Array.from({ length: n }, (_, i) => {
    const x = (i % 30) * 2_500;
    const y = Math.floor(i / 30) * 3_000;
    return {
      stallId: `stall-${i}`,
      code: `SOW-${String.fromCharCode(65 + Math.floor(i / 300))}-${(i % 300) + 1}`,
      polygon: [x, y, x + 2_500, y, x + 2_500, y + 2_500, x, y + 2_500],
      provenance: "GENERATED",
      aisle: `${String.fromCharCode(65 + Math.floor(i / 300))}-${Math.floor((i % 300) / 60) + 1}`,
    };
  });
}

describe("state to style", () => {
  it("gives each state its own colour and pattern", () => {
    const free = styleFor("state", status("FREE"));
    const active = styleFor("state", status("ACTIVE"));
    const quiet = styleFor("state", status("QUIET"));
    const suspended = styleFor("state", status("SUSPENDED"));
    expect(new Set([free.key, active.key, quiet.key, suspended.key]).size).toBe(4);
    expect([free.pattern, active.pattern, quiet.pattern, suspended.pattern]).toEqual(["none", "none", "stripes", "cross"]);
    // Active and free must differ by more than colour: free is light, active solid.
    expect(free.fill).not.toBe(active.fill);
  });

  it("levy bands and today's deposits have their own layers", () => {
    expect(styleFor("levy", status("ACTIVE", { levy: "OWED_1_TO_7" })).pattern).toBe("dots");
    expect(styleFor("levy", status("ACTIVE", { levy: "OWED_OVER_7" })).pattern).toBe("stripes");
    expect(styleFor("levy", status("FREE", { levy: "NONE" })).label).toBe("Nobody holds it");
    expect(styleFor("today", status("ACTIVE", { depositedTodayNgwee: 45_000 })).label).toBe("Deposited today");
    expect(styleFor("today", status("QUIET")).label).toBe("Not yet today");
    expect(styleFor("today", status("FREE")).label).toBe("Free");
  });

  it("every legend entry has a pattern or is plainly distinct, and generated outlines are dashed", () => {
    for (const layer of ["state", "levy", "today"] as const) {
      const legend = legendFor(layer);
      expect(new Set(legend.map((s) => s.fill + s.pattern)).size).toBe(legend.length);
    }
    expect(outlineFor("GENERATED")).toBe("dashed");
    expect(outlineFor("SURVEYED")).toBe("solid");
  });

  it("stalls with the same style are drawn as one path", () => {
    const fps = generated(90);
    const st = new Map(fps.map((f, i) => [f.stallId, status(i % 3 === 0 ? "FREE" : "ACTIVE", { stallId: f.stallId })]));
    const drawn = groupPaths({ footprints: fps, status: st, layer: "state", visible: null });
    expect(drawn.groups).toHaveLength(2);
    expect(drawn.groups.find((g) => g.style.key === "state-free")!.codes).toHaveLength(30);
    expect(drawn.solid).toBe("");
    expect(drawn.dashed.length).toBeGreaterThan(0);
  });
});

describe("geometry", () => {
  it("finds the stall under a point", () => {
    const fps = generated(60);
    const index = new GridIndex(fps);
    expect(index.at(1_000, 1_000)?.stallId).toBe("stall-0");
    expect(index.at(3_000 + 1, 3_000 + 1)?.stallId).toBe("stall-31");
    expect(index.at(2_600, 2_700)).toBeUndefined(); // in the gap between rows
    expect(contains([0, 0, 10, 0, 10, 10, 0, 10], 5, 5)).toBe(true);
    expect(pathOf([0, 0, 10, 0, 10, 10])).toBe("M0 0L10 0L10 -10Z");
  });
});

// --------------------------------------------------------------------------------------------- the page

function fakeApi(fps: Footprint[], hits: unknown[] = []) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const path = url.split("?")[0];
      const body =
        path === `/api/v1/councils/${C}/markets/${M}/plan`
          ? {
              id: "p",
              status: "PUBLISHED",
              sections: [{ name: "A", polygonMm: [0, 0, 75_000, 0, 75_000, 9_000, 0, 9_000] }],
              footprints: fps.map((f) => ({ stallId: f.stallId, stallCode: f.code, polygonMm: f.polygon, provenance: f.provenance, aisle: f.aisle })),
              unmappedStallIds: [],
            }
          : path === `/api/v1/councils/${C}/markets/${M}/map-status`
            ? {
                stalls: [
                  ...fps.map((f, i) => ({ stallId: f.stallId, code: f.code, state: i % 2 ? "ACTIVE" : "QUIET", levy: "PAID_UP", stallClass: "STANDARD", traderName: `Trader ${i}` })),
                  { stallId: "lost", code: "SOW-Z-9", state: "FREE", levy: "NONE" },
                ],
              }
            : path === `/api/v1/councils/${C}/search`
              ? hits
              : undefined;
      return body === undefined ? new Response("{}", { status: 404 }) : new Response(JSON.stringify(body), { status: 200 });
    }),
  );
}

function setWidth(narrow: boolean) {
  vi.stubGlobal("matchMedia", (q: string) => ({ matches: narrow, media: q, addEventListener: () => {}, removeEventListener: () => {} }));
}

beforeEach(() => {
  endSession("signed-out");
  startSession({ token: "t", expiresAt: new Date(Date.now() + 600_000).toISOString() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("the map page", () => {
  it("shows the legend with counts, the unmapped list and the generated-layout note", async () => {
    setWidth(false);
    fakeApi(generated(10));
    render(<MarketMap councilId={C} marketId={M} />);
    const legend = await screen.findByRole("list", { name: "Legend" });
    expect(legend.textContent).toMatch(/Active\s*5/);
    expect(legend.textContent).toMatch(/Quiet \(no trade in 7 days\)\s*5/);
    expect(legend.textContent).toMatch(/Free\s*1/);
    expect(screen.getByText("1 stall not on the plan")).toBeTruthy();
    expect(screen.getByText(/generated from stall numbers, not measured/)).toBeTruthy();
    expect(screen.getByRole("img", { name: "Market plan with 10 stalls" })).toBeTruthy();
  });

  it("highlights the stalls search finds", async () => {
    setWidth(false);
    const fps = generated(10);
    fakeApi(fps, [{ traderName: "Trader 3", stallId: fps[3].stallId, stallCode: fps[3].code, marketId: M }]);
    render(<MarketMap councilId={C} marketId={M} />);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "Find a trader, phone or stall" }), "Trader 3");
    await user.click(screen.getByRole("button", { name: "Find" }));
    const highlight = await screen.findByTestId("highlight");
    expect(highlight.getAttribute("data-codes")).toBe(fps[3].code);
    expect(screen.getByRole("list", { name: "Search results" }).textContent).toContain("Trader 3");
  });

  it("at phone width shows a list by aisle instead of the drawing", async () => {
    setWidth(true);
    fakeApi(generated(70));
    render(<MarketMap councilId={C} marketId={M} />);
    expect(await screen.findByLabelText("Stalls by aisle")).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByRole("heading", { name: "Aisle A-1" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Aisle A-2" })).toBeTruthy();
  });
});

describe("speed", () => {
  it("a generated 5,000-stall market first draws in under one second", () => {
    const fps = generated(5_000);
    const st = new Map(fps.map((f, i) => [f.stallId, status((["FREE", "ACTIVE", "QUIET", "SUSPENDED"] as const)[i % 4], { stallId: f.stallId })]));
    const start = performance.now();
    render(
      <MarketSvg footprints={fps} sections={[]} status={st} layer="state" visible={null} highlighted={new Set()} selected={null} onSelect={() => {}} />,
    );
    const ms = Math.round(performance.now() - start);
    console.log(`MAP DRAW: 5,000 stalls first drawn in ${ms} ms (jsdom, ${navigator.userAgent})`);
    expect(screen.getByRole("img", { name: "Market plan with 5000 stalls" })).toBeTruthy();
    expect(ms).toBeLessThan(1_000);
  });
});
