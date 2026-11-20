package zm.msika.core.fixture;

import zm.msika.api.fixture.ApiThing;

/** Breaks the dependency direction on purpose: core must not depend on api. */
public class BadCoreDependingOnApi {
    ApiThing thing;
}
