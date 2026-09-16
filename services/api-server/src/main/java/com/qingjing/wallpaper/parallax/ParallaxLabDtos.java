package com.qingjing.wallpaper.parallax;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class ParallaxLabDtos {
    private ParallaxLabDtos() {}
    public record SaveRequest(
            @NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String baseResourceVersionId,
            @NotNull JsonNode config) {}
    public record SaveResult(String resourceVersionId,int versionNo,int configFormatVersion) {}
}
