package com.qingjing.wallpaper.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;

class SupportMediaTest {
    @TempDir Path directory;
    SupportMediaService media; String token;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        var support = mock(SupportService.class); var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        var tickets = new HashMap<String,String>();
        doAnswer(call -> { tickets.put(call.getArgument(0),call.getArgument(1)); return null; }).when(values).set(anyString(),anyString(),any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> tickets.get(call.getArgument(0)));
        var crypto = mock(SecurityCrypto.class);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(crypto.randomToken(32)).thenReturn(token); when(crypto.sha256Hex(anyString())).thenAnswer(call -> call.getArgument(0));
        var storage = new LocalFileStorage(directory); var file = storage.commit(storage.stage(new ByteArrayInputStream("0123456789".getBytes()),100),"mp4");
        var attachment = new SupportDtos.Attachment("1",SupportDtos.MessageKind.VIDEO,"demo.mp4","video/mp4",10,null,null,1000L);
        when(support.attachmentRecord(1L)).thenReturn(new SupportService.AttachmentRecord(attachment,file.storageKey().value(),null));
        media = new SupportMediaService(support,redis,crypto,new ObjectMapper().findAndRegisterModules(),mock(JdbcTemplate.class),storage);
        media.access(1,null);
    }
    @Test void videoRangesReturnOnlyRequestedBytesAndRejectInvalidRanges() throws Exception {
        var part = media.content(1,token,"bytes=2-5"); var output = new ByteArrayOutputStream(); part.getBody().writeTo(output);
        assertThat(part.getStatusCode().value()).isEqualTo(206); assertThat(part.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 2-5/10");
        assertThat(output.toString()).isEqualTo("2345"); assertThat(part.getHeaders().getCacheControl()).isEqualTo("no-store");
        var suffix = media.content(1,token,"bytes=-3"); output.reset(); suffix.getBody().writeTo(output); assertThat(output.toString()).isEqualTo("789");
        assertThat(media.content(1,token,"bytes=50-100").getStatusCode().value()).isEqualTo(416);
        assertThat(media.content(1,token,"bytes=0-1,5-6").getStatusCode().value()).isEqualTo(416);
    }
    @Test void grantsCannotBeReusedForAnotherFileOrWithAnInvalidToken() {
        assertThatThrownBy(() -> media.content(2,token,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> media.content(1,"invalid",null)).isInstanceOf(ApiException.class);
    }
}
