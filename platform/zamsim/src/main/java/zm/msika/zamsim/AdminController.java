package zm.msika.zamsim;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.zamsim.callback.CallbackService;
import zm.msika.zamsim.callback.CallbackService.Delivery;
import zm.msika.zamsim.fault.FaultService;
import zm.msika.zamsim.fault.FaultSettings;
import zm.msika.zamsim.wallet.ZamsimExceptions.BadRequest;

/** What the sim console (and tests) use to make zamsim misbehave and to see what it sent. */
@RestController
class AdminController {

    private final FaultService faults;
    private final CallbackService callbacks;

    AdminController(FaultService faults, CallbackService callbacks) {
        this.faults = faults;
        this.callbacks = callbacks;
    }

    @GetMapping("/v1/admin/faults")
    FaultSettings faults() {
        return faults.settings();
    }

    /** Replaces all fault settings. Rates are 0 to 1 and delays are in milliseconds. Takes effect at once. */
    @PutMapping("/v1/admin/faults")
    FaultSettings updateFaults(@RequestBody FaultSettings settings) {
        return faults.update(settings);
    }

    /** The callbacks zamsim has queued or sent, newest first, optionally only PENDING, DELIVERED or DEAD ones. */
    @GetMapping("/v1/admin/callbacks")
    List<Delivery> callbacks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (status != null && !List.of("PENDING", "DELIVERED", "DEAD").contains(status)) {
            throw new BadRequest("status must be PENDING, DELIVERED or DEAD");
        }
        if (limit < 1 || limit > 1000) {
            throw new BadRequest("limit must be 1 to 1000");
        }
        return callbacks.deliveries(status, limit);
    }
}
