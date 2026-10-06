package com.qingjing.wallpaper.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/distribution")
public class DistributionController {
    private final DistributionService service;
    DistributionController(DistributionService service) { this.service=service; }
    @GetMapping("/accounts") public Object accounts() { return service.accounts(); }
    @GetMapping("/metrics") public Object metrics() { return service.metrics(); }
    @PutMapping("/accounts/{id}/metrics") public Object metrics(@PathVariable String id,@RequestBody JsonNode n) {service.saveMetrics(id,n);return Map.of("ok",true);}
    @PostMapping("/accounts") public Object account(@RequestBody JsonNode n) { return service.createAccount(n); }
    @PutMapping("/accounts/{id}") public Object account(@PathVariable String id,@RequestBody JsonNode n) { return service.updateAccount(id,n); }
    @DeleteMapping("/accounts/{id}") public Object remove(@PathVariable String id) { service.archiveAccount(id); return Map.of("ok",true); }
    @PostMapping(value="/media",consumes="application/octet-stream") public Object media(@RequestHeader("X-Filename") String filename,HttpServletRequest request) throws IOException {
        return service.upload(URLDecoder.decode(filename,StandardCharsets.UTF_8),request.getInputStream());
    }
    @GetMapping("/media/{id}") public ResponseEntity<StreamingResponseBody> media(@PathVariable String id) {
        var content=service.content(id);
        return ResponseEntity.ok().contentLength(content.sizeBytes()).header("Content-Type","application/octet-stream").body(output->{try(content){content.inputStream().transferTo(output);}});
    }
    @PostMapping("/media/reuse") public Object reuse(@RequestBody JsonNode n) {return service.reuseMedia(n);}
    @GetMapping("/jobs") public Object jobs() { return service.jobs(); }
    @PostMapping("/batches") public Object batch(@RequestBody JsonNode n) { return service.createBatch(n); }
    @PostMapping("/claim") public Object claim(@RequestBody JsonNode n) { return service.claim(n.path("runnerId").asText()); }
    @PostMapping("/jobs/{id}/report") public Object report(@PathVariable String id,@RequestBody JsonNode n) { service.report(id,n);return Map.of("ok",true); }
    @PostMapping("/jobs/{id}/action") public Object action(@PathVariable String id,@RequestBody JsonNode n) { service.action(id,n);return Map.of("ok",true); }
}
