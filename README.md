# Helmfile for IntelliJ

[Get Helmfile on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35395-helmfile)

An IntelliJ Platform plugin for editing Helmfile state files and templated values files together,
and running Helmfile commands for individual releases.
Targets IntelliJ IDEA 2026.1 (build 261+) with the YAML plugin enabled. Java 21 is required to build it.

## Install

Build with `./gradlew buildPlugin`, then open **Settings → Plugins → ⚙ → Install Plugin from Disk**,
select `build/distributions/helmfile-intellij-plugin-0.3.2.zip`, and restart the IDE if prompted.
The first build downloads the IntelliJ SDK and Gradle dependencies.

The plugin recognizes `helmfile.yaml`, `helmfile.yml`, `*.gotmpl`, and `_*.tpl` helper files as
**Helmfile Template**. For another state filename, use **Associate with File Type → Helmfile Template**.
If another installed plugin already owns a filename, select Helmfile Template in
**Settings → Editor → File Types** for that filename.

## Editing

| Task | How |
| --- | --- |
| Complete Helmfile YAML fields | Press **Ctrl+Space** at a mapping key, including inside releases, chart dependencies (`chart`, `version`, `alias`), repositories, environments, hooks, and Helm defaults. |
| Complete state values | Type `{{ .Values.`, `{{ .StateValues.`, or a nested path such as `{{ .StateValues.versions.` and press **Ctrl+Space**. |
| Navigate to a value definition | **Ctrl+Click / Ctrl+B** on a path segment in `.Values`, `.StateValues`, `.Environment.Values`, or `$.Values`. |
| Navigate back to usages | **Ctrl+Click / Ctrl+B** on a values key or template declaration; **Alt+F7** opens Find Usages across the project. |
| Navigate inherited templates | **Ctrl+Click / Ctrl+B** on `inherit[].template`, or click the parent arrow beside the release name. |
| Navigate to inheriting releases | Click the children arrow beside a template declaration. Templates which inherit other templates also have parent/children arrows. |
| Navigate a non-identifier key | Use `{{ index .Values "image-tag" }}`; navigation also supports quoted keys containing dots. |
| Open a referenced file | **Ctrl+Click / Ctrl+B** on a values file, base file, nested state path, or `readFile` argument. |
| Read function documentation | Use **Quick Documentation (Ctrl+Q)**, or **Alt+Enter → Show Go template function documentation**. |
| Inspect a resolved value | Use **Quick Documentation** on a values path to see its value, source file, and selected environment. |
| Select an environment | **Tools → Helmfile: Select Environment**, also available through **Find Action**. |
| Run a release | Click the run icon beside its `name`, or right-click inside the release and choose **Helmfile → Template / Apply / Delete / Destroy Release…**. |
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

Usage searches use the word index to inspect Helmfile-owned references only. They do not
resolve unrelated Kubernetes YAML references, contact a cluster, or load kubeconfig files.

## Values resolution

The initial environment is `default`; the selected environment is saved per project.
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
Open [examples/helmfile.yaml.gotmpl](examples/helmfile.yaml.gotmpl) and
[examples/values/app.yaml.gotmpl](examples/values/app.yaml.gotmpl) to try navigation and environment switching.

This version resolves static YAML definitions. It does not render Helmfile, infer computed values,
evaluate HCL, decrypt secrets, fetch remote files, or apply CLI state overrides. Functions such as
`exec`, `envExec`, and `fetchSecretValue` are documented but never executed by the editor.
Arbitrary Go variable aliases, dot changes inside `with`/`range`, named-template expansion,
and the context passed from parent Helmfiles are not inferred. Multiple Helmfiles sharing a
values template currently use the nearest canonical state. Conditional template branches are
parsed together, so navigation is best effort where the YAML shape depends on rendering.
Glob discovery is bounded to 5,000 entries and base traversal to 40 levels.

## Run a release

Install `helmfile`, `helm`, and any Helm plugins your state requires, then configure the executable
in **Settings → Tools → Helmfile**. The default executable is `helmfile` from the IDE's PATH.

1. Click the run icon beside a release name and choose **Template**, **Apply**, **Delete**, or **Destroy**.
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
**Delete** runs Helmfile's deprecated `delete` command; **Destroy** runs `destroy` (uninstall and purge).

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

## Development

```sh
./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew runIde
```

To use an installed SDK instead of downloading one:

```sh
./gradlew -PlocalIdePath=/absolute/path/to/idea test buildPlugin
```

`verifyPlugin` checks the build SDK and fails on API-usage warnings as well as compatibility problems.
To check another installed IDE in the same run, add `-PverificationIdePath=/absolute/path/to/other/idea`.
CI runs the verifier and saves its reports as an artifact.

The tests run inside the IntelliJ fixture and cover file recognition, YAML trees containing Go
templates, context completion, values precedence, environment isolation, navigation, documentation,
and release execution. Execution tests use a fake binary and never contact a Kubernetes cluster.
The language uses separate Go template and YAML PSI trees, retaining IntelliJ's YAML parser.
