package zm.msika.ledger.fixture;

import org.springframework.stereotype.Service;
import zm.msika.core.fixture.BadCoreUsingSpring;

/** Breaks two rules on purpose: ledger must not use Spring, and must not depend on core. */
@Service
public class BadLedgerUsingSpringAndCore {
    BadCoreUsingSpring core;
}
