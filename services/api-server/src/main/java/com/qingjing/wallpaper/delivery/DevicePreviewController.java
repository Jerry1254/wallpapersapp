package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DevicePreviewController {
    private final PreviewTicketService previews;
    public DevicePreviewController(PreviewTicketService previews) { this.previews=previews; }
    @PostMapping("/api/v1/device/wallpapers/{wallpaperId}/preview-tickets")
    ResponseEntity<PreviewDtos.PreviewDescriptor> create(@PathVariable String wallpaperId,@Valid @RequestBody PreviewDtos.CreatePreviewTicketRequest body,HttpServletRequest request) {
        return ResponseEntity.status(201).body(previews.create((DevicePrincipal)request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL),Ids.parse(wallpaperId,"wallpaperId"),body));
    }
    @GetMapping("/api/v1/preview/files")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> read(@RequestHeader("Authorization") String authorization) {
        if(!authorization.startsWith("Bearer ") || authorization.length()<=7) throw new ApiException(HttpStatus.UNAUTHORIZED,"PREVIEW_TICKET_INVALID","A preview grant is required");
        var file=previews.read(authorization.substring(7));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.sizeBytes()).header("Cache-Control","no-store")
            .header("Digest","sha-256=:"+java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256()))+":")
            .body(output->file.writer().write(output));
    }
}
