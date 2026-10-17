# MelkCRM — implementation plan

Internal CRM + buyer/seller matching for Iranian real-estate agencies. Android (Kotlin, Compose, M3) + Ktor backend.

## Environment findings (2026-10-17)
- JDK 21, Gradle 8.14.3, Maven Central and Gradle Plugin Portal reachable.
- **No Android SDK, and Google Maven (`dl.google.com` / `maven.google.com`) is blocked** → AGP, AndroidX, Compose, Room cannot be downloaded in this sandbox. The app (`android/app`) is written in full and type-checked with `tools/android-typecheck`; it is built where Google Maven is reachable: GitHub Actions builds and tests it on every push (docs/RELEASE.md).
- Consequence for the architecture: all business logic lives in a pure-Kotlin `:core` module (compiled and tested here) that the app and the backend both use.

## Key decisions
| Topic | Decision | Why |
|---|---|---|
| Modules | `:core` (pure Kotlin domain), `:client` (app logic without Android), `:backend` (Ktor), `android/app` (Android UI and storage) | Logic testable without Android; shared rules between client and server |
| Money | `Long` in **rial** (canonical); display toman (÷10) or rial | Integer, no rounding loss, 9.2×10¹⁸ rial headroom |
| Timestamps | `Long` epoch millis UTC | Calendar-independent |
| Date-only | `Long` epoch day of the **Gregorian ISO date** (`LocalDate.toEpochDay`) | Unambiguous; Jalali is display only |
| Jalali | Own implementation of the jalaali (Borkowski) algorithm, verified against `jalaali-js` | No dependency, correct leap years |
| Backend | Self-hosted Ktor + PostgreSQL (Docker), deployable on Iranian clouds (ArvanCloud, Liara, ParsPack) or on-prem | Firebase/Supabase/AWS are unreliable or sanctioned for Iranian users; abstraction `RemoteDataSource` keeps it swappable |
| Auth | bcrypt passwords, short-lived JWT access + rotating hashed refresh tokens | No secrets in the APK |
| Authorization | Server-side, per agency + role + assignment; confidential fields stored in a separate payload only returned to permitted users | Requirement §19/§21 |
| Sync | Outbox queue on device, optimistic concurrency by `version`; server rejects stale writes with a conflict | Never silently overwrite newer server data |
| Maps | `MapProvider` / `GeocodingProvider` abstraction; osmdroid (OSM-compatible raster tiles, URL configurable to Map.ir/Neshan/self-hosted) + Neshan search API when a key is configured; manual coordinate entry always available | Google Maps is not usable in Iran; Neshan and Map.ir are local providers |
| DI | Manual `AppContainer` | Fewer moving parts |
| Matching | Transparent weighted engine in `:core` (hard constraints → 7 weighted components → classification → negotiation range → missing-data confidence) with configurable weights/thresholds | Requirement §11–12 |

## Milestones
1. Project setup, fa/en localization (RTL), Jalali, toman/rial, design system, navigation.
2. Room database + repositories; customers (multi-role, duplicates), buyer requirements, seller profiles, properties.
3. Map/location layer; matching engine; ranked results with explanation; match review.
4. Activities, reminders, appointments, offers/negotiation audit trail, commissions.
5. Backend (auth, RBAC, agency isolation, sync), reports + CSV export, security hardening.
6. Tests, sample dataset, docs, APK/signing instructions.

Status (2026-10-17): all six milestones implemented. See the README for what has been verified and the known limitations.
