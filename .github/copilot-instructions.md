# Mnema repository instructions

Before changing the repository, read `AGENTS.md`, then use `docs/README.md` to locate the canonical product, architecture, operations, and decision documents for the task.

When creating or updating an Issue or pull request, follow `docs/engineering/work-item-standard.md`. Write for both a human with little project context and an implementation agent. State the outcome, scope, acceptance evidence, risks, and rollback boundary; link to canonical docs instead of copying them.

Do not place secrets or `.env` values in code, logs, Issues, or pull requests. Do not merge, deploy, change GitHub settings, or perform destructive operations unless the task explicitly authorizes that exact external effect.

Run the quality gates required by `AGENTS.md` on the exact commit before proposing merge. Report a blocked or missing gate explicitly.
