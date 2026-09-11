package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.redemption.AdminCodeBatchService.CreatedBatch;
import com.qingjing.wallpaper.redemption.AdminCodeBatchService.DeliveryFile;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminCodeBatchDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CodeBatchPage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CreateCodeBatchRequest;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CreateCodeBatchResponse;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.RedemptionCodePage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.RedemptionCodeStatus;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/code-batches")
public class AdminCodeBatchController {

    private final AdminCodeBatchService batches;

    public AdminCodeBatchController(AdminCodeBatchService batches) {
        this.batches = batches;
    }

    @GetMapping
    CodeBatchPage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q) {
        return batches.list(page, pageSize, q);
    }

    @PostMapping
    ResponseEntity<CreateCodeBatchResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateCodeBatchRequest request,
            HttpServletRequest servletRequest) {
        AdminPrincipal admin = (AdminPrincipal) servletRequest.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        CreatedBatch created = batches.create(admin.id(), idempotencyKey, request);
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.response());
    }

    @GetMapping("/{batchId}")
    AdminCodeBatchDetail get(@PathVariable String batchId) {
        return batches.get(Ids.parse(batchId, "batchId"));
    }

    @GetMapping("/{batchId}/codes")
    RedemptionCodePage codes(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) RedemptionCodeStatus status,
            @RequestParam(required = false) String suffix) {
        return batches.listCodes(Ids.parse(batchId, "batchId"), page, pageSize, status, suffix);
    }

    @GetMapping("/{batchId}/delivery")
    ResponseEntity<byte[]> delivery(
            @PathVariable String batchId,
            @RequestHeader("X-Delivery-Ticket") String ticket) {
        DeliveryFile file = batches.delivery(Ids.parse(batchId, "batchId"), ticket);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    @PostMapping("/{batchId}/delivery-confirmation")
    ResponseEntity<Void> confirm(@PathVariable String batchId) {
        batches.confirmDelivery(Ids.parse(batchId, "batchId"));
        return ResponseEntity.noContent().build();
    }
}
