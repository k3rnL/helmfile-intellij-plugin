package dev.helmfile.intellij;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.ILeafElementType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.templateLanguages.OuterLanguageElementImpl;

final class HelmfileTokens {
    static final IFileElementType FILE = new IFileElementType(HelmfileLanguage.INSTANCE);
    static final IElementType OUTER = new OuterToken();
    static final IElementType DATA = token("YAML_DATA"), ACTION = token("ACTION"), ATOM = token("ATOM"),
            OPEN = token("OPEN"), CLOSE = token("CLOSE"), IDENTIFIER = token("FUNCTION"), PATH = token("VALUE_PATH"),
            KEYWORD = token("KEYWORD"), CONSTANT = token("CONSTANT"), VARIABLE = token("VARIABLE"),
            GO_FUNCTION = token("GO_FUNCTION"), SPRIG_FUNCTION = token("SPRIG_FUNCTION"), HELMFILE_FUNCTION = token("HELMFILE_FUNCTION"),
            CONTEXT = token("CONTEXT"), PROPERTY = token("PROPERTY"),
            STRING = token("STRING"), COMMENT = token("COMMENT"), NUMBER = token("NUMBER"), SYMBOL = token("SYMBOL");
    static boolean isDocumented(IElementType type) {
        return type == IDENTIFIER || type == KEYWORD || type == GO_FUNCTION || type == SPRIG_FUNCTION || type == HELMFILE_FUNCTION;
    }
    private static IElementType token(String name) { return new IElementType(name, HelmfileLanguage.INSTANCE); }
    private static final class OuterToken extends IElementType implements ILeafElementType {
        OuterToken() { super("HELMFILE_OUTER", HelmfileLanguage.INSTANCE); }
        @Override public ASTNode createLeafNode(CharSequence text) { return new OuterLanguageElementImpl(this, text); }
    }
    private HelmfileTokens() {}
}
