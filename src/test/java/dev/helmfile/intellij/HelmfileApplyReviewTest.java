package dev.helmfile.intellij;

import com.intellij.diff.DiffContentFactory;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.*;
import java.util.*;
import org.jetbrains.yaml.YAMLFileType;

public class HelmfileApplyReviewTest extends BasePlatformTestCase {
    private Path directory, state;
    private VirtualFile root;
    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = Files.createTempDirectory("helmfile review ");
        state = Files.writeString(directory.resolve("helmfile.yaml"), "values: [values.yaml]\nreleases: []\n");
        Files.writeString(directory.resolve("values.yaml"), "namespace: staging\n");
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
        assertNotNull(root);
        ModuleRootModificationUtil.addContentRoot(getModule(), root);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ModuleRootModificationUtil.updateModel(getModule(), model -> {
                for (var entry : model.getContentEntries()) if (entry.getUrl().equals(root.getUrl())) model.removeContentEntry(entry);
            });
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } finally { super.tearDown(); }
    }
    private HelmfileApplyReview review() throws Exception {
        return new HelmfileApplyReview(getProject(), new HelmfileCommand(state.toString(), "api", "", "staging", HelmfileOperation.APPLY), Map.of(), true);
    }
    public void testPreviewPsiAndUncommittedDiffDocumentsDoNotInvalidateReview() throws Exception {
        var review = review();
        assertTrue(review.current());
        var content = DiffContentFactory.getInstance().create(getProject(), "kind: ConfigMap\n", YAMLFileType.YML);
        var manager = PsiDocumentManager.getInstance(getProject());
        assertNotNull(manager.getPsiFile(content.getDocument()));
        content.getDocument().setReadOnly(false);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> content.getDocument().insertString(0, "# native preview\n"));
        assertFalse(manager.isCommitted(content.getDocument()));
        assertTrue("A preview document must not invalidate the deployment review", review.current());
        manager.commitDocument(content.getDocument());
        assertTrue("Parsing and committing preview PSI must not invalidate the deployment review", review.current());
    }
    public void testUnsavedValuesEditsInvalidateReviewBeforePsiIsCommitted() throws Exception {
        var file = root.findChild("values.yaml");
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        var review = review();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "changed: true\n"));
        assertFalse(review.current());
    }
    public void testOpeningAndSwitchingNativeDiffResourcesKeepsApprovalEnabled() throws Exception {
        var review = review();
        var command = new HelmfileCommand(state.toString(), "api", "", "staging", HelmfileOperation.DIFF);
        var chain = HelmfileDiffViewer.chain(getProject(), command, HelmfileDiffOutput.parse(HelmfileDiffTest.MODIFIED), List.of(review.action()));
        var context = new com.intellij.diff.DiffContext() {
            @Override public com.intellij.openapi.project.Project getProject() { return HelmfileApplyReviewTest.this.getProject(); }
            @Override public boolean isWindowFocused() { return false; }
            @Override public boolean isFocusedInWindow() { return false; }
            @Override public void requestFocusInWindow() {}
        };
        for (var producer : chain.getRequests()) {
            var request = producer.process(new com.intellij.openapi.util.UserDataHolderBase(), new com.intellij.openapi.progress.EmptyProgressIndicator());
            var viewer = com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE.createComponent(context, request);
            try {
                viewer.init();
                PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
                assertTrue("Opening " + producer.getName() + " must keep the review current", review.current());
            } finally { com.intellij.openapi.util.Disposer.dispose(viewer); }
        }
    }
    public void testExternalValuesChangesInvalidateReview() throws Exception {
        var review = review();
        var file = root.findChild("values.yaml");
        assertNotNull(file);
        WriteAction.run(() -> file.setBinaryContent("namespace: changed\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(review.current());
    }
    public void testSavingRunConfigurationDoesNotInvalidateReview() throws Exception {
        WriteAction.run(() -> root.createChildDirectory(this, ".idea").createChildData(this, "workspace.xml"));
        var file = root.findFileByRelativePath(".idea/workspace.xml");
        assertNotNull(file);
        var review = review();
        WriteAction.run(() -> file.setBinaryContent("<project/>\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(review.current());
    }
}
