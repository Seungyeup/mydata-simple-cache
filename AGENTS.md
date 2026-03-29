# AGENTS.md

This file is instructions for coding agents working in this repository.

## Repo Reality Check (Important)

As of the last scan, this workspace contains no source files or build config
(`ls -la` shows only `.` and `..`). That means there are no repo-specific
commands or style conventions to follow yet.

If you expected a real project here, confirm you are in the correct directory.

## How To Discover The Real Commands (When Code Exists)

Prefer *repo-declared* commands over guesswork:

- Look for `Makefile`/`Justfile`/`Taskfile.yml` first.
- For JS/TS: read `package.json` `scripts`.
- For Python: read `pyproject.toml` (tooling under `[tool.*]`).
- For Go/Rust: read `go.mod` / `Cargo.toml`.
- Check CI for the authoritative sequence: `.github/workflows/*.yml`.

Then record the exact commands back into this file.

## Default Command Conventions (Placeholders)

Only use the sections that match the repo's actual tooling. If a command fails
because the tool does not exist, stop and discover the real command.

### Node / TypeScript (npm/pnpm/yarn/bun)

- Install: `npm ci` (or `pnpm i --frozen-lockfile`, or `yarn install --frozen-lockfile`)
- Build: `npm run build`
- Lint: `npm run lint`
- Format: `npm run format` (or `npm run prettier:check`)
- Test: `npm test`
- Single test (Jest): `npm test -- -t "test name" path/to/file.test.ts`
- Single test (Vitest): `npm test -- -t "test name" path/to/file.test.ts`

Notes:
- When passing args through npm scripts, use `--`.
- Prefer project scripts (they pin versions/config) over calling `jest`/`vitest` directly.

### Python (pytest)

- Install (uv): `uv sync` (or `uv pip install -r requirements.txt`)
- Lint/format (ruff): `ruff check .` and `ruff format .`
- Typecheck (pyright/mypy): `pyright` (or `mypy .`)
- Test: `pytest`
- Single test: `pytest path/to/test_file.py::TestClass::test_name`
- Single test by pattern: `pytest -k "pattern"`

### Go

- Format: `gofmt -w .`
- Lint (golangci-lint): `golangci-lint run ./...`
- Test: `go test ./...`
- Single test: `go test ./... -run '^TestName$' -count=1`

### Rust

- Format: `cargo fmt --all`
- Lint: `cargo clippy --all-targets --all-features -- -D warnings`
- Test: `cargo test`
- Single test: `cargo test test_name -- --nocapture`

## Code Style Guidelines (Language-Agnostic)

When there is no established code style yet, follow these defaults until the
repo introduces a formatter/linter.

### Formatting

- Use the repo formatter if present (Prettier/Black/ruff format/gofmt/rustfmt).
- Prefer automated formatting over manual alignment.
- Keep lines reasonably short (target ~100 chars unless the project standard differs).

### Naming

- Use descriptive names; avoid single-letter variables outside small scopes.
- Keep naming consistent within a module.
- Boolean names read like predicates: `isReady`, `hasAccess`, `shouldRetry`.

### Imports

- Group imports consistently and avoid unused imports.
- Prefer absolute imports only if the repo already uses them.
- Avoid circular imports; if you hit one, step back and restructure.

### Types / Interfaces

- Prefer explicit types at module boundaries (public APIs, exported functions).
- Avoid weakening type safety to "make it compile".
- If you must widen types for flexibility, do it intentionally and locally.

### Error Handling

- Do not swallow errors (no empty `catch` / broad `except` without re-raising).
- Add context when returning/raising errors (what operation failed, key identifiers).
- For user-facing errors: keep messages actionable; avoid leaking secrets.
- Prefer structured errors over string matching.

### Logging

- Log at boundaries (request handlers, jobs, CLIs), not deep utility layers.
- Never log secrets (tokens, passwords, raw credentials, full auth headers).

### Testing

- New behavior should include a test when feasible.
- Keep tests deterministic: avoid timing sleeps; mock time/IO where needed.
- When fixing a bug: add a regression test first if that's practical.

## Repo Layout Defaults (When Adding Code)

If there is no established structure yet, prefer:

- `src/` for library/app code.
- `tests/` for tests (mirror `src/` structure when possible).
- `scripts/` for one-off dev scripts (keep them small and documented).
- `docs/` for design notes and operational runbooks.

Keep module boundaries crisp; avoid dumping everything into a single `utils/`.

## Dependencies And Secrets

- Prefer existing dependencies over adding new ones.
- Record new tooling commands here when you add them.
- Never commit secrets (API keys, tokens, credentials); use `.env.example` patterns.

## TypeScript / JavaScript Defaults (If This Becomes A TS Repo)

- No type-safety suppression (`as any`, `@ts-ignore`, `@ts-expect-error`).
- Prefer `unknown` over `any` for untrusted inputs; narrow via runtime checks.
- Prefer `const`; avoid reassignments unless necessary.
- Validate external inputs at the edge (API requests, env vars, files).

## Cursor / Copilot Rules

No Cursor rules found (`.cursor/rules/` or `.cursorrules`).
No Copilot instructions found (`.github/copilot-instructions.md`).

If these files are added later, they override generic guidance here; copy their
constraints and conventions into this document.

## Update Policy

- If you add build tooling, update the command sections immediately.
- If you add format/lint/typecheck tools, record the exact invocation here.
- If the repo gains conventions (folder structure, error types, import paths),
  document them and remove placeholders.

## PR/Review Checklist (Lightweight)

- `build`, `lint`, and `test` pass locally (or note existing failures).
- Add/adjust a focused test for behavioral changes.
- Keep changes scoped; no drive-by refactors during bug fixes.
- Update `AGENTS.md` if you introduced new commands or conventions.