package com.qingjing.wallpaper.support;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/support/files")
public class SupportFileController {
    private final SupportMediaService media;
    public SupportFileController(SupportMediaService media) { this.media = media; }
    @GetMapping("/{attachmentId}")
    ResponseEntity<StreamingResponseBody> file(@PathVariable String attachmentId, @RequestParam String ticket,
                                               @RequestHeader(value = "Range", required = false) String range) {
        return media.content(SupportService.id(attachmentId), ticket, range);
    }
}
