package dev.helmfile.intellij;

import com.intellij.lang.Language;
import com.intellij.psi.templateLanguages.TemplateLanguage;

public final class HelmfileLanguage extends Language implements TemplateLanguage {
    public static final HelmfileLanguage INSTANCE = new HelmfileLanguage();
    private HelmfileLanguage() { super("Helmfile"); }
}
