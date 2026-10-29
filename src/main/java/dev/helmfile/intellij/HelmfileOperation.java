package dev.helmfile.intellij;

import com.intellij.icons.AllIcons;
import javax.swing.Icon;

enum HelmfileOperation {
    TEMPLATE("Template", "Render and browse release manifests", AllIcons.Actions.Preview),
    DIFF("Diff", "Compare deployed and desired manifests in the IDE diff viewer", AllIcons.Actions.Diff),
    APPLY("Apply", "Review changes in the IDE before applying the release", AllIcons.Actions.Checked),
    DESTROY("Destroy", "Uninstall and purge the release", AllIcons.Actions.Cancel);

    final String label;
    final String description;
    final Icon icon;
    HelmfileOperation(String label, String description, Icon icon) { this.label = label; this.description = description; this.icon = icon; }
    String command() { return name().toLowerCase(java.util.Locale.ROOT); }
    @Override public String toString() { return label; }
}
