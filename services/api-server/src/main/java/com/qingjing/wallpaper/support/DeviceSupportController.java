package com.qingjing.wallpaper.support;

import static com.qingjing.wallpaper.support.SupportDtos.*;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/device/support")
public class DeviceSupportController {
    private final SupportService support;
    private final SupportMediaService media;
    public DeviceSupportController(SupportService support, SupportMediaService media) { this.support = support; this.media = media; }
    @GetMapping("/conversation")
    Conversation conversation(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device) {
        return support.conversation(support.forDevice(device), true);
    }
    @GetMapping("/messages")
    MessagePage messages(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device,
                         @RequestParam(required = false) Long after, @RequestParam(required = false) Long before,
                         @RequestParam(defaultValue = "50") int limit) {
        return support.messages(support.forDevice(device), after, before, limit);
    }
    @GetMapping("/messages/by-client/{clientId}")
    Message byClient(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device, @PathVariable String clientId) {
        return support.byClient(support.forDevice(device), "CUSTOMER", clientId);
    }
    @PostMapping("/messages")
    ResponseEntity<Message> send(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device, @Valid @RequestBody SendRequest body) {
        return ResponseEntity.status(201).body(support.send(support.forDevice(device), true, body));
    }
    @PostMapping("/read")
    Map<String, Boolean> read(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device, @Valid @RequestBody ReadRequest body) {
        support.markRead(support.forDevice(device), true, SupportService.id(body.messageId())); return Map.of("ok", true);
    }
    @PostMapping("/presence")
    Map<String, Boolean> presence(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device, @Valid @RequestBody PresenceRequest body) {
        support.presence(device, body.active()); return Map.of("ok", true);
    }
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Attachment> upload(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(201).body(support.upload(support.forDevice(device), null, MessageKind.IMAGE,
                    input, file.getOriginalFilename(), file.getContentType()));
        }
    }
    @GetMapping("/attachments/{attachmentId}/access")
    MediaAccess access(@RequestAttribute(RequestAttributes.DEVICE_PRINCIPAL) DevicePrincipal device, @PathVariable String attachmentId) {
        return media.access(SupportService.id(attachmentId), device);
    }
}
