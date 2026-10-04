package com.qingjing.wallpaper.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PublicAssetServiceTest {
    private static final long ASSET_ID = 21;
    private static final String PREVIEW_HASH = "d".repeat(64);
    private static final String ORIGINAL_HASH = "a".repeat(64);

    @Test
    void rejectsAnAssetSharedByMarkedAndCleanPublishedWallpapers() {
        var fixture = new Fixture(List.of(31L), true, false);

        assertUnavailable(fixture);
        assertThat(fixture.previewQueries).isZero();
        assertThat(fixture.originalQueries).isZero();
        verifyNoInteractions(fixture.storage);
    }

    @Test
    void rejectsAMarkedCoverAlsoUsedByAPublicCategoryIcon() {
        var fixture = new Fixture(List.of(31L), false, true);

        assertUnavailable(fixture);
        assertThat(fixture.previewQueries).isZero();
        assertThat(fixture.originalQueries).isZero();
        verifyNoInteractions(fixture.storage);
    }

    @Test
    void equivalentMarkedAliasesReturnTheCurrentPreviewOnly() {
        var fixture = new Fixture(List.of(31L, 32L), false, false);

        var content = fixture.service.content(ASSET_ID);

        assertThat(content.storageKey().value()).isEqualTo("preview/31.png");
        assertThat(content.mimeType()).isEqualTo("image/png");
        assertThat(content.sha256()).isEqualTo(PREVIEW_HASH);
        assertThat(fixture.previewQueries).isEqualTo(1);
        assertThat(fixture.originalQueries).isZero();
    }

    @Test
    void cleanAliasesWithoutAMarkedCoverRetainTheirOriginalImage() {
        var fixture = new Fixture(List.of(), true, true);

        var content = fixture.service.content(ASSET_ID);

        assertThat(content.storageKey().value()).isEqualTo("original/21.webp");
        assertThat(content.mimeType()).isEqualTo("image/webp");
        assertThat(content.sha256()).isEqualTo(ORIGINAL_HASH);
        assertThat(fixture.previewQueries).isZero();
        assertThat(fixture.originalQueries).isEqualTo(1);
    }

    @Test
    void anUnfinishedMarkedPreviewNeverFallsBackToTheOriginal() {
        var fixture = new Fixture(List.of(31L), false, false);
        fixture.previewStatus = "PENDING";

        assertThatThrownBy(() -> fixture.service.content(ASSET_ID))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.code()).isEqualTo("PREVIEW_PROCESSING");
                });
        assertThat(fixture.originalQueries).isZero();
        verifyNoInteractions(fixture.storage);
    }

    private static void assertUnavailable(Fixture fixture) {
        assertThatThrownBy(() -> fixture.service.content(ASSET_ID))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("ASSET_NOT_FOUND");
                });
    }

    private static final class Fixture {
        final FileStorage storage = mock(FileStorage.class);
        final PublicAssetService service;
        final List<Long> marked;
        final boolean cleanAlias;
        final boolean iconAlias;
        String previewStatus = "READY";
        int previewQueries;
        int originalQueries;

        Fixture(List<Long> marked, boolean cleanAlias, boolean iconAlias) {
            this.marked = marked;
            this.cleanAlias = cleanAlias;
            this.iconAlias = iconAlias;
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            doAnswer(this::query).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            service = new PublicAssetService(jdbc, storage);
        }

        @SuppressWarnings("unchecked")
        private Object query(InvocationOnMock invocation) throws Exception {
            String sql = invocation.getArgument(0);
            RowMapper<Object> mapper = invocation.getArgument(1);
            Object[] parameters = (Object[]) invocation.getRawArguments()[2];
            if (sql.contains("FROM wallpaper marked")) {
                assertThat(parameters).containsExactly(ASSET_ID);
                List<Object> rows = new ArrayList<>();
                for (long wallpaper : marked) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getLong("id")).thenReturn(wallpaper);
                    when(row.getBoolean("clean_alias")).thenReturn(cleanAlias);
                    when(row.getBoolean("icon_alias")).thenReturn(iconAlias);
                    rows.add(mapper.mapRow(row, rows.size()));
                }
                return rows;
            }
            ResultSet row = mock(ResultSet.class);
            if (sql.contains("LEFT JOIN wallpaper_preview_state")) {
                previewQueries++;
                long wallpaper = (long) parameters[0];
                when(row.getString("status")).thenReturn(previewStatus);
                when(row.getLong("requested_revision")).thenReturn(8L);
                when(row.getLong("generated_revision")).thenReturn(8L);
                when(row.getString("cover_storage_key")).thenReturn("preview/" + wallpaper + ".png");
                when(row.getString("cover_mime_type")).thenReturn("image/png");
                when(row.getLong("cover_size_bytes")).thenReturn(101L);
                when(row.getString("cover_sha256")).thenReturn(PREVIEW_HASH);
            } else if (sql.contains("FROM asset")) {
                originalQueries++;
                assertThat(parameters).containsExactly(ASSET_ID);
                when(row.getString("storage_key")).thenReturn("original/21.webp");
                when(row.getString("mime_type")).thenReturn("image/webp");
                when(row.getLong("size_bytes")).thenReturn(91L);
                when(row.getString("sha256")).thenReturn(ORIGINAL_HASH);
            } else {
                throw new AssertionError("Unexpected public asset query");
            }
            return List.of(mapper.mapRow(row, 0));
        }
    }
}
