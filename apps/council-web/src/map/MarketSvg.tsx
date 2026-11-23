// The market plan drawn as SVG (MARKET-MAP sections 8.1 and 12, decision 0008). Stalls with the same style are
// drawn as ONE path, so a 5,000-stall market is a few dozen elements, not 10,000. The stall under the pointer is
// found with a grid index. Pan by dragging, zoom with the wheel or the buttons; codes appear once zoomed in enough.

import { memo, useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import type { StallStatus } from "../api/schema";
import { boundsOf, GridIndex, pathOf, union, type Box, type Footprint } from "./geometry";
import { FILTERED_OUT, outlineFor, styleFor, type Layer, type Style } from "./style";

type View = { x: number; y: number; w: number; h: number };

export type MarketSvgProps = {
  footprints: Footprint[];
  sections: { name: string; polygon: number[] }[];
  status: Map<string, StallStatus>;
  layer: Layer;
  /** Stall ids the filters keep; null keeps everything. */
  visible: Set<string> | null;
  /** Stall ids found by search, drawn with a heavy outline. */
  highlighted: Set<string>;
  selected: string | null;
  onSelect: (stallId: string) => void;
  /** An agent's round tonight (M-51): stalls done, and held stalls in their aisles not reached yet. */
  roundDone?: Set<string>;
  roundNotReached?: Set<string>;
};

/** Groups footprints by style and builds one path for each group. Exported for the tests. */
export function groupPaths(props: Pick<MarketSvgProps, "footprints" | "status" | "layer" | "visible">) {
  const groups = new Map<string, { style: Style; d: string[]; codes: string[] }>();
  const dashed: string[] = [];
  const solid: string[] = [];
  for (const f of props.footprints) {
    const style = props.visible && !props.visible.has(f.stallId) ? FILTERED_OUT : styleFor(props.layer, props.status.get(f.stallId));
    const d = pathOf(f.polygon);
    const g = groups.get(style.key);
    if (g) {
      g.d.push(d);
      g.codes.push(f.code);
    } else {
      groups.set(style.key, { style, d: [d], codes: [f.code] });
    }
    (outlineFor(f.provenance) === "dashed" ? dashed : solid).push(d);
  }
  return { groups: [...groups.values()], dashed: dashed.join(""), solid: solid.join("") };
}

function fit(box: Box): View {
  const pad = Math.max(box.maxX - box.minX, box.maxY - box.minY) * 0.03 + 1_000;
  return { x: box.minX - pad, y: -box.maxY - pad, w: box.maxX - box.minX + 2 * pad, h: box.maxY - box.minY + 2 * pad };
}

export const MarketSvg = memo(function MarketSvg(props: MarketSvgProps) {
  const { footprints, sections, status, layer, visible, highlighted, selected, onSelect, roundDone, roundNotReached } = props;
  const box = useMemo(() => union(footprints.map((f) => boundsOf(f.polygon)).concat(sections.map((s) => boundsOf(s.polygon)))), [footprints, sections]);
  const home = useMemo(() => fit(box), [box]);
  const [view, setView] = useState<View>(home);
  const index = useMemo(() => new GridIndex(footprints), [footprints]);
  const drawn = useMemo(() => groupPaths({ footprints, status, layer, visible }), [footprints, status, layer, visible]);
  const svg = useRef<SVGSVGElement>(null);
  const drag = useRef<{ x: number; y: number; view: View; moved: boolean } | null>(null);

  const toPlan = (clientX: number, clientY: number) => {
    const r = svg.current!.getBoundingClientRect();
    const scale = Math.max(view.w / (r.width || 1), view.h / (r.height || 1));
    const offsetX = (r.width * scale - view.w) / 2;
    const offsetY = (r.height * scale - view.h) / 2;
    return { x: view.x - offsetX + (clientX - r.left) * scale, y: -(view.y - offsetY + (clientY - r.top) * scale), scale };
  };

  const zoom = (factor: number, cx?: number, cy?: number) =>
    setView((v) => {
      const px = cx ?? v.x + v.w / 2;
      const py = cy ?? v.y + v.h / 2;
      const w = Math.min(Math.max(v.w * factor, 4_000), home.w * 4);
      const h = (v.h * w) / v.w;
      return { x: px - ((px - v.x) * w) / v.w, y: py - ((py - v.y) * h) / v.h, w, h };
    });

  // The wheel zooms the map, not the page. React's wheel listener is passive and cannot stop the page scrolling, so
  // a native listener is added with passive: false.
  const wheel = useRef<(e: globalThis.WheelEvent) => void>(() => {});
  wheel.current = (e: globalThis.WheelEvent) => {
    e.preventDefault();
    const p = toPlan(e.clientX, e.clientY);
    zoom(e.deltaY > 0 ? 1.2 : 1 / 1.2, p.x, -p.y);
  };
  useEffect(() => {
    const el = svg.current;
    if (!el) return;
    const listener = (e: globalThis.WheelEvent) => wheel.current(e);
    el.addEventListener("wheel", listener, { passive: false });
    return () => el.removeEventListener("wheel", listener);
  }, []);
  const onDown = (e: PointerEvent<SVGSVGElement>) => {
    drag.current = { x: e.clientX, y: e.clientY, view, moved: false };
    (e.target as Element).setPointerCapture?.(e.pointerId);
  };
  const onMove = (e: PointerEvent<SVGSVGElement>) => {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.x;
    const dy = e.clientY - d.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true;
    if (!d.moved) return;
    const scale = toPlan(0, 0).scale;
    setView({ ...d.view, x: d.view.x - dx * scale, y: d.view.y - dy * scale });
  };
  const onUp = (e: PointerEvent<SVGSVGElement>) => {
    const d = drag.current;
    drag.current = null;
    if (d && !d.moved) {
      const p = toPlan(e.clientX, e.clientY);
      const hit = index.at(p.x, p.y);
      if (hit) onSelect(hit.stallId);
    }
  };

  // Codes are drawn only when zoomed in enough to read them, and only for stalls in view.
  const labels = useMemo(() => {
    if (view.w > 60_000) return [];
    return footprints.filter((f) => {
      const b = boundsOf(f.polygon);
      return b.maxX > view.x && b.minX < view.x + view.w && -b.minY > view.y && -b.maxY < view.y + view.h;
    });
  }, [footprints, view]);

  const highlightPath = footprints.filter((f) => highlighted.has(f.stallId)).map((f) => pathOf(f.polygon)).join("");
  const roundDonePath = roundDone ? footprints.filter((f) => roundDone.has(f.stallId)).map((f) => pathOf(f.polygon)).join("") : "";
  const roundLeftPath = roundNotReached
    ? footprints.filter((f) => roundNotReached.has(f.stallId)).map((f) => pathOf(f.polygon)).join("")
    : "";
  const selectedPath = footprints.filter((f) => f.stallId === selected).map((f) => pathOf(f.polygon)).join("");
  const stroke = Math.max(view.w / 1_500, 20);

  return (
    <div className="map-frame">
      <div className="map-tools">
        <button aria-label="Zoom in" onClick={() => zoom(1 / 1.5)}>
          +
        </button>
        <button aria-label="Zoom out" onClick={() => zoom(1.5)}>
          −
        </button>
        <button aria-label="Show the whole market" onClick={() => setView(home)}>
          ⤢
        </button>
      </div>
      <svg
        ref={svg}
        className="map"
        role="img"
        aria-label={`Market plan with ${footprints.length} stalls`}
        viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`}
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
      >
        <defs>
          <pattern id="p-stripes" patternUnits="userSpaceOnUse" width="600" height="600" patternTransform="rotate(45)">
            <rect width="600" height="600" fill="none" />
            <line x1="0" y1="0" x2="0" y2="600" stroke="#000" strokeOpacity="0.35" strokeWidth="180" />
          </pattern>
          <pattern id="p-dots" patternUnits="userSpaceOnUse" width="600" height="600">
            <circle cx="300" cy="300" r="110" fill="#000" fillOpacity="0.35" />
          </pattern>
          <pattern id="p-cross" patternUnits="userSpaceOnUse" width="800" height="800">
            <path d="M0 0L800 800M800 0L0 800" stroke="#000" strokeOpacity="0.45" strokeWidth="90" />
          </pattern>
        </defs>
        {sections.map((s) => (
          <g key={s.name}>
            <path d={pathOf(s.polygon)} className="section" strokeWidth={stroke} />
          </g>
        ))}
        {drawn.groups.map((g) => (
          <g key={g.style.key} data-style={g.style.key} data-codes={g.codes.join(" ")}>
            <path d={g.d.join("")} fill={g.style.fill} />
            {g.style.pattern !== "none" && <path d={g.d.join("")} fill={`url(#p-${g.style.pattern})`} />}
          </g>
        ))}
        <path d={drawn.solid} className="outline" strokeWidth={stroke} />
        <path d={drawn.dashed} className="outline" strokeWidth={stroke} strokeDasharray={`${stroke * 4} ${stroke * 3}`} data-testid="generated-outlines" />
        {highlightPath && <path d={highlightPath} className="highlight" strokeWidth={stroke * 5} data-testid="highlight" data-codes={footprints.filter((f) => highlighted.has(f.stallId)).map((f) => f.code).join(" ")} />}
        {roundDonePath && <path d={roundDonePath} className="round-done" strokeWidth={stroke * 4} data-testid="round-done" />}
        {roundLeftPath && (
          <path d={roundLeftPath} className="round-left" strokeWidth={stroke * 4} strokeDasharray={`${stroke * 6} ${stroke * 4}`} data-testid="round-left" />
        )}
        {selectedPath && <path d={selectedPath} className="selected" strokeWidth={stroke * 4} />}
        {sections.map((s) => {
          const b = boundsOf(s.polygon);
          // Sized to the screen, but never wider than the section it names.
          const size = Math.min(stroke * 14, (b.maxX - b.minX) / 7);
          return (
            <text key={`t-${s.name}`} x={b.minX} y={-b.maxY - size * 0.4} className="section-label" fontSize={size}>
              Section {s.name}
            </text>
          );
        })}
        {labels.map((f) => {
          const b = boundsOf(f.polygon);
          return (
            <text key={f.stallId} x={(b.minX + b.maxX) / 2} y={-(b.minY + b.maxY) / 2} className="stall-label" fontSize={(b.maxX - b.minX) / 5}>
              {f.code.split("-").slice(1).join("-")}
            </text>
          );
        })}
      </svg>
    </div>
  );
});
