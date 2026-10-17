# Data model

Entities are Kotlin classes in `core/src/main/kotlin/ir/melkcrm/core/model/` (serialized with
kotlinx.serialization). The same classes are used by the app, the sync protocol and the server's
validation, so a field means the same thing everywhere.

## Conventions

| Kind of value | Stored as | Notes |
|---|---|---|
| Money | `Money(rial: Long)`, integer **rial** | 1 toman = 10 rial. Displayed in toman (default) or rial, always with the unit. All arithmetic is overflow-checked (`Math.addExact` etc.); `Long` holds about 9.2 × 10¹⁷ toman. Percentages are basis points (1/100 of a percent) with half-up rounding. |
| Moment in time | `Long` epoch milliseconds, **UTC** | Created/updated times, activity times, appointment start, offer date and expiry. |
| Date without time | `Long` epoch day of the **Gregorian ISO local date** in the agency time zone (`LocalDate.toEpochDay()`) | Follow-up dates, deadlines, contract date. Jalali is a display and input conversion only and is never stored. The agency zone is `Asia/Tehran`. |
| Construction year | the year as entered, plus its calendar (`JALALI` / `GREGORIAN`) | Building age is computed in that calendar. |
| Coordinates | `Location(point, source, accuracyMeters?)` | `source` is one of `MANUAL_PIN`, `MANUAL_COORDINATES`, `GEOCODED`, `DEVICE_GPS`, `SAMPLE_APPROXIMATE`, `UNKNOWN`. Accuracy is left empty unless it is actually known; geocoded points are approximate and confirmed by the agent. |
| Text names (city, neighbourhood) | as typed | Compared after Persian normalization (`core/.../text/PersianText.kt`). |
| Phone numbers | as typed | Normalized (`+98` / `0098` / `09…`, Persian digits) only for comparison and duplicate detection. |

Prices are kept apart and never substituted for each other: **asking price**, **estimated market
value**, the seller's **confidential minimum**, the buyer's **minimum / preferred / maximum budget** and
**stretch**, an **offered price**, and the **final price**. Rentals have separate deposit and monthly
rent fields; full deposit (رهن کامل) has its own amount.

Every synchronized entity has `id` (UUID string), `agencyId`, `version` (server version, 0 until the
server has accepted it), `updatedAt`, and a soft `deleted` flag.

## Entities

| Entity | Purpose and key fields |
|---|---|
| `Customer` | A person. `roles` (buyer, seller, landlord, tenant, investor; several at once), contact fields, `preferredContact`, `consent` per channel (calls, SMS, messengers), location text, `source`, `assignedAgentId`, `status` (built-in or agency-defined), `priority`, `nextFollowUpDate`, `tags`, `mergedIntoId` when retired by a merge. |
| `BuyerRequirement` | What a buyer or tenant wants. `dealType`, categories, sale budget (`minBudget`, `preferredBudget`, `maxBudget`, `budgetFlexible`, `maxStretch`) or rent limits (`availableDeposit`, `maxDeposit`, `maxMonthlyRent`), financing, provinces / cities / neighbourhoods / alternatives / exclusions, map areas, `locationStrict`, area, bedrooms and floor ranges with strict flags, amenity levels (required / preferred / not important), building age, documents, condition, facing, furnished, urgency, expected date, `needsToSellFirst`, `negotiationFlexibility`, `leadQuality`, status. |
| `SellerProfile` | A seller's or landlord's terms for one property: status, deadline, motivation, `priceFlexibility`, exclusivity, document checklist, commission agreement, follow-up interval, and `confidential` (`minAcceptablePrice`, `minAcceptableDeposit`, `minAcceptableMonthlyRent`, `notes`). |
| `Property` | Inventory item. `reference` (IR-000123), title, category, `dealTypes`, asking price, deposit, rent, full deposit, estimated market value, `negotiable`, location text and `Location`, areas (built, land, usable), rooms, floors, construction year, condition, documents, facing, amenities, heating / cooling, `ownerCustomerId`, `assignedAgentId`, `status`, `openToFurtherOffers`, `confidentialNotes`. |
| `PropertyContact` | Other people linked to a property (co-owner, representative, tenant…). |
| `PropertyImage` / `LocalImage` | Photos: private local file, upload state, server image id. |
| `Activity` | Something that happened: call, SMS, messenger message sent manually, meeting, viewing, note…; outcome, linked customer / property / deal. |
| `TaskItem`, `Appointment` | Follow-ups and meetings with due time, reminder, status; appointments record viewing feedback. |
| `Note` | Free notes on a record. |
| `Deal` | A buyer–property pipeline with `stage` (lead received → … → agreement reached → completed / cancelled) and a required reason when cancelled. |
| `Offer` | Offer or counteroffer within a deal: `direction`, offered price or deposit and rent, unit entered, expiry, conditions, `status` (draft, submitted, countered, accepted, rejected, withdrawn, expired), `parentOfferId` for counteroffers, response note. |
| `Transaction` | The completed contract: final price or deposit and rent, contract date, lease months. |
| `CommissionRecord` | Per party (buyer, seller, landlord, tenant): `expectedAmount` from a rule (always labelled as an **estimate**), `earnedAmount` once confirmed, `receivedAmount`, agent share, status. |
| `CommissionRule` | Agency-defined rule per deal type and party: rates on price, deposit and monthly rent in basis points, optional price tiers, fixed amount, minimum and maximum. No official tariff is assumed. |
| `MatchRecord` | Stored match result (see [MATCHING.md](MATCHING.md)) plus review state, history, feedback and the fingerprint of its inputs. |
| `AuditEvent` | Who did what to which record and when, with old/new values for tracked fields. Money values are stored as rial text; confidential values are recorded as `***`. |
| `Agency`, `AgencyUser`, `AgencySettings` | Agency, its users (role, team, active) and policy switches (for example whether agents may see agency-wide inventory or customers). |

## Confidential fields

`core/.../sync/RecordCodec.kt` splits each record into a shareable `data` object and a separate
`confidential` object:

- `PROPERTY`: `confidentialNotes`
- `SELLER_PROFILE`: `confidential` (minimums and notes)

The server stores both, but returns `confidential` only to users allowed to see it for that record
(owner/admin, the assigned agent, or a manager of the agent's team). A push from a user without that
right cannot change the stored confidential values. Outreach texts and CSV exports never include them.

## Deletion

Nothing is hard-deleted by the app. Records are soft-deleted (`deleted = true`), sold, rented,
withdrawn and archived properties stay in the inventory with their status, and merged customers keep
their record with `mergedIntoId`. Deleting customers, properties, deals, offers, transactions and
commissions needs the delete permission (owner/admin), checked by the server.

## On the device (Room)

`android/app/src/main/kotlin/ir/melkcrm/app/data/db/`:

| Table | Content |
|---|---|
| `records` | One row per entity: type, id, agency, assigned agent, version, updatedAt, deleted, full JSON (including the confidential fields this user may hold), sync state, sample flag, and indexed copies of customerId / propertyId / status for filtering. |
| `record_fts` | FTS4 index of normalized search text (names, phones, references, places). |
| `outbox` | One pending change per record: change id (idempotency key), base version, attempts, next retry, last error, rejected flag. |
| `conflicts` | Local copy and server copy of records changed in both places, until the agent chooses. |
| `audit` | Local history for the record history screen. |
| `meta` | Sync cursor and similar values. |
| `images` | Photo files and upload state. |

The schema is exported to `android/app/schemas/` on build; later versions must add migrations. There is
deliberately no destructive migration fallback, because unsynchronized changes must survive upgrades.
Settings live in DataStore. The access/refresh tokens and the Neshan key are encrypted with an AES-GCM
key kept in the Android Keystore. App data is excluded from cloud backup and device transfer.

`LocalStore` (`client/.../store/LocalStore.kt`) is the storage contract; `InMemoryLocalStore` and
`RoomLocalStore` both pass the same `LocalStoreContract` tests.

## On the server (PostgreSQL)

`backend/src/main/resources/db/migration/V1__init.sql` (applied by Flyway on start):

| Table | Content |
|---|---|
| `agencies` | Agency name, settings and matching configuration (JSON). |
| `users` | Login, bcrypt password hash, role, team, active flag, failed-login counter and lock time, token epoch. |
| `refresh_tokens` | SHA-256 hashes of refresh tokens, token family, expiry, revocation. |
| `records` | Every synchronized record: agency, type, id, assigned agent and team, version, `server_seq` (pull cursor), deleted, `data` and `confidential` JSON. |
| `applied_changes` | Results of applied pushes by change id, so a retried push is answered without applying twice. |
| `audit_events` | Server audit log (creates, updates with field changes, deletes, exports, permission refusals, logins). |
| `images` | Photo metadata; files are stored under `IMAGE_DIR` with random names. |

## Sync protocol

1. Every local change writes the record and an outbox entry in one transaction.
2. **Push** (`POST /api/v1/sync/push`) sends up to 500 changes, each with the version it was based on.
   The server applies a change only if that version is still current; otherwise it answers `CONFLICT`
   with its current copy. Validation and permission failures are `REJECTED` with a code the app shows.
3. **Pull** (`GET /api/v1/sync/pull?cursor=`) returns records changed after the cursor that this user may
   see, with confidential fields stripped where required. Records the user may no longer see arrive as
   tombstones and are removed locally.
4. A pulled record that has unpushed local edits becomes a **conflict**: both versions are kept and the
   agent chooses (keep the server's, or re-apply theirs on top of it). Newer server data is never
   silently overwritten.
