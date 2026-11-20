package zm.msika.api.plate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.plate.StallPlate;

/**
 * Every printed QR code must be drawn faithfully. This draws a whole market's plates and reads each one
 * back from the rendered page. Tokens come from a fixed seed so the result is the same on every run.
 * See {@link QrTestSupport} for what "reads back" means and why several resolutions are tried.
 */
class QrScanabilityTest {

    private static final int PLATES = 300;

    private final PdfPlateRenderer renderer = new PdfPlateRenderer();

    /** 22-character tokens like the real ones, from a fixed seed. */
    private static List<StallPlate> plates() {
        Random random = new Random(20261123L);
        List<StallPlate> plates = new ArrayList<>();
        for (int i = 1; i <= PLATES; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            plates.add(new StallPlate("BIG-A-" + i, "Big Market", token));
        }
        return plates;
    }

    @Test
    @DisplayName("every plate of a 300-stall market reads back to exactly its own stall code and token")
    void everyPlateReadsBack() throws Exception {
        List<StallPlate> plates = plates();

        byte[] pdf = renderer.render(plates);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer pages = new PDFRenderer(document);
            for (int page = 0; page < plates.size(); page++) {
                QrTestSupport.assertPrintedCodeReads(pages, page, plates.get(page).qrText());
            }
        }
    }
}
