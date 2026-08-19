# Cursor Session Exporter

Export a Cursor session into a validated JSON/JSONL bundle containing conversation history, scripts, commands, artifacts, workspace context, restoration metadata, and referenced sessions.

## Requirements

- macOS or Linux
- Java supported by Groovy 6
- Groovy 6.x available as `groovy`
- Cursor transcripts under `~/.cursor/projects`, unless a custom transcript root is supplied

The exporter has no third-party project dependencies. It has been tested with Groovy 6.0.0-beta-1 and Java 26.0.2.

Verify the runtime:

```sh
java -version
groovy --version
```

On macOS, Groovy can be installed with Homebrew using `brew install groovy`. On Linux, install Groovy 6 through SDKMAN or the system package manager.

## User installation

From the repository root:

```sh
chmod u+x cursor/cursor-session-exporter.groovy
mkdir -p "$HOME/.local/bin"
ln -sfn "$PWD/cursor/cursor-session-exporter.groovy" "$HOME/.local/bin/cursor-session-exporter"
```

Add this line to `~/.zshrc` on macOS/zsh or `~/.bashrc` on Linux/bash:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

Reload the shell and verify the command:

```sh
command -v cursor-session-exporter
```

The symlink is preferable to an alias because it works across shells and subprocesses. Recreate it if the repository is moved.

## Configuration

Save reusable defaults without a session ID:

```sh
cursor-session-exporter config \
  --output-dir "$HOME/sessions-export" \
  --reference-scope relevant
```

Every persistent export option can be stored:

```sh
cursor-session-exporter config \
  --output-dir "$HOME/sessions-export" \
  --reference-scope relevant \
  --transcript-root "$HOME/.cursor/projects/example/agent-transcripts" \
  --terminal-root "$HOME/.cursor/projects/example/terminals" \
  --agent-tool-root "$HOME/.cursor/projects/example/agent-tools" \
  --workspace "/path/to/workspace"
```

Configuration is stored in `~/.cursor-session-exporter/config.json`. Running the command without flags prints the stored JSON:

```sh
cursor-session-exporter config
```

Remove a saved value by its JSON key:

```sh
cursor-session-exporter config --unset workspace
```

Command-line options override saved values. Saved values override built-in defaults and inference. Project-specific roots and workspace values apply to every export, so store them only when exports consistently use the same Cursor project.

## Quick start

Only a full session UUID or unique eight-character prefix is normally required:

```sh
SESSION="1ab2c345"
cursor-session-exporter "$SESSION"
```

The exporter searches `~/.cursor/projects` automatically. It derives the terminal and agent-tool roots from the located transcript and infers the workspace from terminal logs.

To invoke the script directly:

```sh
SCRIPT="/absolute/path/to/ai-session-exporter/cursor/cursor-session-exporter.groovy"
SESSION="1ab2c345"
groovy "$SCRIPT" "$SESSION"
```

## Finding a session ID

Session transcripts are stored here:

```text
~/.cursor/projects/<project-slug>/agent-transcripts/<session-id>/<session-id>.jsonl
```

The project slug may be derived from the workspace path or may be opaque. The transcript directory and filename contain the full session UUID.

Cursor tab titles do not reliably show the UUID. To map a tab to a session, search for a unique phrase from its conversation:

```sh
rg -l -F "unique phrase from the chat" \
  "$HOME"/.cursor/projects/*/agent-transcripts/*/*.jsonl
```

The matching directory and filename identify the session. If no files match, use a different phrase. If multiple files match, refine the phrase rather than selecting one arbitrarily. The most recently modified transcript is only a heuristic when multiple tabs or background sessions are active.

## Output directory

`--output-dir` selects where the generated bundle is written:

```sh
groovy "$SCRIPT" "$SESSION" --output-dir "$HOME/sessions-export"
```

The output directory is created when needed. Export directories use `0700` permissions and files use `0600`.

If neither configuration nor `--output-dir` supplies a destination, the fallback is `sessions-export` beside `cursor-session-exporter.groovy`.

## Workspace override

`--workspace` identifies the source project used for workspace metadata and referenced file snapshots:

```sh
groovy "$SCRIPT" "$SESSION" --workspace "/path/to/workspace"
```

This option is normally unnecessary. Use it when workspace inference is unavailable or incorrect, or when the project has moved.

## Reference scope

The default scope is `relevant`:

```sh
groovy "$SCRIPT" "$SESSION" --reference-scope relevant
```

- `recursive`: export every resolved referenced session.
- `direct`: export only depth-one references.
- `relevant`: export references classified as primary or supporting.
- `none`: export only the root session.

The complete forensic reference graph is always generated. Scope controls which referenced session directories are materialized.

## Advanced usage

Source-root overrides are only needed for nonstandard locations:

```sh
groovy "$SCRIPT" "$SESSION" \
  --reference-scope relevant \
  --output-dir "$HOME/sessions-export" \
  --transcript-root "$HOME/.cursor/projects/example/agent-transcripts" \
  --terminal-root "$HOME/.cursor/projects/example/terminals" \
  --agent-tool-root "$HOME/.cursor/projects/example/agent-tools" \
  --workspace "/path/to/workspace"
```

The installed command accepts the same options:

```sh
cursor-session-exporter "$SESSION" \
  --reference-scope relevant \
  --output-dir "$HOME/sessions-export"
```

## Validate an export

Validate an existing bundle without changing it:

```sh
cursor-session-exporter "$SESSION" \
  --output-dir "$HOME/sessions-export" \
  --validate-only
```

## Security

Exports can contain raw conversation text, commands, terminal output, file snapshots, and credentials that appeared in the session. Keep bundles private even though the exporter enforces owner-only permissions.
