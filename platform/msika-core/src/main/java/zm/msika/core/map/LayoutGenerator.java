package zm.msika.core.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays out a plan from stall codes alone (MARKET-MAP section 5.1). {@code SOW-B-114} is section {@code B}, number
 * {@code 114}. Within each section, numbers run along aisles: the first {@code stallsPerSide} on the left of aisle 1,
 * the next on its right, then aisle 2, and so on. Sections are placed in rows, about as many to a row as there are
 * rows, so a market with many sections comes out roughly square rather than as one long strip. Every footprint is
 * {@link MarketPlan.Provenance#GENERATED}: a schematic, which the map draws dashed and labels as not measured.
 *
 * <p>A stall whose code does not follow the market's pattern cannot be placed; it is listed as unmapped.
 */
public final class LayoutGenerator {

    /**
     * How a market's numbers run and how big things are.
     *
     * @param stallsPerSide how many stalls stand on each side of one aisle
     */
    public record Config(int stallsPerSide, int stallWidthMm, int stallDepthMm, int aisleWidthMm, int sectionGapMm) {
        public Config {
            if (stallsPerSide < 1 || stallsPerSide > 500) {
                throw new IllegalArgumentException("stallsPerSide must be 1 to 500");
            }
            if (stallWidthMm < 500 || stallDepthMm < 500 || aisleWidthMm < 500 || sectionGapMm < 0
                    || stallWidthMm > 50_000 || stallDepthMm > 50_000 || aisleWidthMm > 50_000 || sectionGapMm > 100_000) {
                throw new IllegalArgumentException("sizes must be between 0.5 m and 50 m (gaps up to 100 m)");
            }
        }

        /** 30 stalls a side, 2.5 m by 2.5 m stalls, 2 m aisles, 4 m between sections. */
        public static Config standard() {
            return new Config(30, 2_500, 2_500, 2_000, 4_000);
        }
    }

    private static final Pattern CODE = Pattern.compile("[A-Z]{2,5}-([A-Z0-9]{1,4})-([0-9]{1,5})");

    private LayoutGenerator() {}

    /** @param stalls every stall of the market: id to code */
    public static MarketPlan generate(Map<UUID, String> stalls, Config config) {
        // section -> number -> stall id; numbers sorted as numbers, so 9 comes before 10
        Map<String, TreeMap<Integer, UUID>> bySection = new TreeMap<>();
        Set<UUID> unmapped = new HashSet<>();
        for (Map.Entry<UUID, String> e : stalls.entrySet()) {
            Matcher m = CODE.matcher(e.getValue());
            if (!m.matches()) {
                unmapped.add(e.getKey());
                continue;
            }
            TreeMap<Integer, UUID> numbers = bySection.computeIfAbsent(m.group(1), k -> new TreeMap<>());
            if (numbers.putIfAbsent(Integer.parseInt(m.group(2)), e.getKey()) != null) {
                unmapped.add(e.getKey()); // B-007 and B-7 in one market: only one can have the place
            }
        }

        int w = config.stallWidthMm();
        int d = config.stallDepthMm();
        int a = config.aisleWidthMm();
        int stripHeight = d + a + d;
        List<MarketPlan.Section> sections = new ArrayList<>();
        List<MarketPlan.Aisle> aisles = new ArrayList<>();
        List<MarketPlan.Footprint> footprints = new ArrayList<>();
        int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(bySection.size())));
        int sectionX = 0;
        int rowY = 0;
        int rowHeight = 0;
        int inRow = 0;
        for (Map.Entry<String, TreeMap<Integer, UUID>> section : bySection.entrySet()) {
            if (inRow == perRow) {
                sectionX = 0;
                rowY += rowHeight + config.sectionGapMm();
                rowHeight = 0;
                inRow = 0;
            }
            inRow++;
            List<UUID> ordered = new ArrayList<>(section.getValue().values());
            int perAisle = 2 * config.stallsPerSide();
            int aisleCount = (ordered.size() + perAisle - 1) / perAisle;
            int longest = Math.min(config.stallsPerSide(), ordered.size());
            int sectionWidth = longest * w;
            for (int k = 0; k < aisleCount; k++) {
                int y0 = rowY + k * (stripHeight + a);
                String aisleName = section.getKey() + "-" + (k + 1);
                int aisleY = y0 + d + a / 2;
                aisles.add(new MarketPlan.Aisle(aisleName, List.of(new PointMm(sectionX, aisleY),
                        new PointMm(sectionX + sectionWidth, aisleY))));
                for (int i = 0; i < perAisle && k * perAisle + i < ordered.size(); i++) {
                    UUID stall = ordered.get(k * perAisle + i);
                    boolean left = i < config.stallsPerSide();
                    int position = (left ? i : i - config.stallsPerSide()) + 1;
                    int x = sectionX + (position - 1) * w;
                    int y = left ? y0 + d + a : y0;
                    footprints.add(new MarketPlan.Footprint(stall, PolygonMm.rectangle(x, y, w, d),
                            MarketPlan.Provenance.GENERATED, null, aisleName,
                            left ? MarketPlan.Side.L : MarketPlan.Side.R, position));
                }
            }
            int sectionHeight = aisleCount * stripHeight + Math.max(0, aisleCount - 1) * a;
            sections.add(new MarketPlan.Section(section.getKey(), PolygonMm.rectangle(sectionX, rowY, sectionWidth,
                    sectionHeight)));
            rowHeight = Math.max(rowHeight, sectionHeight);
            sectionX += sectionWidth + config.sectionGapMm();
        }
        footprints.sort(Comparator.comparing(MarketPlan.Footprint::aisle).thenComparing(MarketPlan.Footprint::side)
                .thenComparingInt(MarketPlan.Footprint::position));
        return new MarketPlan(sections, aisles, List.of(), footprints, unmapped);
    }
}
