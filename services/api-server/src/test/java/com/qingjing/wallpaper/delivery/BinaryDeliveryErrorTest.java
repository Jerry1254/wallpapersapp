package com.qingjing.wallpaper.delivery;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BinaryDeliveryErrorTest {
    private final DownloadTicketService downloads = mock(DownloadTicketService.class);
    private final PreviewTicketService previews = mock(PreviewTicketService.class);
    private MockMvc mvc;

    @BeforeEach
    void configureHttpBoundary() {
        mvc = MockMvcBuilders.standaloneSetup(
                new DeviceDownloadController(downloads), new DevicePreviewController(previews))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void invalidFormalAndPreviewGrantsReturnJsonEvenWhenClientAcceptsOnlyBinary() throws Exception {
        when(downloads.readProtectedFile("expired")).thenThrow(new ApiException(
                HttpStatus.UNAUTHORIZED, "DOWNLOAD_TICKET_INVALID", "Invalid download grant"));
        when(previews.read("expired")).thenThrow(new ApiException(
                HttpStatus.UNAUTHORIZED, "PREVIEW_TICKET_INVALID", "Invalid preview grant"));
        for (String effect : List.of("delivery", "preview")) {
            mvc.perform(get("/api/v1/" + effect + "/files")
                    .header("Authorization", "Bearer expired").accept(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value(effect.equals("delivery")
                            ? "DOWNLOAD_TICKET_INVALID" : "PREVIEW_TICKET_INVALID"))
                    .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        }
    }

    @Test
    void rateLimitPreservesRetryAfterAndMissingGrantsRemainUnauthorized() throws Exception {
        when(downloads.readProtectedFile("limited")).thenThrow(new ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Retry later", List.of(), 30L));
        mvc.perform(get("/api/v1/delivery/files").header("Authorization", "Bearer limited")
                .accept(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        for (String effect : List.of("delivery", "preview")) {
            mvc.perform(get("/api/v1/" + effect + "/files").accept(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value(effect.equals("delivery")
                            ? "DOWNLOAD_TICKET_INVALID" : "PREVIEW_TICKET_INVALID"));
        }
    }

    @Test
    void successfulFormalAndPreviewStreamsRemainBinary() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        var file = new DownloadTicketService.ProtectedFile(4, "00".repeat(32), output -> output.write(bytes));
        when(downloads.readProtectedFile("valid")).thenReturn(file);
        when(previews.read("valid")).thenReturn(file);
        for (String effect : List.of("delivery", "preview")) {
            var pending = mvc.perform(get("/api/v1/" + effect + "/files")
                    .header("Authorization", "Bearer valid").accept(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(header().string("Content-Length", "4"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().bytes(bytes));
        }
    }
}
