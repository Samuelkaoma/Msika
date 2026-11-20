package zm.msika.core.common;

/**
 * A problem on one row of an uploaded file.
 *
 * @param row the line number in the file as the person sees it, where the header is line 1
 * @param message what is wrong, in plain words
 */
public record RowError(int row, String message) {}
