# MelkCRM

An internal CRM for Iranian real-estate agencies, with transparent buyer–seller matching. It is a
working tool for agency staff, not a public marketplace: customers, buyer requirements, seller
profiles, property inventory, activities, offers, deals and commissions, with a matching engine that
explains every score.

- **Android app** (Kotlin, Jetpack Compose, Material 3, MVVM, Room, DataStore, WorkManager, Navigation
  Compose, Ktor client, osmdroid). Persian (RTL, Vazirmatn, Persian digits, Jalali calendar) and
  English (LTR, Gregorian). Works fully offline on one device, or synchronizes with the agency server.
- **Backend** (Ktor + PostgreSQL) that enforces agency isolation, roles, confidential fields and
  conflict detection. Self-hosted, so it can run on an Iranian provider or on the agency's own server.

## Status: what was verified and what was not

| Part | State |
|---|---|
| `core` (domain rules, Jalali, money, matching, permissions, reports) | Compiled; **108 unit tests pass** |
| `client` (repositories, sync engine, session, matching runs, storage contract) | Compiled; **60 unit tests pass** |
| `backend` | Compiled; **21 tests pass on H2 and on PostgreSQL 16**. The Docker image builds and `docker compose up` starts PostgreSQL 16 and the backend healthy; login, roles, sync push and conflict, photo upload to the volume, CSV export, refresh-token reuse detection and the backup commands were exercised against it over HTTP. |
| Android app | **Built on GitHub Actions** (`.github/workflows/build.yml`): the debug APK builds, the **16 storage contract tests pass on Room and SQLite FTS** (Robolectric), and the release build goes through R8 shrinking. Not yet run on a device or emulator: the screens, map, notifications and sync against a server have only been checked by compiling. |

Every push builds the debug APK. It is attached to the run as the `melkcrm-debug-apk` artifact, and
builds of `main` also put it on the `apk` branch as `melkcrm-debug.apk`. Try it on a device as described
in [docs/RELEASE.md](docs/RELEASE.md) before giving it to agents; real use needs a release build signed
with the agency's own key.

## Repository layout

```
core/       Pure Kotlin domain: entities, validation, Jalali calendar, Money (rial), matching engine,
            permission policy, commission rules, reports, CSV export, sample data.
client/     Platform-independent app logic used by Android: LocalStore contract, repositories,
            sync engine (outbox + conflicts), session, matching service, reminders, HTTP client.
            testFixtures/ holds LocalStoreContract, run against both storage implementations.
backend/    Ktor server, PostgreSQL schema (Flyway), Dockerfile, docker-compose.yml, .env.example.
android/    The Android app (separate Gradle build that includes the root build).
  app/src/main/kotlin/ir/melkcrm/app/   data/ (Room, DataStore, encrypted storage), ui/ (screens), work/
  tools/strings.py, check_resources.py  Generate and check the fa/en string resources.
tools/android-typecheck/  JVM compile check of the Android sources for machines without Google Maven.
docs/       MATCHING.md, DATA_MODEL.md, BACKEND.md, RELEASE.md, PLAN.md, font licence.
```

## Quick start

Requirements: JDK 17 or newer. The root build (core, client, backend) needs only Maven Central.

```bash
./gradlew :core:test :client:test :backend:test      # all JVM tests (H2, nothing to install)
```

**Run the backend locally** (details in [docs/BACKEND.md](docs/BACKEND.md)):

```bash
cd backend && cp .env.example .env    # set JWT_SECRET, passwords and the bootstrap admin
../gradlew -p .. :backend:installDist
docker compose up -d --build          # PostgreSQL 16 + backend on 127.0.0.1:8080
curl http://127.0.0.1:8080/health
```

**Build the Android app** (GitHub Actions does this on every push; locally it needs the Android SDK and
access to Google Maven; details in [docs/RELEASE.md](docs/RELEASE.md)):

```bash
cd android
./gradlew :app:testDebugUnitTest      # Robolectric + Room contract tests
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Without a server the app runs in device-only mode; Settings → Sample data loads clearly synthetic
records (0912-000-xxxx phone numbers, approximate coordinates marked as such) for trying it out. To use
a server, the emulator reaches a backend on the host at `http://10.0.2.2:8080`; any other server must use
HTTPS.

## Features

- **Dashboard** computed from stored records: active buyers and sellers, new customers this week,
  listings for sale and rent, high-priority matches, buyers and properties with no match, upcoming
  appointments, follow-ups due today and overdue, active negotiations, completed transactions,
  expected and received commission, monthly sales; plus quick actions.
- **Customers**: one person can be buyer, seller, landlord, tenant and investor at once; contact
  consent per channel; duplicate detection by normalized phone number or identical name in the same
  city, with a field-by-field merge that keeps the retired record for audit; full-text search in
  Persian and English (word-prefix, Persian character variants normalized).
- **Buyer requirements**: separate sale, rent and full-deposit budgets (never converted into each
  other), required/preferred/not-important levels for each feature, strict flags for location, area
  and bedrooms, map areas, urgency and evidence-based flexibility.
- **Sellers**: confidential minimum price, deposit and rent, document checklist, deadline, and
  "Find best buyers".
- **Properties**: IR-000123 references, statuses, photos (stored privately, uploaded to the server),
  location with its source and accuracy, never hard-deleted; sold and withdrawn listings stay.
- **Map**: osmdroid with a configurable tile server (OpenStreetMap by default, or Neshan, Map.ir or a
  self-hosted server), optional Neshan address search with the agency's own key, manual pins and
  coordinates; geocoded results are marked as approximate and must be confirmed.
- **Matching** ([docs/MATCHING.md](docs/MATCHING.md)): hard constraints, seven weighted components
  (30/25/15/10/10/5/5 by default, configurable), thresholds 85/70/50, confidential negotiation range,
  provisional results when critical data is missing, plain-language reasons. Workflows: best buyers for
  a property, best properties for a buyer, and all opportunities.
- **Match review**: approve, reject with reason, snooze, mark contacted, record viewing feedback,
  compare side by side, and controlled outreach (the agent copies or opens a prepared message that
  contains public listing facts only; nothing is sent automatically).
- **Activities, tasks and appointments** with reminders as local notifications.
- **Offers and counteroffers** with full history, expiry and status rules; **deals** through stages;
  **transactions**; **commissions** calculated from agency rules and shown as estimates until confirmed.
- **Reports**: customers by type and status, new leads per month, buyers by budget, sellers by
  property type, available listings by location, properties by asking price, matching rates,
  viewing-to-offer and offer-to-sale conversion, average days to sell, agent performance, follow-up
  completion and commissions. CSV export of customers and properties goes through the server, only for
  users allowed to export, and is audited.
- **Settings**: language, calendar, toman/rial, Persian digits, reminders, server sign-in, sync
  conflicts, matching weights, commission rules, agents and permissions, map provider, record history.
- **Roles**: owner/admin, manager (team), agent. Every rule is enforced by the server; the app only hides
  what the server would refuse.
- **Offline sync**: every change goes to an outbox; the server accepts it only on top of the version it
  was made on. Otherwise the agent sees both versions and chooses. Older offline data never silently
  overwrites newer server data.

## Security notes

- No server credentials, admin passwords or private API keys are in the app. Agents sign in with their
  own account; tokens and the optional Neshan key are stored encrypted with the Android Keystore.
- Passwords are stored as bcrypt hashes; logins are rate-limited and accounts lock after repeated
  failures; refresh tokens rotate and a reused token revokes its whole family.
- Seller minimums and confidential notes travel in a separate payload that the server only returns to
  users entitled to see them, and only those users can change them. Outreach texts and exports never
  contain them.
- Agencies are isolated by `agency_id` on every query. Exports are permission-checked and audited.
- Photos are accepted only as JPEG, PNG or WebP (checked by content), stored under random names outside
  any web root, and served only after an access check.

## Known limitations

- The Android app has been built and its storage tested, but not yet used on a device (see Status).
- Debug APKs from GitHub are signed with a new throwaway key on every run, so a newer one installs only
  after uninstalling the old one, which deletes its local data. Use a release key for anything kept.
- Neshan search endpoints and response fields follow its public documentation but could not be called
  from here; verify them with a real key before relying on address search. Manual pins always work.
- The default OpenStreetMap tile server has a usage policy that does not allow heavy production use;
  agencies should configure their own provider.
- In device-only mode, agents added in Settings are labels for assignment; they cannot sign in.
  Real multi-user use needs the server.
- Property references created offline on two devices can collide; the app warns but does not
  renumber. Match results are computed on each device and are not shared between devices.
- Agents only get matches among records they can see.
- Removing a photo locally does not delete the server copy yet.
- Reminders can arrive late when Android's battery saving defers background work.
- The map editor draws buyer areas as circles; polygon areas are stored and matched but not drawn.
- The tile-server header (for providers that need one) is stored in app preferences, not encrypted.
- Commission tiers can be edited in the app only for simple rules; tiered rules come from the server.
- WhatsApp and other messengers are opened for the agent to send manually; the app does not read or
  send messages on its own, because no authorized integration is configured.

## Development tools

- `python3 android/tools/strings.py` regenerates `values/strings.xml` and `values-fa/strings.xml` from one
  table; `python3 android/tools/check_resources.py` checks that every string the code references exists
  in both languages with matching format arguments, and that every error and refusal code the code or
  server can produce has a translation.
- `./gradlew -p tools/android-typecheck compileTestKotlin` type-checks the Android sources without Google
  Maven (see the comment at the top of its build file).
- `MELK_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/postgres?user=postgres ./gradlew :backend:test`
  runs the backend tests against PostgreSQL instead of H2.

## Licences

Vazirmatn is used under the SIL Open Font License 1.1 ([docs/VAZIRMATN-OFL.txt](docs/VAZIRMATN-OFL.txt)).
Map data © OpenStreetMap contributors when the default tile server is used.
