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

    @Test
    void staticImagePreservesPublishedMimeTypeAndDigest() throws Exception {
        byte[] bytes = {9, 8, 7};
        var file = new DownloadTicketService.StaticImageFile(
                bytes.length, "11".repeat(32), "image/webp", output -> output.write(bytes));
        when(downloads.readStaticImageFile("valid-static")).thenReturn(file);

        var pending = mvc.perform(get("/api/v1/delivery/static-image")
                        .header("Authorization", "Bearer valid-static").accept("image/webp"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Digest", "sha-256=:ERERERERERERERERERERERERERERERERERERERERERE=:"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void movingPhotoPreviewStreamsTheTicketBoundMp4() throws Exception {
        byte[] bytes = {5, 6, 7};
        var file = new DownloadTicketService.ProtectedFile(
                bytes.length, "22".repeat(32), output -> output.write(bytes));
        when(previews.readMovingPhotoVideo("valid-preview")).thenReturn(file);

        var pending = mvc.perform(get("/api/v1/preview/moving-photo/video")
                        .header("Authorization", "Bearer valid-preview").accept("video/mp4"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Digest", "sha-256=:IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI=:"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void livePhotoPairUsesOneTicketWithExactAppleMimeTypes() throws Exception {
        byte[] photo = {1, 3, 5};
        byte[] video = {2, 4, 6, 8};
        when(downloads.readLivePhotoFile("live-ticket", DownloadTicketService.LivePhotoPart.PHOTO))
                .thenReturn(new DownloadTicketService.ProtectedFile(
                        photo.length, "33".repeat(32), output -> output.write(photo)));
        when(downloads.readLivePhotoFile("live-ticket", DownloadTicketService.LivePhotoPart.VIDEO))
                .thenReturn(new DownloadTicketService.ProtectedFile(
                        video.length, "44".repeat(32), output -> output.write(video)));

        var imagePending = mvc.perform(get("/api/v1/delivery/live-photo/image")
                        .header("Authorization", "Bearer live-ticket").accept("image/heic"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(imagePending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/heic"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(content().bytes(photo));

        var videoPending = mvc.perform(get("/api/v1/delivery/live-photo/video")
                        .header("Authorization", "Bearer live-ticket").accept("video/quicktime"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(videoPending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/quicktime"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(content().bytes(video));
    }
}
