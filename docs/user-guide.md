# Helmfile user guide

[Install the plugin](../README.md#install) before following these instructions.

The plugin recognizes `helmfile.yaml`, `helmfile.yml`, `*.gotmpl`, and `_*.tpl` helpers.
For another state filename, use **Associate with File Type → Helmfile Template**, or change its
association under **Settings → Editor → File Types**.

## Editing

Typing `{{` inserts `{{}}` with the caret inside; typing the closing braces moves over the inserted pair.
This follows the IDE's **Insert paired brackets** preference.

Complete `releases`, `repositories`, or a release's `dependencies` key to insert an editable block.
For another list entry, type `- release`, `- repository`, or `- dependency` and choose that block
from completion. **Tab** moves through the name, chart, version, or URL fields. Chart completion
uses declared repositories and their indexes; the version field offers versions of the chosen chart
and defaults to the newest cached version. Downloads remain in the background; invoke **Ctrl+Space**
again if repository metadata is still loading. **Esc** leaves live-template editing.

| Task | How |
| --- | --- |
| Complete Helmfile YAML fields | Press **Ctrl+Space** at a mapping key, including inside releases, chart dependencies (`chart`, `version`, `alias`), repositories, environments, hooks, and Helm defaults. |
| Complete repositories and charts | Press **Ctrl+Space** in `chart:` for declared repository aliases; after `repository/`, complete chart names from its downloaded index. |
| Complete chart versions | Press **Ctrl+Space** in `version:` for available versions of the associated chart, newest first. |
| Navigate to a repository | **Ctrl+Click / Ctrl+B** on the repository prefix in `chart: repository/chart`. |
| Find repository usages | **Ctrl+Click / Ctrl+B** on the name in `repositories[].name`, or use **Alt+F7**, to find chart references to that declaration. |
| Browse a chart | **Ctrl+Click / Ctrl+B** on the chart-name segment. Opens `Chart.yaml` and adds the complete chart folder under **External Libraries**. |
| Refresh repository metadata | **Tools → Helmfile: Refresh Chart Repositories**, also available through **Find Action** and inspection quick fixes. |
| Complete state values | Type `{{ .Values.`, `{{ .StateValues.`, or a nested path such as `{{ .StateValues.versions.` and press **Ctrl+Space**. |
| Navigate to a value definition | **Ctrl+Click / Ctrl+B** on a path segment in `.Values`, `.StateValues`, `.Environment.Values`, or `$.Values`. |
| Navigate back to usages | **Ctrl+Click / Ctrl+B** on a values key or template declaration; **Alt+F7** opens Find Usages across the project. |
| Navigate inherited templates | **Ctrl+Click / Ctrl+B** on `inherit[].template`, or click the parent arrow beside the release name. |
| Navigate to inheriting releases | Click the children arrow beside a template declaration. Templates which inherit other templates also have parent/children arrows. |
| Navigate a non-identifier key | Use `{{ index .Values "image-tag" }}`; navigation also supports quoted keys containing dots. |
| Open a referenced file | **Ctrl+Click / Ctrl+B** on a values file, base file, nested state path, or `readFile` argument. |
| Read function documentation | Use **Quick Documentation (Ctrl+Q)**, or **Alt+Enter → Show Go template function documentation**. |
| Inspect a resolved value | Use **Quick Documentation** on a values path to see its value, source file, and selected environment. |
| Select an environment | Click its gutter button under `environments`, or use **Tools → Helmfile: Select Environment** through the menu or **Find Action**. |
| Run a release | Click the run icon beside its `name`, or right-click inside the release and choose **Helmfile → Template / Apply / Destroy Release…**. |
| Diff a release | Click its run icon and choose **Diff Release…** to compare deployed and desired YAML in IntelliJ's diff viewer. |
| Diff the whole Helmfile | Click the run icon beside `releases:`, or use **Tools → Helmfile: Diff All Releases…**. Also available in the editor's Helmfile context menu and Find Action. |
| Customize syntax colors | **Settings → Editor → Color Scheme → Helmfile**. |

Shortcuts above use the default Windows/Linux keymap; macOS and custom keymaps differ.
Function help includes signatures, examples, and reference links for Go template built-ins,
Helmfile functions, and commonly used Sprig functions. It is available without an internet connection.
Unclosed actions and unmatched template blocks are highlighted.
Syntax colors inherit the active IDE theme's standard function, keyword, field, variable,
string, number, and delimiter styles. The plugin does not bundle a separate color palette.
Individual categories remain configurable in **Editor → Color Scheme → Helmfile**.
Escaped Go templates inside backticks are highlighted as code;
their values paths support navigation and their functions support documentation too. Ordinary
quoted strings keep their string highlighting.

Inheritance navigation follows local templates and templates loaded from `bases`, including plain
YAML base files. A template declaration links to releases and templates which inherit that actual
definition; unrelated templates with the same name are excluded. Find Usages and declaration
navigation also follow values references in external `.gotmpl` files.
Ctrl+Click on a declaration uses IntelliJ's native **Show Usages** action. Multiple results appear
with source files, line/column locations, source snippets, and the standard preview and toolbar;
a single result navigates directly, following IntelliJ's usual behavior. Ambiguous value
declarations and the downward inheritance arrow use the same native search integration.
Reverse lookup from an environment field follows that declaration's environment, including
usages in releases after a `---` separator, even when another environment is selected.

## Repository completion and validation

Declare repositories using [Helmfile's repository configuration](https://helmfile.readthedocs.io/en/latest/configuration/).
Repository aliases complete immediately. Chart names and versions load in the background when
needed; invoke completion again after loading, or use **Tools → Helmfile: Refresh Chart Repositories**
to load them ahead of time. Downloads use IntelliJ's proxy settings and native background progress.
The refresh action reports how many indexes or OCI tag lists were loaded. Failed repositories show
their HTTP, TLS, or connection error, with **Show Details** opening diagnostics in the native Run
console. Previously downloaded suggestions remain available when a refresh fails.

Completion works in releases, reusable templates, and `dependencies` entries, across `---` separators
and local `bases`. Version completion also follows a chart inherited from a template. For HTTP(S)
repositories, the plugin reads `index.yaml` without downloading chart archives. For OCI charts,
it requests the known chart's registry tags for version completion, including paginated responses
and bearer authentication. OCI chart-name completion falls back to the declared repository alias.
Direct `oci://` chart references support version completion too.

The native **Helmfile chart repositories** inspection checks missing repository names/URLs,
duplicate aliases, undeclared repository prefixes, missing charts, and unavailable exact versions.
Chart and version warnings require a successfully loaded index or tag list. Local charts, remote
chart URLs, unresolved templates, and Helm version constraints are excluded from checks which
cannot be resolved statically. Missing or dynamic base files suppress uncertain alias warnings.
Change the inspection severity or disable it through **Editor → Inspections → Helmfile**.

Ctrl+Click a declared repository name, such as `trino` in `name: trino`, to open IntelliJ's native
usage lookup. It includes chart references in releases, reusable templates, and chart dependencies,
including states that include the declaration through `bases`. The search follows that declaration;
unrelated or overriding repositories with the same alias are excluded. **Alt+F7** opens Find Usages.

Metadata is cached in memory for the current project session. Successful results refresh after five
minutes when needed; failed requests retry after one minute. An unavailable repository preserves
previously downloaded suggestions and repository-alias completion, and suppresses chart/version
warnings until a refresh succeeds. **Settings → Tools → Helmfile** can disable automatic downloads;
the explicit refresh action remains available.

Downloads support static `username`/`password`, Helmfile's repository environment credentials,
`caFile`, `skipTLSVerify`, and client certificates with unencrypted PKCS#8 PEM keys. Registry
credentials are forwarded to another origin only with `passCredentials: true`. Simple state-value
paths and `.Environment.Name` in repository settings resolve statically. Other Go expressions,
secret providers, Helm's credential store, and external downloader plugins are not executed;
repository-alias completion remains available when metadata cannot be loaded.

### Browse chart sources

For `chart: bigdata-helm-charts/bgdmar-utils`, Ctrl+Click on `bigdata-helm-charts` opens its
repository declaration; Ctrl+Click on `bgdmar-utils` opens the chart. A local chart directory
opens its own `Chart.yaml` directly. Remote HTTP(S) and OCI charts download on demand through
[`helm pull`](https://helm.sh/docs/helm/helm_pull/), using the release's version or version constraint.
Version settings inherited from templates and simple state-value paths, including `default` and
`quote` pipelines, are supported. If a state value is unresolved, a standard IntelliJ popup lets
you select the environment and retry opening the chart. You can also select it beforehand with
**Tools → Helmfile: Select Environment**. The choice applies to completion and navigation across
the project. Expressions that require executing Go template functions remain unsupported and
produce a notification naming the selected environment, instead of opening a different version.

Downloaded charts appear as named, read-only folders in **Project → External Libraries**. Expand
the chart to browse `templates`, `values.yaml`, `README.md`, and other packaged files. The folders
are restored from cache when the project reopens. Cached source archives live in the IDE's system
cache; pinned versions are reused, and version ranges or unpinned charts refresh after five minutes.

Install Helm and configure **Settings → Tools → Helmfile → Helm executable** if it is not on the
IDE's PATH. Downloads use Helm's repository and OCI authentication support, including its registry
credential store. Static repository credentials, TLS settings, registry configuration, and chart
provenance options are forwarded where declared. Chart downloads and archive processing run in
cancellable IntelliJ background tasks; Ctrl-hover and initial file analysis never download chart
source packages. Opening a Helmfile may load repository indexes for validation; these requests are
batched in the background, and editing and completion do not wait for the response.
Local completion, forward navigation, syntax diagnostics, and release gutters are available while
IntelliJ indexes the project. Project-wide usage searches and downward inheritance arrows wait
until the index is ready.

If a chart download fails, the notification shows Helm's error together with the chart, requested
version, and selected environment. **Show Details** opens the native Run console with repository
context and Helm's standard error and standard output. Credentials are redacted. Very long output
is explicitly marked as truncated, preserving its first and last sections. Cancellation is silent.

## Values resolution

The initial environment is `default`; the selected environment is saved per project.
Each environment declaration has a gutter button that switches directly to that environment.
A check mark identifies the current environment. Buttons also appear in included YAML bases and
work while the IDE is indexing. Switching immediately refreshes completion, value navigation,
values-file paths, and chart-version resolution. It does not run Helmfile; saved run configurations
retain their own execution environment. Gutter buttons can be hidden under
**Settings → Editor → General → Gutter Icons → Helmfile environment selection**.

Completion and navigation read root `values`, recursively included `bases`, and the selected
environment's `values`. Defaults are collected before environment overrides, and later files
override earlier definitions. Maps merge recursively; scalars and lists replace earlier values.
The winning definition is the navigation target. Release `values` are Helm chart inputs and
do not contribute to Helmfile's `.Values`.

If the current environment is not declared in the state file (for example, `default` when the file
only declares `awsqt`, `awsqo`, and `prod`), completion also offers fields from each declared
environment. These suggestions include inline environment values and referenced values files,
including environments inherited from bases. Each suggestion shows its source file and environment,
such as `helmfile.yaml.gotmpl · awsqt` or `production.yaml · prod`. Nested paths work the same way.
Shared defaults keep their source-file label. Ctrl+Click on a value with multiple possible
environment declarations offers its matching declarations. Select an environment through
**Tools → Helmfile: Select Environment** to narrow completion and forward navigation to
the definitions for that environment. Reverse lookup from a declaration searches the
environments in which that declaration supplies the value.

Values paths are relative to the declaring state file. Local globs and paths containing
`{{ .Environment.Name }}` are supported. A values template finds its state by walking up to
the nearest directory containing a canonical `helmfile.yaml[.gotmpl]` or `helmfile.yml[.gotmpl]`.
Open [examples/helmfile.yaml.gotmpl](../examples/helmfile.yaml.gotmpl) and
[examples/values/app.yaml.gotmpl](../examples/values/app.yaml.gotmpl) to try navigation and environment switching.
For a fuller demo with local charts, environments, inheritance, chart dependencies, and a screenshot
guide, open [examples/screenshots](../examples/screenshots/README.md).

Ctrl+Click the filename in a `values` entry to open the referenced file. Navigation also resolves
simple `.Values`, `.StateValues`, `.Environment.Values`, and `.Environment.Name` expressions using
the selected environment, including quoted paths and reusable release templates. For example,
`{{ .StateValues.envPath }}/values/databases/trino.yaml` opens that environment's existing file.
Clicking the value name inside the expression still opens its declaration. Matching glob paths
offer their files through the IDE's declaration chooser. Unresolved expressions and missing files
do not open another file or execute template functions.

This version resolves static YAML definitions. It does not render Helmfile, infer computed values,
evaluate HCL, decrypt secrets, fetch remote values/base files, or apply CLI state overrides. Functions such as
`exec`, `envExec`, and `fetchSecretValue` are documented but never executed by the editor.
Arbitrary Go variable aliases, dot changes inside `with`/`range`, named-template expansion,
and the context passed from parent Helmfiles are not inferred. Multiple Helmfiles sharing a
values template currently use the nearest canonical state. Conditional template branches are
parsed together, so navigation is best effort where the YAML shape depends on rendering.

## Run a release

Install `helmfile`, `helm`, and any Helm plugins your state requires, then configure the executable
in **Settings → Tools → Helmfile**. The default executable is `helmfile` from the IDE's PATH.

1. Click the run icon beside a release name and choose **Template**, **Diff**, **Apply**, or **Destroy**.
2. Choose the environment in the standard IDE dialog. The list includes environments declared in
   the state and its bases; you can also type a name. The editor's selected environment is the initial choice.
3. Check the release name, optional namespace selector, and command preview, then click **Run**.
   For a templated release name, enter its rendered name for the selected environment.
4. Follow output in IntelliJ's **Run** tool window. Its standard controls stop or rerun the command.

The action creates a temporary **Helmfile** run configuration. Use **Run → Edit Configurations**
to save it, change its environment or command, or set environment variables. Each configuration
remembers its own execution environment, independently of the editor's completion environment.
The process inherits the IDE's environment by default, including the complete `KUBECONFIG` value;
the plugin does not parse or choose kubeconfig files. Per-configuration environment variables use
IntelliJ's standard environment variables editor.

Commands use `--file <state> --environment <environment> --selector name=<release>` and execute
in the state file's directory. A namespace adds `,namespace=<namespace>` to the selector, without
overriding the release namespace. Use it when the same name occurs in multiple namespaces.
Helmfile's normal selector and nested-state rules apply. Arguments are passed directly to the executable.
Helmfile's CLI interactive prompt is disabled because the IDE handles environment confirmation.
**Destroy** runs `destroy` to uninstall the release. Saved configurations using the old Delete action
require choosing Destroy explicitly in **Run → Edit Configurations** before they can run.

### Review a Helmfile diff

**Diff Release…** compares one release. **Helmfile: Diff All Releases…**, including the gutter
beside `releases:`, compares the entire state with its nested Helmfiles. Both actions let you choose
the environment and create a normal Helmfile run configuration. For a saved **Diff** configuration,
an empty release name means all releases; other commands still require an exact release name.
Protected environments require the same extra confirmation on every run and rerun.

Install the [helm-diff plugin](https://github.com/databus23/helm-diff) alongside Helmfile and Helm.
Output and errors appear in IntelliJ's **Run** window; **Stop** cancels the process and **Rerun**
refreshes the comparison. Successful runs open the IDE's native diff viewer:

- **All changes.yaml** presents the combined comparison, with deployed YAML on the left and
  desired YAML on the right.
- The native file chooser and next/previous file actions let you review individual resources,
  grouped by release and namespace as `Kind/name.yaml`, with added/modified/deleted statuses. Chart `# Source:` comments
  remain in the YAML where present. Use the standard toolbar to switch between side-by-side and
  unified views or adjust whitespace and context display.
- The comparison is read-only. Helmfile's usual Secret redaction is retained, and suppressed
  resources are identified. If configured suppression removes context, partial comparisons are
  labeled and a notification points to the original Run output.

An empty diff reports no changes. Failures and output that cannot be displayed produce a
notification with the original command output. Narrow very large comparisons to one release.

### Protect environments

**Settings → Tools → Helmfile → Protected environments (regex)** defaults to `prod.*`, which
matches `prod`, `production`, and `prod-eu`. Matching is case sensitive and covers the entire name.
For example, `(?:prod.*|staging)` protects production environments and `staging`.
An empty expression disables protection.

Every operation on a matching environment opens an additional native confirmation dialog naming
the action, environment, state file, and release selector. **Cancel** is the default button and
starts no process. Confirmation is required again for every rerun, including **Template**.
Invalid expressions cannot be saved in Settings and also block execution if loaded from disk.
This policy applies to commands launched by the plugin's Helmfile run configurations.
Commands typed in a terminal or launched by other tools use those tools' own behavior.

Semantics and function references: [Helmfile](https://helmfile.readthedocs.io/en/stable/),
[Go text/template](https://pkg.go.dev/text/template), [Sprig](https://masterminds.github.io/sprig/).

## Report plugin errors

The plugin uses [JetBrains Marketplace's built-in error handler](https://plugins.jetbrains.com/docs/marketplace/error-handler.html).
In IntelliJ's **IDE Internal Errors** dialog, review the report details and consent to sharing them,
then choose **Report to third-party plugin**. Automatic reporting follows the IDE's
**Send error reports to JetBrains automatically** setting.

