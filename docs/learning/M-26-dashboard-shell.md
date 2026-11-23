# M-26: Dashboard shell and sign-in

Card: `M-26` · Branch: `feature/M-26-dashboard-shell` · Depends on: M-09

## What we are building, in one paragraph

Council officers and admins need a web page: sign in, see their markets, collections and arrears. This card builds
the frame every later page sits in: sign-in, a layout with navigation that also works on a phone, signing out, going
back to sign-in the moment a session ends, and hiding every edit button from officers, who may only read. The pages
themselves arrive in M-27. The browser's calls to msika-api are typed from an OpenAPI description generated from the
Java code.

## New ideas in this lesson

- **OpenAPI**
  - **What it is:** a JSON description of every endpoint: its path, parameters, request body and answer.
  - **Why we need it here:** springdoc writes it from the controllers; the dashboard's TypeScript types are written
    from it. Change an endpoint and the dashboard stops compiling until it is updated.
  - **The chain, each step checked:** Java code → `docs/api/openapi.json` (`OpenApiSpecTest` fails if they differ) →
    `apps/council-web/src/api/schema.ts` (`pnpm typecheck` fails if they differ) → the pages (`tsc`).
- **Our own small generator**
  - The usual tool (openapi-typescript) calls TypeScript's JavaScript API, which TypeScript 7 no longer has. The
    description uses a small part of OpenAPI, so `scripts/gen-api.mjs` (about 150 lines, no dependencies) does it.
- **A typed client**
  - `api("get", "/api/v1/councils/{councilId}/markets", { path: { councilId } })`: a misspelt path, a missing
    parameter or a wrong body is a compile error.
- **Sessions in the browser**
  - The token and its expiry are kept in `sessionStorage` (gone when the tab closes). A timer ends the session at the
    expiry; any 401 ends it too (a deactivated user is refused on the next request, M-09). Both go back to sign-in.
- **Roles in the interface are a convenience, not security**
  - Officers see no edit buttons, but the server still refuses their changes (403). The page only avoids offering
    what would be refused.
- **Component tests**
  - Vitest runs the React app in jsdom (a browser imitation) with a fake `fetch`, and Testing Library clicks and
    types like a person would.

## How we know it works

- `App.test.tsx` (9 tests): sign in; a wrong password; sign out; the token running out with no click; a 401 from the
  API; a stored session that has already expired is not used; an officer sees no "Add market" button, an admin
  does; returning to the page asked for.
- `OpenApiSpecTest`: the committed description matches the code.
- By hand, against the real msika-api: an officer signed in, saw the markets with no edit button, and the layout
  works at 375 px with a menu button.

## Mistakes to avoid

- Hand-writing response types: they drift from the server silently.
- Hiding buttons and calling it security: the API must refuse too.
- Keeping the token in `localStorage`: it would outlive the browser tab on a shared council computer.
