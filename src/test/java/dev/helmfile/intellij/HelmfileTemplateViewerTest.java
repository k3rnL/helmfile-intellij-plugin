package dev.helmfile.intellij;

import com.intellij.diff.DiffContext;
import com.intellij.diff.tools.simple.*;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** Exercise the actual SDK viewer selection and editor, not just the request payload. */
public class HelmfileTemplateViewerTest extends BasePlatformTestCase {
    public void testEveryManifestOpensInANativeSinglePaneEditorWithVisibleYaml() throws Exception {
        var result = HelmfileTemplateOutput.parse(HelmfileTemplateOutputTest.MANIFESTS);
        var command = new HelmfileCommand("/unused/helmfile.yaml", "api", "", "staging", HelmfileOperation.TEMPLATE);
        var chain = HelmfileTemplateViewer.chain(getProject(), command, result);
        DiffContext context = new DiffContext() {
            @Override public Project getProject() { return HelmfileTemplateViewerTest.this.getProject(); }
            @Override public boolean isWindowFocused() { return false; }
            @Override public boolean isFocusedInWindow() { return false; }
            @Override public void requestFocusInWindow() {}
        };
        for (var producer : chain.getRequests()) {
            var request = producer.process(new UserDataHolderBase(), new EmptyProgressIndicator());
            assertTrue(producer.getName(), SimpleDiffTool.INSTANCE.canShow(context, request));
            var viewer = SimpleDiffTool.INSTANCE.createComponent(context, request);
            try {
                assertInstanceOf(viewer, SimpleOnesideDiffViewer.class);
                viewer.init();
                var textViewer = (SimpleOnesideDiffViewer) viewer;
                assertSize(1, textViewer.getEditors());
                assertTrue(textViewer.getEditor().getDocument().getText().contains("apiVersion:"));
                assertTrue(textViewer.getEditor().isViewer());
            } finally { Disposer.dispose(viewer); }
        }
    }
}
