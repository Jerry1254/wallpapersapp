package com.qingjing.wallpaper.support;

import static com.qingjing.wallpaper.support.SupportDtos.*;
import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {
    private final SupportService support;
    private final SupportMediaService media;
    public AdminSupportController(SupportService support, SupportMediaService media) { this.support = support; this.media = media; }
    @GetMapping("/conversations")
    ConversationPage conversations(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "false") boolean unread,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        return support.conversations(search, unread, page, pageSize);
    }
    @GetMapping("/conversations/{conversationId}")
    Conversation conversation(@PathVariable String conversationId) { return support.conversation(SupportService.id(conversationId), false); }
    @GetMapping("/conversations/{conversationId}/messages")
    MessagePage messages(@PathVariable String conversationId, @RequestParam(required = false) Long after,
                         @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int limit) {
        return support.messages(SupportService.id(conversationId), after, before, limit);
    }
    @GetMapping("/conversations/{conversationId}/messages/by-client/{clientId}")
    Message byClient(@PathVariable String conversationId, @PathVariable String clientId) {
        return support.byClient(SupportService.id(conversationId), "ADMIN", clientId);
    }
    @PostMapping("/conversations/{conversationId}/messages")
    ResponseEntity<Message> send(@PathVariable String conversationId, @Valid @RequestBody SendRequest body) {
        return ResponseEntity.status(201).body(support.send(SupportService.id(conversationId), false, body));
    }
    @PostMapping("/conversations/{conversationId}/read")
    Map<String, Boolean> read(@PathVariable String conversationId, @Valid @RequestBody ReadRequest body) {
        support.markRead(SupportService.id(conversationId), false, SupportService.id(body.messageId())); return Map.of("ok", true);
    }
    @DeleteMapping("/conversations/{conversationId}")
    ResponseEntity<Void> hide(@PathVariable String conversationId) { support.hide(SupportService.id(conversationId)); return ResponseEntity.noContent().build(); }
    @GetMapping("/library")
    LibraryPage library(@RequestParam(required = false) LibraryKind kind, @RequestParam(defaultValue = "") String search,
                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        return support.library(kind, search, page, pageSize);
    }
    @PostMapping("/library")
    ResponseEntity<LibraryItem> createLibrary(@Valid @RequestBody LibraryRequest body) { return ResponseEntity.status(201).body(support.saveLibrary(null, body)); }
    @PutMapping("/library/{itemId}")
    LibraryItem updateLibrary(@PathVariable String itemId, @Valid @RequestBody LibraryRequest body) { return support.saveLibrary(SupportService.id(itemId), body); }
    @DeleteMapping("/library/{itemId}")
    ResponseEntity<Void> deleteLibrary(@PathVariable String itemId, @RequestParam long version) {
        support.deleteLibrary(SupportService.id(itemId), version); return ResponseEntity.noContent().build();
    }
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Attachment> upload(@RequestAttribute(RequestAttributes.ADMIN_PRINCIPAL) AdminPrincipal admin,
                                      @RequestParam MessageKind kind, @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(201).body(support.upload(null, admin.id(), kind, input, file.getOriginalFilename(), file.getContentType()));
        }
    }
    @GetMapping("/attachments/{attachmentId}/access")
    MediaAccess access(@PathVariable String attachmentId) { return media.access(SupportService.id(attachmentId), null); }
}
