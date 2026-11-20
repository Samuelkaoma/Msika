package zm.msika.core.stall;

/** Where a stall is in its life. */
public enum StallStatus {

    /** Nobody has it. */
    VACANT,

    /** A trader holds it. */
    ALLOCATED,

    /** Taken out of use by the council. Cannot be allocated. */
    SUSPENDED
}
