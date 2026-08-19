# Cursor Session Exporter

## Deliverables
- Create `cursor/cursor-session-exporter.groovy` as a standalone, dependency-free Groovy 6 entry point.
- Create `cursor/tests/cursor-session-exporter-test.groovy` with isolated fixtures under `cursor/tests/fixtures/`.
- Export bundles under `cursor/sessions-export/<full-session-uuid>/`.
- Preserve the approved implementation checklist and verification record in `IMPLEMENTATION.md`.
- Do not create commits, push changes, or add documentation beyond `PLAN.md` and `IMPLEMENTATION.md`.

## CLI and discovery
- Invoke with `groovy cursor-session-exporter.groovy <session_id>`.
- Accept a full UUID or a unique eight-character hexadecimal prefix.
- Search Cursor transcript roots matching `~/.cursor/projects/*/agent-transcripts/<uuid>/<uuid>.jsonl`.
- Reject malformed, missing, and ambiguous identifiers with distinct nonzero exit codes.
- Support output, transcript-root, terminal-root, agent-tool-root, and workspace overrides.
- Use Groovy and JDK facilities only.

## Export model
- Use full UUIDs for directories and eight-character prefixes for filenames.
- Use JSON for manifests, indexes, reports, summaries, checkpoints, and restore context.
- Use JSONL for ordered event streams.
- Export top-level metadata plus conditional `session`, `scripts`, `commands`, `artifacts`, `workspace`, `integrity`, and `references` sections.
- Recursively export referenced sessions beneath `references/<full-uuid>/`.
- Omit empty optional directories and preserve raw secret values unchanged.

## Parsing and provenance
- Parse transcript JSONL line by line and retain source path, line number, role, content index, and stable event identity.
- Normalize user queries, assistant responses, context, summaries, tool calls, and available tool results.
- Resolve UUIDs and unique prefixes only against locally indexed transcripts.
- Detect reference cycles and record the relationship graph.
- Extract file mutations from Write, Edit, ApplyPatch, and EditNotebook calls.
- Preserve mutation payloads and snapshot existing explicitly referenced workspace files.
- Extract every Shell command and correlate terminal evidence by exact command, normalized command, then title or description.
- Label command-result correlation as exact, inferred, or unmatched; never fabricate missing results.
- Copy explicitly referenced terminal and agent-tool evidence and record unavailable or malformed evidence.

## Restoration and indexing
- Generate restore context containing session identity, relationships, available rules/context, checkpoint state, workspace mappings, required files, and a bootstrap prompt.
- Generate indexes for paths, hosts, URLs, commands, identifiers, tools, and artifacts.
- Link prompts, responses, tool calls, results, file operations, and artifacts with stable content-derived IDs.
- Distinguish current workspace observations from historical transcript evidence.

## Reliability
- Build in a staging directory, validate, then atomically replace the destination.
- Preserve the previous valid bundle if generation or validation fails.
- Reuse unchanged checksum-addressed artifacts on rerun and avoid duplicate events.
- Validate JSON/JSONL syntax, required fields, event ordering, IDs, references, checksums, containment, and manifest counts.
- Treat unavailable historical evidence as reported incompleteness rather than fabricated data.
- Enforce `0700` on the output root and every exported directory and `0600` on every exported file.
- Apply permissions during staging, after copied attributes, before atomic replacement, and after replacement.
- Use POSIX permissions where available and owner-only Java file flags as a best-effort fallback.
- Keep validation read-only while reporting any POSIX permission mismatch.

## Verification
- Cover UUID and prefix resolution, malformed input, extraction, revision reconstruction, command correlation, recursive references, cycles, checksums, determinism, incremental reuse, and atomic recovery.
- Run an integration fixture against synthetic transcript, terminal, and agent-tool roots.
- Run a real export for `6ab4f267-fa2e-4f6f-9cec-8a06fd864a6f`.
- Verify known Automox references, successful and failed/background command evidence, and bundle validation.
- Verify all POSIX export directories are `0700` and all export files are `0600`, including copied artifacts and terminal logs.
- Provide a semantic commit title and description without committing or pushing.

IMPLEMENTATION CHECKLIST:
1. Write the approved specification to `PLAN.md` and its tracker to `IMPLEMENTATION.md`.
2. Add deterministic CLI parsing, session resolution, configurable roots, and exit codes.
3. Add streaming JSONL parsing and stable event/provenance models.
4. Add recursive reference discovery, transcript indexing, cycle prevention, and relationship export.
5. Add query, response, context, summary, and tool-call streams.
6. Add file-operation extraction, revisions, snapshots, patches, hashes, and path controls.
7. Add Shell extraction and exact/inferred/unmatched result correlation.
8. Add artifacts, workspace metadata, runtime, Git, and search indexes.
9. Add manifest, checkpoint, summary, restore context, integrity, and completeness outputs.
10. Add staging, atomic replacement, deterministic reruns, incremental reuse, and validation.
11. Add unit and integration fixtures.
12. Run tests and the real session export.
13. Record verification results and provide the semantic commit title and description.

HARDENING CHECKLIST:
1. Rename the Cursor exporter and test files.
2. Update executable-name references and manifest metadata.
3. Add POSIX and owner-only fallback permission helpers.
4. Secure output roots, staging trees, generated files, copied artifacts, and atomic replacements.
5. Add security metadata and read-only permission validation.
6. Extend fixture coverage for `0700` directories and `0600` files.
7. Regenerate and validate the real session bundle.
