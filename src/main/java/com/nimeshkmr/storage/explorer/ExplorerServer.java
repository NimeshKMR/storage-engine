package com.nimeshkmr.storage.explorer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-only JSON API in front of {@link ExplorerService}, built on the JDK's own HTTP server.
 *
 * <p>Security model (it is a local demo tool, not a service): it binds to 127.0.0.1 only, rejects
 * requests whose Host header is not a loopback name (blocks DNS rebinding), and requires the custom
 * header {@value #CLIENT_HEADER} on every non-GET request. A web page in your browser cannot send that
 * header cross-origin without a CORS preflight, which this server never approves, so other sites
 * cannot trigger inserts or deletes. Database names are validated and every file lives in one data
 * directory.
 *
 * <p>Endpoints (all JSON; errors are {@code {"error":{"code":..., "message":...}}}):
 * <pre>
 * GET    /api/health
 * GET    /api/databases
 * POST   /api/databases?name=&amp;leafCapacity=&amp;internalCapacity=      create + open (409 if it exists)
 * GET    /api/databases/{name}
 * POST   /api/databases/{name}/open                              runs the engine's recovery
 * POST   /api/databases/{name}/close
 * DELETE /api/databases/{name}?confirm={name}
 * POST   /api/databases/{name}/insert?key=&amp;value=                returns an InsertReport
 * POST   /api/databases/{name}/bulk?start=&amp;count=&amp;step=&amp;order=&amp;seed=&amp;multiplier=
 * GET    /api/databases/{name}/search?key=
 * GET    /api/databases/{name}/tree
 * GET    /api/databases/{name}/pages
 * GET    /api/databases/{name}/pages/{pageId}?hex=true
 * GET    /api/databases/{name}/wal?offset=&amp;limit=
 * GET    /api/recovery-demo
 * POST   /api/recovery-demo?scenario=
 * </pre>
 */
public final class ExplorerServer {

    public static final String CLIENT_HEADER = "X-Explorer-Client";
    private static final int DEFAULT_PORT = 8787;

    private final ExplorerService service;
    private final HttpServer server;
    private final ExecutorService executor;

    private ExplorerServer(ExplorerService service, HttpServer server, ExecutorService executor) {
        this.service = service;
        this.server = server;
        this.executor = executor;
    }

    /** Starts on 127.0.0.1. Pass port 0 to let the OS pick a free port (used by tests). */
    public static ExplorerServer start(Path dataDir, int port) throws IOException {
        ExplorerService service = new ExplorerService(dataDir);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        ExplorerServer es = new ExplorerServer(service, http, pool);
        http.createContext("/", es::handle);
        http.setExecutor(pool);
        http.start();
        return es;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public ExplorerService service() {
        return service;
    }

    public void stop() throws IOException {
        server.stop(0);
        executor.shutdownNow();
        service.close();
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        Path dataDir = Paths.get("explorer-data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data-dir" -> dataDir = Paths.get(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("Usage: ExplorerServer [--port 8787] [--data-dir explorer-data]");
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        ExplorerServer es;
        try {
            es = start(dataDir, port);
        } catch (java.net.BindException e) {
            System.err.println("Cannot listen on 127.0.0.1:" + port + " (" + e.getMessage() + "). "
                    + "Is another Explorer backend already running? Use --port to choose another port.");
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                es.stop();
            } catch (IOException e) {
                System.err.println("Error while shutting down: " + e);
            }
        }));
        System.out.println("Storage Explorer API listening on http://127.0.0.1:" + es.port());
        System.out.println("Data directory: " + es.service.dataDir());
    }

    // ------------------------------------------------------------------ request handling

    private void handle(HttpExchange ex) throws IOException {
        int status;
        String body;
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        try {
            guard(ex);
            ex.getRequestBody().readNBytes(1 << 16); // bodies are ignored; parameters travel in the query string
            Object result = route(method, path, query(ex.getRequestURI().getRawQuery()));
            status = method.equals("POST") && isCreate(path) ? 201 : 200;
            body = Json.write(result);
        } catch (ApiException e) {
            status = e.status();
            body = errorJson(e.code(), e.getMessage());
            if (status >= 500) log(method, path, status, e);
        } catch (IOException e) {
            status = 500;
            body = errorJson("ENGINE_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            log(method, path, status, e);
        } catch (RuntimeException e) {
            status = 500;
            body = errorJson("INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            log(method, path, status, e);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static boolean isCreate(String path) {
        return path.equals("/api/databases");
    }

    private static void log(String method, String path, int status, Throwable t) {
        System.err.println("[error] " + method + " " + path + " -> " + status + ": " + t);
        t.printStackTrace(System.err);
    }

    private static String errorJson(String code, String message) {
        return Json.write(Map.of("error", Map.of("code", code, "message", String.valueOf(message))));
    }

    private void guard(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !isLoopbackHost(host)) {
            throw new ApiException(403, "FORBIDDEN_HOST", "Requests must use 127.0.0.1 or localhost as the Host");
        }
        if (!ex.getRequestMethod().equals("GET")
                && (ex.getRequestHeaders().getFirst(CLIENT_HEADER) == null)) {
            throw new ApiException(403, "MISSING_CLIENT_HEADER",
                    "Non-GET requests must send the " + CLIENT_HEADER + " header");
        }
    }

    static boolean isLoopbackHost(String hostHeader) {
        String h = hostHeader.trim().toLowerCase();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(0, end + 1) : h;
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    private Object route(String method, String path, Map<String, String> q) throws IOException {
        String[] seg = path.replaceAll("^/+|/+$", "").split("/");
        if (seg.length < 2 || !seg[0].equals("api")) {
            throw ApiException.notFound("NOT_FOUND", "Unknown path: " + path);
        }
        switch (seg[1]) {
            case "health" -> {
                requireMethod(method, "GET");
                return service.health();
            }
            case "recovery-demo" -> {
                if (method.equals("GET")) return service.recoveryScenarios();
                requireMethod(method, "POST");
                return service.runRecoveryDemo(q.get("scenario"));
            }
            case "databases" -> {
                return routeDatabases(method, seg, q);
            }
            default -> throw ApiException.notFound("NOT_FOUND", "Unknown path: " + path);
        }
    }

    private Object routeDatabases(String method, String[] seg, Map<String, String> q) throws IOException {
        if (seg.length == 2) {
            if (method.equals("GET")) return service.list();
            requireMethod(method, "POST");
            return service.create(q.get("name"),
                    q.containsKey("leafCapacity")
                            ? ExplorerService.parseInt(q.get("leafCapacity"), "leafCapacity")
                            : ExplorerService.DEFAULT_DEMO_LEAF_CAPACITY,
                    q.containsKey("internalCapacity")
                            ? ExplorerService.parseInt(q.get("internalCapacity"), "internalCapacity")
                            : ExplorerService.DEFAULT_DEMO_INTERNAL_CAPACITY);
        }
        String name = seg[2];
        if (seg.length == 3) {
            if (method.equals("GET")) return service.status(name);
            requireMethod(method, "DELETE");
            return service.delete(name, q.get("confirm"));
        }
        String action = seg[3];
        switch (action) {
            case "open" -> {
                requireMethod(method, "POST");
                return service.openDatabase(name);
            }
            case "close" -> {
                requireMethod(method, "POST");
                return service.closeDatabase(name);
            }
            case "insert" -> {
                requireMethod(method, "POST");
                return service.insert(name, ExplorerService.parseInt(q.get("key"), "key"),
                        ExplorerService.parseInt(q.get("value"), "value"));
            }
            case "bulk" -> {
                requireMethod(method, "POST");
                return service.bulk(name,
                        ExplorerService.parseInt(q.get("start"), "start"),
                        ExplorerService.parseInt(q.get("count"), "count"),
                        q.containsKey("step") ? ExplorerService.parseInt(q.get("step"), "step") : 1,
                        q.getOrDefault("order", "ascending"),
                        q.containsKey("seed") ? ExplorerService.parseLong(q.get("seed"), "seed") : 42L,
                        q.containsKey("multiplier") ? ExplorerService.parseInt(q.get("multiplier"), "multiplier") : 10);
            }
            case "search" -> {
                requireMethod(method, "GET");
                return service.search(name, ExplorerService.parseInt(q.get("key"), "key"));
            }
            case "tree" -> {
                requireMethod(method, "GET");
                return service.tree(name);
            }
            case "pages" -> {
                requireMethod(method, "GET");
                if (seg.length == 4) return service.pages(name);
                return service.page(name, ExplorerService.parseLong(seg[4], "pageId"),
                        "true".equalsIgnoreCase(q.get("hex")));
            }
            case "wal" -> {
                requireMethod(method, "GET");
                return service.wal(name,
                        q.containsKey("offset") ? ExplorerService.parseLong(q.get("offset"), "offset") : 0L,
                        q.containsKey("limit") ? ExplorerService.parseInt(q.get("limit"), "limit") : 50);
            }
            default -> throw ApiException.notFound("NOT_FOUND", "Unknown action: " + action);
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Use " + expected + " for this path");
        }
    }
}
