# M-20: Sim console

Card: `M-20` · Branch: `feature/M-20-sim-console` · Depends on: M-19

## What we are building, in one paragraph

To show Msika to a council, a judge or a new team member, we need phones, a Zamtel Money account and a network
that sometimes misbehaves. The sim console gives all three in a browser: a list of the traders and agents Msika
knows, a phone screen for each where you type texts and see Msika's replies, the simulated wallets and
transactions with buttons to add float, pay or approve a request-to-pay, and the fault settings. A deposit can be
shown from the agent's text to the trader's confirmation with no real phone anywhere.

## New ideas in this lesson

- **A single-page app (React)**
  - **What it is:** the browser downloads one page and a bundle of JavaScript, which draws everything and talks to
    the servers with `fetch`. React redraws the parts of the page whose data changed.
  - **Why we need it here:** a phone screen that refreshes every second and three tabs are easier to build this way
    than as server pages.
  - **Tiny example:** `const [messages, setMessages] = useState([]);` and `fetch(...).then(r => r.json()).then(setMessages)`.

- **Polling**
  - **What it is:** asking the server again every few seconds for anything new.
  - **Why we need it here:** a reply arrives a moment after a text is sent (the server processes it after
    answering). The console polls the conversation every 1.5 seconds. M-51 uses server-sent events for the live
    map, where polling would be wasteful.

- **A development proxy**
  - **What it is:** the Vite dev server forwards `/api/...` to msika-api and `/zamsim/...` to zamsim, so the page and
    both services share one address.
  - **Why we need it here:** the browser then needs no cross-origin permissions, and the services need no changes.

- **One path for every way a text arrives**
  - **What it is:** `SmsInbox.receive(...)` stores a text once and starts processing it. The gateway webhook, the
    console's fake phones and (in M-21) the relay all call it.
  - **Why we need it here:** a demo is only honest if the fake phone's text goes through the same code as a real
    one. The console cannot sign webhook calls (the secret must never be in a browser), so it has its own
    endpoint, which only a signed-in Msika operator may use and which exists only when switched on.

## Where it sits in the architecture

```
browser (apps/sim-console)
  ├─ /api/v1/auth/login           → msika-api (operator signs in)
  ├─ /api/v1/sim/phones, /sim/sms → msika-api SimConsoleController → SmsInbox → SmsProcessor (same as the webhook)
  └─ /zamsim/v1/wallets, /transactions, /request-to-pay, /admin/faults → zamsim
```

`SimConsoleController` exists only when `msika.sim-console.enabled=true` and is allowed only for `MSIKA_OPERATOR`.
Msika still never imports zamsim (AGENTS.md rule 6): the browser talks to zamsim directly.

## Step by step

1. zamsim: `GET /v1/wallets` and `GET /v1/transactions`, newest first, never listing the issuer wallet.
2. msika-api: moved "store once, then process" from the webhook into `SmsInbox`; added `SimConsoleController`
   (phones across councils, one council at a time; text as a phone; a phone's conversation).
3. `apps/sim-console`: `api.ts` (typed calls, kwacha formatting with whole ngwee), `App.tsx` (sign-in, phones, wallets
   and payments, faults), `styles.css` (light and dark, works at phone width), and the proxy in `vite.config.ts`.
4. Compose: Msika points at zamsim and zamsim's callbacks at Msika by default; the console endpoints are on in the
   local stack. Secrets still have no defaults (the repository is public).
5. Ran the real services and walked the deposit in the browser (see below). It found a real bug: newer browsers
   return a Promise from `scrollIntoView`, and returning it from an effect crashes React.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/zamsim/.../WalletController.java`, `WalletService.java` | wallet and transaction lists | the console shows them |
| `platform/msika-api/.../sms/SmsInbox.java` | new | one path for every inbound text |
| `platform/msika-api/.../sms/SimConsoleController.java` | new | the fake phones |
| `platform/msika-api/.../security/SecurityConfiguration.java` | `/api/v1/sim/**` for operators | dev tool, still locked |
| `apps/sim-console/src/*`, `vite.config.ts` | the console | the demo without phones |
| `infra/docker-compose.yml`, `infra/.env.example`, `README.md` | wiring and instructions | runs from one command |

## How we know it works

- `SimConsoleApiTest`: an operator lists phones, texts as an agent and sees `HELP_AGENT` in the conversation; a
  council admin gets 403 and nobody signed in gets 401.
- `WalletApiTest.consoleLists` (zamsim): lists, newest first, no issuer, a bad limit is 400.
- `SmsInboundApiTest` still passes after the webhook moved onto `SmsInbox`.
- **Manual check, in a browser, with the real msika-api and zamsim:** signed in; the agent's phone sent
  `DEP B114 450` and got `deposit K450 for SOW-B-114 sent. Ref ...`; zamsim moved the money and called back; the
  trader's phone showed `K450 deposited at SOW-B-114 by agent Mwila. Ref ... Reply NO ...`; `TODAY` answered
  `deposited K450`. The wallets tab showed the agent's float down by K450 and the trader's wallet at K450. The
  faults tab and the layout at 375 px wide were checked too.

## Rebuild it yourself

1. Make a page with a text box that posts to `/api/v1/sim/sms` and lists `/api/v1/sim/sms?phone=...` every second.
2. Add a Vite proxy so the page can call both services without cross-origin errors.
3. Explain in one sentence why the console cannot call `/api/v1/sms/inbound` directly.

## Mistakes to avoid

- Putting the gateway secret in the browser to sign webhook calls: anyone could then fake texts.
- Leaving the console's endpoints on in a pilot: they are switched off unless configured.
- Returning a value from a React effect other than a clean-up function (the `scrollIntoView` crash).
