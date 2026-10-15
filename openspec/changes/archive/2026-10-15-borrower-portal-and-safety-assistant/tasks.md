Scope: capabilities `borrower-loan-overview`, `borrower-document-access`, `borrower-safety-assistant`.
`assistant-prompt-trend-mining` is specified but deliberately not built here — see design.md's Migration Plan
and Open Questions; it ships as its own change once its anonymization design is decided.

## 1. Borrower loan overview — backend

- [x] 1.1 Add `riskBand` and a server-computed `trend` string to `MyLoanView`/`MyLoansController.myLoans()`;
      remove no existing field (additive) but do not add `calibratedProbability`, `modelVersion`, or
      explanation data to this DTO
- [x] 1.2 Add a tenant-scoped repository/service method resolving "any open `LoanCase`" for a given `loanId`,
      filtered to the caller's own `User.getLoanIds()`, returning status, assigned team/analyst, and opened
      date only — never notes
- [x] 1.3 Decide and implement the no-open-case contact fallback per design.md's open question (tenant-level
      default contact, or omit the contact card)
- [x] 1.4 Wire 1.1-1.3 into `GET /v1/my/loans` (or a new `/v1/my/loans/{id}` detail endpoint if the list
      response would get unwieldy)

## 2. Borrower loan overview — frontend

- [x] 2.1 Rework `MyLoanPage.tsx` per the design canvas (https://claude.ai/artifact/5bRwGSU3C8eAr9cPQsSHoK,
      `Main.dc.html`): status band via existing `RiskBadge`/`RiskMeter`, trend sentence, case-status card,
      loan-officer contact card, static risk-factors panel
- [x] 2.2 Trim the borrower sidebar nav to My loan / Documents / Notifications / Account only
- [x] 2.3 Add the static "what affects your risk status" content (generic factor list, no per-loan data)

## 3. Borrower document access

- [x] 3.1 Add `CLIENT`-permitted endpoints (list, upload) scoped to the caller's own `loanIds`, with no
      `caseId` parameter, reusing `FileStorageService`/`LoanAttachment` storage and existing
      validation/malware-scanning
- [x] 3.2 Add the documents list + upload UI to `MyLoanPage.tsx` (or a dedicated Documents page, matching
      the trimmed nav from 2.2)
- [x] 3.3 Test: a `CLIENT` cannot list or upload to a loan outside their own `loanIds`, including a loan in
      another tenant

## 4. Borrower safety assistant — backend

- [x] 4.1 Add a new service (distinct from `AssistantService`) with its own system prompt: scam/phishing
      verification, plain-language document/notice explanation, payment/deadline guidance, mandatory
      escalation framing — no tools, no `loanId` input parameter, resolves the caller's own loan(s)
      server-side only
- [x] 4.2 Add a new controller/endpoint under the `/v1/my/**` access rule (`SecurityConfig`), separate from
      `/assistant/chat`
- [x] 4.3 Add an audit event type for borrower-assistant calls, distinct from the existing
      `AssistantService` audit events, per design.md decision on the shared vs. separate audit path
- [x] 4.4 Rate-limit/meter the new endpoint the same way `/assistant/chat` is metered (`@Metered`)

## 5. Borrower safety assistant — frontend

- [x] 5.1 Add the floating launcher button (fixed bottom-right) and popover panel component, mounted once in
      the borrower layout, per the design canvas (`Main.dc.html`'s `.fab`/`.fab-panel`)
- [x] 5.2 Implement the persistent safety banner line in the panel ("only sees your own loan, never asks for
      your password or one-time code")
- [x] 5.3 Implement the escalation card (loan-officer contact) as an always-present element in the panel,
      not conditional on the assistant's answer
- [x] 5.4 Wire the panel's input to the new endpoint from 4.2

## 6. Verify

- [x] 6.1 Verify: a `CLIENT` account sees status/trend/case/documents/contact for only their own linked
      loan(s), and the raw probability, model version, and explanation data never appear in any response
      reachable by that role
- [x] 6.2 Verify: the borrower assistant, given a `loanId` for a loan the caller does not own, still answers
      using only the caller's own loan(s) — the supplied id has no effect
- [x] 6.3 Verify: the escalation/contact card renders for every borrower-assistant response, including ones
      where the assistant found nothing concerning
- [x] 6.4 Verify: the existing analyst `/assistant/chat` endpoint, its access rule, and its responses are
      unchanged by this change

Verification, 2026-10-14, in a copy of the tree outside OneDrive:
- Backend unit: `BorrowerStatusTest` 6/6, `BorrowerAssistantPromptTest` 4/4, `AssistantPromptTest` 3/3,
  `RateLimitFilterTest` 1/1, `LoanAttachmentCompensationTest` 4/4.
- `ApiIntegrationTest` (Docker) 27/27, including the three borrower tests (6.1-6.4: no score, model, note or flag
  in `/my/loans`; cross-borrower and cross-tenant document refusals with nothing stored; a supplied `loanId`
  ignored; audit endpoint and metric distinct; analyst assistant still staff-only and answering staff).
- Frontend: lint clean, `vitest` 61/61, build, initial bundle 83.9 KB of 120 KB.
- Not done: a visual check of the borrower pages and the assistant panel in a browser. 6.3 is shown by the panel
  rendering the contact card unconditionally under the thread, not by a recorded screenshot.

