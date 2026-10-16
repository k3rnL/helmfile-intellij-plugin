package dev.helmfile.intellij;

import com.intellij.find.actions.*;
import com.intellij.find.findUsages.*;
import com.intellij.internal.statistic.eventLog.events.EventPair;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import java.util.*;
import java.util.function.Supplier;
import org.jetbrains.yaml.psi.YAMLKeyValue;

/** Supplies search results to IntelliJ's existing Show Usages table, preview, and toolbar. */
final class HelmfileUsagePopup implements ShowUsagesActionHandler {
    private final SmartPsiElementPointer<PsiElement> anchor;
    private final List<SmartPsiElementPointer<PsiElement>> declarations;
    private final String name;
    private final SearchScope scope;
    private final boolean declarationMode;

    static HelmfileUsagePopup usages(YAMLKeyValue declaration) {
        return new HelmfileUsagePopup(declaration, declaration.getKeyText(), List.of(), false);
    }
    static HelmfileUsagePopup declarations(PsiElement source, String name, List<? extends PsiElement> declarations) {
        return new HelmfileUsagePopup(source, name, declarations, true);
    }
    private HelmfileUsagePopup(PsiElement source, String name, List<? extends PsiElement> declarations, boolean declarationMode) {
        SmartPointerManager pointers = SmartPointerManager.getInstance(source.getProject());
        this.anchor = pointers.createSmartPsiElementPointer(source);
        this.declarations = declarations.stream().map(element -> pointers.createSmartPsiElementPointer((PsiElement) element)).toList();
        this.name = name; this.declarationMode = declarationMode;
        this.scope = GlobalSearchScope.projectScope(source.getProject());
    }
    private HelmfileUsagePopup(HelmfileUsagePopup original, SearchScope scope) {
        this.anchor = original.anchor; this.declarations = original.declarations;
        this.name = original.name; this.declarationMode = original.declarationMode; this.scope = scope;
    }
    void show(Editor editor) {
        if (editor == null || editor.isDisposed() || !isValid()) return;
        Project project = anchor.getProject();
        ShowUsagesParameters parameters = ShowUsagesParameters.initial(project, editor, JBPopupFactory.getInstance().guessBestPopupLocation(editor));
        ShowUsagesManager.getInstance(project).showElementUsagesWithResult(parameters, this, createUsageView(project));
    }
    List<UsageInfo> results() {
        if (!isValid()) return List.of();
        if (declarationMode) return declarations.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull)
                .filter(element -> PsiSearchScopeUtil.isInScope(scope, element)).map(element -> new UsageInfo(element, false)).toList();
        YAMLKeyValue key = HelmfileSymbols.key(anchor.getElement());
        return key == null ? List.of() : HelmfileUsageSearch.find(key, scope).stream().map(UsageInfo::new).toList();
    }
    boolean isDeclarationMode() { return declarationMode; }
    @Override public boolean isValid() { return !anchor.getProject().isDisposed() && anchor.getElement() != null; }
    @Override public UsageSearchPresentation getPresentation() {
        return new UsageSearchPresentation() {
            @Override public String getSearchTargetString() { return (declarationMode ? "Declarations of " : "Helmfile usages of ") + name; }
            @Override public String getOptionsString() { return declarationMode ? "declarations" : "usages"; }
        };
    }
    @Override public UsageSearcher createUsageSearcher() {
        return processor -> {
            for (UsageInfo result : ReadAction.compute(this::results)) if (!processor.process(new UsageInfo2UsageAdapter(result))) break;
        };
    }
    @Override public void findUsages() {
        UsageViewPresentation presentation = new UsageViewPresentation();
        presentation.setTabText(getPresentation().getSearchTargetString());
        presentation.setTargetsNodeText(name);
        presentation.setUsagesString(declarationMode ? "declarations" : "usages");
        Supplier<UsageSearcher> searcher = this::createUsageSearcher;
        UsageViewManager.getInstance(anchor.getProject()).searchAndShowUsages(UsageTarget.EMPTY_ARRAY, searcher, true, true, presentation, null);
    }
    @Override public ShowUsagesActionHandler showDialog() {
        PsiElement source = anchor.getElement();
        if (source == null) return null;
        FindUsagesOptions options = new FindUsagesOptions(scope);
        FindUsagesHandler handler = new FindUsagesHandler(source) {
            @Override protected boolean isSearchForTextOccurrencesAvailable(PsiElement element, boolean singleFile) { return false; }
        };
        CommonFindUsagesDialog dialog = new CommonFindUsagesDialog(source, anchor.getProject(), options, false, false, false, handler);
        return dialog.showAndGet() ? withScope(dialog.calcFindUsagesOptions().searchScope) : null;
    }
    @Override public ShowUsagesActionHandler withScope(SearchScope scope) { return new HelmfileUsagePopup(this, scope); }
    @Override public ShowUsagesParameters moreUsages(ShowUsagesParameters parameters) { return parameters.moreUsages(); }
    @Override public SearchScope getSelectedScope() { return scope; }
    @Override public SearchScope getMaximalScope() { return GlobalSearchScope.projectScope(anchor.getProject()); }
    @Override public Language getTargetLanguage() { return HelmfileLanguage.INSTANCE; }
    @Override public Class<?> getTargetClass() { return YAMLKeyValue.class; }
    @Override public List<EventPair<?>> getEventData() { return List.of(); }
    @Override public List<EventPair<?>> buildFinishEventData(UsageInfo usageInfo) { return List.of(); }
    @Override public boolean navigateToSingleUsageImmediately() { return false; }
}
