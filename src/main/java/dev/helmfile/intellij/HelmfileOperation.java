package dev.helmfile.intellij;

enum HelmfileOperation {
    TEMPLATE("Template", "Render the release manifests"),
    APPLY("Apply", "Apply changes to the release"),
    DELETE("Delete", "Delete the release (Helmfile's deprecated delete command)"),
    DESTROY("Destroy", "Uninstall and purge the release");

    final String label;
    final String description;
    HelmfileOperation(String label, String description) { this.label = label; this.description = description; }
    String command() { return name().toLowerCase(java.util.Locale.ROOT); }
    @Override public String toString() { return label; }
}
