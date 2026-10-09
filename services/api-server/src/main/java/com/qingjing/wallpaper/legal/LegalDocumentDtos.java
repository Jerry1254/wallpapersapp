package com.qingjing.wallpaper.legal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class LegalDocumentDtos {
    private LegalDocumentDtos() {}
    public record Section(@NotBlank @Size(max=200) String title, @NotBlank @Size(max=20000) String body) {}
    public record Content(@NotBlank @Size(max=100) String title,
                          @NotBlank @Size(max=40) String effectiveDate,
                          @NotBlank @Size(max=10000) String introduction,
                          @NotNull @Size(min=1,max=60) List<@NotNull @Valid Section> sections) {}
    public record WriteRequest(@NotNull @Valid Content content, @NotNull Boolean requiresReconsent) {}
    public record PublishedDocument(String key, long revision, long consentRevision, Content content, Instant publishedAt) {}
    public record PublicDocuments(String consentVersion, List<PublishedDocument> items) {}
    public record AdminDocument(String key, long version, Content draft, boolean requiresReconsent,
                                PublishedDocument published) {}
    public record AdminDocuments(List<AdminDocument> items) {}
}
