package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.delivery.DeliveryDtos.CreateDownloadTicketRequest;
import com.qingjing.wallpaper.delivery.DeliveryDtos.DownloadDescriptor;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qingjing.wallpaper.delivery.DownloadTicketService.MovingPhotoPart;
import com.qingjing.wallpaper.delivery.DownloadTicketService.LivePhotoPart;

@RestController
public class DeviceDownloadController {

    private final DownloadTicketService tickets;

    public DeviceDownloadController(DownloadTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/v1/device/wallpapers/{wallpaperId}/download-tickets")
    ResponseEntity<DownloadDescriptor> create(
            @PathVariable String wallpaperId,
            @Valid @RequestBody CreateDownloadTicketRequest body,
            HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        DownloadDescriptor descriptor = tickets.create(
                principal,
                Ids.parse(wallpaperId, "wallpaperId"),
                body);
        request.setAttribute(RequestAttributes.DEVICE_DOWNLOAD_OUTCOME, descriptor);
        return ResponseEntity.status(201).body(descriptor);
    }

    @GetMapping("/api/v1/delivery/files")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> read(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "DOWNLOAD_TICKET_INVALID", "A download ticket is required");
        }
        var file = tickets.readProtectedFile(authorization.substring(7));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.sizeBytes())
                .header("Cache-Control", "no-store")
                .header("Digest", "sha-256=:" + java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256())) + ":")
                .body(output -> file.writer().write(output));
    }

    @GetMapping("/api/v1/delivery/moving-photo/poster")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> poster(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return movingPhoto(authorization,MovingPhotoPart.POSTER,MediaType.IMAGE_JPEG);
    }

    @GetMapping("/api/v1/delivery/moving-photo/video")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> video(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return movingPhoto(authorization,MovingPhotoPart.VIDEO,MediaType.valueOf("video/mp4"));
    }

    @GetMapping("/api/v1/delivery/static-image")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> staticImage(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        String token=requireTicket(authorization);
        var file=tickets.readStaticImageFile(token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType())).contentLength(file.sizeBytes())
                .header("Cache-Control","no-store")
                .header("Digest","sha-256=:"+java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256()))+":")
                .body(output->file.writer().write(output));
    }

    @GetMapping("/api/v1/delivery/live-photo/image")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> livePhotoImage(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return livePhoto(authorization,LivePhotoPart.PHOTO,MediaType.valueOf("image/heic"));
    }

    @GetMapping("/api/v1/delivery/live-photo/video")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> livePhotoVideo(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return livePhoto(authorization,LivePhotoPart.VIDEO,MediaType.valueOf("video/quicktime"));
    }

    @GetMapping(value="/api/v1/delivery/live-photo/source",produces="video/mp4")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> livePhotoSource(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        var file=tickets.readLivePhotoSourceFile(requireTicket(authorization));
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).contentLength(file.sizeBytes())
                .header("Cache-Control","no-store")
                .header("Digest","sha-256=:"+java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256()))+":")
                .body(output->file.writer().write(output));
    }

    private ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> movingPhoto(
            String authorization,MovingPhotoPart part,MediaType contentType) {
        var file=tickets.readMovingPhotoFile(requireTicket(authorization),part);
        return ResponseEntity.ok().contentType(contentType).contentLength(file.sizeBytes())
                .header("Cache-Control","no-store")
                .header("Digest","sha-256=:"+java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256()))+":")
                .body(output->file.writer().write(output));
    }

    private ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> livePhoto(
            String authorization,LivePhotoPart part,MediaType contentType) {
        var file=tickets.readLivePhotoFile(requireTicket(authorization),part);
        return ResponseEntity.ok().contentType(contentType).contentLength(file.sizeBytes())
                .header("Cache-Control","no-store")
                .header("Digest","sha-256=:"+java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(file.sha256()))+":")
                .body(output->file.writer().write(output));
    }

    private String requireTicket(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,"DOWNLOAD_TICKET_INVALID","A download ticket is required");
        }
        return authorization.substring(7);
    }
}
