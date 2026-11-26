# dough

Tooling for managing the [donut](https://donut.party/) framework's repos: syncing and reporting on every repo, testing changes across repos together, and releasing the CLI.

dough expects every donut repo to be checked out as a sibling of this one:

```
donut/
  dough/
  system/
  endpoint/
  ...
```

## Install

Requires [babashka](https://babashka.org), [gum](https://github.com/charmbracelet/gum), and an authenticated [`gh`](https://cli.github.com).

```sh
ln -s "$PWD/bin/dough" ~/.local/bin/dough
dough          # pick a task
dough status   # repos that are dirty or off their default branch
dough sync     # clone and rebase every donut-party repo
```

Run `bb --config bb.edn tasks` for the full list.
