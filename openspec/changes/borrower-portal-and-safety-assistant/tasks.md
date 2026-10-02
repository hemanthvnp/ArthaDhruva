Scope: capabilities `borrower-loan-overview`, `borrower-document-access`, `borrower-safety-assistant`.
`assistant-prompt-trend-mining` is specified but deliberately not built here — see design.md's Migration Plan
and Open Questions; it ships as its own change once its anonymization design is decided.

## 1. Borrower loan overview — backend

- [ ] 1.1 Add `riskBand` and a server-computed `trend` string to `MyLoanView`/`MyLoansController.myLoans()`;
      remove no existing field (additive) but do not add `calibratedProbability`, `modelVersion`, or
      explanation data to this DTO
- [ ] 1.2 Add a tenant-scoped repository/service method resolving "any open `LoanCase`" for a given `loanId`,
      filtered to the caller's own `User.getLoanIds()`, returning status, assigned team/analyst, and opened
      date only — never notes
- [ ] 1.3 Decide and implement the no-open-case contact fallback per design.md's open question (tenant-level
      default contact, or omit the contact card)
- [ ] 1.4 Wire 1.1-1.3 into `GET /v1/my/loans` (or a new `/v1/my/loans/{id}` detail endpoint if the list
      response would get unwieldy)

## 2. Borrower loan overview — frontend

- [ ] 2.1 Rework `MyLoanPage.tsx` per the design canvas (https://claude.ai/artifact/5bRwGSU3C8eAr9cPQsSHoK,
      `Main.dc.html`): status band via existing `RiskBadge`/`RiskMeter`, trend sentence, case-status card,
      loan-officer contact card, static risk-factors panel
- [ ] 2.2 Trim the borrower sidebar nav to My loan / Documents / Notifications / Account only
- [ ] 2.3 Add the static "what affects your risk status" content (generic factor list, no per-loan data)

## 3. Borrower document access

- [ ] 3.1 Add `CLIENT`-permitted endpoints (list, upload) scoped to the caller's own `loanIds`, with no
      `caseId` parameter, reusing `FileStorageService`/`LoanAttachment` storage and existing
      validation/malware-scanning
- [ ] 3.2 Add the documents list + upload UI to `MyLoanPage.tsx` (or a dedicated Documents page, matching
      the trimmed nav from 2.2)
- [ ] 3.3 Test: a `CLIENT` cannot list or upload to a loan outside their own `loanIds`, including a loan in
      another tenant

## 4. Borrower safety assistant — backend

- [ ] 4.1 Add a new service (distinct from `AssistantService`) with its own system prompt: scam/phishing
      verification, plain-language document/notice explanation, payment/deadline guidance, mandatory
      escalation framing — no tools, no `loanId` input parameter, resolves the caller's own loan(s)
      server-side only
- [ ] 4.2 Add a new controller/endpoint under the `/v1/my/**` access rule (`SecurityConfig`), separate from
      `/assistant/chat`
- [ ] 4.3 Add an audit event type for borrower-assistant calls, distinct from the existing
      `AssistantService` audit events, per design.md decision on the shared vs. separate audit path
- [ ] 4.4 Rate-limit/meter the new endpoint the same way `/assistant/chat` is metered (`@Metered`)

## 5. Borrower safety assistant — frontend

- [ ] 5.1 Add the floating launcher button (fixed bottom-right) and popover panel component, mounted once in
      the borrower layout, per the design canvas (`Main.dc.html`'s `.fab`/`.fab-panel`)
- [ ] 5.2 Implement the persistent safety banner line in the panel ("only sees your own loan, never asks for
      your password or one-time code")
- [ ] 5.3 Implement the escalation card (loan-officer contact) as an always-present element in the panel,
      not conditional on the assistant's answer
- [ ] 5.4 Wire the panel's input to the new endpoint from 4.2

## 6. Verify

- [ ] 6.1 Verify: a `CLIENT` account sees status/trend/case/documents/contact for only their own linked
      loan(s), and the raw probability, model version, and explanation data never appear in any response
      reachable by that role
- [ ] 6.2 Verify: the borrower assistant, given a `loanId` for a loan the caller does not own, still answers
      using only the caller's own loan(s) — the supplied id has no effect
- [ ] 6.3 Verify: the escalation/contact card renders for every borrower-assistant response, including ones
      where the assistant found nothing concerning
- [ ] 6.4 Verify: the existing analyst `/assistant/chat` endpoint, its access rule, and its responses are
      unchanged by this change
