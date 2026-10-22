package dev.helmfile.intellij;

import com.intellij.find.findUsages.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.Processor;
import com.intellij.usageView.UsageInfo;
import java.util.Collection;
import org.jetbrains.annotations.NotNull;

public final class HelmfileFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
    @Override public boolean canFindUsages(@NotNull PsiElement element) {
        if (element instanceof HelmfileUsagePopup.SearchAnchor) return true;
        var key = HelmfileSymbols.key(element);
        return key != null && HelmfileSymbols.isDeclaration(key);
    }
    @Override public FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean forHighlightUsages) {
        if (element instanceof HelmfileUsagePopup.SearchAnchor target) return new FindUsagesHandler(target) {
            @Override public boolean processElementUsages(@NotNull PsiElement element, @NotNull Processor<? super UsageInfo> processor, @NotNull FindUsagesOptions options) {
                var usages = ReadAction.computeBlocking(() -> target.popup.results(options.searchScope));
                for (UsageInfo usage : usages) if (!processor.process(usage)) return false;
                return true;
            }
            @Override protected boolean isSearchForTextOccurrencesAvailable(@NotNull PsiElement element, boolean singleFile) { return false; }
        };
        var key = HelmfileSymbols.key(element);
        return key == null ? null : new FindUsagesHandler(key) {
            @Override public boolean processElementUsages(@NotNull PsiElement element, @NotNull Processor<? super UsageInfo> processor, @NotNull FindUsagesOptions options) {
                var usages = ReadAction.computeBlocking(() -> HelmfileUsageSearch.find(key, options.searchScope).stream().map(UsageInfo::new).toList());
                for (UsageInfo usage : usages) if (!processor.process(usage)) return false;
                return true;
            }
            @Override public @NotNull Collection<PsiReference> findReferencesToHighlight(@NotNull PsiElement target, @NotNull SearchScope scope) {
                return ReadAction.computeBlocking(() -> HelmfileUsageSearch.find(key, scope));
            }
            @Override protected boolean isSearchForTextOccurrencesAvailable(@NotNull PsiElement element, boolean isSingleFile) { return false; }
        };
    }
}
