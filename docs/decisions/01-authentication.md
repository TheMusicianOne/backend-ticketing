# 01: Authentication and authorization

**Status:** Accepted
**Date:** 2026-11-11
**Milestone:** 3 (Security)

## Context

The ticketing API has two kinds of users. **Admins** create venues, events and seats. **Customers** browse events and, from milestone 4, hold and buy seats. Browsing must stay public, because anyone should be able to see what's on sale.

The API has no browser frontend of its own. It's called by API clients, tests and a load simulator that will send thousands of requests per second. Whatever we choose has to be cheap to check on every request, and it has to work across more than one app instance if we ever scale out.

---

## 1. Stateless JWT bearer tokens instead of server-side sessions

**Options**

| Option | How it works |
|---|---|
| Server-side sessions | Login creates a session stored on the server. The client sends a session cookie |
| Opaque tokens | Login returns a random string. The server looks it up in a database or cache on every request |
| **JWTs (chosen)** | Login returns a signed token containing the user's ID and role. The server checks the signature, with no lookup |

**Why**

- Checking a JWT needs no database or cache lookup, which matters for an API designed to be load-tested.
- Any app instance can check any token, so there's no shared session store to set up if we scale out.
- It's the common pattern for APIs called by non-browser clients.

**Trade-offs we accept**

- **Tokens can't be revoked early.** A stolen token, or one belonging to a user whose role changed, stays valid until it expires. We limit this with a short lifetime (decision 4).
- Tokens are larger than session IDs, and their contents can be read by anyone who has them, so they must never contain secrets.

---

## 2. Issue our own tokens with Spring Security, instead of using an external identity provider

**Options**

| Option | Notes |
|---|---|
| External provider (Keycloak, Auth0, Cognito) | Production-grade, but another service to run or pay for, and most of the learning happens outside our code |
| Spring Authorization Server | A full OAuth2 server. Far more than a single API needs |
| A JWT library such as jjwt, with our own filter | Means writing and maintaining our own token validation, which is easy to get wrong |
| **Spring Security's resource server, with our own login endpoint (chosen)** | Spring validates tokens. We only write the login endpoint that issues them |

**Why**

- Spring's tested code handles the risky part, validating signatures, expiry and algorithms, so we don't write our own JWT parsing.
- It keeps everything in one app that runs on the free cloud VMs.
- If we later move to an external provider, only token *issuing* changes. The resource server already validates standard JWTs, so the access rules stay the same.

**Trade-offs we accept**

- We own the login endpoint and password storage, so their security is our responsibility.

---

## 3. HS256 (shared secret) instead of RS256 (public/private key pair)

**Options**

| Option | Notes |
|---|---|
| **HS256 (chosen)** | One secret both signs and checks tokens |
| RS256 | A private key signs, and a public key checks. Other services can verify tokens without being able to create them |

**Why**

- One app both issues and checks tokens, so nothing else needs to verify them, and RS256's main benefit doesn't apply.
- It's simpler to configure: one secret in an environment variable.

**Trade-offs we accept**

- Anything that can check tokens can also create them. If we ever add a separate service that only needs to *verify* tokens, we should switch to RS256.
- The secret must be at least 32 bytes, kept out of Git, and set through `JWT_SECRET` on the server. Changing it logs everyone out.

---

## 4. One-hour tokens, with no refresh tokens

**Options**

| Option | Notes |
|---|---|
| Long-lived tokens (days) | Convenient, but a stolen token stays useful for a long time |
| Short access tokens with refresh tokens | Industry standard for user-facing apps, but needs refresh-token storage, rotation and revocation |
| **One-hour access tokens only (chosen)** | Simple, with limited damage if a token leaks |

**Why**

- One hour limits the impact of the main JWT weakness, that tokens can't be revoked (decision 1).
- Refresh tokens add a lot of code and new security risks, for little benefit in a project without a frontend.

**Trade-offs we accept**

- Users must log in again every hour.

---

## 5. Stateless sessions and CSRF protection turned off

**Why**

CSRF attacks work because browsers attach cookies to requests automatically, so a malicious site can make a logged-in user's browser send a request they didn't intend. Our API doesn't use cookies. A token is only sent when client code deliberately adds an `Authorization: Bearer` header, which another site can't make the browser do. So CSRF protection has nothing to protect here.

`SessionCreationPolicy.STATELESS` makes sure Spring never creates a session, so each request must carry its own token.

**When this would be wrong**

If we ever stored the token in a cookie, for example for a browser frontend, CSRF protection must be turned back on.

---

## 6. Deny by default, with access rules in one place

**Options**

| Option | Notes |
|---|---|
| Open by default, securing specific endpoints | Forgetting a rule exposes an endpoint |
| Annotations on each controller method (`@PreAuthorize`) | Rules sit next to the code, but are spread across many files |
| **URL rules in `SecurityConfig`, ending with `anyRequest().authenticated()` (chosen)** | Every rule in one file, and anything unlisted needs a token |

**Why**

- Forgetting a rule makes a new endpoint too locked rather than too open, which fails safely. A test (`unmappedRoutesRequireAuthentication`) proves this.
- All the rules can be reviewed in one place.

**Trade-offs we accept**

- URL rules can't express "you can only see your own hold". That kind of ownership check will live in the service layer, starting in milestone 4.

---

## 7. One role per user, stored as an enum

**Options**

| Option | Notes |
|---|---|
| **A `role` column with `CUSTOMER` or `ADMIN` (chosen)** | Simple, and enough for two kinds of user |
| A roles table with many-to-many permissions | Flexible, but unnecessary with only two roles |

**Why**

- The system has two clearly separate kinds of user, and a `CHECK` constraint stops invalid roles.
- Roles go into a `roles` claim in the token, as a list, so the format already supports more than one role if we need it later.

---

## 8. bcrypt through Spring's `DelegatingPasswordEncoder`

**Options**

| Option | Notes |
|---|---|
| Fast hashes (SHA-256) | Never suitable for passwords, because attackers can try billions per second |
| Argon2 | Modern and memory-hard, but needs an extra library |
| **bcrypt through `DelegatingPasswordEncoder` (chosen)** | Slow on purpose, salted, built in, and widely accepted |

**Why**

- bcrypt is deliberately slow and adds a random salt to each password, so stolen hashes are expensive to crack.
- The delegating encoder stores the algorithm in each hash (`{bcrypt}...`), so we can switch to Argon2 later without breaking existing passwords.
- Passwords must be 12 to 72 characters. bcrypt ignores input beyond 72 bytes, so we reject longer passwords instead of silently ignoring part of them.

**Trade-offs we accept**

- Every login uses noticeable CPU time. This will show up in load tests, and is a reason to measure login separately.

---

## 9. Emails stored in lowercase, enforced by the database

**Why**

- `Yusuf@Example.com` and `yusuf@example.com` should be the same account.
- The service trims and lowercases emails with `Locale.ROOT`, so the result doesn't change on computers set to other languages, such as Turkish.
- A `CHECK (email = lower(email))` constraint and a `UNIQUE` index act as a safety net if the application code ever forgets.

**Alternative considered**

PostgreSQL's `citext` type compares text without case, but needs an extension and is less portable.

---

## 10. Login errors don't reveal which emails exist

**Why**

An unknown email and a wrong password both return `401` with the same message, "Invalid email or password". Otherwise attackers could find registered emails and target them. A test (`unknownEmailAndWrongPasswordAreIndistinguishable`) checks this.

**Known gaps**

- **Timing:** an unknown email skips the bcrypt check, so it responds faster than a wrong password. A careful attacker could still tell them apart. A fix is to hash against a dummy value when the email isn't found.
- **Registration:** `POST /auth/register` returns `409` for an existing email, which does reveal it. Fixing that properly needs email verification, which is out of scope.

---

## 11. The first admin comes from environment variables

**Options**

| Option | Notes |
|---|---|
| A migration that inserts an admin | Puts a password hash in Git |
| A registration option to become admin | Anyone could use it |
| Promoting a user with SQL by hand | Works, but is easy to forget when setting up a new server |
| **Created at startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD` (chosen)** | No secrets in Git, safe to run on every start |

**Why**

- Credentials stay in the environment, not in the code.
- It's skipped if the variables aren't set, or if the admin already exists.
- Registration always creates customers. The only code path that creates admins is clearly named (`User.admin(...)`).

---

## 12. Identity always comes from the token, never from the request

**Why**

The user's ID is read from the token's `sub` claim, which can't be changed without breaking the signature. Request bodies are controlled by the client, so a user ID taken from a body could be anyone's. From milestone 4, holds and orders will record their owner from the token in the same way.

`GET /users/me` loads the user from the database instead of returning the token's claims, so the response reflects current data, such as a changed role.

---

## Known limitations and future work

| Limitation | Possible fix |
|---|---|
| Tokens can't be revoked before they expire | A denylist of revoked token IDs, or refresh tokens with short-lived access tokens |
| No rate limiting on login | Planned for milestone 8 |
| Login timing reveals unknown emails | Hash against a dummy value when the email isn't found |
| Registration reveals existing emails | Email verification |
| No refresh tokens, so users log in again every hour | Add refresh tokens if a frontend is built |
| HS256 means any verifier could also issue tokens | Switch to RS256 if another service needs to verify tokens |
| `401` and `403` responses have no body | Custom `AuthenticationEntryPoint` and `AccessDeniedHandler` returning `ProblemDetail` |