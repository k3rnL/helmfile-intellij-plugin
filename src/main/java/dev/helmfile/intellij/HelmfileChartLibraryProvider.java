package dev.helmfile.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.*;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** Read-only chart folders in the standard Project / External Libraries tree. */
public final class HelmfileChartLibraryProvider extends AdditionalLibraryRootsProvider {
    @Override public @NotNull Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
        return HelmfileChartSourceService.get(project).libraries();
    }
    static final class ChartLibrary extends SyntheticLibrary implements ItemPresentation {
        final String id;
        private final String title;
        private final VirtualFile root;
        ChartLibrary(String id, String title, VirtualFile root) { this.id = id; this.title = title; this.root = root; }
        @Override public @NotNull Collection<VirtualFile> getSourceRoots() { return List.of(root); }
        @Override public boolean isShowInExternalLibrariesNode() { return true; }
        @Override public String getPresentableText() { return title; }
        @Override public Icon getIcon(boolean unused) { return AllIcons.Nodes.PpLib; }
        @Override public boolean equals(Object other) { return other instanceof ChartLibrary library && id.equals(library.id) && title.equals(library.title) && root.equals(library.root); }
        @Override public int hashCode() { return Objects.hash(id, title, root); }
    }
}
