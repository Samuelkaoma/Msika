# M-09: Council users and security

Card: `M-09` · Branch: `feature/M-09-auth` · Depends on: M-07

## What we are building, in one paragraph

Sign-in and permissions. Until now anyone who could reach the API could do anything. Now a person
signs in with an email and password, receives a short-lived token, and sends it with every request.
The server works out who they are and what role they have, and refuses everything that no rule
explicitly allows. Every change a user makes is written to an audit log that nobody can edit.

## New ideas in this lesson

### Authentication and authorization
- **Authentication:** proving who you are (email and password, then a token).
- **Authorization:** deciding what you may do (your role, and which council).
- **401 versus 403:** `401` means "I don't know who you are" (no token, a bad token, an expired one).
  `403` means "I know who you are, and the answer is no". Tests check which one each case gets.

### Passwords and BCrypt
- **Never store passwords.** Store a *hash*: a one-way scramble. Anyone who steals the database cannot
  turn it back into passwords.
- **BCrypt** is a hash built for passwords. It adds a random *salt* (so two users with the same
  password get different hashes) and is deliberately slow (so guessing millions of passwords is
  expensive). The "strength" setting doubles the time for each +1. We use 12 in real runs and 4 in
  tests so they stay fast.
- **Tiny example:** `$2a$12$...` (60 characters) is what is stored. The test checks that is all we hold.
- **Password policy:** at least 12 characters (length matters more than symbols), at most 72 bytes
  (BCrypt silently ignores anything longer, so we refuse it instead).

### Tokens (JWT)
- **What it is:** a *JSON Web Token* is a small signed text. After sign-in the server gives you one that
  says "this is user X, issued by Msika, valid until 12:15". You send it as
  `Authorization: Bearer <token>` with every request. The server checks the signature, so it does not
  need to remember anything about your session ("stateless").
- **Signed, not secret:** anyone can read a token's contents. The signature only stops them changing it.
  So we put almost nothing in it: just the user id.
- **Short-lived:** 15 minutes, so a stolen token stops working soon.
- **Forgeries we test:** a token signed with another key, an expired one, one for a user that does not
  exist, and the classic `alg: none` trick (a token that claims it needs no signature). All get `401`.

### Re-reading the user on every request
- The token only names the user. On each request we load the user's row from the database and take the
  **role and the active flag from there**. So when a council admin deactivates someone, their very next
  request fails, even though their token has not expired. The test proves it, and that reactivating them
  works again.

### Roles and default deny
- Three roles: `MSIKA_OPERATOR` (Msika staff, creates councils), `COUNCIL_ADMIN` (everything in their own
  council), `COUNCIL_OFFICER` (read-only in their own council).
- **Default deny:** `SecurityConfiguration` lists what is allowed, and the last line is
  `.anyRequest().denyAll()`. A new endpoint that nobody wrote a rule for is unreachable by everyone,
  including the operator. The test has a deliberately rule-less endpoint to prove it.
- **Council scope:** a rule for `/api/v1/councils/{councilId}/**` allows an operator anywhere, and a
  council user only when the `{councilId}` in the URL is their own council.
- **Stateless, so no CSRF:** CSRF attacks rely on browsers automatically sending cookies. We use no
  cookies and no sessions, only a header the page must add itself, so we switch CSRF protection off.

### Why unknown email and wrong password look identical
If the server said "no such email" for one and "wrong password" for the other, an attacker could find
out which emails have accounts. We give one message, `invalid email or password`, and also spend the
same time (a throwaway password check) when the email is unknown, so the *speed* of the answer does not
leak it either. A unit test counts the checks.

### `SECURITY DEFINER` functions: a narrow, deliberate exception to row-level security
- A puzzle from M-08: sign-in has an email but no council, yet RLS hides every row until a council is
  set. We cannot read the user to find out their council.
- **The fix:** two tiny Postgres functions, `auth_find_user_by_email` and `auth_find_user_by_id`, marked
  `SECURITY DEFINER`. That means they run with their *owner's* rights (the owner is exempt from RLS), but
  each can only return one user row. The app role may call them and nothing more. Everything else about
  users still goes through RLS.
- A third function creates the first operator (see below). Operators belong to no council, so RLS hides
  them from the app by design.

### The audit log
- One row per change: who (user id), what (`market.create`), to what, when. It is written in the same
  transaction as the change, so a change and its record succeed or fail together.
- **Append only, enforced by the database:** we `revoke update, delete, truncate` from the app role. The
  test shows each one fails with `permission denied`.
- Reads are not audited. Refused and failed changes are not audited either (nothing changed).

### The first operator (bootstrap)
Someone has to be able to create the first council. Two settings, `msika.bootstrap-operator.email` and
`.password`, create an operator at start-up if they are set and that email does not exist. They have
**no default**, because this repository is public and a default password would be a published one.

## Where it sits in the architecture

```
request --> [security filter chain]
              1. read the Bearer token, check signature + expiry          (Spring Security)
              2. load the user row, refuse if gone or inactive            (userRecheck)
              3. match method + path to a rule; council scope check        (SecurityConfiguration)
           --> controller --> service (@Transactional: tenant.use(council), use case, audit row)
```
`msika-core` holds the rules (`Authenticate`, `CreateUser`, `SetUserActive`, `PasswordPolicy`, `Role`) as
plain Java. `msika-api` holds Spring Security, BCrypt, JWT and the SQL.

## Step by step

1. Branched from M-08, marked the card ◐. Checked the Spring Boot 4 starter names.
2. **`msika-core`:** `Role`, `CouncilUser` (an operator must have no council, everyone else one),
   `PasswordPolicy`, ports `PasswordHasher` and `UserRepository`, use cases `Authenticate`,
   `CreateUser`, `SetUserActive`, and the `AuditLog` port. 17 unit tests with fakes.
3. **Migration `V4`:** `council_user` (with RLS), the three `SECURITY DEFINER` functions, and `audit_log`
   (with RLS and no update/delete for the app).
4. **`msika-api`:** `BcryptPasswordHasher`, `TokenService`, `JdbcUserRepository`, `JdbcAuditLog`,
   `SecurityConfiguration`, `AuthController` (`/auth/login`, `/auth/me`), the user endpoints,
   `BootstrapOperator`.
5. **Audit rows** added to every change in `CouncilAdminService`.
6. **Tests through HTTP.** The existing API tests now sign in as an admin. New `AuthApiTest` (12) and
   `AuditLogTest` (5).
7. **Proved the tests can fail.** We broke three rules on purpose (default-deny to default-allow, stop
   re-checking active, let read-only officers write) and four tests failed. Then we restored them.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-core/.../user/*`, `.../audit/AuditLog.java` | New | Roles, users, sign-in rules, ports |
| `platform/msika-api/.../db/migration/V4__users_and_audit_log.sql` | New | Tables, lookup functions, append-only audit |
| `platform/msika-api/.../security/*` | New | Spring Security, tokens, BCrypt, sign-in endpoints, bootstrap |
| `platform/msika-api/.../persistence/JdbcUserRepository, JdbcAuditLog` | New | Adapters |
| `platform/msika-api/.../admin/CouncilAdminService.java` | Audit row per change | Audit log |
| `platform/msika-api/.../admin/UserAdminController, UserAdminService` | New | Create and deactivate users |
| `platform/msika-api/build.gradle.kts` | Spring Security starters | Authentication |
| `infra/docker-compose.yml`, `infra/.env.example` | Optional sign-in settings, no values | Public repo: no secrets |
| tests | `ApiTest` and `ApiClient` helpers; `AuthApiTest`, `AuditLogTest`; existing API test now signs in | Proof |

## How we know it works

| Done item | Proof |
|---|---|
| Wrong password | `wrongPasswordAndUnknownEmailLookIdentical`: `401`, identical body to an unknown email. Unit test counts the throwaway check |
| Deactivated user refused on the very next request | `deactivatedUserRefusedImmediately`: same token, one request later, `401`; sign-in refused; reactivation works |
| Officer trying to change something gets 403 | `officerIsReadOnly`: create market, import, allocate, free, create user, deactivate user, all `403`; reads `200` |
| An endpoint with no rule gets 403 | `endpointWithNoRuleIsDenied`: `403` for admin, officer and operator, `401` for nobody, `403` for a path that does not exist |
| (extra) council scope, forged tokens, passwords, audit | `councilsAreScopedToTheirUsers`, `badTokensAreRefused`, `passwordsAreNeverStoredOrReturned`, `AuditLogTest` |

## Rebuild it yourself

1. Add `spring-boot-starter-security` and make every endpoint return `401` without a login.
2. Hash a password with `BCryptPasswordEncoder` and check it with `matches`.
3. Issue a JWT after login and accept it with the OAuth2 resource server.
4. Write a `SecurityFilterChain` with an allow list and `.anyRequest().denyAll()`. Add an endpoint with no
   rule and confirm it is `403`.
5. Load the user on every request and deactivate one mid-session.
6. Make an `audit_log` table and `revoke update, delete` on it from the app role.

## Mistakes to avoid

- **Putting the role in the token and trusting it.** It cannot be revoked until it expires. Read it from
  the database.
- **Different messages for "no such user" and "wrong password".** It tells attackers who has an account.
- **Logging requests that contain passwords.** The request records print without the password on purpose.
- **A default password or secret "just for development".** The repository is public. We use no defaults.
- **Letting `permitAll` creep in.** The last rule is `denyAll`. Changing it breaks four tests.
- **Letting the app edit its own audit log.** The database refuses, so a bug or attacker cannot.

## Not done here (on purpose)

- **Login throttling** (blocking repeated wrong passwords) is not built. It belongs with the security
  review (M-44). Until then a determined attacker can try passwords as fast as BCrypt allows.
- **Refresh tokens and logout.** A token simply expires after 15 minutes.
- **Operators cannot be created or deactivated through the API**, only bootstrapped. A deactivated
  operator cannot be undone from the API.
