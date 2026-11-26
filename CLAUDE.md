# dough

dough manages the donut project: a multi-repo Clojure single-page app framework. Work done here typically spans or coordinates the other donut repos, not just this one.

## The multi-repo layout

Every sibling directory of this repo (under `../`) is a related donut framework repo: libraries, tooling, templates, and test apps. Examples: `../system`, `../endpoint`, `../frontend`, `../single-page-app-template`, `../donut-cli`, `../datapotato`. When a task refers to a donut library or repo by name, look for it as a sibling directory.

Planning docs and notes live in the private `../project` repo (`plans/`, `todo.org`, `project-outline.org`), not here: this repo is public.

## Running tasks

Tasks are babashka tasks defined in `bb.edn`. `bin/dough` runs them from any directory (it passes `bb --config` so paths resolve to this repo); install it with `ln -s "$PWD/bin/dough" ~/.local/bin/dough`.

- `dough` - open an interactive task picker (gum, via bblgum)
- `dough status` - report every sibling repo that isn't clean and on its default branch
- `dough sync` - clone (via SSH) and rebase every repo in the donut-party GitHub org, and print a report; needs `gh auth login`
- `dough rebuild-test-app` - install local donut libs, refresh the donut CLI, and rebuild `../template-test` so changes across repos can be tested together
- `dough cli-update-deps` - point the donut CLI at the newest generate/generators on Clojars and the template's main SHA
- `dough cli-bump` - bump the donut CLI's patch version
- `dough cli-publish` - update the CLI's deps, bump its version, commit, tag, and push, which kicks off a CLI release
- `dough brew-formula` - render `../homebrew-brew/Formula/donut.rb` for the CLI's current version
- `dough test` - run the unit tests in `test/`

## Structure

- `src/donut/dough/`: Clojure source. Task namespaces match their bb task names (`status`, `sync`, `cli-publish`, ...).
  - `structure.clj`: locates donut repos on disk (framework root = parent of this repo)
  - `deps.clj`: pure deps.edn/bb.edn editing helpers (rewrite-clj based); the coordinate search is confined to the dependency's own map
  - `clojars.clj`: reads published versions from the Clojars API
  - `versions.clj`: version-string parse/bump/persist helpers
  - `brew.clj`: locally-installed homebrew formula helpers, including `copy-local!`
  - `git.clj`: impure git CLI wrappers (fetch, rebase, stash, clone, etc.) used by `sync` and `status`
  - `github.clj`: wraps `gh api` to list the donut-party org's repos; the source of truth for which repos `sync` touches
  - `sync.clj`: implements `sync`; rebases are all-or-nothing per repo (see its docstring)
  - `file.clj`, `process.clj`: small file and process helpers
  - `menu.clj`: the interactive task picker `bin/dough` shows when called with no arguments
- `test/`: unit tests, run by `dough test` (runner: `test/test_runner.clj`)
