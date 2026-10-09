package com.qingjing.wallpaper.support;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class SupportDtos {
    private SupportDtos() {}
    public enum MessageKind { TEXT, IMAGE, VIDEO }
    public enum LibraryKind { IMAGE, VIDEO, PHRASE }
    public record SendRequest(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientId,
            @NotNull MessageKind kind, @Size(max = 2000) String text,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String attachmentId,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String libraryItemId) {}
    public record ReadRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String messageId) {}
    public record PresenceRequest(@NotNull Boolean active) {}
    public record Attachment(String id, MessageKind kind, String filename, String mimeType, long sizeBytes,
                             Integer width, Integer height, Long durationMs) {}
    public record Message(String id, String conversationId, String clientId, String sender, MessageKind kind,
                          String text, Attachment attachment, Instant createdAt) {}
    public record MessagePage(List<Message> items, String cursor, boolean hasMore) {}
    public record Conversation(String id, String name, boolean online, boolean hidden, long unreadCount, Message lastMessage) {}
    public record ConversationPage(List<Conversation> items, int page, int pageSize, long total) {}
    public record LibraryRequest(@NotNull LibraryKind kind, @NotBlank @Size(max = 60) String title,
                                 @Size(max = 200) String note, @Size(max = 2000) String text,
                                 @Pattern(regexp = "[1-9][0-9]{0,18}") String attachmentId,
                                 @PositiveOrZero Long version) {}
    public record LibraryItem(String id, LibraryKind kind, String title, String note, String text,
                              Attachment attachment, long version, Instant updatedAt) {}
    public record LibraryPage(List<LibraryItem> items, int page, int pageSize, long total) {}
    public record MediaAccess(String url, Instant expiresAt) {}
}
