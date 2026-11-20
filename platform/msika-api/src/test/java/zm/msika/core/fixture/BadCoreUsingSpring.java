package zm.msika.core.fixture;

import java.net.http.HttpClient;
import org.springframework.stereotype.Component;

/** Breaks rule 7 on purpose: msika-core must not use Spring or an HTTP client. */
@Component
public class BadCoreUsingSpring {
    HttpClient client;
}
