# dough

dough manages the donut project: a multi-repo Clojure single-page app framework. Work done here typically spans or coordinates the other donut repos, not just this one.

## The multi-repo layout

Every sibling directory of this repo (under `../`) is a related donut framework repo: libraries, tooling, templates, and test apps. Examples: `../system`, `../endpoint`, `../frontend`, `../single-page-app-template`, `../donut-cli`, `../datapotato`. When a task refers to a donut library or repo by name, look for it as a sibling directory.

Planning docs and notes live in the private `../project` repo (`plans/`, `todo.org`, `project-outline.org`), not here: this repo is public.

## Running tasks

Tasks are babashka tasks defined in `bb.edn`. `bin/dough` runs them from any directory (it passes `bb --config` so paths resolve to this repo); install it with `ln -s "$PWD/bin/dough" ~/.local/bin/dough`.

- `dough` - open an interactive task picker (gum, via bblgum)
- `dough status` - report every sibling repo that isn't clean and on its default branch, naming the change set its branch belongs to
- `dough change new <name> <repo>... [--description TEXT]` - create a change set: branch `<name>` in each repo, recorded in `changes/<name>.edn`
- `dough change list` / `dough change push <name>` / `dough change done <name>` - show open change sets, push their branches, or delete one once every branch is merged into origin's default branch
- `dough checkout <name> [--stash]` - put every repo in a change set on its branch; refuses (changing nothing) if a repo is dirty, unless `--stash`. `dough checkout main` returns every repo to its default branch
- `dough sync` - clone (via SSH) and rebase every repo in the donut-party GitHub org, and print a report; needs `gh auth login`
- `dough link` - write a `:donut/local` alias into `~/.clojure/deps.edn` that overrides every donut library (found through each `deps.edn`'s `:donut/lib`) with its checkout; any app then runs against the checkouts with `clojure -M:dev:donut/local`
- `dough cli <args>` - run the donut CLI from `../donut-cli`, with generate, generators and the app template read from their checkouts; `--lib` libraries resolve from checkouts through `DONUT_LOCAL_LIBS`
- `dough app <name> [--skip-devtools]` - generate `../<name>` through `dough cli` and check it against the checkouts: backend tests, cljs compile and release, tailwind. Only deletes a directory it made (marked by `.dough-app`)
- `dough cli-update-deps` - point the donut CLI at the newest generate/generators on Clojars and the template's main SHA
- `dough cli-bump` - bump the donut CLI's patch version
- `dough cli-publish` - update the CLI's deps, bump its version, commit, tag, and push, which kicks off a CLI release
- `dough brew-formula` - render `../homebrew-brew/Formula/donut.rb` for the CLI's current version
- `dough test` - run the unit tests in `test/`

## Change sets

A change spanning several repos is a change set: a committed file, `changes/<name>.edn`, naming the branch for each repo (`{:description "..." :repos {"frontend" "fix" ...}}`). Change sets coordinate development only; nothing published reads them. See `src/donut/dough/change_set.clj`.

`dough change clone <name> [--shallow]` clones any repo in a change set that isn't checked out, on its branch (from `DOUGH_GIT_BASE`, default `git@github.com:donut-party`).

## CI

- `.github/workflows/ci.yml` runs `dough test` on pushes to `main` and on pull requests.
- `.github/workflows/change-set.yml` tests a change set before release: on pull requests that add or change `changes/*.edn`, or by hand (`gh workflow run change-set.yml -R donut-party/dough -f change=<name>`), it clones the change set's repos with `dough change clone`, then runs `dough app ci-app`. Cloning private repos needs the `DOUGH_READ_TOKEN` org secret: a fine-grained token with read-only Contents access to the donut-party repos.

## Structure

- `src/donut/dough/`: Clojure source. Task namespaces match their bb task names (`status`, `sync`, `cli-publish`, ...).
  - `structure.clj`: locates donut repos on disk (framework root = parent of this repo)
  - `deps.clj`: pure deps.edn/bb.edn editing helpers (rewrite-clj based); the coordinate search is confined to the dependency's own map
  - `clojars.clj`: reads published versions from the Clojars API
  - `versions.clj`: version-string parse/bump/persist helpers
  - `registry.clj`: which donut libraries are checked out, from each `deps.edn`'s `:donut/lib`; used by `link`, `cli`, and `app`
  - `change_set.clj`: the change-set file format and the all-or-nothing checkout plan
  - `git.clj`: impure git CLI wrappers (fetch, rebase, stash, clone, etc.) used by `sync` and `status`
  - `github.clj`: wraps `gh api` to list the donut-party org's repos; the source of truth for which repos `sync` touches
  - `sync.clj`: implements `sync`; rebases are all-or-nothing per repo (see its docstring)
  - `file.clj`, `process.clj`: small file and process helpers
  - `menu.clj`: the interactive task picker `bin/dough` shows when called with no arguments
- `test/`: unit tests, run by `dough test` (runner: `test/test_runner.clj`)
