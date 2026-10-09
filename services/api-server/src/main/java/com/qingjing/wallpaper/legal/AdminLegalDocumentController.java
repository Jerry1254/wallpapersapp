package com.qingjing.wallpaper.legal;

import static com.qingjing.wallpaper.legal.LegalDocumentDtos.*;
import com.qingjing.wallpaper.shared.web.EntityTags;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/legal-documents")
public class AdminLegalDocumentController {
    private final LegalDocumentService service;
    public AdminLegalDocumentController(LegalDocumentService service) { this.service=service; }
    @GetMapping public AdminDocuments list() { return service.list(); }
    @PutMapping("/{key}") public ResponseEntity<AdminDocument> save(@PathVariable String key,@RequestHeader("If-Match") String etag,
            @Valid @RequestBody WriteRequest input,HttpServletRequest request) {
        var saved=service.save(key,EntityTags.parseRequired(etag),input);
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,Map.of("documentKey",key,"operation","saveDraft","version",saved.version()));
        return response(saved);
    }
    @PostMapping("/{key}/publish") public ResponseEntity<AdminDocument> publish(@PathVariable String key,@RequestHeader("If-Match") String etag,HttpServletRequest request) {
        var saved=service.publish(key,EntityTags.parseRequired(etag));
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,Map.of("documentKey",key,"operation","publish","revision",saved.published().revision(),"consentRevision",saved.published().consentRevision()));
        return response(saved);
    }
    private ResponseEntity<AdminDocument> response(AdminDocument result) { return ResponseEntity.ok().header(HttpHeaders.ETAG,EntityTags.of(result.version())).body(result); }
}
