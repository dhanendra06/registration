# Draft Stage Implementation - Test Scenarios

**Version:** v1.0

> **Primary scenario - why draft stage exists:** Registration Processor must **not** access Packet Manager **after ABIS**. Create Draft (before ABIS) copies identity, documents, and biometrics into the ID Repository draft. From Bio Dedupe onward through Finalization, all stages must use the ID Repo draft only - not re-read the registration packet via Packet Manager.

## Primary scenario - no Packet Manager access after ABIS

This is the main scenario that drives the draft-stage design. Validate it first before module-specific checks below.

| Scenario | What to verify |
|----------|----------------|
| Packet Manager not accessed after ABIS | For a supported packet type (NEW, UPDATE, LOST, RES_UPDATE, etc.), after ABIS Middleware / Bio Dedupe completes, trace regproc and Packet Manager logs for the registration ID through Finalization. **No** Packet Manager calls (`getFields`, `getDocument`, `getBiometrics`, `getMetaInfo`, `getTags`, etc.) occur for that RID. Publish and later processing use ID Repo draft APIs only. |
| Create Draft runs before ABIS and fully populates the draft | Pipeline order: Create Draft completes and populates the ID Repo draft **before** the packet enters ABIS. Post-ABIS stages therefore have no need to read the packet from Packet Manager. |

---

This document captures manual verification scenarios for draft-stage changes across modules. Each module is a separate main section below.

## How to verify each scenario

For every scenario below, validate as applicable:

- Registration status (`regprc.registration` - status, sub_status, latest transaction status)
- Registration transaction table (`regprc.registration_transaction` - stage name, transaction type code, status comment)
- Audit / platform messages (success and error codes are consistent for the stage)
- ID Repo draft DB rows, object-store paths (`_draft/{ridHash}/`, `{uinHash}/`), and API responses (for IDREPO scenarios)

---

# Registration Processor Draft Stage Changes

## A. Create Draft stage

Create Draft runs early in the pipeline (stage-group-2). For supported packet types it creates or refreshes an ID Repository draft and populates identity, documents, and biometrics. ACTIVATED and DEACTIVATED update the live identity via the draft-update path and mark the packet **PROCESSED** at this stage.

### NEW

| Scenario | What to verify |
|----------|----------------|
| NEW packet routed through Create Draft creates draft and populates identity/biometrics | Draft exists in ID Repo; identity, documents, and biometrics match packet content; transaction shows `CREATE_DRAFT` success; packet moves to next stage. |
| NEW reprocess with existing draft discards and recreates before populate | On reprocess, existing draft is discarded first; new draft is created and fully populated; no stale partial identity left. |
| NEW with ID Repo transient error at Create Draft is reprocessable and succeeds on retry | First attempt: reprocessable status. After ID Repo recovery: draft created successfully on retry. |
| NEW flow saves `packetCreatedOn` in the ID Repo identity | After Create Draft, demographics draft contains `packetCreatedOn` from packet metaInfo. |

### UPDATE

| Scenario | What to verify |
|----------|----------------|
| UPDATE creates draft with UIN from packet and processes successfully | Draft created with correct UIN; identity, documents, and biometrics populated; packet continues. |
| UPDATE reprocess with existing draft discards and recreates | On reprocess, existing draft is discarded; new draft created and populated. |
| UPDATE with ID Repo transient error at Create Draft is reprocessable | Reprocessable on transient failure; succeeds on retry after ID Repo recovery. |
| UPDATE flow saves `packetCreatedOn` in the ID Repo identity | Demographics draft contains `packetCreatedOn` after Create Draft. |

### RES_UPDATE

| Scenario | What to verify |
|----------|----------------|
| RES_UPDATE creates draft with resolved UIN same as UPDATE | Draft behavior matches UPDATE using resolved UIN from packet. |
| RES_UPDATE reprocess discards and recreates existing draft | Same discard/recreate pattern as UPDATE reprocess. |

### LOST

| Scenario | What to verify |
|----------|----------------|
| LOST creates draft without UIN via `idrepoCreateDraftV2` and populates identity | Draft exists with no UIN; identity populated from LOST packet. |
| LOST reprocess with existing draft discards and recreates | Draft refreshed on reprocess before Bio Dedupe UIN stamp. |
| LOST draft with null identity before UIN stamp does not fail on update/merge | Draft update/merge handles null incoming identity without failure. |
| Validate registration transaction table for stages | Correct stage entries, `CREATE_DRAFT` transaction type, and status comments for LOST. |
| LOST packet allows certain field updates (regression) | Allowed field update behavior is preserved - no regression from prior LOST handling. |

### ACTIVATED

| Scenario | What to verify |
|----------|----------------|
| ACTIVATED reactivates UIN through Create Draft draft-update path | UIN activated in ID Repo; packet **PROCESSED** at Create Draft. |
| ACTIVATED with ID Repo error on draft update is reprocessable | Reprocess succeeds after transient ID Repo failure. |

### DEACTIVATED

| Scenario | What to verify |
|----------|----------------|
| DEACTIVATED deactivates UIN through Create Draft draft-update path | UIN deactivated in ID Repo; packet **PROCESSED** at Create Draft. |
| DEACTIVATED with ID Repo error on draft update is reprocessable | Reprocess succeeds after transient ID Repo failure. |

### Cross-cutting (Create Draft)

| Scenario | What to verify |
|----------|----------------|
| CRVS_NEW / CRVS_DEATH / CORRECTION / REPRINT and similar types | Create Draft skipped or pass-through; packet continues through pipeline as designed. |
| UIN Generator not in registration transaction for all packet types | No `UIN_GENERATOR` transaction rows when UIN Generator is removed from pipeline. |
| All use cases from UIN Generator stage covered where applicable | Any UIN Generator behavior now owned by Create Draft is verified. |
| Registration transaction table and messages | Correct transaction type, sub_status_code, status_comment, and latest transaction status for each Create Draft outcome. |

---

## B. Draft lifecycle (reprocess / stale / discard / ID Repo)

Latest-packet logic ensures only the newest submission for an identity is processed. Validate the following for **all applicable packet types**.

| Scenario | What to verify |
|----------|----------------|
| Latest processed packet should be able to process | Most recent packet for the identity completes Create Draft and downstream stages normally. |
| Older packet should be marked obsolete | Packet older than last committed identity is marked obsolete; no draft publish. |
| Latest packet newer than last processed packet should be able to process | New submission after a prior PROCESSED packet is accepted and completes end-to-end. |
| Error during stale packet check must not be treated as approval for draft creation | If latest-packet check fails, packet must not proceed silently as if it were the latest packet. |

---

## C. LOST + Bio Dedupe integration

After Create Draft builds a UIN-less LOST draft, Bio Dedupe stamps the matched UIN via `idrepoUpdateDraftUin` or rejects and discards the draft.

| Scenario | What to verify |
|----------|----------------|
| LOST single ABIS match stamps UIN on draft via `idrepoUpdateDraftUin` | Draft demographics contain matched UIN after unique bio match. |
| LOST single ABIS match - UIN stamp failure is reprocessable or failed; must not continue further | Stamp failure must not leave packet progressing to later stages in success; would fail at publish draft. Status is reprocess or permanent fail. |
| LOST multiple ABIS matches reduced to one demo match still stamps UIN | After demo dedupe narrows to one match, UIN is stamped on draft. |
| LOST no match rejects and discards draft | Packet rejected; draft discarded in ID Repo. |
| LOST end-to-end: Create Draft - Bio Dedupe stamp - Finalization publish | Full LOST happy path completes with published identity. |
| Multiple ABIS matches without a single demo match rejects the packet | No UIN stamp; packet rejected per LOST rules. |

---

## D. Finalization

Finalization publishes the ID Repo draft for types that require it.

### NEW / UPDATE / RES_UPDATE / ACTIVATED / DEACTIVATED

| Scenario | What to verify |
|----------|----------------|
| NEW with draft present publishes successfully at Finalization | `idrepoPublishDraft` succeeds for NEW; live identity updated; packet **PROCESSED**. Extend validation to UPDATE, RES_UPDATE, ACTIVATED, and DEACTIVATED as applicable to each type's flow. |

### LOST

| Scenario | What to verify |
|----------|----------------|
| LOST with UIN stamped on draft publishes using UIN resolved from demographics draft | Publish succeeds; UIN read from draft demographics matches stamped value. |
| LOST Finalization when UIN not stamped on draft - publish/fail behavior | Expected fail or skip publish; clear status - must not publish without UIN. |

### All types with draft

| Scenario | What to verify |
|----------|----------------|
| Stale reprocess at Finalization discards draft and skips publish | Obsolete packet: draft discarded; publish not called; obsolete status set. |
| Publish draft failure maintained as per existing code - REPROCESS or FAILED | Transient errors reprocess; permanent errors fail - same pattern as existing Finalization behavior. |

---

## E. Workflow manager (discard draft + anonymous profile)

Workflow Manager handles terminal completion: draft cleanup and anonymous profile persistence.

| Scenario | What to verify |
|----------|----------------|
| Discard draft on reject scenarios for all flows | On terminal **REJECTED**, draft discarded in ID Repo for applicable packet types. |
| `"anonymous"` tag present - read from tag and create anonymous profile | Workflow reads `"anonymous"` tag from packet manager and persists anonymous profile. |
| `"anonymous"` tag not present - use fallback mechanism to create profile | When tag is missing, Workflow falls back to existing profile build logic and saves anonymous profile. |

---

## F. Upgrade and migration

Coordinated deployment is required when inserting Create Draft and removing UIN Generator.

| Scenario | What to verify |
|----------|----------------|
| Process existing in-flight packets before upgrade | Pipelines drained or completed; no packets left in inconsistent state at deploy time. |
| Reprocess existing packets from Create Draft stage | Mid-pipeline packets reprocessed from Create Draft get draft created and complete normally. |
| Packets stuck on `uin-generator-bus-in` / `uin-generator-bus-out` | After UIN Generator consumer is removed, stuck messages are identified and reprocessed from Create Draft. |

---

# IDREPO changes for Draft stage

Manual verification for ID Repository draft API and object-store changes. Validate ID Repo draft DB, object-store state, and API responses.

## API contract and versioning

| Scenario | What to verify |
|----------|----------------|
| Existing createDraft / updateDraft / extractBiometrics / publishDraft / getDraft APIs should not be modified | Legacy endpoint paths, request/response contracts, and regproc integration for standard NEW/UPDATE flows behave as before; no unintended breaking change to existing callers. |
| New v2 APIs for createDraft / updateDraft / extractBiometrics / publishDraft / getDraft | v2 endpoints exist for each changed flow; LOST and `_draft`-path behavior is validated through v2 APIs per agreed design. |
| New API for `/uindata/{registrationId}` | UIN is stamped on a LOST draft after ABIS match; encrypted UIN and `uin_hash` stored correctly and usable at publish. |

## LOST flow

| Scenario | What to verify |
|----------|----------------|
| Create draft without UIN then call updateDraft multiple times (LOST) | `createDraftV2` followed by repeated `updateDraft` (demographics, documents, biometrics) succeeds; failure modes when `updateDraft` is invoked before UIN stamp are documented and handled. |

## Publish, discard, and consistency

| Scenario | What to verify |
|----------|----------------|
| publishDraft partial failure - DB vs object-store inconsistency | Simulate object-store copy or delete failure during publish (e.g. draft DB deleted but object store only partially cleaned); record live identity, draft DB, `_draft/{ridHash}/`, and `{uinHash}/` state and whether recovery is possible. |
| Discard cleanup | `discardDraft` removes `_draft/{ridHash}/*` from object store and all draft rows from DB; live `{uinHash}/` objects are not deleted. |

## Object store layout (`_draft` vs live)

| Scenario | What to verify |
|----------|----------------|
| NEW/UPDATE - files in `_draft/{ridHash}/`, moved to live on publish | While draft is open, bio/docs under `_draft/{ridHash}/Biometrics/` or `_draft/{ridHash}/Demographics/`; after publish, promoted to `{uinHash}/Biometrics/` or `{uinHash}/Demographics/`. |
| updateDraft stores bio/docs under `_draft/{ridHash}/` only | New or updated biometrics and documents are written under `_draft/{ridHash}/Biometrics|Demographics/`, not under live `{uinHash}/`. |
| updateDraft CBEFF replacement deletes orphaned `_draft` files | Replacing CBEFF during updateDraft removes orphaned biometric files from `_draft/{ridHash}/`. |
| Live `{uinHash}/` unchanged while draft is open | UPDATE draft edits do not overwrite live identity object-store files until publishDraft completes. |

## Authentication and downstream

| Scenario | What to verify |
|----------|----------------|
| ID authentication after publishDraft for UIN update (biometrics moved to live path) | After publish moves biometrics from `_draft/{ridHash}/` to `{uinHash}/Biometrics/`, ID auth / eKYC succeeds against the updated live biometrics. |
| Post-publish UPDATE draft - ID authentication passes | UPDATE packet published via draft; subsequent ID authentication against the updated live identity succeeds. |

## extractBiometrics and CBEFF file layout

Both **NEW** and **UPDATE** packets carry CBEFF. Run the same UIN through **NEW publish**, then **UPDATE publish** (with `extractBiometrics` if your flow uses it). After each publish, list objects under `{uinHash}/Biometrics/` and match against `uin_biometric.bio_file_id` in DB.

**Question to answer (old code vs new code):** After UPDATE publish, does `{uinHash}/` contain **only the last (UPDATE) biometrics**, or **multiple CBEFF / extracted files** left over from both NEW and UPDATE?

| Scenario | What to verify |
|----------|----------------|
| CBEFF file count in `{uinHash}/Biometrics/` after NEW publish, then after UPDATE publish | **After NEW publish:** count CBEFF files (`.cbeff` / active `bio_file_id` names) and record DB `bio_file_id`. **After UPDATE publish:** count again. Compare old vs new - single active CBEFF per biometric type (UPDATE replaced NEW) **or** multiple CBEFF files (NEW + UPDATE both present). Active DB reference must point to the file used for auth. |
| Extracted template file count per modality after NEW publish, then after UPDATE publish | **After NEW publish:** count extracted template files per modality under `{uinHash}/Biometrics/` (naming pattern: `{bioFileId-prefix}.{modality}.{format}`). **After UPDATE publish** (including `extractBiometrics` if run on UPDATE draft): count again per modality. Compare old vs new - one template set per modality from the **last publish only** **or** duplicate templates from both NEW and UPDATE. |

---

# Khazana - moveObject in object store

Draft publish and UPDATE draft flows use Khazana `moveObject(src, dst, deleteSourceAfterCopy)` for server-side copy. Validate within the **same bucket** (typical: `_draft/{ridHash}/...` - `{uinHash}/...`) and, if your environment uses separate draft/live buckets, **between different buckets**.

| Scenario | What to verify |
|----------|----------------|
| `publishDraft` - move within same bucket (`deleteSourceAfterCopy=true`) | After publish, biometric/document objects exist at `{uinHash}/Biometrics/` or `{uinHash}/Demographics/`; corresponding `_draft/{ridHash}/` objects are **removed**. DB `bio_file_id` / doc references match live paths. |
| UPDATE draft - copy live to draft within same bucket (`deleteSourceAfterCopy=false`) | On UPDATE `createDraft`/`updateDraft`, live `{uinHash}/` files are copied to `_draft/{ridHash}/` without deleting live originals. ID auth against live biometrics still works while draft is open. |
| `moveObject` between different buckets (if configured) | When source and destination use different buckets, `moveObject` completes successfully; destination object is readable; source is deleted only when `deleteSourceAfterCopy=true` (draft to live publish), preserved when `false` (live to draft copy). |
| Partial publish failure after some `moveObject` calls | If publish fails mid-copy, record which files were moved vs left in `_draft/`; retry/reprocess must not leave DB pointing at missing objects or duplicate live/draft copies. |

---

# Packet Manager - getTags API and `type` attribute

Packet Manager `getTags` supports an optional **`type`** request attribute on `POST /v1/packetmanager/getTags`. Regproc calls this via `PacketManagerService.getTags()` (`TagRequestDto` with `id`, optional `tagNames`, optional `type`).

**Filtering rules:** explicit **`tagNames` take precedence** over `type`. `type` is case-insensitive and trimmed. Supported values: `anonymous`, `all`; omit or blank `type` returns **standard (non-anonymous) tags only**.

| Scenario | What to verify |
|----------|----------------|
| `getTags` without `type` and without `tagNames` - backward compatibility | Call `getAllTags(registrationId)` after Packet Classifier; response includes classifier tags (e.g. `AGE_GROUP`, meta-info tags) but **excludes** `"anonymous"`. Behaviour matches new default (non-anonymous only), not the old "return everything" response. |
| `getTags` with `type=all` | Returns **all** tags including `"anonymous"` when present. Tag set matches what Packet Classifier stored via `addOrUpdateTags`. |
| `getTags` with `type=anonymous` | Returns only the `"anonymous"` tag JSON (same content Workflow Manager needs). Equivalent to regproc calling `getTags(registrationId, List.of("anonymous"))` when that tag exists. |
| Explicit `tagNames` precedence over `type` | Request with both `tagNames=["anonymous"]` and `type=all` (or any other `type`) returns tags filtered by **`tagNames` only**; `type` is ignored. |
| `getTags` when requested tag is missing | `tagNames=["anonymous"]` or `type=anonymous` when no anonymous tag was written returns `KER-PUT-024`; regproc treats this as **null** (Workflow falls back - see Registration Processor section E). |
| Invalid `type` value | Non-blank value other than `anonymous` / `all` (e.g. typo) returns expected error (`TAG_NOT_FOUND` / HTTP 400); must not silently fall back to default filtering. |

---

# MOSIP Config changes

Validate updated config in the **mosip-config** repo (Camel routes, stage wiring, and `registration-processor-default.properties`) after Create Draft replaces UIN Generator.

| Scenario | What to verify |
|----------|----------------|
| All packet types - Camel route uses Create Draft, not UIN Generator | For **every** enabled packet type (NEW, UPDATE, LOST, RES_UPDATE, ACTIVATED, DEACTIVATED, CORRECTION, REPRINT, CRVS_*, biometric_correction, and any others in your environment), inspect Camel routes and stage config. Routes that previously sent packets to UIN Generator must now use **Create Draft** (`create-draft-bus-in` / `create-draft-bus-out`). **No** packet type may reference `uin-generator-bus-in` or `uin-generator-bus-out`. UIN Generator must not run for any packet type. |
| End-to-end - no UIN Generator execution for any packet type | Process one packet per type. `regprc.registration_transaction` shows `CREATE_DRAFT` where draft work runs; **no** `UIN_GENERATOR` rows for any type. Create Draft stage is deployed; UIN Generator stage is not deployed or consumed. |
| Upgrade - packets stuck on UIN Generator bus resume from Create Draft | Simulate or use in-flight packets left on `uin-generator-bus-in` / `uin-generator-bus-out` after upgrade. Reprocess from **Create Draft** stage: existing draft is discarded and recreated, packet proceeds through the pipeline and completes (or reaches expected terminal status). No permanent stall on UIN Generator bus. |
