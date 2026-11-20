package zm.msika.api.plate;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
import zm.msika.core.plate.PlateRenderer;
import zm.msika.core.plate.StallPlate;

/**
 * Draws stall plates as an A6 PDF, one page per stall: the market name, a big QR code, and the stall
 * code in large letters so it can be read without scanning.
 *
 * <p>The QR code is drawn as vector rectangles, not a picture, so it stays sharp at any print size.
 * Each square is a whole number of points wide and neighbouring dark squares in a row are drawn as one
 * rectangle, so the code prints with crisp edges and no hairline gaps. The code has plenty of clear space
 * around it, because a scanner needs a quiet border.
 *
 * <p>{@code QrScanabilityTest} reads every plate of a 300-stall market back from the rendered page.
 */
@Component
class PdfPlateRenderer implements PlateRenderer {

    private static final PDRectangle PAGE = PDRectangle.A6;

    /** The QR code is made as large as fits inside this, using whole-point squares. */
    private static final float QR_MAX_SIZE = 240f;

    @Override
    public byte[] render(List<StallPlate> plates) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (StallPlate plate : plates) {
                PDPage page = new PDPage(PAGE);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    draw(content, plate, bold, regular);
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not draw the plates", e);
        }
    }

    private static void draw(PDPageContentStream content, StallPlate plate, PDFont bold, PDFont regular)
            throws IOException {
        float width = PAGE.getWidth();
        float height = PAGE.getHeight();

        centred(content, "MSIKA", regular, 11, width, height - 28);
        centred(content, plate.marketName(), bold, 17, width, height - 52);

        float qrTop = height - 96;
        float qrBottom = qr(content, plate.qrText(), width, qrTop);

        centred(content, plate.stallCode(), bold, 28, width, qrBottom - 60);
        centred(content, "Keep this plate clean and in view.", regular, 8, width, 14);
    }

    /**
     * Draws the QR code centred on the page with its top edge at {@code top}, and returns the y of its
     * bottom edge.
     */
    private static float qr(PDPageContentStream content, String text, float pageWidth, float top) throws IOException {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q,
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, 0));
        } catch (WriterException e) {
            throw new IllegalStateException("could not encode the QR code", e);
        }
        int modules = matrix.getWidth();
        float square = (float) Math.floor(QR_MAX_SIZE / modules);
        float size = square * modules;
        float left = (float) Math.floor((pageWidth - size) / 2);

        content.setNonStrokingColor(0f);
        for (int row = 0; row < matrix.getHeight(); row++) {
            int col = 0;
            while (col < modules) {
                if (!matrix.get(col, row)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < modules && matrix.get(col, row)) {
                    col++;
                }
                // PDF's origin is the bottom left, a matrix's is the top left.
                content.addRect(left + start * square, top - (row + 1) * square, (col - start) * square, square);
            }
        }
        content.fill();
        return top - size;
    }

    private static void centred(PDPageContentStream content, String text, PDFont font, float size, float pageWidth,
            float y) throws IOException {
        String safe = printable(text);
        float textWidth = font.getStringWidth(safe) / 1000 * size;
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset((pageWidth - textWidth) / 2, y);
        content.showText(safe);
        content.endText();
    }

    /** The built-in fonts only draw Latin-1 characters; anything else becomes a question mark. */
    private static String printable(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            safe.append(c >= 32 && c <= 255 ? c : '?');
        }
        return safe.toString();
    }
}
