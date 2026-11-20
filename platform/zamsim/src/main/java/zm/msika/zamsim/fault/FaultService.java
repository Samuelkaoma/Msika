package zm.msika.zamsim.fault;

import java.util.random.RandomGenerator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the fault settings (kept in the database so every instance agrees), and rolls the
 * dice for them. Rates of exactly 0 and 1 never and always happen, which is what makes tests predictable.
 */
@Service
public class FaultService {

    private final JdbcClient jdbc;
    private final RandomGenerator random;

    FaultService(JdbcClient jdbc, RandomGenerator random) {
        this.jdbc = jdbc;
        this.random = random;
    }

    @Transactional(readOnly = true)
    public FaultSettings settings() {
        return jdbc.sql("select failure_rate, callback_delay_ms, duplicate_callback_rate, reorder_rate, "
                        + "reorder_extra_delay_ms from fault_settings where id = 1")
                .query((rs, row) -> new FaultSettings(
                        rs.getDouble("failure_rate"),
                        rs.getInt("callback_delay_ms"),
                        rs.getDouble("duplicate_callback_rate"),
                        rs.getDouble("reorder_rate"),
                        rs.getInt("reorder_extra_delay_ms")))
                .single();
    }

    @Transactional
    public FaultSettings update(FaultSettings settings) {
        jdbc.sql("update fault_settings set failure_rate = :failure, callback_delay_ms = :delay, "
                        + "duplicate_callback_rate = :duplicate, reorder_rate = :reorder, "
                        + "reorder_extra_delay_ms = :extra where id = 1")
                .param("failure", settings.failureRate())
                .param("delay", settings.callbackDelayMs())
                .param("duplicate", settings.duplicateCallbackRate())
                .param("reorder", settings.reorderRate())
                .param("extra", settings.reorderExtraDelayMs())
                .update();
        return settings;
    }

    /** True with the given probability. A rate of 0 is never true and 1 is always true. */
    public boolean happens(double rate) {
        if (rate <= 0) {
            return false;
        }
        if (rate >= 1) {
            return true;
        }
        return random.nextDouble() < rate;
    }
}
