# Maintainer and agent guide

Keep [README.md](README.md) focused on installing and using the plugin. Detailed user help belongs
in [docs/user-guide.md](docs/user-guide.md). Build instructions, architecture, commit conventions,
and release operations belong here. `AGENTS.md` points agents to this guide.

## Make meaningful commits

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```text
type(optional-scope): describe the resulting behavior
```

| Type | Use | Release effect |
| --- | --- | --- |
| `feat` | A user-visible capability | Minor |
| `fix` | Correct user-visible behavior | Patch |
| `perf` | Improve performance | Patch |
| `docs`, `test`, `refactor`, `build`, `ci`, `chore`, `style` | Changes without a feature or fix | No release by themselves |
| Any type with `!` or a `BREAKING CHANGE:` footer | Incompatible behavior; explain the change and migration | Major |

Examples:

```text
feat(completion): suggest chart dependency versions
fix(navigation): resolve state values after YAML document separators
perf(repositories): reuse cached chart indexes during completion
docs: explain environment protection in the user guide
ci(release): publish generated commit notes to Marketplace
```

- Commit each coherent, tested change separately. Keep a fix and its regression tests together.
- Describe what changed for a user or maintainer. Avoid `WIP`, `updates`, `misc fixes`, and generic summaries.
- Use the body to explain why, relevant limitations, and issue references. These messages become release notes.
- Commit completed work as you go; do not leave unrelated changes bundled in a final catch-all commit.
- Inspect the diff and stage explicit files. Preserve unrelated user changes and never commit credentials,
  generated release notes, build output, or downloaded charts.
- Use a Conventional Commit title when squashing a pull request; it becomes the commit on `main`.
- CI validates new commits with commitlint. Historical commits through `v0.5.3` are unchanged.

Validate a message or a range:

```sh
printf '%s\n' 'fix(navigation): resolve inherited chart versions' | npm run lint:commits
npm run lint:commits -- --from v0.5.3 --to HEAD --verbose
```

## Build and verify

Requirements: Java 21 and Node 22.14 or later in the 22.x line, or Node 24.10 or later.
The Gradle wrapper downloads Gradle and the IntelliJ SDK on the first build.

```sh
npm ci --ignore-scripts
npm test
./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew runIde
```

Use `-PlocalIdePath=/absolute/path/to/idea` with an installed SDK to avoid downloading one.
`verifyPlugin` checks the build SDK and treats API usage warnings as failures. Add
`-PverificationIdePath=/absolute/path/to/other/idea` to check another installed IDE.
Run checks appropriate to each change; release preparation always runs the full suite.

Local builds use version `0.0.0-dev`; their ZIP is in `build/distributions/`. Release versions are
supplied by semantic-release as `-PpluginVersion`. Do not maintain a version number by hand.
The Build workflow tests commits, release tooling, plugin behavior, structure, and IntelliJ API
compatibility on pushes to `main`, pull requests, and manual runs. Its artifacts contain the ZIP
and verification reports. Normal builds have no publishing credentials.

## Release from Git history

Releases are explicitly triggered on `main`, using the **Release** workflow. Publish when the user
requests a release; ordinary pushes run checks. No maintained changelog or generated release-note
file belongs in Git. Do not create release commits, edit version numbers, or write release notes by hand.

1. Finish the changes, make meaningful Conventional Commits, and push `main`.
2. Run **Actions → Release → Run workflow** on `main`, or:

   ```sh
   gh workflow run release.yml --ref main
   ```

3. semantic-release finds the previous reachable stable `v*` release tag and analyzes only the commits
   since that tag. It chooses the highest applicable version increment. If there are no release-worthy
   commits, no version is published. Existing tags, beginning with `v0.5.3` for this workflow, are the baseline.
4. It generates Markdown notes for that delta and converts the same notes to HTML under ignored
   `build/release/`. Gradle embeds the HTML in `plugin.xml` as `<change-notes>`.
5. The release plugin checks the version and notes, runs tests, builds the ZIP, verifies structure and
   IntelliJ compatibility, and optionally verifies signing before semantic-release creates the new tag.
6. It uploads the plugin to Marketplace and creates the GitHub release with the generated Markdown
   and the verified ZIP. Both destinations describe the same delta. Generated files are never committed.

See `release.config.mjs` and `scripts/marketplace-release.mjs` for the integration. The npm package
is private and is never published. GitHub issue/PR comments and automatic failure issues are disabled.
Publishing happens in one workflow: releases created with `GITHUB_TOKEN` do not trigger another
`release` workflow. Do not restore the old manual-tag/`release: published` pipeline alongside it.

Credentials live in [GitHub Actions secrets](https://github.com/k3rnL/helmfile-intellij-plugin/settings/secrets/actions):

| Secret | Purpose |
| --- | --- |
| `PUBLISH_TOKEN` | Required Marketplace token for a user allowed to update plugin 35395. |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY` | Optional PEM author signing; provide both or neither. |
| `PRIVATE_KEY_PASSWORD` | Password for an encrypted signing key. |

The workflow uses its built-in `GITHUB_TOKEN` with `contents: write` for tags, releases, and assets.
Gradle publishing disables its configuration cache to keep credentials out of it. The release job
is serialized and publishes to the stable Marketplace channel. Prerelease channels are not configured.

After a release, check the workflow's actual Marketplace publishing step and the GitHub release ZIP.
Marketplace upload success means the version was submitted; JetBrains approval can still be pending.
If publishing fails after a tag is created, inspect the workflow before retrying: semantic-release
has already consumed that tag. Recover that release deliberately instead of deleting published tags
or inventing an empty follow-up version. Attach every created pull request to the current task.

## Architecture and implementation constraints

- The plugin targets IntelliJ IDEA 2026.1 / build 261+, with YAML and VCS modules.
- Separate Go-template and YAML PSI trees retain IntelliJ's YAML parser. Do not evaluate Go templates,
  invoke `exec`/`envExec`, decrypt secrets, or contact Kubernetes while editing or resolving usages.
- Value resolution reads static root/environment values and local bases. Maps merge recursively;
  scalars/lists replace earlier values. Release `values` are chart inputs, not Helmfile state values.
- Usage search uses the word index for Helmfile-owned references. Local completion, forward navigation,
  syntax diagnostics, and release gutters work while indexing; project-wide searches wait for indexes.
- Prefer native references, Show Usages, Find Usages, line markers, run configurations, background tasks,
  notification actions, consoles, and diff viewers. Use the active IDE theme's syntax styles.
- Chart source navigation downloads on demand with Helm and adds read-only chart folders under External
  Libraries. Never download chart archives from hover, initial file analysis, or reference resolution.
- Repository metadata loads in cancellable background batches. Preserve cached suggestions on failure,
  suppress uncertain validation, and redact credentials from every diagnostic. Set determinate progress
  before using fractions. Current metadata bounds are 32 MiB; retry/refresh timing is in the service.
- Go function documentation remains available offline. Ordinary strings retain string highlighting;
  escaped Go-template actions in backticks support function help and value navigation.
- Values/base paths are relative to the declaring file. Values templates use the nearest canonical
  Helmfile; context from arbitrary parent Helmfiles and computed Go aliases is not inferred.
- Guard nullable YAML parent mappings and PSI lifetime across asynchronous tasks. Use thread-safe
  collections when IntelliJ can collect references concurrently.
- Execution inherits the complete `KUBECONFIG`; do not parse it or choose one config file. The IDE
  confirms protected environments on every run and rerun. Default protection is the full-match regex `prod.*`.
- Template, diff, apply, and destroy support both an exact release selector and an explicit whole-state
  scope without selectors. A single-release dialog must still require a rendered release name.
- Diff uses Helm's standard text renderer with full context, disables external `HELM_DIFF_TOOL`,
  treats detailed exit code 2 as changes, and limits captured output to 32 MiB. Preserve Secret redaction.
- Apply runs that diff first. Only a complete review can approve the captured command once;
  approvals are transient and are never cloned, serialized, or reused by reruns. Recheck environment
  protection before the actual noninteractive apply. Real project file edits invalidate the review;
  preview PSI, temporary diff documents, and IDE configuration saves must not invalidate it.
- Template captures stdout separately from the Run console and parses resource identities in a
  cancellable background task. The native read-only YAML viewer offers aggregate and per-resource
  output. Preserve raw YAML, bound output to 32 MiB, and never preview failed or stopped runs.
- Tests use IntelliJ fixtures, fake Helm/Helmfile executables, and local HTTP servers. They never apply
  a real Helmfile or contact a Kubernetes cluster. Release-tool tests use temporary local Git repositories.
- Marketplace's built-in error handler collects user-consented reports. Authors find them in Marketplace
  **Exceptions** and **Freezes**. Do not replace it with unsolicited external error reporting.
