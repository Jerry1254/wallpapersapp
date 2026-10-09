package com.qingjing.wallpaper.legal;

import static com.qingjing.wallpaper.legal.LegalDocumentDtos.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/legal-documents")
public class LegalDocumentController {
    private final LegalDocumentService service;
    public LegalDocumentController(LegalDocumentService service) { this.service=service; }
    // Only published text; no device session, registration, cookies or credentials required.
    @CrossOrigin(origins={"https://biguo66.top","https://www.biguo66.top"},allowCredentials="false")
    @GetMapping
    public ResponseEntity<PublicDocuments> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.published());
    }
}
