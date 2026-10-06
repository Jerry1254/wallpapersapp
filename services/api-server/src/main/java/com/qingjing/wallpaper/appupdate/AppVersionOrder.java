package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.shared.web.ApiException;
import java.math.BigInteger;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

/** iOS comparisons use the App Store marketing version, never an unpublished build number. */
final class AppVersionOrder {
    private AppVersionOrder() {}
    static String platform(String value) {
        if (!"android".equals(value) && !"ios".equals(value) && !"harmony".equals(value)) {
            throw invalid("platform must be android, ios or harmony");
        }
        return value;
    }
    static String normalize(String value) {
        if (value == null || value.length() > 64 || !value.matches("[0-9]+(?:\\.[0-9]+){0,2}")) {
            throw invalid("A numeric versionName is required");
        }
        String[] parts = Arrays.stream(value.split("\\.")).map(part -> new BigInteger(part).toString()).toArray(String[]::new);
        int length = parts.length;
        while (length > 1 && parts[length - 1].equals("0")) length--;
        return String.join(".", Arrays.copyOf(parts, length));
    }
    static int compare(String platform, String nameA, long codeA, String nameB, long codeB) {
        if (!"ios".equals(platform)) return Long.compare(codeA, codeB);
        String[] a = normalize(nameA).split("\\.");
        String[] b = normalize(nameB).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            BigInteger x = i < a.length ? new BigInteger(a[i]) : BigInteger.ZERO;
            BigInteger y = i < b.length ? new BigInteger(b[i]) : BigInteger.ZERO;
            int compared = x.compareTo(y);
            if (compared != 0) return compared;
        }
        return 0;
    }
    static void code(long value) {
        if (value < 1 || value > 9007199254740991L) throw invalid("A positive versionCode is required");
    }
    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "APP_RELEASE_INVALID", message);
    }
}
