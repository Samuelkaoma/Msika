# 0008: Market plans are drawn as SVG without a map library

Status: Accepted (card M-50)

## Context
A market plan is a drawing in millimetres on a flat plane (0006), not a map in latitude and longitude. Map
libraries (Leaflet, OpenLayers, MapLibre) are built for tiles and degrees, add a large dependency, and would need a
fake coordinate system to show a plan.

## Decision
The plan is plain SVG drawn by React, with pan and zoom done by changing the `viewBox`. Stalls with the same style
are drawn as ONE `<path>` (with a second path for its pattern), so a 5,000-stall market is a few dozen elements, and
the stall under the pointer is found with a grid index on the client. Leaflet is kept for the city overview only
(card M-55), where real map tiles are the point.

## Consequences
- No map dependency in the dashboard; printing (M-57) can reuse the same SVG at a true scale.
- First draw of a generated 5,000-stall market: about 0.5 to 0.8 s in a browser, including fetching it (M-50).
- Hover effects per stall are not free (stalls are not separate elements); selection and search highlights are drawn
  as overlay paths instead.
