package zm.msika.core.plate;

import java.util.List;

/** Draws plates into a printable document. Implemented in msika-api (a PDF library lives there). */
public interface PlateRenderer {

    /** One A6 page per plate, in the order given, as a PDF. */
    byte[] render(List<StallPlate> plates);
}
