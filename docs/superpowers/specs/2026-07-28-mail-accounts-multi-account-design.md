# Mail-Accounts — Multi-Account SMTP Configuration

**Date:** 2026-07-28
**Status:** Approved (design)
**Branch context:** `feature/mail-templates-jobs`

## Summary

Replace the single, hard-wired SMTP configuration (the `mail_settings`
singleton) with a real collection of mail accounts. The settings area's "SMTP"
tab is renamed **Mail-Accounts** and adopts the same left-list / right-detail
layout already used by the Jobs and Vorlagen tabs. The standalone test-email
feature is removed (UI and server endpoint).

Mail jobs already carry a `senderAccountId`; today it must equal the singleton
id and is ignored by the send path. After this change it references a real
account, and the send path uses that account's SMTP config.

## Goals

- Store multiple SMTP accounts with full CRUD.
- Rename the tab **SMTP → Mail-Accounts** with a list/detail layout matching
  Jobs/Vorlagen (left list, right detail, placeholder when nothing selected).
- Remove the test-email section (frontend and backend).
- Make the send path and job validation resolve the account by the job's
  `senderAccountId`.
- No singleton concept anywhere.

## Non-Goals

- Per-account send quotas, throttling, or rate limits.
- A "default account" concept — jobs pick their sender explicitly.
- Restoring/keeping a credential-verification ("test mail") tool.
- Reworking the Jobs or Vorlagen tabs beyond the account dropdown, which keeps
  working and now shows real multiples.

## Decisions

1. **Multi-account** (not a re-styled single config).
2. **List label:** dedicated `name` field per account (list title = name,
   subtitle = from-address). Mirrors Jobs/Vorlagen having a name.
3. **No singleton:** plain `mail_accounts` collection with ordinary `ObjectId`
   ids. `MailSettings` entity + `SINGLETON_ID` retired.
4. **Migration:** import the existing `mail_settings` config into
   `mail_accounts` as a normal account (fresh id) so credentials aren't lost.
5. **Delete safety:** block deleting an account referenced by any `MailJob`.
6. **Test endpoint:** removed server-side as well as in the UI.

## Data Model

New entity `MailAccount` → collection `mail_accounts`:

| Field              | Type            | Notes                                    |
|--------------------|-----------------|------------------------------------------|
| `id`               | `ObjectId`      | Ordinary id (no pinned singleton).       |
| `name`             | `String`        | List label, required.                    |
| `host`             | `String`        | SMTP host.                               |
| `port`             | `int`           | 1–65535.                                 |
| `encryption`       | `MailEncryption`| `NONE` / `STARTTLS` / `SSL_TLS` (reuse). |
| `username`         | `String`        | Optional (auth).                         |
| `encryptedPassword`| `String`        | AES-256-GCM via `EncryptionService`.     |
| `fromAddress`      | `String`        | Required, email format.                  |
| `fromName`         | `String`        | Optional display name.                   |
| `enabled`          | `boolean`       | Per-account gate.                        |

`MailEncryption` enum and `EncryptionService` are reused unchanged.

## Migration

One-time, idempotent, at startup (same pattern as existing
`GroupInstanceMigration`):

1. If the `mail_accounts` collection already has documents → no-op.
2. Else if a `mail_settings` document exists → insert one `MailAccount` copying
   its fields, `name` defaulted to the from-address (fallback: `"Standard"`),
   fresh `ObjectId`.
3. Drop the `mail_settings` collection/document afterward.

Existing `MailJob.senderAccountId` values (old singleton id) will not match the
new account id. This is expected: sender validation flags such jobs, and the
admin re-selects the account once. No attempt is made to preserve the old id.

## Backend Endpoints — `MailAccountResource` (admin-only)

Base path `/api/v1/mail-accounts`. Default-deny security (not whitelisted).

| Method & Path        | Behavior                                                        |
|----------------------|-----------------------------------------------------------------|
| `GET /`              | List all accounts (DTO, no password; `passwordSet` flag).       |
| `GET /{id}`          | Single account DTO; 404 if unknown.                             |
| `POST /`             | Create; validates fields; encrypts password if provided; 201.   |
| `PUT /{id}`          | Update; `password` sets/updates encrypted, `clearPassword` clears; 404 if unknown. |
| `DELETE /{id}`       | Delete; **409** if referenced by any `MailJob`; else 204.       |

**DTOs:**
- Response DTO: `id, name, host, port, encryption, username, fromAddress,
  fromName, enabled, passwordSet` (never the password).
- Update/create DTO: same editable fields plus `password` (optional) and
  `clearPassword` (optional boolean).

**Validation** (reuse the current `MailSettingsResource` rules): `name`
non-blank; `host` non-blank; `port` in 1–65535; `encryption` present;
`fromAddress` matches the email pattern; if `username` set then a password must
be set or already stored.

`MailSettingsResource` (its `GET`, `PUT`, and `POST /test`) is removed, along
with the now-unused test DTOs.

## Send Path

`MailService.prepareMessage(...)` currently loads `MailSettings.findSingleton()`
and ignores the job's account. Change it to operate on a supplied `MailAccount`:

- The scheduler resolves the account by `job.senderAccountId` and passes it to
  `sendHtml(...)`, which passes it to `prepareMessage(...)`.
- Guards become per-account: account exists, `enabled`, and complete
  (`isIncomplete` logic reused against the account).
- `buildProperties`, auth session, from-address/name construction all read from
  the account instead of the singleton.

`EncryptionService.isConfigured()` (the global AES key check) is unchanged.

## Job Sender Validation

`MailJobResource.validateSenderAccountId`: instead of comparing to
`SINGLETON_ID`, load `MailAccount.findById(senderAccountId)` and require it
exists **and** `enabled`. Unknown or disabled → 400 with a clear reason.

## Delete Safety

`DELETE /{id}` checks whether any `MailJob` has `senderAccountId == id`. If so,
respond **409** with a message naming the conflict; the existing
`WebApplicationExceptionMapper` surfaces the reason to the UI. Otherwise delete
and return 204.

## Frontend

New standalone component `mail-account-editor` under `settings/mail/`, reusing
the list/detail shell established by the Jobs and Vorlagen editors:

- **Left list:** one item per account — `name` (title), `fromAddress`
  (subtitle), an `aktiv` badge when enabled, a delete button, and a "Neues
  Konto" button in the header. Empty-state text when there are none.
- **Right detail:** editing state gate (`editing`) with a "Kein Konto
  ausgewählt" placeholder when nothing is selected — same pattern as the Jobs
  editor. Form fields: `name`, `host`, `port`, `encryption` (select),
  `username`, `password` (write-only; shows "gesetzt" when `passwordSet`),
  `fromAddress`, `fromName`, `enabled` (toggle). Save / Verwerfen / Delete.
- Selecting an account or "Neues Konto" opens the editor; save/delete/discard
  returns to the placeholder.

`MailComponent` (the tab host): the SMTP form and the entire test-email section
are removed; the tab hosts `<app-mail-account-editor>` and its label changes
**SMTP → Mail-Accounts**.

`MailAccountService` gains `get(id)`, `create`, `update(id)`, `delete(id)`
alongside the existing `list()`. `MailAccount` model gains `name` and
`passwordSet` (plus editable fields as needed for the form). The Jobs editor's
account dropdown is unchanged in code and now lists real multiples.

## Testing

**Backend**
- `MailAccountResource`: CRUD happy paths; validation rejects (blank name/host,
  bad port, bad email, username-without-password); password set/clear/omit;
  `passwordSet` reflected; password never returned.
- Migration: imports the singleton (fields copied, fresh id, name default),
  drops `mail_settings`, and is idempotent (no duplicate on re-run; no-op when
  accounts already exist).
- Send path: `prepareMessage`/`sendHtml` uses the passed account; disabled or
  incomplete account is rejected with the right category.
- Job validation: accepts an enabled account; rejects unknown and disabled.
- Delete: 409 when referenced by a job; 204 otherwise.

**Frontend**
- `mail-account-editor`: loads/lists accounts; placeholder until selected;
  new/select opens editor; create vs. update dispatch; password only sent when
  entered; delete; returns to placeholder after save/delete.
- `MailComponent`: renders the Mail-Accounts tab; no test-email controls.

## Rollout / Risks

- Jobs created before migration point at the old singleton id and must have
  their sender re-selected; validation makes this visible rather than silently
  failing at send time.
- Removing the test endpoint drops the only credential self-check; accepted.
