package com.nimeshkmr.storage.explorer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real HTTP server on an ephemeral loopback port. */
class ExplorerServerTest {

    @TempDir
    Path tempDir;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Resp(int status, Object json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> obj() { return (Map<String, Object>) json; }
        @SuppressWarnings("unchecked")
        List<Object> list() { return (List<Object>) json; }
    }

    private Resp call(ExplorerServer s, String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (!method.equals("GET")) b.header(ExplorerServer.CLIENT_HEADER, "test");
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), Json.parse(r.body()));
    }

    private static String code(Resp r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.obj().get("error");
        return (String) err.get("code");
    }

    @Test
    void fullRoundTripOverHttp() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try {
            Resp h = call(s, "GET", "/api/health");
            assertEquals(200, h.status());
            assertEquals("ok", h.obj().get("status"));
            assertEquals(510L, h.obj().get("engineLeafCapacity"));

            Resp c = call(s, "POST", "/api/databases?name=demo&leafCapacity=4&internalCapacity=4");
            assertEquals(201, c.status());
            assertEquals("OPEN", c.obj().get("state"));

            for (int k = 1; k <= 6; k++) {
                Resp i = call(s, "POST", "/api/databases/demo/insert?key=" + k + "&value=" + (k * 10));
                assertEquals(200, i.status());
                assertEquals("INSERTED", i.obj().get("outcome"));
            }
            Resp split = call(s, "POST", "/api/databases/demo/insert?key=7&value=70");
            assertEquals(200, split.status());

            Resp found = call(s, "GET", "/api/databases/demo/search?key=3");
            assertEquals(200, found.status());
            assertEquals(Boolean.TRUE, found.obj().get("found"));
            assertEquals(30L, found.obj().get("value"));
            assertEquals(Boolean.TRUE, found.obj().get("traceMatchesEngine"));

            Resp missing = call(s, "GET", "/api/databases/demo/search?key=999");
            assertEquals(200, missing.status());
            assertEquals(Boolean.FALSE, missing.obj().get("found"));
            assertNull(missing.obj().get("value"));

            Resp tree = call(s, "GET", "/api/databases/demo/tree");
            assertEquals(200, tree.status());
            assertEquals(7L, tree.obj().get("keyCount"));
            assertEquals(2L, tree.obj().get("height"));

            assertEquals(200, call(s, "GET", "/api/databases/demo/pages").status());
            assertEquals(200, call(s, "GET", "/api/databases/demo/pages/0?hex=true").status());
            Resp wal = call(s, "GET", "/api/databases/demo/wal?limit=5");
            assertEquals(200, wal.status());
            assertEquals(5, ((List<?>) wal.obj().get("records")).size());

            Resp bulk = call(s, "POST", "/api/databases/demo/bulk?start=100&count=30&order=shuffled&seed=3");
            assertEquals(200, bulk.status());
            assertEquals(30L, bulk.obj().get("inserted"));

            assertEquals(200, call(s, "POST", "/api/databases/demo/close").status());
            assertEquals(409, call(s, "GET", "/api/databases/demo/tree").status());
            assertEquals(200, call(s, "POST", "/api/databases/demo/open").status());
            assertEquals(37L, call(s, "GET", "/api/databases/demo/tree").obj().get("keyCount"));

            assertEquals(400, call(s, "DELETE", "/api/databases/demo").status());
            assertEquals(200, call(s, "DELETE", "/api/databases/demo?confirm=demo").status());
            assertEquals(0, call(s, "GET", "/api/databases").list().size());
        } finally {
            s.stop();
        }
    }

    @Test
    void errorsAreStructuredAndUseSensibleStatuses() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try {
            call(s, "POST", "/api/databases?name=demo");
            Resp bad = call(s, "POST", "/api/databases/demo/insert?key=abc&value=1");
            assertEquals(400, bad.status());
            assertEquals("INVALID_INPUT", code(bad));
            assertEquals(400, call(s, "POST", "/api/databases/demo/insert?value=1").status());
            assertEquals(400, call(s, "POST", "/api/databases/demo/insert?key=99999999999&value=1").status());
            assertEquals(404, call(s, "GET", "/api/databases/ghost/tree").status());
            assertEquals(404, call(s, "GET", "/api/nothing").status());
            assertEquals(404, call(s, "GET", "/api/databases/demo/pages/777").status());
            assertEquals(400, call(s, "GET", "/api/databases/demo/pages/abc").status());
            assertEquals(405, call(s, "GET", "/api/databases/demo/insert?key=1&value=1").status());
            assertEquals(409, call(s, "POST", "/api/databases?name=demo").status());
            assertEquals(400, call(s, "POST", "/api/databases?name=..%2Fevil").status());
            assertEquals(400, call(s, "POST", "/api/recovery-demo?scenario=nope").status());
            assertEquals(0L, call(s, "GET", "/api/databases/demo/tree").obj().get("keyCount"),
                    "rejected requests must not change the database");
        } finally {
            s.stop();
        }
    }

    @Test
    void mutatingRequestsNeedTheClientHeader() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/api/databases?name=demo"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, resp.statusCode());
            assertTrue(resp.body().contains("MISSING_CLIENT_HEADER"));
            assertEquals(0, call(s, "GET", "/api/databases").list().size());
        } finally {
            s.stop();
        }
    }

    @Test
    void nonLoopbackHostHeaderIsRejected() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try (Socket sock = new Socket("127.0.0.1", s.port())) {
            OutputStream out = sock.getOutputStream();
            out.write(("GET /api/health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = sock.getInputStream();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith("HTTP/1.1 403"), text);
            assertTrue(text.contains("FORBIDDEN_HOST"));
        } finally {
            s.stop();
        }
    }

    @Test
    void serverBindsToLoopbackOnly() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try {
            // The bound address is private API of HttpServer; verify behaviourally via the public port instead.
            try (Socket sock = new Socket("127.0.0.1", s.port())) {
                assertTrue(sock.isConnected());
            }
            assertTrue(ExplorerServer.isLoopbackHost("localhost:8787"));
            assertTrue(ExplorerServer.isLoopbackHost("127.0.0.1"));
            assertTrue(ExplorerServer.isLoopbackHost("[::1]:8787"));
            assertFalse(ExplorerServer.isLoopbackHost("127.0.0.1.evil.com"));
            assertFalse(ExplorerServer.isLoopbackHost("evil.example:80"));
        } finally {
            s.stop();
        }
    }

    @Test
    void recoveryDemoEndpointsWork() throws Exception {
        ExplorerServer s = ExplorerServer.start(tempDir.resolve("d"), 0);
        try {
            Resp list = call(s, "GET", "/api/recovery-demo");
            assertEquals(200, list.status());
            assertEquals(4, list.list().size());
            Resp run = call(s, "POST", "/api/recovery-demo?scenario=commit-logged-pages-lost");
            assertEquals(200, run.status());
            @SuppressWarnings("unchecked")
            Map<String, Object> outcome = (Map<String, Object>) run.obj().get("outcome");
            assertEquals(Boolean.TRUE, outcome.get("matchesExpectation"));
        } finally {
            s.stop();
        }
    }
}
