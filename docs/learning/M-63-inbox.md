# M-63: The inbox page

Card: `M-63` · Branch: `feature/M-63-inbox` · Depends on: M-62, M-26

## What we are building, in one paragraph

The pages where council users see and decide requests: what waits for me, what I raised, what is overdue or escalated
(for admins), and one page per request with what will happen in words, the evidence, the history, and approve or
reject with a reason.

## New ideas in this lesson

- **The server says what I may do**: each request carries `refusal`, the reason this user cannot decide it, or nothing.
  The page shows buttons when it is empty and the reason when it is not, so the rules live in one place.
- **The button is a courtesy, the server is the rule**: Reject is disabled without a reason, and the API refuses it too.
- **Deep link**: `/map?market=...&stall=...` opens the map with that stall selected.
- **The view is in the address** (`/inbox?view=raised`), so reload and back work.
- **After an error, load again**: the request may have changed, or now says why it could not be carried out.

## How we know it works

`inbox.test.tsx`: each view, the filters, the count on the home page, the request page, a rejection without a reason,
a second approval, withdrawing, and an approval that could not be carried out.

## Mistakes to avoid

- Repeating the permission rules in the page.
- Showing a deadline of Lusaka midnight as the next day.
