# Historical research archive — hardware transfer 2026-09-15

Current entry point: [hardware handoff](../../../epic-74-hardware-handoff.md).
The owner explicitly requested preserving all unfinished task work before replacing
the workstation. These are research/prototype artifacts, not production acceptance.

Previously untracked editor/storage/verification evidence is now retained at its
referenced documentation paths. Dates and old NOT RUN statements are historical;
later slice evidence and GitHub PRs supersede them. Do not rerun proposed DDL against
any shared or production database.

Three Java storage experiments moved from the old Learning storage_spike test package
to `../storage/src/`, outside the production test source set. The two standalone
runner source paths are adjusted; old line references describe original provenance.
Their source bytes are otherwise preserved. These runners intentionally retain a
stopped named synthetic container for inspection; clean up only that exact resource.

Eight unconnected Angular paper prototype files moved from `frontend/src/app/paper/`
to `../editor/paper-shell-prototype/angular/`. They are reference source, not runnable
as-is from this folder and not a new production component. Resolve original imports
deliberately in an isolated prototype if reproducing historical screenshots.

`execution-2026-09-12.md` is a frozen historical log, superseded by the hardware guide.
The original owner's prompt is deliberately archived under engineering/prompts;
newer local-only and owner-managed merge instructions supersede its old delivery
permissions. Dependency approvals are retained separately in dependency-decisions.

Caches, node_modules, class files, `.env`, credentials and private logs are excluded.
An identical duplicate editor screenshot is retained to preserve historical links
and checksums; no claim of a second independent run is based on identical bytes.
