package zm.msika.core.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every SMS Msika sends, in one place (AGENTS.md rule 10). A template has placeholders like {@code {AMOUNT}} or
 * {@code {AMOUNT2}}: the word names the {@link Slot}, which says how long a value can be, and a digit tells two
 * slots of the same kind apart.
 *
 * <p>Because each slot has a longest possible value, every template can be checked once, in a test, with the
 * longest values filled in: if it fits 160 GSM-7 characters then, it fits for every trader. Free-text slots (a
 * name, a reason) are cut to their length and cleaned of characters a plain SMS cannot carry; any other value
 * that is too long is a bug and is refused.
 */
public enum SmsTemplate {

    // ---------------------------------------------------------------------------------------------------------
    // Anyone
    // ---------------------------------------------------------------------------------------------------------
    NOT_REGISTERED("Msika: this number is not registered. Ask your market office or agent to register you, or send REG and your stall number."),
    UNKNOWN_COMMAND("Msika: sorry, we did not understand. Send HELP for the list of commands."),
    SENDER_AMBIGUOUS("Msika: this number is registered with more than one council. Please call your market office."),
    AGENTS_ONLY("Msika: {COMMAND} is for agents only. Send HELP for your commands."),
    TRADERS_ONLY("Msika: {COMMAND} is for traders only. Send HELP for your commands."),
    USAGE_PLAIN("Msika: send {COMMAND} on its own. Send HELP for the list of commands."),
    USAGE_REG("Msika: send REG and your stall number, like REG SOW-B-114."),
    USAGE_SALE("Msika: send SALE and the amount, like SALE 120. Nothing was recorded."),
    USAGE_DEP("Msika: send DEP, the stall number and the amount, like DEP B114 450. Nothing was recorded."),
    USAGE_NO("Msika: send NO and the 4-character ref, like NO 8F2K."),
    USAGE_UNDO("Msika: send UNDO and the 4-character ref, like UNDO 8F2K."),
    USAGE_LOAN("Msika: send LOAN, the amount and your supplier code, like LOAN 800 SUP-12."),
    INVALID_AMOUNT("Msika: {COMMAND} needs an amount in kwacha, like 450 or 12.50. Nothing was recorded."),
    AMOUNT_NOT_POSITIVE("Msika: the amount must be more than K0. Nothing was recorded."),
    AMOUNT_TOO_LARGE("Msika: one message can record at most {AMOUNT}. Nothing was recorded."),
    INVALID_STALL("Msika: that stall number is not valid. Use the number on the stall plate, like B114."),
    INVALID_REFERENCE("Msika: a ref is 4 letters and numbers, like 8F2K. Check the SMS you were sent."),
    STALL_NOT_FOUND("Msika: no stall {STALL} in your markets. Check the number on the stall plate."),
    REF_NOT_FOUND("Msika: no deposit {REF} found for you this week. Check the ref in the SMS."),
    STALL_AMBIGUOUS("Msika: {STALL} matches more than one stall. Send the full number from the plate, like SOW-B-114."),
    STALL_IN_TWO_COUNCILS("Msika: more than one council has a stall {STALL}. Please ask your market office to register you."),
    NO_STALL("Msika: you have no stall at the moment. Please call your market office."),
    NOT_DONE("Msika: not done: {REASON}. Nothing was recorded."),
    HELP_TRADER("Msika: SALE 120, TODAY, BAL, LEVY, MONTH, NO + ref, STOP. Questions? Call your market office."),
    HELP_AGENT("Msika agent: DEP + stall + amount, UNDO + ref, ROUND. Questions? Call your market office."),

    // ---------------------------------------------------------------------------------------------------------
    // Traders
    // ---------------------------------------------------------------------------------------------------------
    REG_SENT("Msika: request sent for stall {STALL}. The market office will confirm."),
    REG_REJECTED("Msika: your request for stall {STALL} was not approved. Please visit your market office."),
    REG_APPROVED("Msika: you are approved for stall {STALL}. Reply YES to accept the terms and have your levy paid from your deposits."),
    CONSENT_DONE("Msika: you are registered at {STALL}. Levy will be paid from your deposits. Send HELP for commands."),
    NOTHING_TO_CONSENT("Msika: there is nothing waiting for your YES. Send HELP for commands."),
    SALE_NOTED("Msika: sale {AMOUNT} noted. Today declared {AMOUNT2}. Ref {REF}."),
    TODAY("Msika {STALL} today: deposited {AMOUNT}, digital {AMOUNT2}, declared {AMOUNT3}, levy paid {AMOUNT4}."),
    BALANCE_WALLET("Msika: wallet {AMOUNT}."),
    BALANCE("Msika: wallet {AMOUNT}. Levy paid to {DATE}. Arrears {AMOUNT2}."),
    BALANCE_NO_ARREARS("Msika: wallet {AMOUNT}. Levy paid to {DATE}. No arrears."),
    LEVY_UNKNOWN("Msika: levy details are not available yet. Please ask your market office."),
    LEVY("Msika: levy {AMOUNT} a day at {STALL}. Paid to {DATE}. Arrears {AMOUNT2}."),
    MONTH("Msika {MONTH}: deposited {AMOUNT}, digital {AMOUNT2}, declared {AMOUNT3}, levy {AMOUNT4}. Reply OK or WRONG."),
    STATEMENT_OK("Msika: thank you. Your {MONTHNAME} statement is confirmed."),
    STATEMENT_WRONG("Msika: your {MONTHNAME} statement is marked wrong. The market office will contact you."),
    NO_STATEMENT("Msika: you have no statement to confirm yet."),
    DISPUTE_NOT_CONFIRMED("Msika: deposit {REF} is not confirmed yet. Send NO {REF} again once you get the confirmation."),
    DISPUTE_FLAGGED("Msika: deposit {REF} is flagged. The market office will contact you."),
    LOAN_OFFER("Msika: you can get up to {AMOUNT} for stock. Reply LOAN {AMOUNT} and your supplier code."),
    LOAN_NONE("Msika: no stock loan is available to you yet. Keep depositing to build your record."),
    STOPPED("Msika: daily summaries stopped. Send START to turn them back on."),
    STARTED("Msika: daily summaries are on again. Send STOP to stop them."),
    DEPOSIT_CONFIRMED("Msika: {AMOUNT} deposited at {STALL} by agent {NAME}. Ref {REF}. Reply NO {REF} if this is wrong."),
    DEPOSIT_CANCELLED_TRADER("Msika: deposit {REF} of {AMOUNT} at {STALL} was cancelled by the agent. Nothing was recorded."),
    STALL_SUSPENDED("Msika: stall {STALL} is suspended by the council. Please visit your market office."),
    STALL_REINSTATED("Msika: stall {STALL} is back in use. Questions? Call your market office."),
    STALL_CLASS_CHANGED("Msika: stall {STALL} is now class {CLASS}. Questions? Call your market office."),
    LEVY_RECEIPT("Msika: levy paid {FROMDATE} to {DATE} at {STALL}: {AMOUNT}. Arrears {AMOUNT2}. Thank you."),

    // ---------------------------------------------------------------------------------------------------------
    // Agents
    // ---------------------------------------------------------------------------------------------------------
    DEPOSIT_SENT("Msika: deposit {AMOUNT} for {STALL} sent. Ref {REF}."),
    DEPOSIT_FAILED("Msika: deposit {REF} FAILED: {REASON}. Cash not recorded."),
    DEPOSIT_UNAVAILABLE("Msika: payments are not answering. Deposit for {STALL} NOT sent. Try again in a few minutes."),
    UNDO_DONE("Msika: deposit {REF} cancelled. The trader has been told."),
    UNDO_REFUSED("Msika: deposit {REF} cannot be cancelled: {REASON}."),
    AGENT_SUSPENDED("Msika: you can no longer use agent commands. Please call your market office."),
    AGENT_REINSTATED("Msika: you can use agent commands again. Send HELP for the list."),
    ROUND("Msika agent {NAME} today: {COUNT} deposits, {AMOUNT}. Float {AMOUNT2}.");

    /** The kinds of value a placeholder can hold, each with the longest it can be. */
    public enum Slot {
        /** A command word: {@code SURRENDER} is the longest. */
        COMMAND(9, false),
        /** {@code K99,999,999.99}, or {@code -K9,999,999.99}. */
        AMOUNT(15, false),
        /** {@code SOWET-ABCD-12345}: a market code of 5, a section of 4 and a number of 5. */
        STALL(16, false),
        REF(4, false),
        /** A stall class, 1 to 20 capitals, digits or underscores. */
        CLASS(20, false),
        /** {@code 30 Sep}. */
        DATE(6, false),
        FROMDATE(6, false),
        MONTH(3, false),
        /** {@code September}. */
        MONTHNAME(9, false),
        COUNT(5, false),
        /** A person's name, cut to fit. */
        NAME(20, true),
        /** Why something failed, cut to fit. */
        REASON(40, true);

        final int max;
        final boolean free;

        Slot(int max, boolean free) {
            this.max = max;
            this.free = free;
        }

        public int max() {
            return max;
        }

        public boolean freeText() {
            return free;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z]+)(\\d?)}");

    private final String text;

    SmsTemplate(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** The placeholders in the template, such as {@code AMOUNT} and {@code AMOUNT2}, each listed once. */
    public List<String> placeholders() {
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            String name = m.group(1) + m.group(2);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return Collections.unmodifiableList(names);
    }

    /** The slot a placeholder name belongs to: {@code AMOUNT2} is an {@link Slot#AMOUNT}. */
    public static Slot slotOf(String placeholder) {
        return Slot.valueOf(placeholder.replaceAll("\\d$", ""));
    }

    /**
     * Fills the template.
     *
     * @param values a value for every placeholder
     * @throws IllegalArgumentException if a value is missing or too long for its slot (free text is cut instead),
     *     or the result would not fit one SMS
     */
    public String render(Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) + m.group(2);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException(name() + " needs a value for " + name);
            }
            Slot slot = slotOf(name);
            if (slot.free) {
                value = Gsm7.sanitise(value.strip());
                if (value.length() > slot.max) {
                    value = value.substring(0, slot.max).strip();
                }
            } else if (value.length() > slot.max || !Gsm7.isEncodable(value) || Gsm7.septets(value) != value.length()) {
                throw new IllegalArgumentException(name() + ": " + name + " value '" + value + "' does not fit its slot");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        String reply = out.toString();
        if (!Gsm7.fitsOneSms(reply)) {
            throw new IllegalArgumentException(name() + " rendered to more than one SMS");
        }
        return reply;
    }

    /** Fills a template from name and value pairs: {@code render("AMOUNT", "K450", "REF", "8F2K")}. */
    public String render(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("names and values come in pairs");
        }
        Map<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return render(values);
    }
}
