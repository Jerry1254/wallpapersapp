package com.qingjing.wallpaper.appupdate;

/** Single HTTP byte ranges; malformed/multiple/out-of-bounds ranges are consistently rejected. */
record ApkByteRange(long start, long end, boolean partial) {
    long length() { return end - start + 1; }
    static ApkByteRange parse(String header, long size) {
        if (size <= 0) throw new IllegalArgumentException("Empty package");
        if (header == null) return new ApkByteRange(0, size - 1, false);
        if (!header.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException("Invalid byte range");
        String[] pieces = header.substring(6).split("-", -1);
        long start;
        long end;
        if (pieces[0].isEmpty()) {
            long suffix = Long.parseLong(pieces[1]);
            if (suffix <= 0) throw new IllegalArgumentException("Invalid byte range");
            start = Math.max(0, size - suffix);
            end = size - 1;
        } else {
            start = Long.parseLong(pieces[0]);
            end = pieces[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(pieces[1]), size - 1);
        }
        if (start < 0 || start >= size || end < start) throw new IllegalArgumentException("Unsatisfiable byte range");
        return new ApkByteRange(start, end, true);
    }
}
