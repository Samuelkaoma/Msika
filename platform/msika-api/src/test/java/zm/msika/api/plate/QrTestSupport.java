package zm.msika.api.plate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Reads QR codes back from rendered plates in tests.
 *
 * <p>One image can defeat ZXing's reader even when the code in it is perfect: about one image in two
 * hundred, measured on noise-free images ZXing drew itself, and the same code often reads at another
 * resolution. A real scanner reads many video frames, so a printed code only needs to read in some
 * frame. A genuine drawing fault would be different: it would fail at every resolution, or read as the
 * wrong text. {@link #assertPrintedCodeReads} therefore tries several resolutions, passes as soon as one
 * reads, fails if any resolution reads text that is not the expected text, and fails if none reads.
 */
public final class QrTestSupport {

    /** Resolutions to try, in dots per inch. */
    private static final int[] DPIS = {150, 200, 250, 300};

    private QrTestSupport() {}

    /** The text ZXing reads from the image, or null if it finds nothing. */
    public static String decode(BufferedImage image) {
        try {
            return new MultiFormatReader()
                    .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Asserts that the QR code on the given page reads back to {@code expected} at some resolution, and
     * never to anything else.
     */
    public static void assertPrintedCodeReads(PDFRenderer pages, int pageIndex, String expected) throws IOException {
        for (int dpi : DPIS) {
            String text = decode(pages.renderImageWithDPI(pageIndex, dpi));
            if (text != null) {
                assertThat(text).as("QR code on page " + (pageIndex + 1) + " read at " + dpi + " dpi")
                        .isEqualTo(expected);
                return;
            }
        }
        fail("the QR code on page " + (pageIndex + 1) + " (" + expected + ") could not be read at any of "
                + java.util.Arrays.toString(DPIS) + " dpi");
    }
}
