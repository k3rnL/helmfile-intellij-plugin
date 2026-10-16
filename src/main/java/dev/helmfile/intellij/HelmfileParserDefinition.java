package dev.helmfile.intellij;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.*;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.*;
import org.jetbrains.annotations.NotNull;

public final class HelmfileParserDefinition implements ParserDefinition {
    @Override public @NotNull Lexer createLexer(Project project) { return new HelmfileLexer(); }
    @Override public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            PsiBuilder.Marker file = builder.mark();
            while (!builder.eof()) {
                if (builder.getTokenType() == HelmfileTokens.DATA) builder.advanceLexer();
                else {
                    PsiBuilder.Marker atom = builder.mark(); builder.advanceLexer(); atom.done(HelmfileTokens.ATOM);
                }
            }
            file.done(root);
            return builder.getTreeBuilt();
        };
    }
    @Override public @NotNull IFileElementType getFileNodeType() { return HelmfileTokens.FILE; }
    @Override public @NotNull TokenSet getWhitespaceTokens() { return TokenSet.create(TokenType.WHITE_SPACE); }
    @Override public @NotNull TokenSet getCommentTokens() { return TokenSet.create(HelmfileTokens.COMMENT); }
    @Override public @NotNull TokenSet getStringLiteralElements() { return TokenSet.create(HelmfileTokens.STRING); }
    @Override public @NotNull PsiElement createElement(ASTNode node) { return new Atom(node); }
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) { return new File(provider); }

    static final class File extends PsiFileBase {
        File(FileViewProvider provider) { super(provider, HelmfileLanguage.INSTANCE); }
        @Override public @NotNull FileType getFileType() { return HelmfileFileType.INSTANCE; }
    }
    static final class Atom extends ASTWrapperPsiElement {
        Atom(ASTNode node) { super(node); }
        @Override public PsiReference @NotNull [] getReferences() { return HelmfileReferences.forAtom(this); }
    }
}
