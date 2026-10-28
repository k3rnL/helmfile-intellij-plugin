package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.io.HttpRequests;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;
import javax.net.ssl.*;
import org.snakeyaml.engine.v2.api.*;

/** Uses the IDE's HTTP stack (proxy settings included); never runs Helm or modifies its repositories. */
final class HelmfileRepositoryDownload {
    private static final int LIMIT = 32 * 1024 * 1024;
    private record Response(int status, String challenge, String next, String body) {}

    static HelmfileChartIndex load(HelmfileRepositories.Access access, String chart, ProgressIndicator indicator) throws IOException {
        try {
            SSLContext ssl = ssl(access);
            if (!access.oci()) {
                URI base = URI.create(access.url());
                URI index = new URI(base.getScheme(), base.getAuthority(), base.getPath().replaceFirst("/*$", "/") + "index.yaml", base.getQuery(), null);
                Response response = get(index, access, ssl, null, indicator);
                requireSuccess(response);
                return HelmfileChartIndex.parse(response.body());
            }
            String url = access.url().replaceFirst("^(?:oci|https?)://", "");
            URI source = URI.create((access.plainHttp() ? "http://" : "https://") + url.replaceFirst("/+$", "") + "/" + chart);
            URI next = new URI(source.getScheme(), source.getAuthority(), "/v2" + source.getPath() + "/tags/list", "n=1000", null);
            List<HelmfileChartIndex.Chart> versions = new ArrayList<>();
            Set<URI> visited = new HashSet<>();
            String token = null;
            while (next != null) {
                indicator.checkCanceled();
                if (!visited.add(next) || visited.size() > 50) throw new IOException("OCI tag pagination limit exceeded");
                Response response = get(next, access, ssl, token, indicator);
                if (response.status() == 401 && token == null) {
                    token = bearer(response.challenge(), next, access, ssl, indicator);
                    if (token != null) response = get(next, access, ssl, token, indicator);
                }
                requireSuccess(response);
                Object loaded = json(response.body());
                if (!(loaded instanceof Map<?, ?> result) || !result.containsKey("tags")) throw new IOException("Invalid OCI tags response");
                if (result.get("tags") instanceof List<?> tags) for (Object tag : tags) {
                    String version = tag.toString().replace('_', '+');
                    if (version.matches("v?\\d+\\.\\d+\\.\\d+(?:[-+].*)?")) versions.add(new HelmfileChartIndex.Chart(chart, version, "OCI chart", false));
                }
                URI current = next;
                next = null;
                if (response.next() != null) {
                    var matcher = Pattern.compile("<([^>]+)>;\\s*rel=\"?next\"?").matcher(response.next());
                    if (matcher.find()) {
                        next = current.resolve(matcher.group(1));
                        if (!sameOrigin(current, next)) throw new IOException("OCI tags redirected to another registry");
                    }
                }
            }
            versions.sort((left, right) -> com.intellij.util.text.VersionComparatorUtil.compare(right.version(), left.version()));
            return new HelmfileChartIndex(Map.of(chart, List.copyOf(versions)));
        } catch (GeneralSecurityException | URISyntaxException | IllegalArgumentException | org.snakeyaml.engine.v2.exceptions.YamlEngineException error) {
            throw new IOException("Unsupported repository URL, TLS configuration, or response", error);
        }
    }
    private static Object json(String body) {
        return new Load(LoadSettings.builder().setCodePointLimit(LIMIT).setMaxAliasesForCollections(0).build()).loadFromString(body);
    }
    private static String bearer(String challenge, URI registry, HelmfileRepositories.Access access, SSLContext ssl, ProgressIndicator indicator) throws IOException {
        if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        Map<String, String> parameters = new HashMap<>();
        var matcher = Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(challenge.substring(7));
        while (matcher.find()) parameters.put(matcher.group(1), matcher.group(2));
        String realm = parameters.get("realm");
        if (realm == null) return null;
        URI tokenUri = URI.create(realm);
        // Cross-host authentication services are common for public OCI registries. Never
        // forward registry credentials there unless passCredentials was explicitly enabled.
        if (!tokenUri.getScheme().equals("https") && !(sameOrigin(registry, tokenUri) && access.plainHttp())) return null;
        StringBuilder query = new StringBuilder(realm);
        for (String key : List.of("service", "scope")) if (parameters.containsKey(key)) {
            query.append(query.indexOf("?") < 0 ? '?' : '&').append(key).append('=').append(URLEncoder.encode(parameters.get(key), StandardCharsets.UTF_8));
        }
        Response response = get(URI.create(query.toString()), access, ssl, null, registry, indicator);
        requireSuccess(response);
        Object loaded = json(response.body());
        if (!(loaded instanceof Map<?, ?> map)) return null;
        Object token = map.containsKey("token") ? map.get("token") : map.get("access_token");
        return token instanceof String value ? value : null;
    }
    private static void requireSuccess(Response response) throws IOException {
        if (response.status() != 200) {
            String meaning = switch (response.status()) {
                case 401 -> " Unauthorized";
                case 403 -> " Forbidden";
                case 404 -> " Not Found";
                case 429 -> " Too Many Requests";
                default -> "";
            };
            throw new IOException("Repository returned HTTP " + response.status() + meaning);
        }
    }
    private static Response get(URI uri, HelmfileRepositories.Access access, SSLContext ssl, String token, ProgressIndicator indicator) throws IOException {
        return get(uri, access, ssl, token, uri, indicator);
    }
    private static Response get(URI uri, HelmfileRepositories.Access access, SSLContext ssl, String token, URI credentialsOrigin, ProgressIndicator indicator) throws IOException {
        return HttpRequests.request(uri.toString()).connectTimeout(5000).readTimeout(10000).redirectLimit(5)
                .throwStatusCodeException(false).tuner(connection -> {
                    URI destination = URI.create(connection.getURL().toString());
                    if (!Set.of("http", "https").contains(destination.getScheme())
                            || uri.getScheme().equals("https") && !destination.getScheme().equals("https")) throw new IOException("Unsupported repository redirect");
                    boolean credentials = sameOrigin(credentialsOrigin, destination) || access.passCredentials();
                    if (credentials && token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
                    else if (credentials && (!access.username().isEmpty() || !access.password().isEmpty())) {
                        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((access.username() + ":" + access.password()).getBytes(StandardCharsets.UTF_8)));
                    }
                    if (connection instanceof HttpsURLConnection https) {
                        if (ssl != null && (credentials || access.certFile().isEmpty())) https.setSSLSocketFactory(ssl.getSocketFactory());
                        if (access.skipTLSVerify()) https.setHostnameVerifier((host, session) -> true);
                    }
                }).connect(request -> {
                    indicator.checkCanceled();
                    var connection = (HttpURLConnection) request.getConnection();
                    int status = connection.getResponseCode();
                    if (status != 200) return new Response(status, connection.getHeaderField("WWW-Authenticate"), null, "");
                    try (InputStream input = request.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            indicator.checkCanceled();
                            if (bytes.size() + count > LIMIT) throw new IOException("Repository response exceeds 32 MiB");
                            bytes.write(buffer, 0, count);
                        }
                        return new Response(status, null, connection.getHeaderField("Link"), bytes.toString(StandardCharsets.UTF_8));
                    }
                });
    }
    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme()) && Objects.equals(left.getHost(), right.getHost())
                && port(left) == port(right);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80; }
    private static SSLContext ssl(HelmfileRepositories.Access access) throws GeneralSecurityException, IOException {
        if (!access.skipTLSVerify() && access.caFile().isEmpty() && access.certFile().isEmpty() && access.keyFile().isEmpty()) return null;
        TrustManager[] trust = null;
        if (access.skipTLSVerify()) trust = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth) {}
        }};
        else if (!access.caFile().isEmpty()) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null);
            int index = 0;
            try (InputStream input = Files.newInputStream(Path.of(access.caFile()))) {
                for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) store.setCertificateEntry("ca-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store); trust = factory.getTrustManagers();
        }
        KeyManager[] keys = null;
        if (!access.certFile().isEmpty() || !access.keyFile().isEmpty()) {
            if (access.certFile().isEmpty() || access.keyFile().isEmpty()) throw new IOException("Both certFile and keyFile are required");
            java.security.cert.Certificate[] chain;
            try (InputStream input = Files.newInputStream(Path.of(access.certFile()))) {
                chain = CertificateFactory.getInstance("X.509").generateCertificates(input).toArray(java.security.cert.Certificate[]::new);
            }
            if (chain.length == 0) throw new IOException("Client certificate file is empty");
            String pem = Files.readString(Path.of(access.keyFile()));
            if (!pem.contains("-----BEGIN PRIVATE KEY-----")) throw new IOException("Client key must use unencrypted PKCS#8 PEM format");
            byte[] encoded = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            PrivateKey key = KeyFactory.getInstance(chain[0].getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(encoded));
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null); store.setKeyEntry("client", key, new char[0], chain);
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(store, new char[0]); keys = factory.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys, trust, null); return context;
    }
    private HelmfileRepositoryDownload() {}
}
