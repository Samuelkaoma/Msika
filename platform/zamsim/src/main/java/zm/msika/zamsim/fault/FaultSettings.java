package zm.msika.zamsim.fault;

import zm.msika.zamsim.wallet.ZamsimExceptions.BadRequest;

/**
 * How unreliable zamsim should pretend to be (ARCHITECTURE.md section 6, "fault injection"). Integration
 * tests and demos turn these on to prove Msika copes with a payments system that misbehaves.
 *
 * <p>Rates are probabilities from 0 (never) to 1 (always).
 *
 * @param failureRate chance that a cash-in, transfer or approved request-to-pay fails with
 *     {@code SIMULATED_FAILURE} instead of moving money
 * @param callbackDelayMs how long to wait before the first attempt to deliver a callback
 * @param duplicateCallbackRate chance that a callback is delivered twice (same callback id both times)
 * @param reorderRate chance that a callback is held back by an extra {@code reorderExtraDelayMs}, so a
 *     later payment's callback can overtake it (callbacks arrive out of order)
 * @param reorderExtraDelayMs the extra delay for a held-back callback
 */
public record FaultSettings(
        double failureRate,
        int callbackDelayMs,
        double duplicateCallbackRate,
        double reorderRate,
        int reorderExtraDelayMs) {

    /** Everything off: zamsim behaves perfectly. */
    public static FaultSettings none() {
        return new FaultSettings(0, 0, 0, 0, 2000);
    }

    public FaultSettings {
        rate("failureRate", failureRate);
        rate("duplicateCallbackRate", duplicateCallbackRate);
        rate("reorderRate", reorderRate);
        if (callbackDelayMs < 0 || reorderExtraDelayMs < 0) {
            throw new BadRequest("delays must not be negative");
        }
    }

    private static void rate(String name, double value) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new BadRequest(name + " must be between 0 and 1");
        }
    }
}
