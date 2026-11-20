package zm.msika.core.sms;

import java.util.Objects;

/**
 * A command a trader or agent sent by SMS, as {@code docs/SMS-PROTOCOL.md} defines it. Produced by
 * {@link SmsCommandParser}; nothing here has been checked against the database yet (the stall may not exist, the
 * reference may belong to someone else). Amounts are whole ngwee (AGENTS.md rule 1).
 */
public sealed interface SmsCommand {

    /** Who may send it. A trader's number cannot use agent commands and the other way round. */
    enum Audience {
        TRADER, AGENT,
        /** Either kind of sender (only {@code HELP}). */
        ANYONE
    }

    Audience audience();

    // ---------------------------------------------------------------------------------------------------------
    // Trader commands
    // ---------------------------------------------------------------------------------------------------------

    /** {@code REG SOW-B-114}: ask to be registered at a stall. */
    record Register(String stallCode) implements SmsCommand {
        public Register {
            Objects.requireNonNull(stallCode);
        }

        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code YES}: accept the terms and the daily levy sweep after approval. */
    record Consent() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code SALE 120} or {@code S 120}: a cash sale that was not deposited. */
    record DeclareSale(long amountNgwee) implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code TODAY}: today's totals. */
    record Today() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code BAL}: wallet balance and levy status. */
    record Balance() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code LEVY}: levy details. */
    record Levy() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code MONTH}: last month's statement summary. */
    record Month() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code OK}: accept the latest statement. */
    record StatementOk() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code WRONG}: dispute the latest statement. */
    record StatementWrong() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code NO 8F2K}: dispute a deposit an agent recorded. */
    record DisputeDeposit(String reference) implements SmsCommand {
        public DisputeDeposit {
            Objects.requireNonNull(reference);
        }

        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /**
     * {@code LOAN} asks what loan is possible; {@code LOAN 800 SUP-12} asks for one from a supplier (Phase 7).
     *
     * @param amountNgwee null for the plain question
     * @param supplierCode null for the plain question
     */
    record Loan(Long amountNgwee, String supplierCode) implements SmsCommand {
        public Loan {
            if ((amountNgwee == null) != (supplierCode == null)) {
                throw new IllegalArgumentException("a loan request has both an amount and a supplier, or neither");
            }
        }

        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code HELP}: the list of commands. Anyone may send it. */
    record Help() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.ANYONE;
        }
    }

    /** {@code STOP}: stop non-essential messages. */
    record Stop() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    /** {@code START}: turn non-essential messages back on. */
    record Start() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.TRADER;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Agent commands
    // ---------------------------------------------------------------------------------------------------------

    /** {@code DEP B114 450}: a closing-time cash deposit for a stall. */
    record Deposit(String stallCode, long amountNgwee) implements SmsCommand {
        public Deposit {
            Objects.requireNonNull(stallCode);
        }

        @Override
        public Audience audience() {
            return Audience.AGENT;
        }
    }

    /** {@code UNDO 8F2K}: cancel the agent's own deposit. */
    record Undo(String reference) implements SmsCommand {
        public Undo {
            Objects.requireNonNull(reference);
        }

        @Override
        public Audience audience() {
            return Audience.AGENT;
        }
    }

    /** {@code ROUND}: the agent's totals for today. */
    record Round() implements SmsCommand {
        @Override
        public Audience audience() {
            return Audience.AGENT;
        }
    }
}
