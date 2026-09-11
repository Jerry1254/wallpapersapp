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
        return ResponseEntity.status(201).body(tickets.create(
                principal,
                Ids.parse(wallpaperId, "wallpaperId"),
                body));
    }

    @GetMapping("/api/v1/delivery/files")
    ResponseEntity<byte[]> read(@RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "DOWNLOAD_TICKET_INVALID", "A download ticket is required");
        }
        return ResponseEntity.ok(tickets.readProtectedFile(authorization.substring(7)));
    }
}
