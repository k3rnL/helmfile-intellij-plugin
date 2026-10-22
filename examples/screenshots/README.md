# Screenshot demo

Open `helmfile.yaml.gotmpl` in IntelliJ and keep `values/service.yaml.gotmpl` in a second tab.
The charts are included locally. No chart repositories or credentials are required.
The configured context, `helmfile-screenshots`, is a placeholder for this demo.

For a compact editor screenshot, frame `templates` and the first three releases together.
For Go syntax highlighting, use `values/service.yaml.gotmpl`.

| Screenshot | How to show it |
| --- | --- |
| Environment-aware completion | Leave the editor environment at `default`, remove `namespace` from `.StateValues.namespace` in `common`, and press Ctrl+Space after the dot. The declared environments have different namespaces; rows show the source file and environment. Undo the edit afterward. |
| Nested values completion | In the `api` dependency version, remove `utils` and press Ctrl+Space after `.StateValues.versions.`. Suggestions come from `base.yaml`. |
| YAML schema completion | Inside `api`, add a blank line at the same indentation as `dependencies`, then press Ctrl+Space. Inside a dependency item, do the same at the indentation of `version` to show `chart`, `version`, and `alias`. |
| Native usages popup | Ctrl+Click `namespace` in the inline `dev` environment values. There are uses in the common template and multiple `needs` entries, so the native table has several rows. |
| Parent and child navigation | The `service` template has both parent and child gutter arrows. Click its child arrow to show `api`, `worker`, and `scheduler` inheriting it. |
| Declaration navigation | Select `dev` using Tools → Helmfile: Select Environment, then Ctrl+Click `.StateValues.namespace` or `inherit[].template: service`. |
| Function documentation | In `values/service.yaml.gotmpl`, place the caret on `default`, `required`, `printf`, or `toYaml` and press Ctrl+Q. Alt+Enter also offers function documentation. |
| Value documentation | With `dev` selected, press Ctrl+Q on `replicas` or `logLevel` in `.StateValues` to show its value, source file, and environment. |
| Release actions | Click the run icon beside `api`. Its menu contains Template, Apply, Delete, and Destroy. Choose Template to show the environment chooser and command preview. |
| Protected environment | Choose Template for `api`, select `prod-eu`, and click Run Template. With the default protection regex `prod.*`, the extra confirmation dialog appears. Cancel after taking the screenshot. |
| Native Run console | Choose Template for `worker` with `dev` selected and run it. The included local chart renders into the standard Run tool window. |

The editor environment affects completion and navigation. Each release action also lets you
choose its execution environment independently. Use Template for the render preview.

To render from a terminal, run from this directory:

```sh
helmfile --environment dev template
```

Use the default Windows/Linux keymap shortcuts above; macOS shortcuts differ.
