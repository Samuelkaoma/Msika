package zm.msika.core.phone;

import java.util.Arrays;
import zm.msika.core.common.InvalidInputException;

/** The language a person receives messages in. Only English has replies so far; the others come in M-42. */
public enum Language {

    ENGLISH("en"),
    NYANJA("ny"),
    BEMBA("bem");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    /** The short code stored in the database and sent over the API. */
    public String code() {
        return code;
    }

    /** @return English when the code is missing or blank */
    public static Language fromCode(String code) {
        if (code == null || code.isBlank()) {
            return ENGLISH;
        }
        return Arrays.stream(values())
                .filter(l -> l.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new InvalidInputException("language must be en, ny or bem"));
    }
}
