package com.qingjing.wallpaper.creator;

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
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creator records use the same business database and storage boundary as the catalog. */
@Service
public class CreatorWorkspaceService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final FileStorage storage;
    private static final Set<String> COLLECTIONS=Set.of("projects","templates","favorites","works","meta");
    CreatorWorkspaceService(JdbcTemplate db,ObjectMapper json,FileStorage storage){this.db=db;this.json=json;this.storage=storage;}
    static ApiException invalid(String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"CREATOR_INVALID",message);}
    static String id(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw invalid("无效的创作数据标识");return value;}
    static String uuid(String value){try{UUID.fromString(value);if(value.length()!=36)throw new IllegalArgumentException();return value;}catch(RuntimeException e){throw invalid("任务或处理器标识需为 UUID");}}
    static String collection(String value){if(!COLLECTIONS.contains(value))throw invalid("无效的数据集合");return value;}
    JsonNode decode(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid creator payload",e);}}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> records(String collection,String after,int limit){
        if(limit<1||limit>25)throw invalid("分页数量需为 1–25");
        if(!after.isEmpty())id(after);
        collection(collection);
        // Only sort lightweight IDs. Sorting SELECT * can put multi-megabyte
        // JSON snapshots into MySQL's sort buffer and fail even on a one-row page.
        var ids=db.queryForList("SELECT id FROM creator_workspace_record WHERE collection_name=? AND id>? ORDER BY id LIMIT ?",String.class,collection,after,limit);
        var rows=ids.stream().map(key->record(collection,key)).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);
        result.put("nextCursor",ids.size()==limit?ids.get(ids.size()-1):null);return result;
    }
    private Map<String,Object> record(ResultSet r,int row)throws SQLException{return Map.of("record",decode(r.getString("payload")),"version",r.getLong("lock_version"));}
    public Map<String,Object> record(String collection,String id){return db.query("SELECT * FROM creator_workspace_record WHERE collection_name=? AND id=?",this::record,collection(collection),id(id)).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","创作记录不存在"));}
    @Transactional
    public Map<String,Object> save(String collection,String id,long expected,JsonNode n){
        collection(collection);id(id);JsonNode record=n.path("record"),refs=n.path("mediaIds");
        if(!record.isObject()||!id.equals(record.path("id").asText())||!refs.isArray()||refs.size()>5000)throw invalid("创作快照或文件清单无效");
        String payload=record.toString();if(payload.getBytes(StandardCharsets.UTF_8).length>10*1024*1024)throw invalid("创作快照超过 10 MiB");
        String name=record.path("name").asText("");if(!collection.equals("meta")&&(name.isBlank()||name.length()>80))throw invalid("名称需为 1–80 个字符");
        Set<String> unique=new HashSet<>();for(JsonNode ref:refs){String key=id(ref.asText());if(!unique.add(key))continue;if(db.queryForObject("SELECT COUNT(*) FROM creator_workspace_media WHERE id=?",Long.class,key)==0)throw invalid("素材文件尚未保存："+key);}
        String fingerprint=hash(payload+"\n"+new TreeSet<>(unique));
        // Insert a placeholder to serialize simultaneous first saves of the same client ID.
        db.update("INSERT IGNORE INTO creator_workspace_record(collection_name,id,payload,payload_sha256,lock_version) VALUES(?,?,?, ?,0)",collection,id,"{}","");
        var row=db.queryForMap("SELECT lock_version,payload_sha256 FROM creator_workspace_record WHERE collection_name=? AND id=? FOR UPDATE",collection,id);
        long version=((Number)row.get("lock_version")).longValue();
        if(fingerprint.equals(row.get("payload_sha256")))return record(collection,id);
        if(version!=expected)throw new ApiException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","这份创作数据已在其他页面更新，请重新打开后合并修改");
        java.sql.Timestamp deleted=null;if(!record.path("deletedAt").isMissingNode()&&!record.path("deletedAt").isNull()){try{deleted=java.sql.Timestamp.from(Instant.parse(record.path("deletedAt").asText()));}catch(Exception e){throw invalid("回收时间无效");}}
        db.update("UPDATE creator_workspace_record SET payload=?,payload_sha256=?,lock_version=lock_version+1,deleted_at=?,updated_at=CURRENT_TIMESTAMP(3) WHERE collection_name=? AND id=?",payload,fingerprint,deleted,collection,id);
        db.update("DELETE FROM creator_workspace_reference WHERE collection_name=? AND record_id=?",collection,id);
        for(String ref:unique)db.update("INSERT INTO creator_workspace_reference(collection_name,record_id,media_id) VALUES(?,?,?)",collection,id,ref);
        return record(collection,id);
    }
    @Transactional
    public void remove(String collection,String id,long expected){
        var old=record(collection,id);ObjectNode record=((JsonNode)old.get("record")).deepCopy();record.put("deletedAt",Instant.now().toString());
        List<String> refs=db.queryForList("SELECT media_id FROM creator_workspace_reference WHERE collection_name=? AND record_id=?",String.class,collection,id);
        ObjectNode body=json.createObjectNode().set("record",record);body.set("mediaIds",json.valueToTree(refs));save(collection,id,expected,body);
    }
    public Map<String,Object> media(String id){
        return db.query("SELECT * FROM creator_workspace_media WHERE id=?",(r,i)->{
            Map<String,Object> result=new LinkedHashMap<>();result.put("id",r.getString("id"));result.put("filename",r.getString("filename"));result.put("type",r.getString("media_type"));result.put("mimeType",r.getString("mime_type"));result.put("sizeBytes",r.getLong("size_bytes"));result.put("sha256",r.getString("sha256"));result.put("metadata",decode(r.getString("metadata")));return result;
        },id(id)).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"MEDIA_MISSING","素材文件不存在"));
    }
    public StoredContent content(String id){String key=db.queryForList("SELECT storage_key FROM creator_workspace_media WHERE id=?",String.class,id(id)).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"MEDIA_MISSING","素材文件不存在"));return storage.open(new StorageKey(key));}
    public Map<String,Object> upload(String id,String filename,JsonNode metadata,InputStream source){
        id(id);if(metadata==null||!metadata.isObject())throw invalid("素材元数据无效");if(filename==null||filename.isBlank()||filename.length()>255||filename.contains("/")||filename.contains("\\"))throw invalid("文件名无效");
        String type=metadata.path("type").asText();String extension=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        Map<String,Set<String>> types=Map.of("image",Set.of("png","jpg","jpeg","webp"),"video",Set.of("mp4","mov","webm"),"audio",Set.of("mp3","m4a","wav","aac","ogg","flac"),"4d",Set.of("zip"),"bundle",Set.of("zip"));
        if(!types.containsKey(type)||!types.get(type).contains(extension))throw invalid("不支持的创作文件格式");
        long limit=type.equals("image")?50L*1024*1024:type.equals("4d")?100L*1024*1024:512L*1024*1024;
        StagedObject staged=storage.stage(source,limit);StoredObject saved=null;boolean registered=false;
        try{
            if(staged.sizeBytes()==0)throw invalid("素材文件为空");
            var old=db.queryForList("SELECT sha256,media_type FROM creator_workspace_media WHERE id=?",id);
            if(!old.isEmpty()){if(!staged.sha256().equals(old.get(0).get("sha256"))||!type.equals(old.get(0).get("media_type")))throw new ApiException(HttpStatus.CONFLICT,"MEDIA_ID_CONFLICT","素材标识已用于另一份文件");return media(id);}
            saved=storage.commit(staged,extension);
            String mime=switch(extension){case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";case "mp4"->"video/mp4";case "mov"->"video/quicktime";case "webm"->"video/webm";case "mp3"->"audio/mpeg";case "m4a"->"audio/mp4";case "wav"->"audio/wav";case "aac"->"audio/aac";case "ogg"->"audio/ogg";case "flac"->"audio/flac";default->"application/zip";};
            int inserted=db.update("INSERT IGNORE INTO creator_workspace_media(id,filename,media_type,mime_type,storage_key,size_bytes,sha256,metadata) VALUES(?,?,?,?,?,?,?,?)",id,filename,type,mime,saved.storageKey().value(),saved.sizeBytes(),saved.sha256(),metadata.toString());
            if(inserted==0){storage.delete(saved.storageKey());saved=null;var result=media(id);if(!staged.sha256().equals(result.get("sha256"))||!type.equals(result.get("type")))throw new ApiException(HttpStatus.CONFLICT,"MEDIA_ID_CONFLICT","素材标识已用于另一份文件");return result;}
            registered=true;return media(id);
        }catch(RuntimeException e){if(saved!=null&&!registered)storage.delete(saved.storageKey());throw e;}finally{storage.discard(staged);}
    }
    private Map<String,Object> mapTask(ResultSet r,int ignored)throws SQLException{
        Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","state","stage","project_id","task_type","input_hash"))result.put(switch(key){case "project_id"->"projectId";case "task_type"->"type";case "input_hash"->"inputHash";default->key;},r.getString(key));
        result.put("input",decode(r.getString("input_snapshot")));result.put("progress",r.getBigDecimal("progress"));result.put("payloadMediaId",r.getString("payload_media_id"));result.put("outputMediaIds",decode(r.getString("output_media_ids")));result.put("attempt",r.getInt("attempt"));result.put("error",r.getString("error_message"));result.put("leaseToken",r.getString("lease_token"));result.put("updatedAt",r.getTimestamp("updated_at"));return result;
    }
    public Map<String,Object> task(String id){return db.query("SELECT * FROM creator_workspace_task WHERE id=?",this::mapTask,uuid(id)).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","生成任务不存在"));}
    public List<Map<String,Object>> tasks(){var rows=db.query("SELECT * FROM creator_workspace_task ORDER BY created_at DESC LIMIT 100",this::mapTask);for(var row:rows){JsonNode input=(JsonNode)row.remove("input");row.put("name",input.path("name").asText(""));row.remove("leaseToken");}return rows;}
    @Transactional
    public Map<String,Object> createTask(JsonNode n){
        String id=uuid(n.path("id").asText()),project=id(n.path("projectId").asText()),type=n.path("type").asText();JsonNode input=n.path("input");
        if(!Set.of("WALLPAPER_RENDER","CONTENT_RENDER","GALLERY_RENDER").contains(type)||!input.isObject()||input.toString().length()>10*1024*1024)throw invalid("生成任务参数无效");
        if(db.queryForList("SELECT id FROM creator_workspace_record WHERE collection_name='projects' AND id=? AND deleted_at IS NULL FOR UPDATE",String.class,project).isEmpty())throw invalid("请先保存项目后再生成");
        String hash=hash(input.toString());
        var existing=db.query("SELECT * FROM creator_workspace_task WHERE project_id=? AND task_type=? AND input_hash=? AND state IN ('SUCCEEDED','QUEUED','RUNNING','PREPARING') ORDER BY created_at DESC LIMIT 1",this::mapTask,project,type,hash);
        if(!existing.isEmpty())return existing.get(0);
        db.update("INSERT IGNORE INTO creator_workspace_task(id,project_id,task_type,input_hash,input_snapshot,output_media_ids) VALUES(?,?,?,?,?,'[]')",id,project,type,hash,input.toString());
        var result=task(id);if(!hash.equals(result.get("inputHash"))||!project.equals(result.get("projectId"))||!type.equals(result.get("type")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一任务标识的生成参数发生变化");return result;
    }
    @Transactional
    public Map<String,Object> action(String id,JsonNode n){
        db.queryForList("SELECT id FROM creator_workspace_task WHERE id=? FOR UPDATE",id(id));var task=task(id);String action=n.path("action").asText(),state=(String)task.get("state");
        if(action.equals("cancel")){if(state.equals("RUNNING"))db.update("UPDATE creator_workspace_task SET state='CANCEL_REQUESTED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);else if(Set.of("PREPARING","QUEUED","INTERRUPTED","FAILED").contains(state))db.update("UPDATE creator_workspace_task SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);}
        else if(action.equals("enqueue")){if(typeIsGallery(task))throw invalid("图片任务应在画面生成后完成");if(!Set.of("PREPARING","FAILED","INTERRUPTED").contains(state))return task;String payload=id(n.path("payloadMediaId").asText());if(!media(payload).get("type").equals("bundle"))throw invalid("生成任务缺少素材包");db.update("UPDATE creator_workspace_task SET payload_media_id=?,state='QUEUED',error_message=NULL,stage='等待本地编码',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",payload,id);}
        else if(action.equals("retry")){if(!Set.of("FAILED","INTERRUPTED").contains(state)||task.get("payloadMediaId")==null)throw invalid("此任务需要重新打开编辑页面生成");db.update("UPDATE creator_workspace_task SET state='QUEUED',error_message=NULL,progress=NULL,stage='等待重试',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);}
        else if(action.equals("complete")){if(!typeIsGallery(task)||!state.equals("PREPARING"))throw invalid("此任务不能由编辑页面完成");JsonNode outputs=n.path("outputMediaIds");if(!outputs.isArray()||outputs.isEmpty()||outputs.size()>100)throw invalid("图片输出清单无效");for(JsonNode output:outputs)if(!media(output.asText()).get("type").equals("image"))throw invalid("图片任务输出类型无效");db.update("UPDATE creator_workspace_task SET state='SUCCEEDED',output_media_ids=?,progress=100,stage='图片已生成',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",outputs.toString(),id);}
        else if(action.equals("fail")){if(!state.equals("PREPARING"))return task;String message=n.path("message").asText("画面准备中断");db.update("UPDATE creator_workspace_task SET state='FAILED',error_message=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",message.substring(0,Math.min(1000,message.length())),id);}
        else throw invalid("任务操作无效");return task(id);
    }
    private boolean typeIsGallery(Map<String,Object> task){return "GALLERY_RENDER".equals(task.get("type"));}
    @Transactional
    public Map<String,Object> claim(String runner){
        uuid(runner);db.update("UPDATE creator_workspace_task SET state=CASE WHEN state='CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'INTERRUPTED' END,lease_token=NULL,error_message='本地处理连接中断，可重试',updated_at=CURRENT_TIMESTAMP(3) WHERE state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until<CURRENT_TIMESTAMP(3)");
        var next=db.queryForList("SELECT id FROM creator_workspace_task WHERE state='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");if(next.isEmpty())return Map.of();String id=(String)next.get(0).get("id");
        db.update("UPDATE creator_workspace_task SET state='RUNNING',runner_id=?,lease_token=?,lease_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(3)),attempt=attempt+1,stage='正在读取素材',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",runner,UUID.randomUUID().toString(),id);return task(id);
    }
    @Transactional
    public Map<String,Object> report(String id,JsonNode n){
        db.queryForList("SELECT id FROM creator_workspace_task WHERE id=? FOR UPDATE",id(id));var task=task(id);String state=(String)task.get("state");
        var valid=db.queryForObject("SELECT COUNT(*) FROM creator_workspace_task WHERE id=? AND lease_token=? AND runner_id=? AND attempt=? AND lease_until>=CURRENT_TIMESTAMP(3) AND state IN ('RUNNING','CANCEL_REQUESTED')",Long.class,id,n.path("leaseToken").asText(),n.path("runnerId").asText(),n.path("attempt").asInt(-1));
        if(valid==null||valid==0)throw new ApiException(HttpStatus.CONFLICT,"TASK_LEASE_EXPIRED","生成任务租约已过期");
        String next=n.path("state").asText("RUNNING");if(!Set.of("RUNNING","SUCCEEDED","FAILED","CANCELLED").contains(next))throw invalid("任务状态无效");
        if(state.equals("CANCEL_REQUESTED"))next=next.equals("RUNNING")?state:"CANCELLED";
        JsonNode outputs=n.path("outputMediaIds");if(next.equals("SUCCEEDED")){if(!outputs.isArray()||outputs.size()!=1||!media(outputs.get(0).asText()).get("type").equals("video"))throw invalid("视频任务输出无效");}
        String stage=n.path("stage").asText((String)task.get("stage"));if(stage.length()>255)stage=stage.substring(0,255);String error=n.path("error").asText("");if(error.length()>1000)error=error.substring(0,1000);
        Double progress=n.path("progress").isNumber()?n.path("progress").asDouble():null;if(progress!=null&&(!Double.isFinite(progress)||progress<0||progress>100))throw invalid("进度无效");
        db.update("UPDATE creator_workspace_task SET state=?,stage=?,progress=?,error_message=?,output_media_ids=?,lease_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",next,stage,progress,error.isBlank()?null:error,next.equals("SUCCEEDED")?outputs.toString():((JsonNode)task.get("outputMediaIds")).toString(),id);return task(id);
    }
}
