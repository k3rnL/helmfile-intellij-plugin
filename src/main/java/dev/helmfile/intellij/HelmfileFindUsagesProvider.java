package dev.helmfile.intellij;

import com.intellij.lang.cacheBuilder.*;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.*;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class HelmfileFindUsagesProvider implements FindUsagesProvider {
    @Override public WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(new HelmfileLexer(), TokenSet.create(HelmfileTokens.DATA, HelmfileTokens.PATH, HelmfileTokens.IDENTIFIER),
                TokenSet.create(HelmfileTokens.COMMENT), TokenSet.create(HelmfileTokens.STRING));
    }
    @Override public boolean canFindUsagesFor(@NotNull PsiElement element) { return HelmfileSymbols.key(element) != null; }
    @Override public String getHelpId(@NotNull PsiElement element) { return null; }
    @Override public @NotNull String getType(@NotNull PsiElement element) { return "Helmfile declaration"; }
    @Override public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        var key = HelmfileSymbols.key(element); return key == null ? element.getText() : key.getKeyText();
    }
    @Override public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) { return getDescriptiveName(element); }
}
