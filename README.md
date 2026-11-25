# Branch Monitor for Jenkins

IntelliJ plugin that automatically watches the Jenkins builds for the Git branches
currently checked out in your project — no more manual Jenkins browsing per repo/branch.

**Strictly read-only:** the plugin only reads build status and logs via HTTP GET.
It never triggers, aborts, replays or modifies builds.

## Features

- Detects all Git roots of the project and their checked-out branches (live on checkout).
  Optionally also repositories whose VCS mapping is `<None>` or that are not registered in the IDE
  (found via the IDE's VCS root detection, branch/remote read with the IDE's Git executable; nested repos,
  excluded/ignored folders and roots outside the project content are skipped; no push tracking there).
- Resolves the matching Jenkins job per branch:
  1. manual mappings (`remote-url-regex => job-url-template`, placeholders `{repo}`, `{branch}`, `{branchEnc}`),
  2. name-based heuristic for multibranch pipelines incl. project-key folders and encoded branch names,
  3. fallback: search the Jenkins folder tree (3 levels, cached 10 min) for a job named like the repository.
- **Repositories settings page** (per project, Settings → Tools → Branch Monitor for Jenkins → *Repositories*):
  all repositories found, uncheck to stop monitoring one (also skipped by pull guard and push statistics);
  shows root, source, remote URL and branch, and *Check Job Resolution* lists every job URL tried.
- Tool window **"Jenkins Builds"** with a **table**: status bubble (blue = building, green = success,
  red = failed, yellow = unstable, gray = unknown), repository, branch, build, result/time, your push state.
  Right-click the header to hide columns; click a header for temporary sorting (toolbar resets to activity order).
- **Type** in the table to filter jobs (speed search). **Right-click a row** (or Shift+F10) → *Show Log* / *Open Build in Browser* / *Open Job in Browser* / *Copy URL*.
- **Log viewer** as tool window tab (a regular IDE console, so plugins like *Grep Console* apply): live tail for running builds, error highlighting, previous/next error, **type to search** (opens the IDE find bar),
  errors-only filter, "Open Full Log in Editor" for big logs (default limit 2 MB in the tab).
- **Push tracking:** pushed branches stay monitored after switching branches; notifications only for
  *your* pushes (passed / green again), with *Show Log* action. The first red build after a push opens a
  dialog (*Show Log* / *Open in Browser* / *Close*); further failing rebuilds of that push stay quiet.
- **Pull guard:** confirmation dialog ("Pull Anyway" / "Cancel") before *Git Pull*, *Update Project*, *Update Selected*
  (Git tool window / branch popup) and *Pull into current* when the affected branch's Jenkins build is red (other
  people's builds count here). Branches without a cached status are checked on demand. Terminal pulls are not intercepted.
- **Status bar widget:** worst state of all watched branches + your push statistics
  (`↑pushes ✓green ✗red`, own builds only); right-click to reset.
- Credentials (API token) stored in the IDE PasswordSafe, adaptive polling intervals.
## Build

```
.\gradlew.bat buildPlugin
```

Requires JDK 21. The plugin zip lands in `build\distributions\`.

Run a sandbox IDE with the plugin: `.\gradlew.bat runIde`

## Install locally

IntelliJ → Settings → Plugins → ⚙ → *Install Plugin from Disk…* →
`build\distributions\jenkins-branch-monitor-<version>.zip`

## Configure

Settings → Tools → **Branch Monitor for Jenkins**: server URL, username, API token
(create one in Jenkins under *your user → Security → API Token*), optional manual mappings.

## License

[MIT](LICENSE)

## Concept

The original design notes (German) are in [docs/KONZEPT.md](docs/KONZEPT.md) and
[docs/KONZEPT-0.2.md](docs/KONZEPT-0.2.md).
