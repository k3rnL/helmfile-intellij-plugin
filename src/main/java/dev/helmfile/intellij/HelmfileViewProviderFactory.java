package dev.helmfile.intellij;

import com.intellij.lang.Language;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.templateLanguages.*;
import com.intellij.psi.tree.IElementType;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.impl.YAMLFileImpl;

public final class HelmfileViewProviderFactory implements FileViewProviderFactory {
    @Override public @NotNull FileViewProvider createFileViewProvider(@NotNull VirtualFile file, Language language, @NotNull PsiManager manager, boolean physical) {
        return new Provider(manager, file, physical);
    }
    private static final class Provider extends MultiplePsiFilesPerDocumentFileViewProvider implements TemplateLanguageFileViewProvider {
        private static final TemplateDataElementType YAML = new TemplateDataElementType("HELMFILE_YAML", HelmfileLanguage.INSTANCE,
                HelmfileTokens.DATA, HelmfileTokens.OUTER) {
            @Override protected com.intellij.lexer.Lexer createBaseLexer(TemplateLanguageFileViewProvider provider) { return new HelmfileLexer(true); }
        };
        Provider(PsiManager manager, VirtualFile file, boolean physical) { super(manager, file, physical); }
        @Override public @NotNull Language getBaseLanguage() { return HelmfileLanguage.INSTANCE; }
        @Override public @NotNull Language getTemplateDataLanguage() { return YAMLLanguage.INSTANCE; }
        @Override public @NotNull Set<Language> getLanguages() { return Set.of(HelmfileLanguage.INSTANCE, YAMLLanguage.INSTANCE); }
        @Override public IElementType getContentElementType(@NotNull Language language) { return language == YAMLLanguage.INSTANCE ? YAML : null; }
        @Override protected PsiFileImpl createFile(@NotNull Language language) {
            if (language == YAMLLanguage.INSTANCE) {
                YAMLFileImpl file = new YAMLFileImpl(this); file.setContentElementType(YAML); return file;
            }
            return language == HelmfileLanguage.INSTANCE ? new HelmfileParserDefinition.File(this) : null;
        }
        @Override protected @NotNull MultiplePsiFilesPerDocumentFileViewProvider cloneInner(@NotNull VirtualFile copy) {
            return new Provider(getManager(), copy, false);
        }
    }
}
