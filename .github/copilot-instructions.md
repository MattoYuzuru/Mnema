# Mnema repository instructions

Before changing the repository, read `AGENTS.md`, then use `docs/README.md` to locate the canonical product, architecture, operations, and decision documents for the task.

When creating or updating an Issue or pull request, follow `docs/engineering/work-item-standard.md`. Write for both a human with little project context and an implementation agent. State the outcome, scope, acceptance evidence, risks, and rollback boundary; link to canonical docs instead of copying them.

Do not place secrets or `.env` values in code, logs, Issues, or pull requests. Use the task-scoped autonomy and external-effect boundaries in `AGENTS.md`; do not add a second approval loop or treat an authorized end-to-end workflow as unrelated confirmations.

Run the quality gates required by `AGENTS.md` on the exact commit before proposing merge. Report a blocked or missing gate explicitly.

Use the repository-local Git identity as the sole commit attribution. Do not add co-author, sign-off, generated-by, on-behalf-of, or similar commit trailers unless the user explicitly asks for them.
