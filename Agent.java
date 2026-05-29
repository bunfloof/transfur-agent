package transfur;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Wire protocol (all integers big-endian, the natural DataOutputStream order):
 *   handshake : the key bytes (default "secretfurry")
 *   header    : long total_files
 *               long total_bytes
 *   per file  : short path_len (unsigned)
 *               <path_len> bytes UTF-8 relative path, '/' separators
 *               long file_size
 *               <file_size> bytes contents
 */
public final class Agent {

    private static final String DEFAULT_KEY = "secretfurry";
    private static final int DEFAULT_PORT = 25565;
    private static final int CHUNK = 64 * 1024;
    private static final long REPORT_EVERY_MS = 5000L;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final String MARKER = ".transfur_complete";

    private static long startTime;
    private static long lastReport;
    private static long bytesSent;
    private static long bytesAtLastReport;
    private static long filesSent;
    private static long totalFiles;
    private static long totalBytes;

    public static void main(String[] args) {
        System.out.println("transfur: migration agent starting");

        Properties cfg = loadConfig();
        String host = cfg.getProperty("host", "").trim();
        int port = parseInt(cfg.getProperty("port"), DEFAULT_PORT);
        String key = cfg.getProperty("key", DEFAULT_KEY);
        List<String> excludes = parseExcludes(cfg.getProperty("exclude", ""));

        if (host.isEmpty()) {
            System.out.println("transfur: ERROR - no destination host configured. Aborting.");
            return;
        }

        Path root = Paths.get("").toAbsolutePath().normalize();
        Path selfJar = locateSelf();

        Path marker = root.resolve(MARKER);
        if (Files.exists(marker)) {
            System.out.println("transfur: this server was already migrated (" + MARKER
                    + " present). Nothing to do.");
            return;
        }

        System.out.println("transfur: scanning " + root);
        long[] totals;
        try {
            totals = countFiles(root, selfJar, excludes);
        } catch (IOException e) {
            System.out.println("transfur: ERROR scanning files: " + e.getMessage());
            return;
        }
        totalFiles = totals[0];
        totalBytes = totals[1];
        System.out.println("transfur: found " + totalFiles + " files, " + human(totalBytes)
                + " to transfer");
        if (totalFiles == 0) {
            System.out.println("transfur: nothing to transfer. Aborting.");
            return;
        }

        Socket sock = null;
        try {
            System.out.println("transfur: connecting to " + host + ":" + port);
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            sock.setTcpNoDelay(true);

            OutputStream raw = sock.getOutputStream();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, CHUNK));

            out.write(key.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(totalFiles);
            out.writeLong(totalBytes);

            startTime = System.currentTimeMillis();
            lastReport = startTime;

            streamFiles(root, selfJar, excludes, out);
            out.flush();

            double secs = Math.max(0.001, (System.currentTimeMillis() - startTime) / 1000.0);
            long avg = (long) (bytesSent / secs);
            System.out.println("transfur: transfer complete: " + filesSent + " files, "
                    + human(bytesSent) + " in " + fmt1(secs) + "s (avg " + human(avg) + "/s)");

            try {
                Files.write(marker, ("migrated " + new Date()).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignore) {

            }
        } catch (IOException e) {
            System.out.println("transfur: ERROR during transfer: " + e.getMessage());
            System.out.println("transfur: if this is a connection failure, the destination "
                    + host + ":" + port + " may be unreachable from this host.");
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (IOException ignore) { }
            }
        }
    }

    private static long[] countFiles(final Path root, final Path selfJar,
                                      final List<String> excludes) throws IOException {
        final long[] acc = new long[2];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !isExcluded(root, file, selfJar, excludes)) {
                    acc[0]++;
                    acc[1] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return acc;
    }

    private static void streamFiles(final Path root, final Path selfJar,
                                    final List<String> excludes,
                                    final DataOutputStream out) throws IOException {
        final byte[] buf = new byte[CHUNK];
        final IOException[] thrown = new IOException[1];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || isExcluded(root, file, selfJar, excludes)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    sendOne(root, file, out, buf);
                } catch (IOException e) {
                    thrown[0] = e;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }

    private static void sendOne(Path root, Path file, DataOutputStream out, byte[] buf)
            throws IOException {
        String rel = toRelativeSlash(root, file);
        byte[] pathBytes = rel.getBytes(StandardCharsets.UTF_8);
        long size = Files.size(file);

        out.writeShort(pathBytes.length & 0xFFFF);
        out.write(pathBytes);
        out.writeLong(size);

        long remaining = size;
        InputStream in = null;
        try {
            in = new BufferedInputStream(Files.newInputStream(file), CHUNK);
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    Arrays.fill(buf, (byte) 0);
                    while (remaining > 0) {
                        int p = (int) Math.min(buf.length, remaining);
                        out.write(buf, 0, p);
                        remaining -= p;
                        bytesSent += p;
                        maybeReport();
                    }
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
                bytesSent += n;
                maybeReport();
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignore) { }
            }
        }
        filesSent++;
    }

    private static boolean isExcluded(Path root, Path file, Path selfJar,
                                      List<String> excludes) {
        Path abs = file.toAbsolutePath().normalize();
        if (selfJar != null && abs.equals(selfJar)) {
            return true;
        }
        String rel = toRelativeSlash(root, file);
        for (int i = 0; i < excludes.size(); i++) {
            String ex = excludes.get(i);
            String prefix = ex.endsWith("/") ? ex : ex + "/";
            if (rel.equals(ex) || rel.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String toRelativeSlash(Path root, Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i).toString());
        }
        return sb.toString();
    }

    private static Path locateSelf() {
        try {
            return Paths.get(Agent.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        InputStream in = Agent.class.getResourceAsStream("/config.properties");
        if (in == null) {
            System.out.println("transfur: WARNING - no embedded config.properties found; "
                    + "using defaults (will fail without a host).");
            return p;
        }
        try {
            p.load(in);
        } catch (IOException e) {
            System.out.println("transfur: WARNING - failed to read embedded config: "
                    + e.getMessage());
        } finally {
            try { in.close(); } catch (IOException ignore) { }
        }
        return p;
    }

    private static void maybeReport() {
        long now = System.currentTimeMillis();
        if (now - lastReport < REPORT_EVERY_MS) {
            return;
        }
        double dt = Math.max(0.001, (now - lastReport) / 1000.0);
        long speed = (long) ((bytesSent - bytesAtLastReport) / dt);
        double pct = totalBytes > 0 ? (bytesSent * 100.0 / totalBytes) : 0.0;
        System.out.println("transfur: progress: " + filesSent + "/" + totalFiles + " files | "
                + human(bytesSent) + " / " + human(totalBytes) + " (" + fmt1(pct) + "%) | "
                + human(speed) + "/s");
        lastReport = now;
        bytesAtLastReport = bytesSent;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> parseExcludes(String s) {
        List<String> list = new ArrayList<String>();
        list.add(MARKER);
        if (s != null && !s.trim().isEmpty()) {
            String[] parts = s.split(",");
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim().replace('\\', '/');
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    private static String human(long bytes) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024.0 && i < u.length - 1) {
            v /= 1024.0;
            i++;
        }
        if (i == 0) {
            return bytes + " " + u[i];
        }
        return fmt1(v) + " " + u[i];
    }

    private static String fmt1(double d) {
        return String.format(java.util.Locale.US, "%.1f", d);
    }

    private Agent() { }
}
