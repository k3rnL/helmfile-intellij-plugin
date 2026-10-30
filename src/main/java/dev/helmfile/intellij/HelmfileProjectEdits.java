package dev.helmfile.intellij;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;

/** Real project edits invalidate deployment reviews; preview PSI and Run configuration saves do not. */
@Service(Service.Level.PROJECT)
public final class HelmfileProjectEdits implements Disposable, ModificationTracker {
    private final AtomicLong changes = new AtomicLong();
    public HelmfileProjectEdits(Project project) {
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                if (source(project, FileDocumentManager.getInstance().getFile(event.getDocument()))) changes.incrementAndGet();
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void before(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    if ((event instanceof VFileContentChangeEvent || event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent
                            || event instanceof VFilePropertyChangeEvent property && property.getPropertyName().equals(VirtualFile.PROP_NAME))
                            && source(project, event.getFile())) { changes.incrementAndGet(); return; }
                }
            }
        });
    }
    private static boolean source(Project project, VirtualFile file) {
        return !project.isDisposed() && file != null && file.isInLocalFileSystem()
                && !file.getPath().contains("/.idea/") && !file.getPath().contains("/.git/")
                && ProjectFileIndex.getInstance(project).isInContent(file);
    }
    @Override public long getModificationCount() { return changes.get(); }
    @Override public void dispose() {}
}
