package zm.msika.core.stall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.RowError;

/**
 * Reads the CSV a council uploads to create stalls in bulk.
 *
 * <p>The first line is a header naming the columns {@code section}, {@code number} and {@code class},
 * in any order. Every other line is one stall. Row numbers in errors are file line numbers, so the
 * header is row 1 and the first stall is row 2. The whole file is accepted or rejected: if any row is
 * bad, nothing is created, and every bad row (up to {@link #MAX_ERRORS}) is reported.
 *
 * <p>Pure Java: no database, no framework. Quoted fields are supported; a quoted field cannot span
 * lines.
 */
public final class StallCsv {

    /** The most stalls one upload may create. */
    public static final int MAX_ROWS = 5000;

    /** The most row errors reported at once. */
    public static final int MAX_ERRORS = 50;

    private static final Pattern SECTION = Pattern.compile("[A-Z0-9]{1,4}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,5}");
    private static final Pattern CLASS = Pattern.compile("[A-Z0-9_]{1,20}");

    /** One valid row, with the file line it came from. */
    public record Row(int row, String section, String number, String stallClass) {}

    private StallCsv() {}

    /**
     * @return the stalls in file order
     * @throws InvalidInputException if the header is wrong, there are too many rows, or any row is
     *     bad; its {@link InvalidInputException#rows()} lists the bad ones
     */
    public static List<Row> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new InvalidInputException("the file is empty");
        }
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        String[] lines = text.split("\\r?\\n", -1);

        Map<String, Integer> columns = header(split(lines[0]));

        List<Row> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        int dataRows = 0;
        for (int i = 1; i < lines.length; i++) {
            int rowNumber = i + 1;
            if (lines[i].isBlank()) {
                continue;
            }
            dataRows++;
            if (dataRows > MAX_ROWS) {
                throw new InvalidInputException("too many rows: at most " + MAX_ROWS + " stalls per upload");
            }
            List<String> fields = split(lines[i]);
            String section = field(fields, columns.get("section")).toUpperCase(Locale.ROOT);
            String number = field(fields, columns.get("number"));
            String stallClass = field(fields, columns.get("class")).toUpperCase(Locale.ROOT);

            String problem = null;
            if (!SECTION.matcher(section).matches()) {
                problem = "section must be 1 to 4 letters or digits, like B";
            } else if (!NUMBER.matcher(number).matches()) {
                problem = "number must be 1 to 5 digits, like 114";
            } else if (!CLASS.matcher(stallClass).matches()) {
                problem = "class must be 1 to 20 letters, digits or underscores, like STANDARD";
            } else {
                String key = section + "-" + number;
                Integer earlier = firstSeen.putIfAbsent(key, rowNumber);
                if (earlier != null) {
                    problem = "stall " + key + " appears twice (first on row " + earlier + ")";
                }
            }

            if (problem == null) {
                rows.add(new Row(rowNumber, section, number, stallClass));
            } else {
                errors.add(new RowError(rowNumber, problem));
            }
        }

        if (!errors.isEmpty()) {
            List<RowError> shown = errors.subList(0, Math.min(errors.size(), MAX_ERRORS));
            String message = errors.size() == 1
                    ? "1 row is not valid, nothing was created"
                    : errors.size() + " rows are not valid, nothing was created";
            throw new InvalidInputException(message, shown);
        }
        if (rows.isEmpty()) {
            throw new InvalidInputException("the file has a header but no stalls");
        }
        return rows;
    }

    private static Map<String, Integer> header(List<String> names) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            columns.putIfAbsent(names.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : List.of("section", "number", "class")) {
            if (!columns.containsKey(required)) {
                throw new InvalidInputException(
                        "row 1 must be a header with the columns section, number and class",
                        List.of(new RowError(1, "missing column: " + required)));
            }
        }
        return columns;
    }

    private static String field(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index).trim() : "";
    }

    /** Splits one line on commas, honouring double quotes (two quotes in a row is a literal quote). */
    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
