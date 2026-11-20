package zm.msika.core.stall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.RowError;

/** Specification for {@link StallCsv}: good files are read, bad rows are named by row number. */
class StallCsvTest {

    private static List<RowError> rowsOf(Throwable t) {
        return ((InvalidInputException) t).rows();
    }

    @Nested
    @DisplayName("a good file")
    class GoodFile {

        @Test
        @DisplayName("is read row by row, with line numbers where the header is row 1")
        void readsRows() {
            List<StallCsv.Row> rows = StallCsv.parse("section,number,class\nB,114,STANDARD\nB,115,LARGE\n");

            assertThat(rows).containsExactly(
                    new StallCsv.Row(2, "B", "114", "STANDARD"),
                    new StallCsv.Row(3, "B", "115", "LARGE"));
        }

        @Test
        @DisplayName("columns may be in any order, in any case, with spaces around values")
        void flexibleColumns() {
            List<StallCsv.Row> rows = StallCsv.parse("Class , Number,SECTION\n standard , 7 , a \n");

            assertThat(rows).containsExactly(new StallCsv.Row(2, "A", "7", "STANDARD"));
        }

        @Test
        @DisplayName("Windows line endings, a byte order mark and blank lines are fine")
        void windowsFiles() {
            List<StallCsv.Row> rows = StallCsv.parse("﻿section,number,class\r\nB,1,STANDARD\r\n\r\nB,2,STANDARD\r\n");

            assertThat(rows).extracting(StallCsv.Row::row).containsExactly(2, 4);
        }

        @Test
        @DisplayName("quoted fields are read")
        void quotedFields() {
            List<StallCsv.Row> rows = StallCsv.parse("section,number,class\n\"B\",\"114\",\"STANDARD\"\n");

            assertThat(rows).containsExactly(new StallCsv.Row(2, "B", "114", "STANDARD"));
        }
    }

    @Nested
    @DisplayName("a bad file is rejected as a whole")
    class BadFile {

        @Test
        @DisplayName("a bad row is named by its row number")
        void badRowNamedByNumber() {
            String csv = "section,number,class\nB,114,STANDARD\nB,abc,STANDARD\nB,116,STANDARD\n";

            assertThatThrownBy(() -> StallCsv.parse(csv))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(t -> {
                        assertThat(rowsOf(t)).hasSize(1);
                        assertThat(rowsOf(t).get(0).row()).isEqualTo(3);
                        assertThat(rowsOf(t).get(0).message()).contains("number");
                    })
                    .hasMessageContaining("1 row is not valid");
        }

        @Test
        @DisplayName("every bad row is reported, not just the first")
        void allBadRowsReported() {
            String csv = "section,number,class\nB,1,STANDARD\n,2,STANDARD\nB,x,STANDARD\nB,4,\n";

            assertThatThrownBy(() -> StallCsv.parse(csv)).satisfies(t ->
                    assertThat(rowsOf(t)).extracting(RowError::row).containsExactly(3, 4, 5));
        }

        @Test
        @DisplayName("a stall listed twice names the first row it appeared on")
        void duplicateInFile() {
            String csv = "section,number,class\nB,114,STANDARD\nB,115,STANDARD\nb,114,LARGE\n";

            assertThatThrownBy(() -> StallCsv.parse(csv)).satisfies(t -> {
                assertThat(rowsOf(t)).hasSize(1);
                assertThat(rowsOf(t).get(0).row()).isEqualTo(4);
                assertThat(rowsOf(t).get(0).message()).contains("first on row 2");
            });
        }

        @Test
        @DisplayName("a missing column is reported on row 1")
        void missingColumn() {
            assertThatThrownBy(() -> StallCsv.parse("section,number\nB,1\n")).satisfies(t -> {
                assertThat(rowsOf(t).get(0).row()).isEqualTo(1);
                assertThat(rowsOf(t).get(0).message()).contains("class");
            });
        }

        @Test
        @DisplayName("empty files and header-only files are rejected")
        void emptyFiles() {
            assertThatThrownBy(() -> StallCsv.parse("")).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> StallCsv.parse("   \n")).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> StallCsv.parse("section,number,class\n"))
                    .hasMessageContaining("no stalls");
        }

        @Test
        @DisplayName("too-long values are rejected")
        void tooLong() {
            assertThatThrownBy(() -> StallCsv.parse("section,number,class\nTOOLONG,1,STANDARD\n"))
                    .satisfies(t -> assertThat(rowsOf(t).get(0).message()).contains("section"));
            assertThatThrownBy(() -> StallCsv.parse("section,number,class\nB,123456,STANDARD\n"))
                    .satisfies(t -> assertThat(rowsOf(t).get(0).message()).contains("number"));
            assertThatThrownBy(() -> StallCsv.parse("section,number,class\nB,1," + "X".repeat(21) + "\n"))
                    .satisfies(t -> assertThat(rowsOf(t).get(0).message()).contains("class"));
        }

        @Test
        @DisplayName("no more than 50 errors are listed, but the count is told")
        void errorsAreCapped() {
            StringBuilder csv = new StringBuilder("section,number,class\n");
            for (int i = 0; i < 80; i++) {
                csv.append("B,bad,STANDARD\n");
            }

            assertThatThrownBy(() -> StallCsv.parse(csv.toString()))
                    .hasMessageContaining("80 rows are not valid")
                    .satisfies(t -> assertThat(rowsOf(t)).hasSize(50));
        }

        @Test
        @DisplayName("more than 5000 stalls in one upload are refused")
        void tooManyRows() {
            StringBuilder csv = new StringBuilder("section,number,class\n");
            for (int i = 0; i <= StallCsv.MAX_ROWS; i++) {
                csv.append("A,").append(i).append(",STANDARD\n");
            }

            assertThatThrownBy(() -> StallCsv.parse(csv.toString())).hasMessageContaining("too many rows");
        }
    }
}
