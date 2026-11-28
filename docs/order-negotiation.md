# Order negotiation (#485 / #487)

Proposed shared contract: `OrderNegotiationRepository`, `OrderNegotiation`, `OrderOffer` and
`OrderTerms`. Reuse the existing `OrderResult` and `OrderError` types.

`PendingNegotiation` encodes account/order-scoped operations as Bundle-safe primitives for explicit,
idempotent recovery. This foundation does not enable the feature or supply a Firebase adapter.
Persistence/security belongs to #487; payment execution belongs to #486.

AI assistance: OpenAI Codex.
