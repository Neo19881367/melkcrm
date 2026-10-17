# Matching rules

The engine is `core/src/main/kotlin/ir/melkcrm/core/matching/MatchingEngine.kt`. It is deterministic
arithmetic over recorded fields: no machine learning, no hidden inputs. Every number below is either a
setting in `MatchingConfig` (editable by an owner/admin in Settings → Matching, validated on the server)
or a constant in the engine. If the engine changes, this document and `MatchingConfig.rulesVersion`
change with it, and stored results are recalculated.

A match is one buyer requirement against one property (plus the seller profile linked to the
property, when there is one). Evaluation has five stages.

## 1. Hard constraints (exclusion)

A pair is **not eligible** when any of these holds. Only explicit facts exclude; missing data never
does (it lowers confidence instead, see stage 5).

| Rule | Reason code |
|---|---|
| Property deleted, or status not matchable. Matchable: `AVAILABLE`; also `RESERVED` / `UNDER_NEGOTIATION` when the property is flagged *open to further offers* (shown as a backup candidate) | `PROPERTY_NOT_AVAILABLE` |
| Requirement deleted or not `ACTIVE` | `REQUIREMENT_NOT_ACTIVE` |
| Customer deleted or inactive | `CUSTOMER_INACTIVE` |
| The buyer owns the property | `OWN_PROPERTY` |
| Requirement deal type (sale / rent / full deposit) not offered by the property | `DEAL_TYPE_MISMATCH` |
| Property category not among the requirement's categories (only when categories were given) | `CATEGORY_MISMATCH` |
| Property in a city or neighbourhood the buyer excluded | `EXCLUDED_CITY`, `EXCLUDED_NEIGHBORHOOD` |
| Requirement marked *location strict* and the property is outside every stated area | `OUTSIDE_REQUIRED_LOCATION` |
| Area or bedrooms outside the range when that range is marked strict | `AREA_OUT_OF_RANGE`, `BEDROOMS_OUT_OF_RANGE` |
| A feature marked **required** is known to be absent | `MANDATORY_FEATURE_MISSING` |
| Price (or deposit / rent) above the buyer's absolute ceiling, unless one of the exceptions in *Budget* applies | `OVER_BUDGET`, `OVER_DEPOSIT_LIMIT`, `OVER_RENT_LIMIT` |

Excluded pairs are hidden from ranked lists by default and can be shown with their exclusion reasons.

## 2. Weighted score

Seven components each produce a fraction from 0 to 1, multiplied by its weight. Default weights
(must total 100; the readiness weight is capped at 15 so an unresponsive but suitable buyer is never
pushed out):

| Component | Weight |
|---|---|
| Budget | 30 |
| Location | 25 |
| Size and layout | 15 |
| Features and condition | 10 |
| Timing | 10 |
| Negotiation flexibility | 5 |
| Lead readiness | 5 |

Points per component are rounded with the largest-remainder method, so the breakdown shown to the
agent always adds up exactly to the total score.

When a component cannot be evaluated because a value is missing, its fraction is **0.3** (deliberately
below neutral, so incomplete records do not outrank complete ones) and the missing field is recorded.

### Budget (sale)

Uses the property's **asking price**, never the confidential minimum, for scoring.

- No asking price, or the buyer has neither a maximum nor a preferred budget: 0.3 and missing data.
  **A missing budget is never treated as unlimited.**
- Price ≤ maximum budget: 1.0. If a preferred budget is set and the price is above it, the fraction falls
  linearly to 0.7 at the maximum. If the price is below the buyer's minimum budget: 0.75 with a concern.
- Price above the maximum but within the buyer's **stretch** (only when the buyer said the budget is
  flexible and gave a stretch amount): from 0.6 down to 0.3 at the stretch ceiling.
- Price above the absolute ceiling:
  - If the seller's confidential minimum is recorded and is within the ceiling: 0.35 with the concern
    "above budget, negotiation needed". The explanation does not reveal the minimum.
  - If the minimum is recorded and above the ceiling: excluded.
  - If no minimum is recorded, the property is not marked non-negotiable, and the price is at most
    `overBudgetTolerancePercent` (default 10 %) above the ceiling: 0.15 with a concern. Otherwise excluded.

### Budget (rent and full deposit)

Deposit and monthly rent are compared separately with the same rules (limit = maximum deposit or the
available deposit, and maximum rent) and averaged. They are **never converted into each other**.
Full-deposit (رهن کامل) compares the full-deposit amount with the deposit limit.

### Location

The best single match counts:

| Situation | Fraction |
|---|---|
| Preferred neighbourhood, or inside a saved map area | 1.0 |
| Within `nearAreaFullKm` (1 km) of a map area | 0.8, then linear to 0 at `nearAreaZeroKm` (8 km) |
| Alternative neighbourhood | 0.75 |
| Preferred city, when the buyer gave only cities | 0.85 (0.4 if neighbourhoods or areas were also given) |
| Preferred province, when only provinces were given | 0.7 (otherwise 0.2) |
| No location preference recorded | 0.5 and missing data |

Names are compared after Persian text normalization (Arabic ي/ك/ة versus Persian ی/ک/ه, hamza forms,
half-spaces, diacritics and tatweel, Persian and Arabic digits, case). A map area is a polygon when it
has one (distance 0 inside, otherwise to the nearest edge) or else a circle (distance to the circle
edge, great-circle). A property without coordinates cannot be placed in a map area: the result is
marked as missing data, not as outside.

### Size and layout

Weighted parts, only for ranges the buyer gave: area 0.5, bedrooms 0.3, floor 0.2.

- Area below the minimum loses score linearly and reaches 0 at `areaShortfallZeroPercent` (20 %) below;
  above the maximum it reaches 0 at `areaExcessZeroPercent` (40 %) above.
- One bedroom fewer than the minimum: 0.4; two or more fewer: 0; more than the maximum: 0.8.
- Floor outside the range: 0.3.
- No size preference at all: 0.6 and missing data.

### Features and condition

Each feature the buyer cares about (parking, elevator, storage, other amenities, new build, building
age, document type, condition, facing, furnished) counts with weight 2 if required and 1 if preferred:
present 1.0, absent 0 (excludes if required), unknown 0.5 with missing data. No feature preferences: 1.0.
Building age is computed from the construction year in the calendar it was entered in.

### Timing

From urgency: immediate 1.0, within 1 month 0.9, 3 months 0.7, 6 months 0.5, flexible 0.4, unknown 0.3.
An expected date overrides it (≤ 30 days 1.0, ≤ 90 0.75, ≤ 180 0.55, later 0.4). Fitting the seller's
deadline adds 0.1; missing it multiplies by 0.6. A buyer who must sell first multiplies by 0.75.

### Negotiation flexibility

Average of the recorded evidence only: buyer flexibility (none 0.2, low 0.45, medium 0.7, high 1.0),
budget flagged flexible (0.8), seller price flexibility (same scale), property negotiable (0.8) or not
(0.3). With no evidence the fraction is a neutral 0.5 and the reasons say so. **Flexibility is never
inferred.**

### Lead readiness

Starts at 0.5: confirmed interest +0.15, requested viewing +0.15, submitted offer +0.2, hot lead +0.1,
cold lead −0.1, two or three unanswered follow-ups −0.15, four or more −0.25. Kept between 0.1 and 1.0.

## 3. Classification

| Score | Class |
|---|---|
| ≥ 85 | Excellent |
| ≥ 70 | Strong |
| ≥ 50 | Possible |
| < 50 | Weak |
| any, with an exclusion | Not eligible |

Thresholds are configurable (they must increase: possible < strong < excellent).

Recommended next action: not eligible → none; provisional → collect missing information; excellent or
strong → follow up the offer, schedule a viewing, or contact the buyer (depending on what has already
happened); possible → review.

## 4. Confidential negotiation range

For sale, and for deposit and rent separately: if the seller's confidential minimum and the buyer's
ceiling are both recorded, the range is **feasible** from the minimum up to the lower of the ceiling and
the asking price, or **not feasible** with the gap between them. Otherwise it is unknown.

This assessment is shown only to users allowed to see confidential data for that record. It is never
included in outreach texts, exports, or anything shown to either party, and it is not a price offer.

## 5. Missing data, confidence and provisional results

Every missing input is listed with the field name. Confidence starts at 1.0 and each missing field
subtracts a penalty (buyer budget 0.30, asking price 0.30, deposit / rent limits and values 0.15,
location 0.15, area 0.10, others 0.0–0.05), with a floor of 0.1. Confidence ≥ 0.8 is high, ≥ 0.5
medium, otherwise low.

A result is **provisional** when a critical field is missing: buyer budget, deposit or rent limit,
buyer location, asking price, property deposit or rent, property location, property area, or a feature
the buyer marked required. Provisional results are labelled as such and recommend collecting the data.

Ranked lists sort by eligibility, then score, then confidence.

## Workflows

- **A. Best buyers for a property** (property screen, or "Find best buyers" on a seller).
- **B. Best properties for a buyer requirement** (requirement screen).
- **C. All opportunities** (Matching screen), over every active requirement and matchable property
  the user can see.

Results are stored as match records with a fingerprint of their inputs and the rules version. When a
requirement, property, seller profile, customer status, engagement or the rules change, the record is
re-evaluated and flagged "changed since review" if the agent had already reviewed it. Review state
(approved for contact, contacted, viewing, offer, rejected with reason, snoozed until a date, closed)
is never changed by the engine.

## Explanations

Reasons are structured codes with their numbers (amounts in rial, distances, counts), rendered in the
user's language and currency by `core/.../i18n/ReasonRenderer.kt`. Example (English):

> Score 78 — Strong. Budget 30/30: asking price within the maximum budget. Location 20/25: alternative
> neighbourhood. Size 12/15: area fits; one bedroom fewer than wanted. Timing 7/10. Confidence: high.
