# Helmfile for IntelliJ

[Get Helmfile on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35395-helmfile)

Edit Helmfile YAML and Go templates with completion, navigation, and function documentation.
Run Helmfile commands and review deployment changes from your IDE.

## Install

1. Open **Settings → Plugins → Marketplace**.
2. Search for **Helmfile**, install it, and restart if prompted.
3. Open `helmfile.yaml`, `helmfile.yml`, or a `.gotmpl` file.

Requires IntelliJ IDEA 2026.1 or later with YAML support enabled. Editing features work without
Helm or access to a cluster. To run commands or download charts, install
[Helmfile](https://helmfile.readthedocs.io/en/stable/) and [Helm](https://helm.sh/), then configure
their paths under **Settings → Tools → Helmfile**.

You can also install a ZIP from [GitHub Releases](https://github.com/k3rnL/helmfile-intellij-plugin/releases)
using **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## What you can do

| Feature | Try it |
| --- | --- |
| Complete YAML and Go templates | Press **Ctrl+Space** for Helmfile fields, state values, repositories, charts, and versions. |
| Follow values and templates | **Ctrl+Click / Ctrl+B** goes to declarations; **Alt+F7** finds usages. Gutter arrows follow template inheritance in both directions. |
| Open values files | **Ctrl+Click** a filename, including paths built from static state values and the selected environment. |
| Read function documentation | **Ctrl+Q** shows Go, Sprig, and Helmfile function help, including signatures and examples. |
| Browse chart sources | **Ctrl+Click** a chart name to open `Chart.yaml`, then browse its folder under **External Libraries**. |
| Find repository usages | **Ctrl+Click** a repository's declared name to see the charts that use it. |
| Switch environments | Use **Tools → Helmfile: Select Environment** to resolve the corresponding values and versions. |
| Run a release | Click the play icon beside its name for **Template**, **Diff**, **Apply**, **Delete**, or **Destroy**. |
| Review deployment changes | Diff one release or the whole Helmfile in the IDE's diff viewer, including individual resources. |

Shortcuts use the default Windows/Linux keymap. Syntax colors follow your IDE theme and can be
changed in **Settings → Editor → Color Scheme → Helmfile**.

## Try an example

Open [examples/screenshots/helmfile.yaml.gotmpl](examples/screenshots/helmfile.yaml.gotmpl) for a demo
with environments, values, inheritance, local charts, and chart dependencies. The
[demo guide](examples/screenshots/README.md) walks through the features.

For commands, repository authentication, values resolution, and supported template expressions,
see the [user guide](docs/user-guide.md).

## Run commands safely

Release actions let you choose an environment and review the command before running it.
Output appears in the IDE's **Run** window, where you can stop or rerun the command.
Helmfile **Diff** requires the [helm-diff plugin](https://github.com/databus23/helm-diff).

Environments matching `prod.*` require extra confirmation for every action, including reruns.
Configure this expression under **Settings → Tools → Helmfile → Protected environments**.

## Get help

[Report a problem or request a feature](https://github.com/k3rnL/helmfile-intellij-plugin/issues).
For an IDE exception, review the report in **IDE Internal Errors** and choose
**Report to third-party plugin** to send it through JetBrains Marketplace.

Contributing? Read [AGENT.md](AGENT.md) for development, commit conventions, and release instructions.
