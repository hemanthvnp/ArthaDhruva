## Context

`CLIENT` is a fully wired role (`AuthController`, `ActivationController`, `SecurityConfig`'s `/v1/my/**` rule)
with nothing behind it worth looking at: `MyLoansController.myLoans()` returns `calibratedProbability` and
`computedAt`, nothing else. Everything else a borrower might want — case status, documents, who to contact,
why the number is what it is — either doesn't exist for `CLIENT` or exists only on analyst-scoped endpoints
that assume the caller can see any loan in the tenant.

The existing LLM assistant (`AssistantController`/`AssistantService`) is explicitly ANALYST/ADMIN-only by its
own doc comment: it accepts an arbitrary `loanId` within the tenant and grounds its answer in that loan's
case notes. It has no tools and changes nothing, which makes it safe for analysts to use freely — but that
safety property doesn't transfer to borrowers, because the risk for a borrower-facing assistant isn't "can it
act," it's "can it see or say things it shouldn't." Opening `/assistant/chat` to `CLIENT` with a loanId
ownership check would still let the model see internal analyst notes about the borrower's own loan, phrased
for an analyst audience, and would still answer whatever is asked rather than something scoped to keeping the
borrower safe.

A visual design pass for the whole surface exists as a Claude Artifact canvas
(https://claude.ai/artifact/5bRwGSU3C8eAr9cPQsSHoK, two artboards: the reworked "My loan" page, and the
assistant panel as a detail reference) and is the layout and copy source of truth for implementation.

## Goals / Non-Goals

**Goals:**
- A borrower can see their loan's status, documents, and an open case's status without being shown analyst
  internals.
- A borrower has a way to ask something and reach a human, in-context, without leaving the page they're on.
- A second assistant exists specifically to protect the borrower (scam/phishing verification, plain-language
  explanation, escalation) and is impossible to reach from the analyst assistant's code path or vice versa.
- Every new borrower-facing read is provably scoped to the caller's own `User.getLoanIds()` — no borrower can
  cause the backend to evaluate a `loanId` they are not linked to, even by supplying one explicitly.

**Non-Goals:**
- Real in-app messaging. The loan-officer contact is a stub (`mailto:`) in this change.
- Real-time/push notifications for the assistant or case updates (separate backlog item; today's
  `NotificationBell` poll stays as-is).
- Changing the existing analyst `/assistant/chat` endpoint, its access rule, or its prompt in any way.
- Specifying the trend-mining anonymization mechanism in full (see Open Questions) — this change defines the
  boundary it must respect, not the algorithm.

## Decisions

**1. The borrower sees a status band and a trend sentence, never the raw probability.** `MyLoanView` gains a
`riskBand` (reusing the existing `riskBand()` mapping) and a short trend string computed server-side; it does
not gain `calibratedProbability` formatted differently — the field stays off the borrower response entirely.
*Why not just hide the number in the UI:* a field present in the API response is a field a curious borrower's
browser devtools will show them anyway. Keeping it off `MyLoanView` (a different DTO from the analyst-facing
`ScoreResponse`) makes the abstraction a backend guarantee, not a frontend courtesy. *Why not show the number
at all:* a precise model-driven default probability handed to the person it describes is not actionable for
them and edges toward adverse-action-notice territory a real lender would need legal sign-off for; a band plus
a trend sentence is both safer and more useful to someone who isn't going to do anything differently between
4.1% and 4.3%.

**2. The borrower assistant is a new service and endpoint, not a role check on the existing one.** A new
`assistant.BorrowerAssistantService`/`BorrowerAssistantController` (package TBD at implementation time) with
its own system prompt, reachable only via a new endpoint under the `/v1/my/**` access rule. It takes no
`loanId` parameter at all — it resolves the caller's own linked loan(s) server-side from `User.getLoanIds()`,
the same pattern `MyLoansController` already uses, so there is no parameter to tamper with. *Why not add a
`CLIENT`-safe branch inside `AssistantService`:* the two assistants have different jobs (inform the business
vs. protect the person being scored), different allowed inputs (case notes vs. nothing beyond the borrower's
own loan and documents), and different failure modes (a wrong analyst answer costs analyst time; a wrong
borrower answer about a scam costs the borrower money). Branching one service on role invites exactly the kind
of context leak this change exists to avoid — a future change to the analyst prompt could silently alter what
borrowers see. Two services with nothing shared but the LLM client and the audit pattern keep that impossible
by construction.

**3. The assistant is a floating launcher with a popover panel, not a page.** A fixed bottom-right button
(the conventional chat-widget position) opening a compact panel anchored above it, mounted once in the
borrower layout rather than routed to. *Why not a sidebar page* (the analyst assistant's pattern): the
analyst assistant is a research session — loan context-switching, multi-turn investigation, a dedicated
working surface. The borrower's need ("I'm looking at a suspicious text right now, is this real") is a quick,
contextual check from wherever they already are; a page would mean abandoning what they were looking at to ask
about it. The app already has the right mechanism for an overlay panel triggered from a fixed control — the
`NotificationBell` popover — so this reuses that pattern rather than inventing a new one, as a new component
rather than extending the bell's.

**4. A borrower-visible case status needs a new lookup, not a new table.** `LoanCase` already carries
`loanId` and `tenantId`; there is no existing query from "a `CLIENT`'s `loanId`" to "any open case referencing
it" because every existing case entry point assumes an analyst searching across the tenant. This change adds
one read method on the existing repository/service (tenant-scoped, filtered to the caller's own `loanIds`,
status not `CLOSED`), not a new table or a denormalized pointer on `User`.

**5. Borrower document access is new endpoints, not a loosened filter on the analyst ones.** The analyst
attachment endpoints are case-scoped and tenant-wide by design (an analyst can see any case's attachments).
A borrower endpoint that just added a tenant+ownership filter to the same controller would couple two
authorization models that should fail independently. New `CLIENT`-permitted endpoints, reusing
`FileStorageService`/`LoanAttachment` storage but with their own scoping (caller's own `loanIds` only, no
`caseId` parameter at all) keep that boundary as explicit as decision 2's.

**6. Prompt-trend mining stays a later phase with a hard boundary, not a mechanism specified now.** The
`insights` package already clusters case notes into topics (`InsightService`, `TopicModel`) on a schedule; the
same approach pointed at borrower-assistant prompts is architecturally cheap. What is not cheap, and not
decided here, is the anonymization mechanism that keeps individual transcripts from ever reaching an analyst
as a side effect. This change states the requirement (aggregated signal only, no per-borrower drill-down) and
defers the how to its own design pass; see Open Questions. The repo already has `PiiRedactor`/`AuditRedactor`
(uncommitted, from the current hardening pass) that any implementation of this phase should build on rather
than duplicate.

## Risks / Trade-offs

- **[Risk]** A borrower pastes a message into the safety assistant that contains a third party's personal
  data (a scammer's phone number, another person's name). → The assistant has no tools and persists nothing
  beyond the existing audit trail's retention policy; this change does not add a new place that data is stored
  longer-term, and the trend-mining phase (decision 6) must not read raw prompts, only post-anonymization
  output.
- **[Risk]** The safety assistant gives a wrong verdict — calls a real lender communication a scam, or misses
  a real one. → The escalation card (decision 3's panel) is always present regardless of the model's answer,
  never conditionally hidden on a "confident" response; the prompt is written to hedge and defer to a human
  rather than assert certainty.
- **[Risk]** A borrower-scoped endpoint (case lookup, documents, assistant) is implemented with a filter that
  can be bypassed by supplying someone else's id. → All three reuse the same pattern already proven in
  `MyLoansController` (resolve the caller's own `loanIds` server-side, never trust a client-supplied id for
  a borrower-scoped read), and should be covered by the same kind of cross-tenant/cross-borrower test the
  existing RLS and tenant-filter tests already use as a template.
- **[Trade-off]** No real in-app messaging means "Message" is a `mailto:` stub. Acceptable for this change;
  revisit if usage shows borrowers actually need a tracked thread rather than an email.
- **[Trade-off]** The assistant has no memory across turns (matching the existing analyst assistant's
  single-turn design) — a borrower re-pasting context every message is a real friction cost, accepted to keep
  this change's scope to the safety boundary rather than a chat-history feature.

## Migration Plan

1. Additive only: new `MyLoanView` fields, new endpoints, new frontend components. No existing endpoint,
   column, or contract changes.
2. Ship the "My loan" page rework and document access first (capabilities `borrower-loan-overview`,
   `borrower-document-access`) — no LLM dependency, lowest risk.
3. Ship the safety assistant (`borrower-safety-assistant`) once the above is live, since its escalation card
   depends on the loan-officer contact already existing on the page.
4. Prompt-trend mining (`assistant-prompt-trend-mining`) is a separate follow-up change once its own design
   is written; not part of this change's `tasks.md`.
5. Rollback at any point is redeploying the previous image; nothing here is destructive or requires a data
   migration to undo.

## Open Questions

- Who is the loan-officer contact when a loan has no open `LoanCase`? Today "assigned to" only exists on
  `LoanCase` (`LoanCaseService`); there is no loan-level owning analyst. Needs a decision before
  implementation: a configured tenant-level default contact, or deferring the contact card entirely when
  there's no open case.
- What cohort size makes a trend-mining signal "aggregated enough" to surface to analysts (decision 6) — a
  raw count, a minimum-N threshold, a k-anonymity-style rule? Deferred to that phase's own design.
- Should the safety assistant's audit events feed the same `AuditEventWriter`/`model_invocation_events` path
  as the analyst assistant (same storage, a distinguishing event type) or a separate table, given the
  difference in who the data is about and what it may contain? Leaning toward the same path with a type
  discriminator, for one review surface, but not decided.
