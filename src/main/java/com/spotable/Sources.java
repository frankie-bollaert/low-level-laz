package com.spotable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Shared resolution of command-line path arguments into a sorted, de-duplicated list of byte-range
 * "sources", for the two binary readers {@link LazBinaryReader} and {@link TifBinaryReader}.
 * <p>
 * An argument may be a local file, a local directory (walked recursively), a local glob, or an
 * {@code s3://} URI (a single object, a {@code prefix/} listing, or a glob over listed keys). The
 * two readers use different concrete {@code Source} types — LAS/LAZ reads a leading byte range as a
 * stream, GeoTIFF needs random access — so the source is built through the supplied factories;
 * everything else (S3 URI parsing, globbing, directory walking, {@code ListObjectsV2} listing,
 * de-duplication and ordering) lives here once.
 * <p>
 * All methods are static; this class is not instantiable.
 */
final class Sources {

    private Sources() {}

    /** A parsed {@code s3://bucket/key} reference. */
    record S3Ref(String bucket, String key) {}

    /**
     * Parses {@code s3://bucket/key} into its bucket and key, or returns {@code null} if {@code uri}
     * is not a well-formed {@code s3://bucket/key} string (missing scheme or no {@code /} after the
     * bucket). The key may be empty (a bare {@code s3://bucket/}).
     */
    static S3Ref parseS3(String uri) {
        if (uri == null || !uri.startsWith("s3://")) return null;
        String rest = uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        return new S3Ref(rest.substring(0, slash), rest.substring(slash + 1));
    }

    /**
     * Resolves {@code args} into a sorted, de-duplicated list of sources. {@code nameFilter} keeps
     * only matching file names when listing a directory or an S3 prefix (globs match the full
     * path/key themselves, so the filter is not applied to them). {@code local} builds a source from
     * a local path and {@code s3Source} from an S3 {@code (bucket, key)}; {@code s3} may be
     * {@code null} when no {@code s3://} argument is present.
     */
    static <S> List<S> collect(String[] args, S3Client s3, Predicate<String> nameFilter,
                               Function<Path, S> local, BiFunction<String, String, S> s3Source)
            throws IOException {
        // TreeMap keeps output deterministic (sorted by label) and drops duplicates.
        Map<String, S> out = new TreeMap<>();
        for (String arg : args) {
            if (arg.startsWith("s3://")) collectS3(arg, s3, nameFilter, s3Source, out);
            else collectLocal(arg, nameFilter, local, out);
        }
        return new ArrayList<>(out.values());
    }

    // ---- Local resolution ----

    private static <S> void collectLocal(String arg, Predicate<String> nameFilter,
                                         Function<Path, S> local, Map<String, S> out) throws IOException {
        Path p = Path.of(arg);
        if (Files.isDirectory(p)) {
            walkLocal(p, q -> true, nameFilter, local, out);
        } else if (Files.isRegularFile(p)) {
            out.put(p.toString(), local.apply(p));
        } else if (hasGlob(arg)) {
            expandLocalGlob(arg, local, out);
        } else {
            System.err.println("No such file or directory: " + arg);
        }
    }

    private static <S> void walkLocal(Path dir, Predicate<Path> pathMatch, Predicate<String> nameFilter,
                                      Function<Path, S> local, Map<String, S> out) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> nameFilter == null || nameFilter.test(p.getFileName().toString()))
                .filter(pathMatch)
                .forEach(p -> out.put(p.toString(), local.apply(p)));
        }
    }

    private static <S> void expandLocalGlob(String pattern, Function<Path, S> local,
                                            Map<String, S> out) throws IOException {
        // Anchor the glob to an absolute path so matching is unambiguous, then walk from the longest
        // leading directory that contains no glob characters. The glob matcher replaces the name
        // filter here, so no extension filtering is applied.
        String abs = Path.of(pattern).toAbsolutePath().normalize().toString();
        int firstGlob = indexOfGlob(abs);
        int sep = abs.lastIndexOf(File.separatorChar, firstGlob);
        Path base = Path.of(sep <= 0 ? File.separator : abs.substring(0, sep));
        if (!Files.isDirectory(base)) {
            System.err.println("No matches for glob: " + pattern);
            return;
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + abs);
        walkLocal(base, matcher::matches, null, local, out);
    }

    // ---- S3 resolution ----

    private static <S> void collectS3(String uri, S3Client s3, Predicate<String> nameFilter,
                                      BiFunction<String, String, S> s3Source, Map<String, S> out) {
        S3Ref ref = parseS3(uri);
        if (ref == null) {
            System.err.println("Malformed S3 URI (expected s3://bucket/key): " + uri);
            return;
        }
        String bucket = ref.bucket(), key = ref.key();
        if (hasGlob(key)) {
            // List under the longest non-glob prefix, then match keys against the glob.
            int g = indexOfGlob(key);
            int lastSlash = key.lastIndexOf('/', g);
            String prefix = lastSlash < 0 ? "" : key.substring(0, lastSlash + 1);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + key);
            listS3(s3, bucket, prefix, k -> matcher.matches(Path.of(k)), s3Source, out);
        } else if (key.isEmpty() || key.endsWith("/")) {
            // Prefix ("directory") listing: every matching name under it.
            listS3(s3, bucket, key, nameFilter, s3Source, out);
        } else {
            // Single object.
            out.put("s3://" + bucket + "/" + key, s3Source.apply(bucket, key));
        }
    }

    private static <S> void listS3(S3Client s3, String bucket, String prefix, Predicate<String> keyMatch,
                                   BiFunction<String, String, S> s3Source, Map<String, S> out) {
        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).build();
        boolean any = false;
        for (S3Object o : s3.listObjectsV2Paginator(req).contents()) {
            String key = o.key();
            if (key.endsWith("/")) continue;        // skip "folder" placeholder keys
            if (keyMatch == null || keyMatch.test(key)) {
                out.put("s3://" + bucket + "/" + key, s3Source.apply(bucket, key));
                any = true;
            }
        }
        if (!any) {
            System.err.println("No matches under s3://" + bucket + "/" + prefix);
        }
    }

    // ---- glob helpers ----

    static boolean hasGlob(String s) {
        return indexOfGlob(s) >= 0;
    }

    private static int indexOfGlob(String s) {
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '*', '?', '[', '{' -> { return i; }
            }
        }
        return -1;
    }
}
