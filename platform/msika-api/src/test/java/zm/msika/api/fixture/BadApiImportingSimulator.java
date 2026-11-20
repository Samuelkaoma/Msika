package zm.msika.api.fixture;

import zm.msika.zamsim.fixture.SimulatorThing;

/** Breaks rule 6 on purpose: Msika code must never import from a simulator. */
public class BadApiImportingSimulator {
    SimulatorThing sim;
}
