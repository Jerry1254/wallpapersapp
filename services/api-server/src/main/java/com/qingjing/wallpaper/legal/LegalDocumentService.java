package com.qingjing.wallpaper.legal;

import static com.qingjing.wallpaper.legal.LegalDocumentDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegalDocumentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LegalDocumentService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional(readOnly=true)
    public PublicDocuments published() {
        var items = rows(false).stream().map(this::publishedView).toList();
        return new PublicDocuments(items.stream().map(i -> i.key()+":"+i.consentRevision())
                .reduce((a,b) -> a+"|"+b).orElseThrow(),items);
    }
    @Transactional(readOnly=true)
    public AdminDocuments list() { return new AdminDocuments(rows(false).stream().map(this::adminView).toList()); }

    @Transactional
    public AdminDocument save(String key,long expectedVersion,WriteRequest input) {
        requireVersion(require(key),expectedVersion);
        String content=encode(input.content());
        if (content.length()>240000 || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>450000) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"LEGAL_DOCUMENT_INVALID","协议内容过长");
        jdbc.update("UPDATE legal_document SET draft_json=?, requires_reconsent=?, lock_version=lock_version+1 WHERE document_key=?",
                content,input.requiresReconsent(),key);
        return get(key);
    }
    @Transactional
    public AdminDocument publish(String key,long expectedVersion) {
        Row row=require(key); requireVersion(row,expectedVersion);
        // Repeated submission of identical content does not create another consent requirement.
        if (row.draft().equals(row.content())) {
            if (row.requiresReconsent()) {
                jdbc.update("UPDATE legal_document SET requires_reconsent=FALSE, lock_version=lock_version+1 WHERE document_key=?",key);
                return get(key);
            }
            return adminView(row);
        }
        long revision=row.revision()+1;
        long consentRevision=row.consentRevision()+(row.requiresReconsent()?1:0);
        Instant now=Instant.now();
        jdbc.update("INSERT INTO legal_document_publication(document_key,revision,consent_revision,content_json,published_at) VALUES(?,?,?,?,?)",
                key,revision,consentRevision,encode(row.draft()),java.sql.Timestamp.from(now));
        jdbc.update("UPDATE legal_document SET published_revision=?, requires_reconsent=FALSE, lock_version=lock_version+1 WHERE document_key=?",
                revision,key);
        return get(key);
    }
    private AdminDocument get(String key) { return rows(false).stream().filter(r->r.key().equals(key)).map(this::adminView).findFirst().orElseThrow(); }
    private Row require(String key) {
        if (!List.of("privacy","terms").contains(key)) throw new ApiException(HttpStatus.NOT_FOUND,"LEGAL_DOCUMENT_NOT_FOUND","协议不存在");
        return rows(true).stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();
    }
    private List<Row> rows(boolean locked) {
        return jdbc.query("""
                SELECT d.document_key,d.draft_json,d.requires_reconsent,d.lock_version,
                       p.revision,p.consent_revision,p.content_json,p.published_at
                FROM legal_document d JOIN legal_document_publication p
                ON p.document_key=d.document_key AND p.revision=d.published_revision
                ORDER BY d.document_key
                """+(locked?" FOR UPDATE":""),this::row);
    }
    private Row row(ResultSet rs,int index) throws SQLException {
        return new Row(rs.getString("document_key"),decode(rs.getString("draft_json")),rs.getBoolean("requires_reconsent"),
                rs.getLong("lock_version"),rs.getLong("revision"),rs.getLong("consent_revision"),
                decode(rs.getString("content_json")),rs.getTimestamp("published_at").toInstant());
    }
    private void requireVersion(Row row,long expected) {
        if (row.version()!=expected) throw new ApiException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","协议已被修改，请刷新后再操作");
    }
    private Content decode(String text) {
        try { return json.readValue(text,Content.class); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored legal document",e); }
    }
    private String encode(Content content) {
        try { return json.writeValueAsString(content); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Unable to encode legal document",e); }
    }
    private PublishedDocument publishedView(Row row) { return new PublishedDocument(row.key(),row.revision(),row.consentRevision(),row.content(),row.publishedAt()); }
    private AdminDocument adminView(Row row) { return new AdminDocument(row.key(),row.version(),row.draft(),row.requiresReconsent(),publishedView(row)); }
    private record Row(String key,Content draft,boolean requiresReconsent,long version,long revision,long consentRevision,Content content,Instant publishedAt) {}
}
