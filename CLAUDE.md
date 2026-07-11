# donut project management

This repo is the **command center** for donut, a multi-repo Clojure single-page app framework. Work done here typically spans or coordinates the other donut repos, not just this one.

## The multi-repo layout

Every sibling directory of this repo (under `../`) is a related donut framework repo — libraries, tooling, templates, and test apps. Examples: `../system`, `../endpoint`, `../frontend`, `../single-page-app`, `../donut-cli`, `../datapotato`. When a task refers to a donut library or repo by name, look for it as a sibling directory.

## What this repo does

- Automates local development workflows across the donut repos
- Wrangles the dependency graph of the framework's libraries (storing and working with the graph)
- Holds cross-repo planning docs in `plans/` (e.g. `plans/dep-graph/` for CI planning)

## Running tasks

Tasks are babashka tasks defined in `bb.edn`, runnable from any directory via `bin/run` (it passes `bb --config` so paths resolve to this repo):

- `bin/run` - open an interactive task picker (gum, via bblgum)
- `bin/run rebuild-test-app` - install local donut libs, refresh the donut CLI, and rebuild `../template-test` so changes across repos can be tested together
- `bin/run regenerate-legacy` - older template-test regeneration flow (npm i, git init, entity scaffold), superseded by `rebuild-test-app`
- `bin/run test` - run the unit tests in `test/`

## Structure

- `src/donut/project_management/` — Clojure source
  - `structure.clj` — locates donut repos on disk (framework root = parent of this repo)
  - `deps.clj` — pure deps.edn/bb.edn editing helpers (rewrite-clj based)
  - `versions.clj` — version-string parse/bump/persist helpers
  - `brew.clj` — locally-installed homebrew formula path helpers
  - `local_test.clj` - implements the `rebuild-test-app` task (step plans are data; pure fns are tested)
  - `generate.clj` - implements the `regenerate-legacy` task
  - `menu.clj` - the interactive task picker `bin/run` shows when called with no arguments
- `test/` — unit tests, run by `bin/run test` (runner: `test/test_runner.clj`)
- `plans/` — planning documents for cross-repo initiatives
- `bb.edn` / `build.clj` — Babashka tasks and build config
- `todo.org` — running task list

Utility namespaces here consolidate and improve the ones in `../donut-cli/develop/src` (which still exist - the `rebuild-test-app` task shells out to `../donut-cli/develop/bin/develop`). Shared lower-level helpers live in `../bb-utils` (`donut.bb-utils.*`), a `:local/root` dep.
