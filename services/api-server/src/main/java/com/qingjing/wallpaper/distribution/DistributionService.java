package com.qingjing.wallpaper.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DistributionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final FileStorage storage;
    DistributionService(JdbcTemplate db, ObjectMapper json, FileStorage storage) {
        this.db = db; this.json = json; this.storage = storage;
    }
    static ApiException bad(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DISTRIBUTION_INVALID", message); }
    static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "DISTRIBUTION_CONFLICT", message); }
    static String text(JsonNode n, String key, int max, boolean required) {
        String value = n.path(key).asText("").trim();
        if ((required && value.isEmpty()) || value.codePointCount(0, value.length()) > max) throw bad(key + " 长度不符合要求");
        return value;
    }
    static String uuid(String value) {
        try { if (UUID.fromString(value).toString().equals(value)) return value; } catch (RuntimeException ignored) { }
        throw bad("无效的标识");
    }
    private JsonNode decode(String value) {
        try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("Invalid stored distribution payload", e); }
    }
    private Map<String,Object> account(ResultSet r, int row) throws SQLException {
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("id", r.getString("id")); v.put("platform", r.getString("platform"));
        v.put("name", r.getString("display_name")); v.put("group", r.getString("group_name"));
        v.put("runnerId", r.getString("runner_id")); v.put("status", r.getString("login_status"));
        v.put("platformUserId",r.getString("platform_user_id"));v.put("nickname",r.getString("nickname"));
        v.put("avatarUrl",r.getString("avatar_url"));v.put("identityCheckedAt",r.getTimestamp("identity_checked_at"));
        v.put("checkedAt", r.getTimestamp("checked_at")); return v;
    }
    public List<Map<String,Object>> accounts() { return db.query("SELECT * FROM creator_social_account WHERE archived=FALSE ORDER BY created_at", this::account); }
    public List<Map<String,Object>> metrics() {
        return db.query("SELECT m.* FROM creator_account_metrics m JOIN (SELECT account_id,MAX(id) id FROM creator_account_metrics GROUP BY account_id) latest ON latest.id=m.id",(r,i)->Map.of("accountId",r.getString("account_id"),"metrics",decode(r.getString("metrics")),"sourceUrl",r.getString("source_url"),"collectedAt",r.getTimestamp("collected_at")));
    }
    public void saveMetrics(String id,JsonNode n) {
        var account=requireAccount(id);
        String expected=account.get("platform").equals("xhs")?"https://creator.xiaohongshu.com/":"https://creator.douyin.com/";
        String url=text(n,"sourceUrl",1000,true);
        if(!url.startsWith(expected)) throw bad("数据来源不正确");
        JsonNode values=n.path("metrics");
        if(!values.isObject()) throw bad("指标格式不正确");
        var allowed=Set.of("followers","likes","favorites","plays","comments");
        values.fields().forEachRemaining(entry->{
            if(!allowed.contains(entry.getKey()) || !entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToLong() || entry.getValue().asLong()<0) throw bad("指标格式不正确");
        });
        db.update("INSERT INTO creator_account_metrics(account_id,metrics,source_url) VALUES(?,?,?)",id,values.toString(),url);
    }
    Map<String,Object> requireAccount(String id) {
        return db.query("SELECT * FROM creator_social_account WHERE id=? AND archived=FALSE", this::account, uuid(id)).stream().findFirst().orElseThrow(() -> bad("账号不存在"));
    }
    public Map<String,Object> createAccount(JsonNode n) {
        String platform = text(n,"platform",16,true);
        if (!Set.of("xhs","douyin").contains(platform)) throw bad("仅支持小红书和抖音");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO creator_social_account(id,platform,display_name,group_name,runner_id) VALUES(?,?,?,?,?)", id,platform,text(n,"name",80,true),text(n,"group",80,false),uuid(n.path("runnerId").asText()));
        return requireAccount(id);
    }
    @Transactional
    public Map<String,Object> updateAccount(String id, JsonNode n) {
        db.queryForList("SELECT id FROM creator_social_account WHERE id=? FOR UPDATE",uuid(id));
        var account=requireAccount(id);
        if (n.has("name")) db.update("UPDATE creator_social_account SET display_name=?, group_name=? WHERE id=?",text(n,"name",80,true),text(n,"group",80,false),id);
        if (n.has("status")) {
            String status=text(n,"status",24,true);
            if (!Set.of("ready","expired","disconnected","unverified").contains(status)) throw bad("无效的登录状态");
            if(!account.get("runnerId").equals(uuid(n.path("runnerId").asText()))) throw conflict("请在账号原来登录的电脑检查身份");
            if(n.has("identity")) {
                JsonNode identity=n.path("identity");
                String userId=identityId(identity,account.get("platformUserId"));
                String nickname=text(identity,"nickname",160,false),avatar=text(identity,"avatarUrl",2000,false);
                if(!avatar.isEmpty()) {
                    try {var uri=java.net.URI.create(avatar);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();}
                    catch(IllegalArgumentException e){throw bad("头像地址无效");}
                }
                try {db.update("UPDATE creator_social_account SET platform_user_id=?,nickname=?,avatar_url=?,identity_checked_at=CURRENT_TIMESTAMP(3) WHERE id=?",userId,nickname,avatar,id);}
                catch(DuplicateKeyException e){throw conflict("这个平台账号已经添加，请使用已有账号");}
            } else if(status.equals("ready")) throw bad("请先读取并核对平台账号身份");
            db.update("UPDATE creator_social_account SET login_status=?,checked_at=CURRENT_TIMESTAMP(3) WHERE id=? AND runner_id=?",status,id,uuid(n.path("runnerId").asText()));
        }
        return requireAccount(id);
    }
    static String identityId(JsonNode identity,Object existing) {
        String userId=text(identity,"platformUserId",128,true);
        if(!userId.matches("(?:profile|handle):[A-Za-z0-9_.-]+"))throw bad("未读取到可核对的平台账号标识");
        if(existing!=null&&!existing.equals(userId))throw conflict("本次登录与原平台账号不一致，请重新登录原账号；其他账号请单独添加");
        return userId;
    }
    @Transactional
    public void archiveAccount(String id) {
        db.queryForList("SELECT id FROM creator_social_account WHERE id=? FOR UPDATE",uuid(id));
        Long active=db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE account_id=? AND status IN ('running','submitting')",Long.class,id);
        if (active != null && active>0) throw conflict("该账号正在执行任务，结束后再移除");
        db.update("UPDATE creator_publish_job SET status='cancelled', message='账号已移除' WHERE account_id=? AND status='queued'",id);
        db.update("UPDATE creator_social_account SET archived=TRUE,login_status='disconnected' WHERE id=?",id);
    }
    public Map<String,Object> upload(String filename, InputStream source) {
        if (filename.isBlank() || filename.length()>255 || filename.contains("/") || filename.contains("\\")) throw bad("文件名无效");
        String ext=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        boolean image=Set.of("jpg","jpeg","png","webp").contains(ext);
        if (!image && !Set.of("mp4","mov","webm").contains(ext)) throw bad("不支持这个素材格式");
        StagedObject staged=storage.stage(source,image?32L*1024*1024:2L*1024*1024*1024);
        StoredObject saved=null;
        try {
            if (staged.sizeBytes()==0) throw bad("素材为空");
            saved=storage.commit(staged,ext); String id=UUID.randomUUID().toString();
            db.update("INSERT INTO creator_publish_media(id,filename,media_type,extension,storage_key,size_bytes,sha256) VALUES(?,?,?,?,?,?,?)",id,filename,image?"image":"video",ext,saved.storageKey().value(),saved.sizeBytes(),saved.sha256());
            return Map.of("id",id,"filename",filename,"type",image?"image":"video","size",saved.sizeBytes(),"extension",ext);
        } catch (RuntimeException e) { if (saved!=null) storage.delete(saved.storageKey()); throw e; }
        finally { storage.discard(staged); }
    }
    Map<String,Object> media(String id) {
        return db.queryForList("SELECT id,media_type,extension,storage_key,size_bytes FROM creator_publish_media WHERE id=?",uuid(id)).stream().findFirst().orElseThrow(()->bad("素材不存在，请重新上传"));
    }
    public StoredContent content(String id) { return storage.open(new StorageKey((String)media(id).get("storage_key"))); }
    @Transactional
    public Map<String,Object> reuseMedia(JsonNode n) {
        String workspaceId=text(n,"workspaceMediaId",128,true);
        var rows=db.queryForList("SELECT * FROM creator_workspace_media WHERE id=?",workspaceId);
        if(rows.isEmpty())throw bad("作品文件尚未同步到本地后台，请先保存项目");
        var source=rows.get(0);String type=(String)source.get("media_type"),filename=(String)source.get("filename");
        String ext=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!(type.equals("image")&&Set.of("jpg","jpeg","png","webp").contains(ext))&&!(type.equals("video")&&Set.of("mp4","mov","webm").contains(ext)))throw bad("该作品格式暂不支持发布");
        long size=((Number)source.get("size_bytes")).longValue();
        if(size<=0||size>(type.equals("image")?32L:2048L)*1024*1024)throw bad("作品文件大小超过发布范围");
        db.update("INSERT IGNORE INTO creator_publish_media(id,filename,media_type,extension,storage_key,size_bytes,sha256,workspace_media_id) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),filename,type,ext,source.get("storage_key"),size,source.get("sha256"),workspaceId);
        return db.queryForList("SELECT id,filename,media_type AS type,size_bytes AS size,extension FROM creator_publish_media WHERE workspace_media_id=?",workspaceId).get(0);
    }
    // Resolve source names and generation IDs from the saved project, never from client labels.
    ObjectNode sourceSnapshot(JsonNode source,JsonNode post) {
        String projectId=text(source,"projectId",128,true);
        var rows=db.queryForList("SELECT payload FROM creator_workspace_record WHERE collection_name='projects' AND id=? AND deleted_at IS NULL",projectId);
        if(rows.isEmpty())throw bad("来源项目不存在，请先保存项目");
        JsonNode project=decode(rows.get(0).get("payload").toString());
        if(!project.path("deletedAt").asText("").isEmpty())throw bad("来源项目已移入回收站");
        JsonNode ids=source.path("assetIds");
        if(!ids.isArray()||ids.size()!=post.path("mediaIds").size())throw bad("来源素材与发布文件不一致");
        ObjectNode snapshot=json.createObjectNode();snapshot.put("projectId",projectId);snapshot.put("projectName",project.path("name").asText("未命名项目"));
        snapshot.set("assetIds",ids.deepCopy());var assets=snapshot.putArray("assets");
        for(int i=0;i<ids.size();i++) {
            JsonNode asset=null;for(JsonNode item:project.path("media"))if(item.path("id").asText().equals(ids.get(i).asText())){asset=item;break;}
            if(asset==null)throw bad("来源素材不属于这个项目");
            String workspaceId=asset.path("file").path("$creatorFile").asText();
            var linked=db.queryForList("SELECT workspace_media_id FROM creator_publish_media WHERE id=?",uuid(post.path("mediaIds").get(i).asText()));
            if(linked.isEmpty()||!workspaceId.equals(linked.get(0).get("workspace_media_id")))throw bad("来源素材内容已变化，请重新预览发布");
            ObjectNode row=assets.addObject();row.put("assetId",ids.get(i).asText());row.put("name",asset.path("name").asText());
            String workId=asset.path("contentItemId").asText("");
            if(!workId.isEmpty()) {
                JsonNode work=null;for(JsonNode item:project.path("data").path("content").path("items"))if(item.path("id").asText().equals(workId)){work=item;break;}
                if(work==null||!work.path("deletedAt").asText("").isEmpty())throw bad("来源作品已移入回收站，请先恢复作品");
                row.put("workId",workId);row.put("workName",work.path("name").asText());row.put("generationTaskId",work.path("taskId").asText(""));
            }
        }
        return snapshot;
    }
    private void validatePost(String platform,JsonNode n) {
        String type=text(n,"type",8,true);
        if (!Set.of("image","video").contains(type)) throw bad("请选择图文或视频");
        text(n,"title",platform.equals("douyin")&&type.equals("video")?30:20,true);
        String body=text(n,"body",1000,false);
        JsonNode tags=n.path("tags"), files=n.path("mediaIds");
        if (!tags.isArray() || tags.size()>10) throw bad("话题最多 10 个");
        for(JsonNode tag:tags) if(!tag.isTextual() || tag.asText().isBlank() || tag.asText().length()>50 || tag.asText().matches(".*[\\s#].*")) throw bad("话题不能包含空格或 #，单个不超过 50 字");
        int total=body.codePointCount(0,body.length());
        Set<String> uniqueTags=new HashSet<>();
        for(JsonNode tag:tags) {
            if(!uniqueTags.add(tag.asText())) throw bad("话题不能重复");
            total+=tag.asText().codePointCount(0,tag.asText().length())+3;
        }
        if(total>1000) throw bad("正文和话题合计超过当前助手支持的 1000 字");
        if (!files.isArray() || files.isEmpty() || files.size()>(type.equals("video")?1:platform.equals("douyin")?35:18)) throw bad("素材数量超出当前发布助手支持范围");
        for(JsonNode file:files) if (!type.equals(media(file.asText()).get("media_type"))) throw bad("素材类型不匹配");
        for(String cover:List.of("coverId","landscapeCoverId")) if(!n.path(cover).asText("").isEmpty()) {
            if (!type.equals("video") || (!platform.equals("douyin") && cover.equals("landscapeCoverId")) || !media(n.path(cover).asText()).get("media_type").equals("image")) throw bad("封面参数不正确");
        }
        String declaration=n.path("declaration").asText("");
        if (!declaration.isEmpty() && (!platform.equals("douyin") || !type.equals("video") || !Set.of("内容由AI生成","内容为个人观点或见解").contains(declaration))) throw bad("不支持这项内容声明");
    }
    @Transactional
    public List<Map<String,Object>> createBatch(JsonNode n) {
        String batch=uuid(n.path("id").asText());
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(n.toString().getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); }
        db.update("INSERT IGNORE INTO creator_publish_batch(id,request_hash) VALUES(?,?)",batch,hash);
        String old=db.queryForObject("SELECT request_hash FROM creator_publish_batch WHERE id=? FOR UPDATE",String.class,batch);
        if (!hash.equals(old)) throw conflict("同一批次的发布内容发生变化，请重新预览");
        if (db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE batch_id=?",Long.class,batch)>0) return jobsForBatch(batch);
        JsonNode entries=n.path("entries");
        if (!entries.isArray() || entries.isEmpty() || entries.size()>50) throw bad("请选择 1–50 个账号");
        Instant due=Instant.now();
        if (!n.path("scheduledAt").asText("").isEmpty()) {
            try { due=Instant.parse(n.path("scheduledAt").asText()); } catch(Exception e) { throw bad("定时时间无效"); }
            if (due.isBefore(Instant.now())) throw bad("请选择未来的时间");
        }
        Set<String> seen=new HashSet<>();
        for(JsonNode entry:entries) {
            String accountId=uuid(entry.path("accountId").asText());
            if(!seen.add(accountId)) throw bad("账号不能重复");
            db.queryForList("SELECT id FROM creator_social_account WHERE id=? FOR UPDATE",accountId);
            Map<String,Object> account=requireAccount(accountId);
            if(!"ready".equals(account.get("status"))||account.get("platformUserId")==null) throw bad("请先完成账号登录和身份检查");
            validatePost((String)account.get("platform"),entry.path("post"));
            ObjectNode post=(ObjectNode)entry.path("post").deepCopy();
            if(post.has("source"))post.set("source",sourceSnapshot(post.path("source"),post));
            db.update("INSERT INTO creator_publish_job(id,batch_id,account_id,payload,due_at,scheduled) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),batch,accountId,post.toString(),Timestamp.from(due),!n.path("scheduledAt").asText("").isEmpty());
        }
        return jobsForBatch(batch);
    }
    private Map<String,Object> job(ResultSet r,int row) throws SQLException {
        Map<String,Object> v=new LinkedHashMap<>();
        for(String key:List.of("id","status","message")) v.put(key,r.getString(key));
        v.put("accountId",r.getString("account_id"));v.put("accountName",r.getString("display_name"));v.put("platform",r.getString("platform"));
        v.put("platformUserId",r.getString("platform_user_id"));v.put("batchId",r.getString("batch_id"));
        v.put("post",decode(r.getString("payload")));v.put("dueAt",r.getTimestamp("due_at"));v.put("updatedAt",r.getTimestamp("updated_at"));v.put("resultUrl",r.getString("result_url"));
        return v;
    }
    private static final String JOB_QUERY="SELECT j.*,a.display_name,a.platform,a.platform_user_id FROM creator_publish_job j JOIN creator_social_account a ON a.id=j.account_id ";
    public List<Map<String,Object>> jobs() { return db.query(JOB_QUERY+"ORDER BY j.created_at DESC LIMIT 200",this::job); }
    List<Map<String,Object>> jobsForBatch(String batch) { return db.query(JOB_QUERY+"WHERE j.batch_id=? ORDER BY j.created_at",this::job,batch); }
    @Transactional
    public Map<String,Object> claim(String runner) {
        uuid(runner);
        // An interrupted submission is never retried automatically.
        db.update("UPDATE creator_publish_job SET status='uncertain',message='发布助手中断，请到平台核对结果',lease_token=NULL WHERE status IN ('running','submitting') AND heartbeat_at < CURRENT_TIMESTAMP(3) - INTERVAL 2 MINUTE");
        db.update("UPDATE creator_publish_job SET status='needs_input',message='已错过计划开始时间，请确认后点击重试' WHERE status='queued' AND scheduled=TRUE AND due_at < CURRENT_TIMESTAMP(3) - INTERVAL 10 MINUTE");
        List<Map<String,Object>> candidates=db.query("SELECT j.* FROM creator_publish_job j JOIN creator_social_account a ON a.id=j.account_id WHERE j.status='queued' AND j.due_at<=CURRENT_TIMESTAMP(3) AND a.runner_id=? AND a.archived=FALSE AND a.login_status='ready' ORDER BY j.due_at LIMIT 1 FOR UPDATE SKIP LOCKED",(r,i)->Map.of("id",r.getString("id"),"accountId",r.getString("account_id")),runner);
        if (candidates.isEmpty()) return Map.of();
        var candidate=candidates.get(0);
        db.queryForList("SELECT id FROM creator_social_account WHERE id=? FOR UPDATE",candidate.get("accountId"));
        if(db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE account_id=? AND status IN ('running','submitting')",Long.class,candidate.get("accountId"))>0) return Map.of();
        String token=UUID.randomUUID().toString();
        db.update("UPDATE creator_publish_job SET status='running',message='准备素材',lease_token=?,heartbeat_at=CURRENT_TIMESTAMP(3) WHERE id=?",token,candidate.get("id"));
        Map<String,Object> v=db.query(JOB_QUERY+"WHERE j.id=?",this::job,candidate.get("id")).get(0);v.put("leaseToken",token);
        List<Map<String,Object>> files=new ArrayList<>(); JsonNode post=(JsonNode)v.get("post");
        for(JsonNode file:post.path("mediaIds")) { var m=media(file.asText());files.add(Map.of("id",file.asText(),"extension",m.get("extension"))); }
        for(String key:List.of("coverId","landscapeCoverId")) if(!post.path(key).asText("").isEmpty()) {var m=media(post.path(key).asText());files.add(Map.of("id",post.path(key).asText(),"extension",m.get("extension")));}
        v.put("files",files); return v;
    }
    @Transactional
    public void report(String id,JsonNode n) {
        String token=uuid(n.path("leaseToken").asText()), status=text(n,"status",24,true);
        if(!Set.of("running","submitting","submitted","failed","uncertain","needs_input").contains(status)) throw bad("无效的任务状态");
        String url=text(n,"resultUrl",1000,false);
        if(!url.isEmpty() && !url.matches("https://(?:www\\.douyin\\.com|creator\\.douyin\\.com|www\\.xiaohongshu\\.com|creator\\.xiaohongshu\\.com)/[^\\s]*")) throw bad("结果链接不正确");
        List<String> current=db.queryForList("SELECT status FROM creator_publish_job WHERE id=? AND lease_token=? FOR UPDATE",String.class,uuid(id),token);
        if(current.isEmpty()) throw conflict("任务租约已失效，请停止操作");
        if(current.get(0).equals("submitting") && Set.of("running","failed","needs_input").contains(status)) throw conflict("提交后只能报告已提交或结果待核对");
        if(status.equals("submitted") && !current.get(0).equals("submitting")) throw conflict("请先记录提交动作");
        int changed=db.update("UPDATE creator_publish_job SET status=?,message=?,result_url=?,heartbeat_at=CURRENT_TIMESTAMP(3) WHERE id=? AND lease_token=? AND status IN ('running','submitting')",status,text(n,"message",1000,false),url,uuid(id),token);
        if(changed==0) throw conflict("任务租约已失效，请停止操作");
    }
    @Transactional
    public void action(String id,JsonNode n) {
        List<Map<String,Object>> rows=db.queryForList("SELECT status FROM creator_publish_job WHERE id=? FOR UPDATE",uuid(id));
        if(rows.isEmpty()) throw bad("任务不存在");
        String status=(String)rows.get(0).get("status"), action=n.path("action").asText();
        if(action.equals("cancel") && status.equals("queued")) db.update("UPDATE creator_publish_job SET status='cancelled',message='已取消' WHERE id=?",id);
        else if(action.equals("retry") && Set.of("failed","needs_input").contains(status)) db.update("UPDATE creator_publish_job SET status='queued',message='',due_at=CURRENT_TIMESTAMP(3),scheduled=FALSE,lease_token=NULL WHERE id=?",id);
        else if(action.equals("resolve") && Set.of("uncertain","submitted").contains(status) && Set.of("published","failed").contains(n.path("result").asText())) db.update("UPDATE creator_publish_job SET status=?,message='用户已在平台核对' WHERE id=?",n.path("result").asText(),id);
        else throw conflict("当前状态不能执行此操作；结果不确定时请先到平台核对");
    }
}
