## Why

The `CLIENT` role already exists end to end — login, 2FA, `/v1/my/**` access — but the only thing a borrower can
see once logged in is a bare `calibratedProbability` and a timestamp (`MyLoansController`, `MyLoanPage.tsx`).
Everything else the platform knows about their loan (case status, documents, who to contact, what affects their
risk) is either locked behind analyst-only endpoints or doesn't exist for a borrower at all. That is not a
smaller version of the analyst product; it is an unfinished one, and it is unfinished in a way that matters:
a raw default-probability figure handed to the person it describes is an analyst artifact, not something a
borrower can act on, and it brushes against adverse-action/fair-lending disclosure norms that a real lending
product would have to answer for. The borrower side of this SaaS needs its own deliberately-scoped surface,
not the analyst surface with a role check loosened.

A design pass for this surface was already built and reviewed as a Claude Artifact canvas
(https://claude.ai/artifact/5bRwGSU3C8eAr9cPQsSHoK) and is the visual source of truth for `design.md`.

## What Changes

- **The "My loan" page stops showing a raw probability.** It shows a plain-language status band (`Good
  standing` / `Needs attention` / `Elevated risk`, reusing the existing `riskBand()`/`RiskBadge`/`RiskMeter` —
  the meter already renders position without a number) and a one-line trend sentence instead of a delta figure.
  The raw `calibratedProbability`, model version, and SHAP explanation stay analyst/admin-only; `MyLoanView`
  is not simply extended with more analyst fields.
- **A case-status card.** If a borrower's loan has an open `LoanCase`, show its status and assigned team in
  plain language, never internal case notes; otherwise, "no open items." This needs a new tenant-scoped lookup
  from a `CLIENT`'s `loanId` to any `LoanCase` referencing it, which does not exist today (case lookups are
  currently analyst/case-search only).
- **A loan-officer contact card.** The case's assigned analyst (or a configured default contact when there is
  no open case), with a message affordance. In-app messaging itself is out of scope for this change (see
  Impact); the affordance can be a `mailto:` stub.
- **Borrower-scoped document access.** A borrower can view and upload attachments on their own linked loan(s),
  reusing the existing `LoanAttachment`/`FileStorageService` machinery behind new `CLIENT`-permitted endpoints
  that are scoped to the caller's own `User.getLoanIds()` — distinct from the existing analyst/case-scoped
  attachment endpoints, which remain tenant-wide for analysts.
- **A static "what affects your risk status" panel.** Generic factor names only (payment history, credit
  utilization, loan-to-value, length of credit history) — never the borrower's own SHAP values.
- **A narrowed borrower navigation.** My loan, Documents, Notifications, Account. No Cases, Automation Rules,
  Model Governance, or anything else an analyst's sidebar carries.
- **A second, separate LLM assistant, scoped and purposed for the borrower.** The existing `/assistant/chat`
  (`AssistantController`) is explicitly ANALYST/ADMIN-only today: it accepts any `loanId` in the tenant and
  grounds its answer in that loan's case notes, which is not safe to expose to a borrower even restricted to
  their own loan, because its entire purpose is informing the business, not protecting the person being scored.
  This change adds a distinct endpoint and service, hard-scoped to only the caller's own linked loan(s) (never
  an arbitrary `loanId`, even if supplied), with a different system prompt oriented around borrower safety:
  verifying whether a message the borrower describes is a likely scam, explaining their own documents/notices
  in plain language, payment/deadline guidance, and explicit escalation to their loan officer on anything
  uncertain — never financial or legal advice framing. No tools, no cross-loan or cross-tenant access, its own
  audit event type distinct from analyst assistant calls.
- **The assistant is a floating launcher, not a page.** A round button fixed to the bottom-right corner of the
  viewport (the conventional chat-widget position), opening a compact popover panel above it — not a sidebar
  nav item, not a route. Visually reuses the app's existing popover pattern (see `NotificationBell.tsx`) as a
  new, separate component.
- **BREAKING**: none. `MyLoanView` gains fields rather than losing any; new endpoints are additive; the
  existing analyst `/assistant/chat` is untouched.

## Capabilities

### New Capabilities
- `borrower-loan-overview`: the reworked "My loan" page — plain-language status and trend, case-status card,
  loan-officer contact, and the generic risk-factors panel, all deliberately excluding analyst-only figures.
- `borrower-document-access`: CLIENT-scoped view/upload of attachments on the caller's own linked loan(s).
- `borrower-safety-assistant`: the borrower-only, own-loan-scoped assistant (endpoint, service, floating UI),
  purposed around borrower safety rather than loan-risk Q&A, and its audit trail.
- `assistant-prompt-trend-mining` (later phase, see `design.md`): aggregated, anonymized topic clustering over
  `borrower-safety-assistant` prompts, surfaced to analysts only as portfolio-level trend signals (e.g. "N
  borrowers this week described a gift-card payment scam") — individual borrower transcripts are never
  surfaced to analysts as a side effect of this capability. Reuses the topic-clustering approach already built
  for case notes (`insights/InsightService.java`, `TopicModel.java`) against a new data source rather than new
  clustering infrastructure.

### Modified Capabilities
- None. `openspec/specs/` is empty: no prior capability specs exist for this repository.

## Impact

- **Backend**: `MyLoansController`/`MyLoanView` gain fields; a new tenant-scoped "open case for this loan"
  lookup; new `CLIENT`-permitted attachment endpoints alongside the existing analyst ones; a new
  `assistant` package addition (service + controller) separate from `AssistantService`/`AssistantController`,
  with its own audit event type; `SecurityConfig` gains a new `/v1/my/**`-style route for it.
- **Frontend**: `MyLoanPage.tsx` reworked; a new floating assistant launcher + popover component mounted
  in the borrower layout only; borrower sidebar nav trimmed to four items.
- **Data**: no breaking schema changes. The later trend-mining phase needs its own retention/anonymization
  design (what is dropped before clustering, how "aggregated only" is enforced) — flagged as an open design
  decision, not specified here.
- **Out of scope**: real in-app messaging (the contact card is a stub), SSE/real-time push notifications (a
  separate backlog item), and any change to the existing analyst `/assistant/chat` endpoint or its access
  rule.
