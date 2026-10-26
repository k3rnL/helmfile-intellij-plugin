package dev.helmfile.intellij;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public class HelmfileUsageConcurrencyTest extends BasePlatformTestCase {
    public void testAutomaticHighlightingKeepsAllConcurrentValueUsages() throws Exception {
        checkConcurrentSearch(false);
    }

    public void testConcurrentTemplateSearchKeepsAllInheritingReleases() throws Exception {
        checkConcurrentSearch(true);
    }

    private void checkConcurrentSearch(boolean template) throws Exception {
        int count = 512;
        StringBuilder text = new StringBuilder("""
                environments:
                  production:
                    values:
                      - namespace: production
                templates:
                  shared:
                    chart: example/api
                releases:
                """);
        for (int i = 0; i < count; i++) text.append("""
                  - name: api-%d
                    inherit:
                      - template: shared
                    namespace: {{ .StateValues.namespace }}
                """.formatted(i));
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", text.toString());
        String word = template ? "shared" : "namespace";
        YAMLKeyValue declaration = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(key -> key.getKeyText().equals(word)).findFirst().orElseThrow();
        String occurrence = template ? "template: shared" : ".StateValues.namespace";
        List<Integer> offsets = new ArrayList<>();
        for (int from = 0, offset; (offset = text.indexOf(occurrence, from)) >= 0; from = offset + occurrence.length()) {
            offsets.add(offset + occurrence.lastIndexOf(word));
        }
        assertSize(count, offsets);
        // Preload PSI and cached resolution data, as the editor does before highlighting.
        HelmfileValues.allDefinitions(file);
        HelmfileTemplates.declarations(file);
        AtomicInteger searches = new AtomicInteger();
        replaceWithConcurrentWordSearch(file, word, offsets, searches);
        var handler = new HelmfileFindUsagesHandlerFactory().createFindUsagesHandler(declaration, true);
        assertNotNull(handler);
        Set<Integer> expected = new HashSet<>(offsets);
        for (int iteration = 0; iteration < 10; iteration++) {
            Collection<PsiReference> references = template
                    ? HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(getProject()))
                    : handler.findReferencesToHighlight(declaration, new LocalSearchScope(file));
            assertSize(count, references);
            Set<Integer> actual = new HashSet<>();
            for (PsiReference reference : references) {
                assertNotNull(reference);
                actual.add(reference.getElement().getTextOffset() + reference.getRangeInElement().getStartOffset());
            }
            assertEquals(expected, actual);
        }
        assertEquals(10, searches.get());
    }

    private void replaceWithConcurrentWordSearch(PsiFile file, String word, List<Integer> offsets, AtomicInteger searches) {
        PsiSearchHelper delegate = PsiSearchHelper.getInstance(getProject());
        PsiSearchHelper helper = (PsiSearchHelper) Proxy.newProxyInstance(PsiSearchHelper.class.getClassLoader(),
                new Class<?>[]{PsiSearchHelper.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("processElementsWithWord") || !word.equals(args[2])) {
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException exception) { throw exception.getCause(); }
                    }
                    searches.incrementAndGet();
                    TextOccurenceProcessor processor = (TextOccurenceProcessor) args[0];
                    int threads = 8;
                    ExecutorService workers = Executors.newFixedThreadPool(threads);
                    CountDownLatch ready = new CountDownLatch(threads);
                    CountDownLatch start = new CountDownLatch(1);
                    try {
                        List<Future<?>> tasks = new ArrayList<>();
                        for (int thread = 0; thread < threads; thread++) {
                            int partition = thread;
                            tasks.add(workers.submit(() -> ReadAction.runBlocking(() -> {
                                ready.countDown();
                                try { assertTrue(start.await(10, TimeUnit.SECONDS)); }
                                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                                for (int index = partition; index < offsets.size(); index += threads) {
                                    // Index callbacks can revisit an occurrence: results must also stay deduplicated.
                                    assertTrue(processor.execute(file, offsets.get(index)));
                                    assertTrue(processor.execute(file, offsets.get(index)));
                                }
                            })));
                        }
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        start.countDown();
                        for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
                        return true;
                    } finally {
                        start.countDown();
                        workers.shutdownNow();
                        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                    }
                });
        ServiceContainerUtil.replaceService(getProject(), PsiSearchHelper.class, helper, getTestRootDisposable());
    }
}
