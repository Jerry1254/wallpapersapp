package com.qingjing.wallpaper.support;

import static com.qingjing.wallpaper.support.SupportDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class SupportIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("support_test").withUsername("support_test").withPassword(UUID.randomUUID().toString());
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    @TempDir Path directory;
    SupportService support;
    static final DevicePrincipal USER = new DevicePrincipal(291, UUID.randomUUID().toString(), DeviceDtos.DevicePlatform.ANDROID,
            DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
    static final DevicePrincipal OTHER = new DevicePrincipal(327, UUID.randomUUID().toString(), DeviceDtos.DevicePlatform.IOS,
            DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
    byte[] image;
    @BeforeAll static void database() {
        var datasource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(datasource); transactions = new DataSourceTransactionManager(datasource);
        jdbc.update("INSERT INTO admin_account(id,singleton_key,username,password_hash,password_changed_at) VALUES(1,1,'support-test','test-only-not-a-password',UTC_TIMESTAMP(6))");
        for (var device : List.of(USER, OTHER)) jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6))",
                device.deviceId(), UUID.randomUUID().toString(), device.platform().name(), "com.qingjing.bizhi", String.format("%064d", device.deviceId()));
    }
    @BeforeEach void service() throws Exception {
        jdbc.update("DELETE FROM support_message"); jdbc.update("DELETE FROM support_library_item");
        jdbc.update("DELETE FROM support_attachment"); jdbc.update("DELETE FROM support_conversation");
        var storage = new LocalFileStorage(directory);
        var uploads = new AssetUploadService(storage, new AssetContentValidator(storage, new ObjectMapper()), mock(CoverImageOptimizer.class));
        support = new SupportService(jdbc, mock(StringRedisTemplate.class), uploads, storage, transactions);
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", output); image = output.toByteArray();
    }
    static SendRequest text(String value) { return new SendRequest(UUID.randomUUID().toString(), MessageKind.TEXT, value, null, null); }
    Attachment upload(Long conversation) { return support.upload(conversation, conversation == null ? 1L : null, MessageKind.IMAGE,
            new ByteArrayInputStream(image), "screenshot.png", "image/png"); }
    @Test void concurrentRetriesCommitExactlyOneMessageAndRecoverTheLostResponse() throws Exception {
        long conversation = support.forDevice(USER); var request = text("第一次咨询");
        var workers = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Message>>();
            for (int i = 0; i < 16; i++) results.add(workers.submit(() -> support.send(conversation, true, request)));
            var ids = new HashSet<String>();
            for (var result : results) ids.add(result.get(15, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(support.byClient(conversation, "CUSTOMER", request.clientId()).id()).isEqualTo(ids.iterator().next());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_message", Long.class)).isEqualTo(1);
            assertThat(support.conversation(conversation, false).unreadCount()).isEqualTo(1);
            var changed = new SendRequest(request.clientId(), MessageKind.TEXT, "不同内容", null, null);
            assertThatThrownBy(() -> support.send(conversation, true, changed)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        } finally { workers.shutdownNow(); }
    }
    @Test void onlyNewCustomerMessagesRestoreHiddenConversationsAndHistoryRemains() {
        long conversation = support.forDevice(USER);
        assertThat(support.conversations("", false, 1, 50).total()).isZero();
        var request = text("旧消息"); var old = support.send(conversation, true, request);
        support.hide(conversation); support.forDevice(USER); support.markRead(conversation, false, Long.parseLong(old.id()));
        support.send(conversation, true, request); support.send(conversation, false, text("客服迟到回复"));
        assertThat(support.conversations("", false, 1, 50).total()).isZero();
        assertThat(support.messages(conversation, null, null, 50).items()).hasSize(2);
        support.send(conversation, true, text("用户新的咨询"));
        assertThat(support.conversations("用户 0291", true, 1, 50).items()).hasSize(1);
        assertThat(support.conversation(conversation, false).unreadCount()).isEqualTo(1);
    }
    @Test void readingAnOldPositionCannotClearLaterMessagesOrMoveTheSharedCursorBackward() {
        long conversation = support.forDevice(USER);
        var first = support.send(conversation, true, text("消息一"));
        var second = support.send(conversation, true, text("消息二"));
        support.markRead(conversation, false, Long.parseLong(first.id()));
        assertThat(support.conversation(conversation, false).unreadCount()).isEqualTo(1);
        support.markRead(conversation, false, Long.parseLong(second.id()));
        support.markRead(conversation, false, Long.parseLong(first.id()));
        assertThat(support.conversation(conversation, false).unreadCount()).isZero();
        long other = support.forDevice(OTHER);
        assertThatThrownBy(() -> support.markRead(other, false, Long.parseLong(second.id()))).isInstanceOf(ApiException.class);
    }
    @Test void pagingAndReconnectCursorsStayWithinTheConversation() {
        long conversation = support.forDevice(USER), other = support.forDevice(OTHER);
        var first = support.send(conversation, true, text("一")); support.send(other, true, text("另一个用户"));
        var second = support.send(conversation, false, text("二")); var third = support.send(conversation, true, text("三"));
        var recent = support.messages(conversation, null, null, 2);
        assertThat(recent.items()).extracting(Message::id).containsExactly(second.id(), third.id()); assertThat(recent.hasMore()).isTrue();
        assertThat(support.messages(conversation, null, Long.parseLong(recent.cursor()), 2).items()).extracting(Message::id).containsExactly(first.id());
        var initial = support.messages(conversation, 0L, null, 2);
        assertThat(initial.items()).extracting(Message::id).containsExactly(first.id(), second.id()); assertThat(initial.hasMore()).isTrue();
        assertThat(support.messages(conversation, Long.parseLong(initial.cursor()), null, 2).items()).extracting(Message::id).containsExactly(third.id());
    }
    @Test void privateAttachmentsCannotBeReadOrSentAcrossConversations() {
        long conversation = support.forDevice(USER), other = support.forDevice(OTHER); var attachment = upload(conversation);
        assertThat(support.authorizedAttachment(Long.parseLong(attachment.id()), conversation).view()).isEqualTo(attachment);
        assertThatThrownBy(() -> support.authorizedAttachment(Long.parseLong(attachment.id()), other)).isInstanceOf(ApiException.class);
        var request = new SendRequest(UUID.randomUUID().toString(), MessageKind.IMAGE, null, attachment.id(), null);
        assertThatThrownBy(() -> support.send(other, true, request)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> support.send(other, false, request)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> support.saveLibrary(null, new LibraryRequest(LibraryKind.IMAGE, "私人截图", "", null, attachment.id(), null))).isInstanceOf(ApiException.class);
    }
    @Test void libraryReplacementAndDeletionPreserveHistoryAndRejectStaleSelections() {
        long conversation = support.forDevice(USER); var oldFile = upload(null);
        var item = support.saveLibrary(null, new LibraryRequest(LibraryKind.IMAGE, "教程", "", null, oldFile.id(), null));
        var first = support.send(conversation, false, new SendRequest(UUID.randomUUID().toString(), MessageKind.IMAGE, null, oldFile.id(), item.id()));
        var newFile = upload(null);
        var edited = support.saveLibrary(Long.parseLong(item.id()), new LibraryRequest(LibraryKind.IMAGE, "教程更新", "说明", null, newFile.id(), item.version()));
        assertThat(edited.version()).isEqualTo(1);
        assertThatThrownBy(() -> support.saveLibrary(Long.parseLong(item.id()), new LibraryRequest(LibraryKind.IMAGE, "覆盖", "", null, newFile.id(), item.version())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("VERSION_CONFLICT"));
        support.deleteLibrary(Long.parseLong(item.id()), edited.version());
        assertThat(support.library(null, "", 1, 50).items()).isEmpty();
        assertThat(support.messages(conversation, null, null, 50).items().get(0).attachment().id()).isEqualTo(first.attachment().id());
        assertThat(support.authorizedAttachment(Long.parseLong(oldFile.id()), conversation).view()).isEqualTo(oldFile);
        assertThatThrownBy(() -> support.send(conversation, false, new SendRequest(UUID.randomUUID().toString(), MessageKind.IMAGE, null, newFile.id(), item.id()))).isInstanceOf(ApiException.class);
        assertThat(support.send(conversation, false, new SendRequest(first.clientId(), MessageKind.IMAGE, null, oldFile.id(), item.id())).id()).isEqualTo(first.id());
    }
    @Test void concurrentLibraryEditsCannotOverwriteTheFirstCommittedVersion() throws Exception {
        var attachment = upload(null);
        var item = support.saveLibrary(null,new LibraryRequest(LibraryKind.IMAGE,"原图","",null,attachment.id(),null));
        var workers = Executors.newFixedThreadPool(8);
        try {
            var attempts = new ArrayList<Future<Boolean>>();
            for (int i=0;i<8;i++) {
                final int index=i;
                attempts.add(workers.submit(() -> {
                    try { support.saveLibrary(Long.parseLong(item.id()),new LibraryRequest(LibraryKind.IMAGE,"编辑"+index,"",null,attachment.id(),item.version())); return true; }
                    catch(ApiException failure) { assertThat(failure.code()).isEqualTo("VERSION_CONFLICT"); return false; }
                }));
            }
            int saved=0; for(var attempt:attempts) if(attempt.get(15,TimeUnit.SECONDS)) saved++;
            assertThat(saved).isEqualTo(1); assertThat(support.library(null,"",1,50).items().get(0).version()).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }
}
