// Plan geometry in the browser: polygons in plan millimetres (y up), drawn in SVG (y down), and a grid index to find
// the stall under the pointer without testing all 5,000.

export type Footprint = { stallId: string; code: string; polygon: number[]; provenance?: string; aisle?: string };

/** An SVG path for a polygon, with y flipped so north is up. */
export function pathOf(polygon: number[]): string {
  let d = "";
  for (let i = 0; i + 1 < polygon.length; i += 2) {
    d += `${i === 0 ? "M" : "L"}${polygon[i]} ${-polygon[i + 1]}`;
  }
  return d + "Z";
}

export type Box = { minX: number; minY: number; maxX: number; maxY: number };

export function boundsOf(polygon: number[]): Box {
  const b = { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity };
  for (let i = 0; i + 1 < polygon.length; i += 2) {
    b.minX = Math.min(b.minX, polygon[i]);
    b.maxX = Math.max(b.maxX, polygon[i]);
    b.minY = Math.min(b.minY, polygon[i + 1]);
    b.maxY = Math.max(b.maxY, polygon[i + 1]);
  }
  return b;
}

export function union(boxes: Box[]): Box {
  return boxes.reduce(
    (a, b) => ({ minX: Math.min(a.minX, b.minX), minY: Math.min(a.minY, b.minY), maxX: Math.max(a.maxX, b.maxX), maxY: Math.max(a.maxY, b.maxY) }),
    { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity },
  );
}

/** Ray casting: is the point inside the polygon? */
export function contains(polygon: number[], x: number, y: number): boolean {
  let inside = false;
  const n = polygon.length / 2;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = polygon[2 * i];
    const yi = polygon[2 * i + 1];
    const xj = polygon[2 * j];
    const yj = polygon[2 * j + 1];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** A coarse grid over the plan: each cell lists the footprints whose box touches it. */
export class GridIndex {
  private readonly cells = new Map<string, Footprint[]>();

  constructor(
    private readonly footprints: Footprint[],
    private readonly cellMm = 10_000,
  ) {
    for (const f of footprints) {
      const b = boundsOf(f.polygon);
      for (let cx = Math.floor(b.minX / cellMm); cx <= Math.floor(b.maxX / cellMm); cx++) {
        for (let cy = Math.floor(b.minY / cellMm); cy <= Math.floor(b.maxY / cellMm); cy++) {
          const key = `${cx},${cy}`;
          const list = this.cells.get(key);
          if (list) list.push(f);
          else this.cells.set(key, [f]);
        }
      }
    }
  }

  /** The footprint containing the plan point, if any. */
  at(x: number, y: number): Footprint | undefined {
    const list = this.cells.get(`${Math.floor(x / this.cellMm)},${Math.floor(y / this.cellMm)}`) ?? [];
    return list.find((f) => contains(f.polygon, x, y));
  }

  get size(): number {
    return this.footprints.length;
  }
}
