/*
 * lazbounds — read only the 2D bounding box (min/max X and Y) from LAS/LAZ
 * files and print it as a WKT polygon.
 *
 * A LAZ file begins with the same uncompressed LAS public header block as a
 * plain LAS file; only the point records that follow are compressed. The
 * bounding box lives at fixed offsets in that header, so we read just the
 * first 211 bytes of each file — no point data, no decompression.
 *
 * Accepts any mix of: files, directories (walked recursively for *.las/*.laz),
 * and glob patterns (e.g. 'data/ * /tile?.laz').
 *
 * Build:  cc -O2 -o lazbounds lazbounds.c
 * Usage:  lazbounds <file|dir|glob>...
 */

#define _XOPEN_SOURCE 700      /* nftw, S_ISREG, etc. */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdint.h>
#include <ctype.h>
#include <errno.h>
#include <ftw.h>
#include <glob.h>
#include <sys/stat.h>

/* Byte offsets of the bounding-box doubles within the public header block. */
enum {
    OFF_MAX_X = 179,
    OFF_MIN_X = 187,
    OFF_MAX_Y = 195,
    OFF_MIN_Y = 203,
    BYTES_NEEDED = OFF_MIN_Y + 8   /* up to and including Min Y => 211 */
};

/* Read a little-endian IEEE-754 double from a byte buffer at `off`.
 * Assumes a little-endian host (x86-64, arm64); both are LE. */
static double le_double(const unsigned char *buf, int off) {
    double d;
    memcpy(&d, buf + off, sizeof d);
    return d;
}

/* Print a coordinate compactly: integers without a trailing ".0", and a
 * trimmed %.17g otherwise (round-trippable, locale-"C" decimal point). */
static void print_coord(double v) {
    if (v == (double)(long long)v) {
        printf("%lld", (long long)v);
    } else {
        printf("%.17g", v);
    }
}

/* Read the header of one file and print "path; POLYGON ((...))".
 * Returns 0 on success, non-zero on failure (already reported to stderr). */
static int emit_bounds(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "Skipping %s: %s\n", path, strerror(errno));
        return 1;
    }

    unsigned char head[BYTES_NEEDED];
    size_t got = fread(head, 1, BYTES_NEEDED, f);
    fclose(f);

    if (got < BYTES_NEEDED) {
        fprintf(stderr, "Skipping %s: too short to contain a LAS header\n", path);
        return 1;
    }
    if (memcmp(head, "LASF", 4) != 0) {
        fprintf(stderr, "Skipping %s: not a LAS/LAZ file (bad signature)\n", path);
        return 1;
    }

    double minx = le_double(head, OFF_MIN_X);
    double maxx = le_double(head, OFF_MAX_X);
    double miny = le_double(head, OFF_MIN_Y);
    double maxy = le_double(head, OFF_MAX_Y);

    /* POLYGON ((minX minY, maxX minY, maxX maxY, minX maxY, minX minY)) */
    printf("%s; POLYGON ((", path);
    print_coord(minx); putchar(' '); print_coord(miny); fputs(", ", stdout);
    print_coord(maxx); putchar(' '); print_coord(miny); fputs(", ", stdout);
    print_coord(maxx); putchar(' '); print_coord(maxy); fputs(", ", stdout);
    print_coord(minx); putchar(' '); print_coord(maxy); fputs(", ", stdout);
    print_coord(minx); putchar(' '); print_coord(miny);
    fputs("))\n", stdout);
    return 0;
}

static int has_las_laz_ext(const char *path) {
    size_t n = strlen(path);
    return n >= 4 && (strcasecmp(path + n - 4, ".las") == 0 ||
                      strcasecmp(path + n - 4, ".laz") == 0);
}

static int has_glob_char(const char *s) {
    return strpbrk(s, "*?[") != NULL;
}

/* nftw callback: emit bounds for each regular *.las/*.laz file found. */
static int g_walk_failures;          /* nftw has no user-data arg, so use a static */
static int walk_cb(const char *path, const struct stat *sb,
                   int typeflag, struct FTW *ftwbuf) {
    (void)sb; (void)ftwbuf;
    if (typeflag == FTW_F && has_las_laz_ext(path)) {
        g_walk_failures += emit_bounds(path);
    }
    return 0;   /* keep walking */
}

/* Process one CLI argument: file, directory, or glob. Returns failure count. */
static int process_arg(const char *arg) {
    struct stat st;

    if (stat(arg, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            g_walk_failures = 0;
            if (nftw(arg, walk_cb, 16, FTW_PHYS) != 0) {
                fprintf(stderr, "Error walking %s: %s\n", arg, strerror(errno));
                return 1;
            }
            return g_walk_failures;
        }
        if (S_ISREG(st.st_mode)) {
            return emit_bounds(arg);
        }
        fprintf(stderr, "Skipping %s: not a regular file or directory\n", arg);
        return 1;
    }

    if (has_glob_char(arg)) {
        glob_t gl;
        int rc = glob(arg, GLOB_NOSORT, NULL, &gl);
        if (rc == GLOB_NOMATCH) {
            fprintf(stderr, "No matches for glob: %s\n", arg);
            globfree(&gl);
            return 0;
        }
        if (rc != 0) {
            fprintf(stderr, "Glob error for %s\n", arg);
            globfree(&gl);
            return 1;
        }
        int failures = 0;
        for (size_t i = 0; i < gl.gl_pathc; i++) {
            const char *m = gl.gl_pathv[i];
            struct stat ms;
            if (stat(m, &ms) == 0 && S_ISREG(ms.st_mode)) {
                failures += emit_bounds(m);
            }
        }
        globfree(&gl);
        return failures;
    }

    fprintf(stderr, "No such file or directory: %s\n", arg);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <file|dir|glob>...\n", argv[0]);
        fprintf(stderr, "  Each argument may be a LAS/LAZ file, a directory\n");
        fprintf(stderr, "  (searched recursively for *.las/*.laz), or a glob such\n");
        fprintf(stderr, "  as 'data/*/tile?.laz'. Prints one 'path; WKT' line per file.\n");
        return 2;
    }

    int failures = 0;
    for (int i = 1; i < argc; i++) {
        failures += process_arg(argv[i]);
    }
    return failures > 0 ? 1 : 0;
}
