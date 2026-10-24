package com.nimeshkmr.storage.explorer;

import com.nimeshkmr.storage.InsertReport;
import com.nimeshkmr.storage.InternalNode;
import com.nimeshkmr.storage.LeafNode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The operations behind the HTTP API. All state lives in the engine's files inside one data
 * directory; this class only validates input, opens and closes engines, and forwards calls.
 *
 * <p>Only databases created through this service (they have a {@code .explorer.properties} sidecar
 * recording the demo fan-out) can be opened. Names are restricted to a safe pattern so a request
 * can never point the engine at an arbitrary path.
 */
public final class ExplorerService implements AutoCloseable {

    public static final int DEFAULT_DEMO_LEAF_CAPACITY = 4;
    public static final int DEFAULT_DEMO_INTERNAL_CAPACITY = 4;
    public static final int MAX_OPEN_DATABASES = 8;
    public static final int MAX_BULK_COUNT = 2000;
    public static final int PAGE_LIST_LIMIT = 2000;
    public static final int MAX_WAL_PAGE = 500;

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final String META_SUFFIX = ".explorer.properties";

    private final Path dataDir;
    private final Map<String, DemoDatabase> open = new ConcurrentHashMap<>();

    public ExplorerService(Path dataDir) throws IOException {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        Files.createDirectories(this.dataDir);
    }

    public Path dataDir() {
        return dataDir;
    }

    // ------------------------------------------------------------------ validation helpers

    static String validateName(String name) {
        if (name == null || !NAME.matcher(name).matches() || RESERVED.contains(name)) {
            throw ApiException.invalid("Database name must match [a-z0-9][a-z0-9_-]{0,31} and not be a reserved "
                    + "device name; got: " + (name == null ? "(missing)" : "'" + name + "'"));
        }
        return name;
    }

    static int parseInt(String text, String field) {
        if (text == null || text.isBlank()) {
            throw ApiException.invalid("Missing required parameter '" + field + "'");
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw ApiException.invalid("'" + field + "' must be a 32-bit integer; got: '" + text + "'");
        }
    }

    static long parseLong(String text, String field) {
        if (text == null || text.isBlank()) {
            throw ApiException.invalid("Missing required parameter '" + field + "'");
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw ApiException.invalid("'" + field + "' must be an integer; got: '" + text + "'");
        }
    }

    private Path dbPath(String name) {
        return dataDir.resolve(name + ".db");
    }

    private Path walPath(String name) {
        return dataDir.resolve(name + ".wal");
    }

    private Path metaPath(String name) {
        return dataDir.resolve(name + META_SUFFIX);
    }

    private DemoDatabase requireOpen(String name) {
        validateName(name);
        DemoDatabase db = open.get(name);
        if (db == null || db.isClosed()) {
            if (Files.exists(metaPath(name))) {
                throw ApiException.conflict("NOT_OPEN",
                        "Database '" + name + "' exists but is not open. Open it first (this runs recovery).");
            }
            throw ApiException.notFound("DATABASE_NOT_FOUND", "No demo database named '" + name + "'");
        }
        return db;
    }

    // ------------------------------------------------------------------ lifecycle

    public Map<String, Object> health() {
        return Inspector.map(
                "status", "ok",
                "dataDir", dataDir.toString(),
                "pageSize", Inspector.PAGE_SIZE,
                "engineLeafCapacity", LeafNode.DEFAULT_MAX_ENTRIES,
                "engineInternalCapacity", InternalNode.DEFAULT_MAX_KEYS,
                "demoLeafCapacity", DEFAULT_DEMO_LEAF_CAPACITY,
                "demoInternalCapacity", DEFAULT_DEMO_INTERNAL_CAPACITY,
                "minCapacity", LeafNode.MIN_CAPACITY,
                "maxOpenDatabases", MAX_OPEN_DATABASES,
                "maxBulkCount", MAX_BULK_COUNT,
                "openDatabases", new ArrayList<>(open.keySet()));
    }

    public List<Dto.DatabaseStatus> list() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(dataDir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(META_SUFFIX))
                    .map(f -> f.substring(0, f.length() - META_SUFFIX.length()))
                    .filter(n -> NAME.matcher(n).matches())
                    .forEach(names::add);
        }
        Collections.sort(names);
        List<Dto.DatabaseStatus> out = new ArrayList<>();
        for (String n : names) {
            out.add(statusOf(n));
        }
        return out;
    }

    public Dto.DatabaseStatus status(String name) throws IOException {
        validateName(name);
        if (!Files.exists(metaPath(name))) {
            throw ApiException.notFound("DATABASE_NOT_FOUND", "No demo database named '" + name + "'");
        }
        return statusOf(name);
    }

    private Dto.DatabaseStatus statusOf(String name) throws IOException {
        DemoDatabase db = open.get(name);
        if (db != null && !db.isClosed()) {
            return db.use(d -> d.tree.readConsistently((dm, root) -> new Dto.DatabaseStatus(
                    name, "OPEN", Files.size(d.dbPath), d.wal.getLogSize(),
                    d.leafCapacity, d.internalCapacity, root, dm.getNumPages(), d.tree.isRecoveryRequired())));
        }
        int[] caps = readCapacities(name);
        long dbBytes = Files.exists(dbPath(name)) ? Files.size(dbPath(name)) : 0;
        long walBytes = Files.exists(walPath(name)) ? Files.size(walPath(name)) : 0;
        return new Dto.DatabaseStatus(name, "CLOSED", dbBytes, walBytes, caps[0], caps[1], null, null, null);
    }

    private int[] readCapacities(String name) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(metaPath(name), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        try {
            int leaf = Integer.parseInt(p.getProperty("leafCapacity", ""));
            int internal = Integer.parseInt(p.getProperty("internalCapacity", ""));
            return new int[] {leaf, internal};
        } catch (NumberFormatException e) {
            throw new IOException("Sidecar file " + metaPath(name).getFileName() + " is damaged", e);
        }
    }

    public synchronized Dto.DatabaseStatus create(String name, int leafCapacity, int internalCapacity)
            throws IOException {
        validateName(name);
        checkCapacities(leafCapacity, internalCapacity);
        if (Files.exists(dbPath(name)) || Files.exists(walPath(name)) || Files.exists(metaPath(name))) {
            throw ApiException.conflict("ALREADY_EXISTS",
                    "A database named '" + name + "' already exists. Open it, or delete it explicitly first.");
        }
        checkOpenLimit();
        Properties p = new Properties();
        p.setProperty("leafCapacity", Integer.toString(leafCapacity));
        p.setProperty("internalCapacity", Integer.toString(internalCapacity));
        p.setProperty("createdBy", "storage-explorer");
        try (Writer w = Files.newBufferedWriter(metaPath(name), StandardCharsets.UTF_8)) {
            p.store(w, "Demo fan-out for this database. It must not change after creation.");
        }
        try {
            DemoDatabase db = DemoDatabase.open(name, dbPath(name), walPath(name), leafCapacity, internalCapacity);
            open.put(name, db);
        } catch (IOException | RuntimeException e) {
            deleteFiles(name, e); // creation failed: leave nothing half-made behind
            throw e;
        }
        return statusOf(name);
    }

    public synchronized Dto.DatabaseStatus openDatabase(String name) throws IOException {
        validateName(name);
        if (!Files.exists(metaPath(name))) {
            throw ApiException.notFound("DATABASE_NOT_FOUND",
                    "No demo database named '" + name + "' (only databases created by this explorer can be opened)");
        }
        DemoDatabase existing = open.get(name);
        if (existing != null && !existing.isClosed()) {
            throw ApiException.conflict("ALREADY_OPEN", "Database '" + name + "' is already open");
        }
        if (!Files.exists(dbPath(name)) || !Files.exists(walPath(name))) {
            throw ApiException.conflict("FILES_MISSING",
                    "Database '" + name + "' is missing its .db or .wal file; refusing to create a new empty one");
        }
        checkOpenLimit();
        int[] caps = readCapacities(name);
        DemoDatabase db = DemoDatabase.open(name, dbPath(name), walPath(name), caps[0], caps[1]);
        open.put(name, db);
        return statusOf(name);
    }

    public synchronized Dto.DatabaseStatus closeDatabase(String name) throws IOException {
        validateName(name);
        DemoDatabase db = open.remove(name);
        if (db == null) {
            throw ApiException.conflict("NOT_OPEN", "Database '" + name + "' is not open");
        }
        db.close();
        return statusOf(name);
    }

    public synchronized Map<String, Object> delete(String name, String confirm) throws IOException {
        validateName(name);
        if (!name.equals(confirm)) {
            throw ApiException.invalid("Deleting requires confirm=" + name);
        }
        if (!Files.exists(metaPath(name))) {
            throw ApiException.notFound("DATABASE_NOT_FOUND", "No demo database named '" + name + "'");
        }
        DemoDatabase db = open.remove(name);
        if (db != null) db.close();
        List<String> removed = new ArrayList<>();
        for (Path p : new Path[] {dbPath(name), walPath(name), metaPath(name)}) {
            if (Files.deleteIfExists(p)) removed.add(p.getFileName().toString());
        }
        return Inspector.map("deleted", name, "files", removed);
    }

    private void deleteFiles(String name, Throwable primary) {
        for (Path p : new Path[] {dbPath(name), walPath(name), metaPath(name)}) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                primary.addSuppressed(e);
            }
        }
    }

    private void checkOpenLimit() {
        long live = open.values().stream().filter(d -> !d.isClosed()).count();
        if (live >= MAX_OPEN_DATABASES) {
            throw ApiException.conflict("TOO_MANY_OPEN", "At most " + MAX_OPEN_DATABASES + " databases can be open");
        }
    }

    private static void checkCapacities(int leaf, int internal) {
        if (leaf < LeafNode.MIN_CAPACITY || leaf > LeafNode.DEFAULT_MAX_ENTRIES) {
            throw ApiException.invalid("leafCapacity must be between " + LeafNode.MIN_CAPACITY + " and "
                    + LeafNode.DEFAULT_MAX_ENTRIES);
        }
        if (internal < InternalNode.MIN_CAPACITY || internal > InternalNode.DEFAULT_MAX_KEYS) {
            throw ApiException.invalid("internalCapacity must be between " + InternalNode.MIN_CAPACITY + " and "
                    + InternalNode.DEFAULT_MAX_KEYS);
        }
    }

    // ------------------------------------------------------------------ data operations

    public InsertReport insert(String name, int key, int value) throws IOException {
        return requireOpen(name).use(db -> db.tree.insertWithReport(key, value));
    }

    public Dto.BulkResult bulk(String name, int start, int count, int step, String order, long seed, int multiplier)
            throws IOException {
        if (count < 1 || count > MAX_BULK_COUNT) {
            throw ApiException.invalid("count must be between 1 and " + MAX_BULK_COUNT);
        }
        if (step == 0 || Math.abs((long) step) > 1_000_000L) {
            throw ApiException.invalid("step must be non-zero and at most 1,000,000 in magnitude");
        }
        if (!"ascending".equals(order) && !"shuffled".equals(order)) {
            throw ApiException.invalid("order must be 'ascending' or 'shuffled'");
        }
        long last = (long) start + (long) (count - 1) * step;
        if (last > Integer.MAX_VALUE || last < Integer.MIN_VALUE) {
            throw ApiException.invalid("Generated keys would leave the 32-bit integer range");
        }
        int[] keys = new int[count];
        for (int i = 0; i < count; i++) keys[i] = (int) ((long) start + (long) i * step);
        if ("shuffled".equals(order)) {
            Random rnd = new Random(seed);
            for (int i = count - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = keys[i];
                keys[i] = keys[j];
                keys[j] = t;
            }
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            long v = (long) keys[i] * multiplier;
            if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
                throw ApiException.invalid("key * multiplier overflows a 32-bit value at key " + keys[i]);
            }
            values[i] = (int) v;
        }
        return requireOpen(name).use(db -> {
            long t0 = System.currentTimeMillis();
            long rootBefore = db.tree.getRootPageID();
            int inserted = 0, updated = 0, leafSplits = 0, internalSplits = 0, newRoots = 0;
            for (int i = 0; i < count; i++) {
                InsertReport r = db.tree.insertWithReport(keys[i], values[i]);
                if (r.outcome() == InsertReport.Outcome.INSERTED) inserted++; else updated++;
                for (InsertReport.Split s : r.splits()) {
                    if (s.kind() == InsertReport.NodeKind.LEAF) leafSplits++; else internalSplits++;
                    if (s.createdNewRoot()) newRoots++;
                }
            }
            return new Dto.BulkResult(count, inserted, updated, leafSplits, internalSplits, newRoots,
                    rootBefore, db.tree.getRootPageID(), order, System.currentTimeMillis() - t0);
        });
    }

    public Dto.SearchTrace search(String name, int key) throws IOException {
        return requireOpen(name).use(db -> db.tree.readConsistently((dm, root) -> {
            // Same lock hold: the engine's own search result and the descent we trace cannot disagree
            // because of a concurrent insert. A mismatch would indicate a bug and is reported as such.
            Integer engineValue = db.tree.search(key);
            return Inspector.trace(dm, root, key, engineValue, db.tree.getLeafCapacity());
        }));
    }

    public Dto.TreeSnapshot tree(String name) throws IOException {
        return requireOpen(name).use(db -> db.tree.readConsistently((dm, root) ->
                Inspector.snapshot(dm, root, db.tree.getLeafCapacity(), db.tree.getInternalCapacity())));
    }

    public Dto.PageList pages(String name) throws IOException {
        return requireOpen(name).use(db -> db.tree.readConsistently((dm, root) ->
                Inspector.listPages(dm, root, PAGE_LIST_LIMIT)));
    }

    public Dto.PageInfo page(String name, long pageId, boolean hex) throws IOException {
        return requireOpen(name).use(db -> db.tree.readConsistently((dm, root) ->
                Inspector.pageInfo(dm, root, pageId, hex)));
    }

    public Dto.WalView wal(String name, long offset, int limit) throws IOException {
        if (offset < 0) throw ApiException.invalid("offset must not be negative");
        if (limit < 1 || limit > MAX_WAL_PAGE) {
            throw ApiException.invalid("limit must be between 1 and " + MAX_WAL_PAGE);
        }
        // The read lock excludes writers, so no record is half-appended while the log is read.
        return requireOpen(name).use(db -> db.tree.readConsistently((dm, root) ->
                Inspector.walView(db.wal, offset, limit)));
    }

    // ------------------------------------------------------------------ recovery demo

    public List<Map<String, Object>> recoveryScenarios() {
        return RecoveryDemo.describeAll();
    }

    public Map<String, Object> runRecoveryDemo(String scenario) throws IOException {
        return RecoveryDemo.run(scenario);
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (DemoDatabase db : new ArrayList<>(open.values())) {
            try {
                db.close();
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        open.clear();
        if (failure != null) throw failure;
    }

    /** Used by tests to confirm which names are currently open. */
    Set<String> openNames() {
        return new HashSet<>(open.keySet());
    }
}
