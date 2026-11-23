-- Indexes for the map status (card M-49): one query must answer for a 5,000-stall market in under 300 ms.
-- Each matches one part of that query: who holds a stall now, its unpaid levy, its disputed deposits, and the
-- stalls of a market by code.

create index levy_charge_unpaid_idx on levy_charge (council_id, stall_id) where settled_by_instruction_id is null;
create index deposit_disputed_idx on deposit (council_id, stall_id) where status = 'DISPUTED';
create index stall_market_code_idx on stall (council_id, market_id, code);
create index ledger_entry_stall_day_idx on ledger_entry (council_id, business_date, stall_id) include (kind, amount_ngwee);
