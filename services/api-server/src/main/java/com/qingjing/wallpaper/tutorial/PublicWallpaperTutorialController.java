package com.qingjing.wallpaper.tutorial;

import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.PublicTutorialList;

import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/public/wallpaper-tutorials")
public class PublicWallpaperTutorialController {

    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    private final PublicWallpaperTutorialService tutorials;

    PublicWallpaperTutorialController(PublicWallpaperTutorialService tutorials) {
        this.tutorials = tutorials;
    }

    @GetMapping
    PublicTutorialList list() {
        return new PublicTutorialList(tutorials.list());
    }

    @RequestMapping(path = "/{tutorialKey}/video", method = {RequestMethod.GET, RequestMethod.HEAD})
    ResponseEntity<StreamingResponseBody> video(
            @PathVariable String tutorialKey,
            @RequestParam(name = "v", required = false) Long requestedVersion,
            HttpServletRequest request,
            HttpServletResponse servletResponse) throws IOException {
        PublicWallpaperTutorialService.TutorialContent content = tutorials.content(tutorialKey);
        boolean head = RequestMethod.HEAD.name().equals(request.getMethod());
        String etag = '\"' + content.sha256() + '\"';
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        // A mismatched validator requires a complete representation; Range applies only to GET.
        String rangeHeader = head || (ifRange != null && !etag.equals(ifRange))
                ? null : request.getHeader(HttpHeaders.RANGE);
        ByteRange range;
        try {
            range = parseRange(rangeHeader, content.sizeBytes());
        } catch (ApiException exception) {
            servletResponse.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + content.sizeBytes());
            throw exception;
        }
        StoredContent storedContent = tutorials.open(content);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(range.partial()
                        ? HttpStatus.PARTIAL_CONTENT
                        : HttpStatus.OK)
                .contentType(MediaType.valueOf("video/mp4"))
                .contentLength(range.length())
                .cacheControl(requestedVersion == null || requestedVersion != content.version()
                        ? CacheControl.noCache()
                        : CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.ETAG, etag);
        if (range.partial()) {
            response.header(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + content.sizeBytes());
        }
        if (head) {
            storedContent.close();
            return response.body(null);
        }

        StreamingResponseBody body = output -> {
            try (StoredContent source = storedContent) {
                InputStream input = source.inputStream();
                input.skipNBytes(range.start());
                byte[] buffer = new byte[STREAM_BUFFER_BYTES];
                long remaining = range.length();
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new IOException("Tutorial video ended before the declared content length");
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        };
        return response.body(body);
    }

    private ByteRange parseRange(String header, long size) {
        if (size <= 0) {
            throw rangeNotSatisfiable(size);
        }
        if (header == null || header.isBlank()) {
            return new ByteRange(0, size - 1, false);
        }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0) {
            throw rangeNotSatisfiable(size);
        }
        String value = header.substring("bytes=".length()).trim();
        int dash = value.indexOf('-');
        if (dash < 0 || dash != value.lastIndexOf('-')) {
            throw rangeNotSatisfiable(size);
        }
        try {
            String startValue = value.substring(0, dash).trim();
            String endValue = value.substring(dash + 1).trim();
            if (startValue.isEmpty()) {
                long suffixLength = Long.parseLong(endValue);
                if (suffixLength <= 0) {
                    throw rangeNotSatisfiable(size);
                }
                return new ByteRange(Math.max(0, size - suffixLength), size - 1, true);
            }
            long start = Long.parseLong(startValue);
            long end = endValue.isEmpty() ? size - 1 : Math.min(Long.parseLong(endValue), size - 1);
            if (start < 0 || start >= size || end < start) {
                throw rangeNotSatisfiable(size);
            }
            return new ByteRange(start, end, true);
        } catch (NumberFormatException exception) {
            throw rangeNotSatisfiable(size);
        }
    }

    private ApiException rangeNotSatisfiable(long size) {
        return new ApiException(
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                "RANGE_NOT_SATISFIABLE",
                "The requested tutorial video range is invalid");
    }

    private record ByteRange(long start, long end, boolean partial) {
        long length() {
            return end - start + 1;
        }
    }
}
