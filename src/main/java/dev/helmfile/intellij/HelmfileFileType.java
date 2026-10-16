package dev.helmfile.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public final class HelmfileFileType extends LanguageFileType {
    public static final HelmfileFileType INSTANCE = new HelmfileFileType();
    private HelmfileFileType() { super(HelmfileLanguage.INSTANCE); }
    @Override public @NotNull String getName() { return "Helmfile Template"; }
    @Override public @NotNull String getDescription() { return "Helmfile YAML / Go template"; }
    @Override public @NotNull String getDefaultExtension() { return "yaml.gotmpl"; }
    @Override public Icon getIcon() { return AllIcons.FileTypes.Config; }
}
