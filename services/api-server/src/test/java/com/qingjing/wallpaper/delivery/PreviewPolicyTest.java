package com.qingjing.wallpaper.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Payload;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewPolicyTest {
    private final PreviewWatermarkRenderer renderer = mock(PreviewWatermarkRenderer.class);
    private final SecurePackagePublisher publisher = new SecurePackagePublisher(
            null, null, null, null, null, null, null, null, null, renderer);

    @Test void onlyTheFrontmost4dLayerIsMarkedWithoutChangingLayerOrderOrOtherSourceBytes() {
        var front = payload("FOREGROUND", 0, "image/png", "front");
        var second = payload("FOREGROUND", 1, "image/png", "second");
        var background = payload("BACKGROUND", 0, "image/png", "background");
        var config = payload("PARALLAX_CONFIG", 0, "application/json", "configuration");
        var source = List.of(background, front, second, config);
        byte[] originalFront = front.content().clone();
        byte[] marked = bytes("marked-front");
        when(renderer.image(front.content())).thenReturn(marked);

        var preview = publisher.previewPayloads("LAYER_PARALLAX", source, true);

        assertThat(preview).hasSize(4);
        assertThat(preview.get(0)).isSameAs(background);
        assertThat(preview.get(1).role()).isEqualTo("FOREGROUND");
        assertThat(preview.get(1).ordinal()).isZero();
        assertThat(preview.get(1).content()).isEqualTo(marked);
        assertThat(preview.get(2)).isSameAs(second);
        assertThat(preview.get(3)).isSameAs(config);
        assertThat(front.content()).isEqualTo(originalFront);
        verify(renderer).image(front.content());
        verifyNoMoreInteractions(renderer);
    }

    @Test void cleanPolicyKeepsEveryOriginalPayloadAndDoesNotInvokeMediaEncoding() {
        var source = List.of(payload("VIDEO", 0, "video/mp4", "original-video"));
        assertThat(publisher.previewPayloads("VIDEO", source, false)).isSameAs(source);
        verifyNoInteractions(renderer);
    }

    @Test void staticAndVideoPreviewsUseDerivedBytesWhileFormalSourcePayloadsRemainUnchanged() {
        var image = payload("STATIC_IMAGE", 0, "image/jpeg", "original-image");
        var video = payload("VIDEO", 0, "video/mp4", "original-video");
        when(renderer.image(image.content())).thenReturn(bytes("marked-image"));
        when(renderer.video(video.content(), false)).thenReturn(bytes("marked-video"));

        var imagePreview = publisher.previewPayloads("STATIC_IMAGE", List.of(image), true).get(0);
        var videoPreview = publisher.previewPayloads("VIDEO", List.of(video), true).get(0);
        assertThat(imagePreview.mimeType()).isEqualTo("image/png");
        assertThat(imagePreview.content()).isEqualTo(bytes("marked-image"));
        assertThat(videoPreview.mimeType()).isEqualTo("video/mp4");
        assertThat(videoPreview.content()).isEqualTo(bytes("marked-video"));
        assertThat(image.content()).isEqualTo(bytes("original-image"));
        assertThat(video.content()).isEqualTo(bytes("original-video"));
    }

    private static Payload payload(String role, int ordinal, String mime, String content) {
        return new Payload(role, ordinal, mime, bytes(content));
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
