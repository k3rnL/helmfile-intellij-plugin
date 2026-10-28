package dev.helmfile.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HelmfileRepositoryDownloadTest extends BasePlatformTestCase {
    private HttpServer server;
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }
    @Override protected void tearDown() throws Exception {
        try { if (server != null) server.stop(0); } finally { super.tearDown(); }
    }
    private String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private HelmfileRepositories.Access access(String url, boolean oci, String username, String password) {
        return new HelmfileRepositories.Access("test", url, oci, username, password, "", "", "", false, false, true);
    }
    private HelmfileChartIndex load(HelmfileRepositories.Access access, String chart) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> HelmfileRepositoryDownload.load(access, chart, new EmptyProgressIndicator()))
                .get(15, TimeUnit.SECONDS);
    }
    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    public void testIndexDownloadPreservesBasePathQueryAndBasicAuthentication() throws Exception {
        AtomicReference<String> authentication = new AtomicReference<>(), query = new AtomicReference<>();
        server.createContext("/charts/index.yaml", exchange -> {
            authentication.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            reply(exchange, HelmfileRepositoryInsightTest.INDEX);
        });
        var index = load(access(origin() + "/charts?project=1", false, "user", "secret"), "");
        assertContainsElements(index.charts().keySet(), "api", "worker");
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)), authentication.get());
        assertEquals("project=1", query.get());
    }
    public void testCredentialsAreNotForwardedAcrossRedirectOrigins() throws Exception {
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authentication = new AtomicReference<>();
        destination.createContext("/index.yaml", exchange -> { authentication.set(exchange.getRequestHeaders().getFirst("Authorization")); reply(exchange, HelmfileRepositoryInsightTest.INDEX); });
        destination.start();
        try {
            server.createContext("/index.yaml", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/index.yaml");
                exchange.sendResponseHeaders(302, -1); exchange.close();
            });
            assertNotNull(load(access(origin(), false, "user", "secret"), ""));
            assertNull(authentication.get());
        } finally { destination.stop(0); }
    }
    public void testOciTagDownloadFollowsPaginationAndHelmBuildMetadataEncoding() throws Exception {
        server.createContext("/v2/charts/api/tags/list", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("last=")) reply(exchange, "{\"name\":\"charts/api\",\"tags\":[\"1.0.0\"]}");
            else {
                exchange.getResponseHeaders().set("Link", "</v2/charts/api/tags/list?last=2>; rel=\"next\"");
                reply(exchange, "{\"name\":\"charts/api\",\"tags\":[\"2.0.0_build.1\",\"latest\"]}");
            }
        });
        var index = load(access(origin() + "/charts", true, "", ""), "api");
        assertEquals(List.of("2.0.0+build.1", "1.0.0"), index.charts().get("api").stream().map(HelmfileChartIndex.Chart::version).toList());
    }
    public void testInvalidIndexIsNotAcceptedAsEmptyRepository() {
        for (String content : List.of("<html>Unauthorized</html>", "apiVersion: v1\n", "apiVersion: v2\nentries: {}\n", "entries: [broken")) {
            try { HelmfileChartIndex.parse(content); fail("Accepted invalid index: " + content); }
            catch (IOException expected) { /* An unavailable index must not produce missing-chart warnings. */ }
        }
    }
    public void testOciBearerChallengeRequestsScopedTokenAndRetriesTags() throws Exception {
        AtomicReference<String> scope = new AtomicReference<>(), authentication = new AtomicReference<>();
        server.createContext("/token", exchange -> {
            scope.set(exchange.getRequestURI().getQuery());
            authentication.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, "{\"token\":\"registry-token\"}");
        });
        server.createContext("/v2/charts/api/tags/list", exchange -> {
            if ("Bearer registry-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                reply(exchange, "{\"tags\":[\"1.0.0\"]}");
            else {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"" + origin()
                        + "/token\",service=\"registry\",scope=\"repository:charts/api:pull\"");
                exchange.sendResponseHeaders(401, -1); exchange.close();
            }
        });
        assertTrue(load(access(origin() + "/charts", true, "user", "password"), "api").containsVersion("api", "1.0.0"));
        assertEquals("service=registry&scope=repository:charts/api:pull", scope.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8)), authentication.get());
    }
    public void testIndexDoesNotConstructArbitraryYamlObjects() {
        try { HelmfileChartIndex.parse("!!java.net.URL [https://example.org]"); fail("Accepted custom YAML object tag"); }
        catch (IOException expected) { }
    }
    public void testBatchProgressIsDeterminateAndFailuresPreserveCachedSuggestions() throws Exception {
        server.createContext("/good/index.yaml", exchange -> reply(exchange, HelmfileRepositoryInsightTest.INDEX));
        server.createContext("/denied/index.yaml", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        var good = new HelmfileRepositoryService.Query(access(origin() + "/good", false, "", ""), "");
        var denied = new HelmfileRepositoryService.Query(access(origin() + "/denied?private_token=query-secret", false, "", ""), "");
        var service = HelmfileRepositoryService.get(getProject());
        var cached = HelmfileChartIndex.parse(HelmfileRepositoryInsightTest.INDEX);
        service.remember(denied, cached);
        List<Double> fractions = new ArrayList<>();
        var indicator = new com.intellij.openapi.progress.util.ProgressIndicatorBase() {
            @Override public void setFraction(double fraction) {
                assertFalse("Fractional progress requires determinate mode", isIndeterminate());
                fractions.add(fraction);
                super.setFraction(fraction);
            }
        };
        indicator.setIndeterminate(true);
        ApplicationManager.getApplication().executeOnPooledThread(() -> service.loadRepositories(List.of(good, denied), indicator)).get(15, TimeUnit.SECONDS);
        assertEquals(List.of(0.0, 0.5, 1.0), fractions);
        assertTrue(service.snapshot(good, false).validated());
        var failure = service.snapshot(denied, false);
        assertFalse(failure.validated());
        assertSame(cached, failure.index());
        assertTrue(failure.error(), failure.error().contains("HTTP 403 Forbidden"));
        assertTrue(failure.error().contains("last downloaded index"));
        assertFalse(failure.error(), failure.error().contains("query-secret"));
    }
    public void testManualRefreshNotificationIncludesTheActualHttpFailure() {
        server.createContext("/denied/index.yaml", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        var query = new HelmfileRepositoryService.Query(access(origin() + "/denied?private_token=query-secret", false, "", ""), "");
        List<com.intellij.notification.Notification> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(com.intellij.notification.Notifications.TOPIC,
                new com.intellij.notification.Notifications() {
                    @Override public void notify(com.intellij.notification.Notification notification) { notifications.add(notification); }
                });
        var started = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() ->
                HelmfileRepositoryService.get(getProject()).refresh(List.of(query), true));
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(started, 10_000);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Missing repository failure notification", () -> !notifications.isEmpty(), 10_000);
        var notification = notifications.getFirst();
        assertTrue(notification.getContent(), notification.getContent().contains("HTTP 401 Unauthorized"));
        assertFalse(notification.getContent().contains("query-secret"));
        assertEquals("Show Details", notification.getActions().getFirst().getTemplatePresentation().getText());
    }
    public void testSlowRepositoryDownloadDoesNotBlockEditingCompletionOrGutters() {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/slow/index.yaml", exchange -> {
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); reply(exchange, HelmfileRepositoryInsightTest.INDEX); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { finished.set(true); exchange.close(); }
        });
        var query = new HelmfileRepositoryService.Query(access(origin() + "/slow", false, "", ""), "");
        var started = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() -> HelmfileRepositoryService.get(getProject()).refresh(List.of(query)));
        try {
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Repository download did not start", () -> entered.getCount() == 0, 5000);
            myFixture.configureByText("helmfile.yaml.gotmpl", """
                    values:
                      - namespace: quality
                        name: app
                    releases:
                      - name: app
                        chart: example/api
                        namespace: {{ .StateValues.<caret> }}
                    """);
            myFixture.type("n");
            myFixture.completeBasic();
            assertContainsElements(myFixture.getLookupElementStrings(), "namespace", "name");
            if (myFixture.getLookup() != null) myFixture.getLookup().hideLookup(true);
            assertTrue(myFixture.findAllGutters().stream().anyMatch(gutter -> String.valueOf(gutter.getTooltipText()).contains("Run Helmfile")));
            assertFalse("Editor analysis waited for the repository response", finished.get());
        } finally {
            release.countDown();
            com.intellij.testFramework.PlatformTestUtil.waitForFuture(started, 10_000);
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Repository task did not finish", () -> finished.get(), 10_000);
        }
    }
}
