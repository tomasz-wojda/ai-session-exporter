# Cursor Session Exporter Implementation

## Checklist
- [x] 1. Materialize the approved specification and checklist.
- [x] 2. Implement CLI parsing, session resolution, configurable roots, and exit codes.
- [x] 3. Implement streaming transcript parsing and stable provenance.
- [x] 4. Implement recursive session references and cycle prevention.
- [x] 5. Export conversation and tool event streams.
- [x] 6. Export file operations, revisions, snapshots, patches, and hashes.
- [x] 7. Export commands and correlate terminal or agent-tool outcomes.
- [x] 8. Export artifacts, workspace metadata, runtime, Git state, and search indexes.
- [x] 9. Generate manifests, summaries, checkpoint, restore context, and integrity reports.
- [x] 10. Implement staging, atomic replacement, deterministic output, incremental reuse, and validation.
- [x] 11. Add unit and integration fixtures.
- [x] 12. Run automated and real-session verification.
- [x] 13. Record final verification and semantic commit information.

## Verification record
- Runtime: Groovy 6.0.0-beta-1 on Java 26.0.2.
- Fixture suite: `groovy tests/session-exporter-test.groovy` passed.
- Fixture coverage: full UUID and prefix resolution, ambiguous/missing/malformed identifiers, malformed JSONL preservation, query/response/tool extraction, file revisions and snapshots, exact/inferred/unmatched command correlation, failed command outcomes, recursive cycles, deterministic reruns, incremental artifact reuse, validation, and preservation of a prior bundle after failure.
- Real export: session `6ab4f267-fa2e-4f6f-9cec-8a06fd864a6f` exported successfully.
- Real validation: 386 files validated; 15 sessions including 14 recursive references; known Automox references were present.
- Real root session: 740 events, 88 queries, 292 responses, 335 tool calls, 128 Shell commands, 36 file operations, and 12 artifacts.
- Command evidence: successful, failed, aborted, and unmatched outcomes were retained without inventing unavailable results.
- Determinism: the stable timeline checksum remained unchanged across consecutive exports.
- Incremental reuse: the second real export reused 84 unchanged artifacts.
- Static checks: Cursor diagnostics reported no linter errors; `git diff --check` reported no whitespace errors.
- Repository state: files remain untracked; no commit or push was performed.

## Semantic commit information
- Title: `feat(session-exporter): add deterministic Cursor session archives`
- Description: `Export Cursor conversations, recursive references, scripts, commands, artifacts, and restoration metadata into validated JSON/JSONL bundles while preserving incomplete historical evidence explicitly.`
