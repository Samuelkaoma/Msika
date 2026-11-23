# M-50: Market map page

Card: `M-50` · Branch: `feature/M-50-market-map` · Depends on: M-26, M-49

## What we are building, in one paragraph

The council can now see its market: every stall drawn where its plan puts it, coloured by its state (or by levy, or
by whether it deposited today), with a pattern as well as a colour so it reads in black and white and for
colour-blind people. Generated layouts are outlined dashed and labelled as not measured. You can pan, zoom, filter by
section, class and state, search for a trader or stall (results are outlined in red), and click a stall to see its
panel. On a phone it becomes a list by aisle.

## New ideas in this lesson

- **SVG and the viewBox**: SVG draws in its own units; `viewBox` says which part of that space the screen shows.
  Panning moves the box, zooming resizes it. Our units are plan millimetres, with y flipped so north is up.
- **Draw by style, not by stall**: 5,000 stalls as 10,000 elements is slow to build. Stalls with the same look become
  one `<path>`; a grid index finds the stall under a click. Highlights and the selection are extra paths on top.
- **Patterns, not only colours**: SVG `<pattern>` fills (stripes, dots, a cross) layered over the colour.
- **Passive wheel listeners**: React's `onWheel` cannot stop the page scrolling; a native listener with
  `passive: false` can.
- **Phones get a list**: a drawing of thousands of stalls is not usable at 375 px.

## How we know it works

- `map.test.tsx`: each state, levy band and today's style differ by pattern or colour; generated outlines are dashed;
  stalls group into one path per style; the grid index finds the clicked stall; the legend shows counts and the
  unmapped list; search outlines the found stall; phone width shows the aisle list and no drawing; and a 5,000-stall
  generated market renders (in jsdom) in about 30 ms.
- By hand in a real browser, against the real API with a published generated plan of 5,000 stalls: first draw in
  480 to 771 ms (three runs) from choosing the market, including fetching the plan and status, React in development
  mode, on an 8-core Windows laptop. Zoom shows stall codes, a click opens the panel, the wheel zooms without
  scrolling the page, and 375 px shows the list.

## Mistakes to avoid

- One element per stall for big markets.
- Colour alone for meaning.
- Trusting a jsdom timing as a browser timing: jsdom does not paint. Measure in a browser too.
