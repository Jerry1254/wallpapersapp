package com.qingjing.wallpaper.creator;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.catalog.AdminContentDtos.*;
import com.qingjing.wallpaper.catalog.AdminWallpaperService;
import com.qingjing.wallpaper.catalog.WallpaperPublicationChecks;
import com.qingjing.wallpaper.creator.CreatorPublicationDtos.*;
import com.qingjing.wallpaper.delivery.*;
import com.qingjing.wallpaper.iosacquisition.IosProductService;
import com.qingjing.wallpaper.parallax.ParallaxPackageService;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable publication only. Editing, media libraries and rendering remain owned by the studio. */
@Service
public class CreatorPublicationService {
    private static final Logger LOG=LoggerFactory.getLogger(CreatorPublicationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final CreatorCapabilities capabilities;
    private final AdminWallpaperService wallpapers;
    private final ParallaxPackageService parallax;
    private final IosProductService ios;
    private final SecurePackagePublisher packages;
    private final MovingPhotoPublisher moving;
    private final LivePhotoPublisher live;
    private final WallpaperPublicationChecks checks;
    private final DataSource datasource;

    public CreatorPublicationService(JdbcTemplate jdbc,PlatformTransactionManager transactions,ObjectMapper mapper,
            Validator validator,CreatorCapabilities capabilities,AdminWallpaperService wallpapers,
            ParallaxPackageService parallax,IosProductService ios,SecurePackagePublisher packages,
            MovingPhotoPublisher moving,LivePhotoPublisher live,WallpaperPublicationChecks checks) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.mapper=mapper;this.validator=validator;
        this.capabilities=capabilities;this.wallpapers=wallpapers;this.parallax=parallax;this.ios=ios;
        this.packages=packages;this.moving=moving;this.live=live;this.checks=checks;
        this.datasource=Objects.requireNonNull(jdbc.getDataSource());
    }

    public Task submit(JsonNode input,String idempotencyKey,long adminId) {
        Request request=decode(input);
        String key;
        try{key=UUID.fromString(idempotencyKey).toString();}
        catch(Exception e){throw invalid("Idempotency-Key 必须是 UUID");}
        String hash=hash(request);
        return transaction(()->{
            jdbc.update("""
                    INSERT INTO creator_wallpaper_publication
                      (environment_id,idempotency_key,request_sha256,request_json,steps,created_by_admin_id)
                    VALUES(?,?,?,CAST(? AS JSON),JSON_OBJECT(),?)
                    ON DUPLICATE KEY UPDATE id=id
                    """,request.environmentId(),key,hash,write(request),adminId);
            var row=jdbc.queryForMap("SELECT * FROM creator_wallpaper_publication WHERE environment_id=? AND idempotency_key=? FOR UPDATE",request.environmentId(),key);
            if(!hash.equals(row.get("request_sha256")))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","请求键已用于其他输入；修改输入后请使用新键");
            // The first queued submission is validated before commit. Replays return its durable status.
            if(number(row,"attempt")==0 && "QUEUED".equals(row.get("state")) && row.get("wallpaper_id")==null)validateInput(request);
            return view(row);
        });
    }

    public Task get(long id){return view(row(id,false));}
    public Map<String,Object> list(String clientProjectKey,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100 || (clientProjectKey!=null && !clientProjectKey.matches("[A-Za-z0-9._:-]{1,128}")))throw invalid("列表参数不合法");
        String where="environment_id=? AND (? IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.clientProjectKey'))=?)";
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM creator_wallpaper_publication WHERE "+where,Long.class,capabilities.environmentId(),clientProjectKey,clientProjectKey);
        var items=jdbc.queryForList("SELECT * FROM creator_wallpaper_publication WHERE "+where+" ORDER BY id DESC LIMIT ? OFFSET ?",capabilities.environmentId(),clientProjectKey,clientProjectKey,pageSize,(page-1L)*pageSize).stream().map(this::view).toList();
        return Map.of("items",items,"page",new PageMetadata(page,pageSize,total,(int)((total+pageSize-1)/pageSize)));
    }
    public Task retry(long id){return transaction(()->{
        var row=row(id,true);
        if(!"FAILED".equals(row.get("state")) || !bool(row.get("retryable")))throw error(HttpStatus.CONFLICT,"STATE_CONFLICT","仅可重试允许恢复的失败任务；需修改输入时请创建新任务");
        if(!capabilities.environmentId().equals(row.get("environment_id")))throw environmentMismatch();
        jdbc.update("UPDATE creator_wallpaper_publication SET state='QUEUED',error_code=NULL,error_message=NULL,retryable=FALSE WHERE id=?",id);
        return get(id);
    });}

    public Request decode(JsonNode input) {
        if(input==null || !input.isObject() || write(input).getBytes(StandardCharsets.UTF_8).length>128*1024)throw invalid("请求必须是小于 128 KiB 的 JSON 对象");
        Request request;
        try{request=mapper.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(input);}
        catch(Exception e){throw invalid("请求字段或类型不符合创作台上架契约");}
        var violations=validator.validate(request);
        if(!violations.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","请求校验失败",violations.stream().map(v->new ApiException.ErrorDetail(v.getPropertyPath().toString(),v.getMessage())).toList());
        if(!CreatorCapabilities.RULES_VERSION.equals(request.rulesVersion()))throw error(HttpStatus.CONFLICT,"CREATOR_RULES_CHANGED","上架规则已更新，请重新读取 capabilities");
        if(capabilities.environmentId().equals("UNCONFIGURED") || !capabilities.environmentId().equals(request.environmentId()))throw environmentMismatch();
        if((request.wallpaperId()==null)!=(request.expectedWallpaperVersion()==null))throw invalid("更新必须同时提供 wallpaperId 和 expectedWallpaperVersion");
        var pairs=new HashSet<String>();
        for(Resource resource:request.resources()) {
            String pair=resource.platform()+":"+resource.resourceType();
            if(!WallpaperPublicationChecks.legalPair(resource.platform().name(),resource.resourceType().name()) || !pairs.add(pair))throw invalid("平台类型不合法或同一能力出现多次");
            boolean zip=resource.resourceType()==ResourceType.LAYER_PARALLAX;
            if(zip?(resource.sourcePackageId()==null || resource.assetId()!=null):(resource.assetId()==null || resource.sourcePackageId()!=null))throw invalid("4D 必须提供 sourcePackageId；其他资源必须提供 assetId，不能混用");
        }
        if(new HashSet<>(request.retainResourceVersionIds()).size()!=request.retainResourceVersionIds().size())throw invalid("retainResourceVersionIds 不能重复");
        if(request.action()==Action.PUBLISH && request.resources().isEmpty() && request.retainResourceVersionIds().isEmpty())throw invalid("上架至少需要一个资源版本");
        if(request.iosAcquisition()!=null && "CREDITS".equals(request.iosAcquisition().acquisitionMode())) {
            if(request.iosAcquisition().credits()!=null)com.qingjing.wallpaper.iosacquisition.IosCreditProductService.packFor(request.iosAcquisition().credits());
            else if(request.iosAcquisition().enabled() || request.iosAcquisition().firstFreeEligible())throw invalid("启用 iOS 积分购买或首免前必须填写 credits");
        }
        return request;
    }

    private void validateInput(Request request) {
        long cover=Ids.parse(request.metadata().coverAssetId(),"coverAssetId");
        requireAsset(cover,"WALLPAPER_COVER");
        for(Resource resource:request.resources()) {
            if(resource.assetId()!=null){
                String role=role(resource.resourceType());
                var asset=requireAsset(Ids.parse(resource.assetId(),"assetId"),role);
                if(resource.resourceType()==ResourceType.LIVE_PHOTO && !"video/mp4".equals(asset.get("mime_type")))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IOS_SOURCE_MP4_REQUIRED","iOS 正式资源必须是源 MP4");
            } else if(jdbc.queryForObject("SELECT COUNT(*) FROM parallax_source_package WHERE id=? AND status='READY'",Integer.class,Ids.parse(resource.sourcePackageId(),"sourcePackageId"))!=1)
                throw error(HttpStatus.UNPROCESSABLE_ENTITY,"PARALLAX_PACKAGE_REQUIRED","4D 源包不存在或未就绪");
        }
        var linked=jdbc.queryForList("SELECT wallpaper_id FROM creator_wallpaper_link WHERE environment_id=? AND client_project_key=?",Long.class,request.environmentId(),request.clientProjectKey());
        if(!linked.isEmpty() && (request.wallpaperId()==null || linked.get(0)!=Ids.parse(request.wallpaperId(),"wallpaperId")))throw error(HttpStatus.CONFLICT,"CREATOR_WALLPAPER_LINK_CONFLICT","该创作项目已关联其他壁纸；请读取原任务并明确更新原壁纸");
        if(request.wallpaperId()!=null) {
            long id=Ids.parse(request.wallpaperId(),"wallpaperId");
            var wallpaper=wallpapers.get(id);
            requireVersion(wallpaper.version(),request.expectedWallpaperVersion());
            if(wallpaper.status()==WallpaperStatus.ARCHIVED)throw error(HttpStatus.CONFLICT,"STATE_CONFLICT","归档壁纸不能更新");
            if(request.action()==Action.SAVE_DRAFT && wallpaper.status()==WallpaperStatus.PUBLISHED)throw error(HttpStatus.CONFLICT,"STATE_CONFLICT","已上架商品不能伪装为保存草稿；请明确上架更新或先下架");
            var other=jdbc.queryForList("SELECT client_project_key FROM creator_wallpaper_link WHERE environment_id=? AND wallpaper_id=?",String.class,request.environmentId(),id);
            if(!other.isEmpty() && !other.get(0).equals(request.clientProjectKey()))throw error(HttpStatus.CONFLICT,"CREATOR_WALLPAPER_LINK_CONFLICT","该壁纸已关联另一个创作项目");
            var newPairs=request.resources().stream().map(r->r.platform()+":"+r.resourceType()).collect(java.util.stream.Collectors.toSet());
            var retained=new HashSet<>(request.retainResourceVersionIds());
            for(var variant:wallpaper.variants())for(var version:variant.resourceVersions()) {
                if(retained.contains(version.id()) && (!variant.enabled() || !Set.of(ResourceVersionStatus.READY,ResourceVersionStatus.PUBLISHED).contains(version.status())))throw invalid("保留版本必须就绪且变体启用");
                if(request.action()==Action.PUBLISH && version.status()==ResourceVersionStatus.PUBLISHED && !retained.contains(version.id()) && !newPairs.contains(variant.platform()+":"+variant.resourceType()))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY,"PUBLICATION_SELECTION_INCOMPLETE","更新必须明确保留未替换的已发布资源，不能意外删除其他平台能力");
            }
            Set<String> owned=wallpaper.variants().stream().flatMap(v->v.resourceVersions().stream()).map(AdminResourceVersion::id).collect(java.util.stream.Collectors.toSet());
            if(!owned.containsAll(retained))throw invalid("保留的资源版本不属于目标壁纸");
        } else if(!request.retainResourceVersionIds().isEmpty())throw invalid("新壁纸不能保留已有资源版本");
    }

    /** MySQL advisory lock is held on a dedicated connection, released automatically on process loss. */
    @Scheduled(fixedDelayString="${qingjing.creator.publication-poll-ms:1000}")
    public void runNext() {
        if(capabilities.environmentId().equals("UNCONFIGURED"))return;
        try(Connection connection=datasource.getConnection()) {
            if(!advisory(connection,"SELECT GET_LOCK('qj_creator_wallpaper_publication',0)"))return;
            try {
                var candidates=jdbc.queryForList("SELECT id FROM creator_wallpaper_publication WHERE environment_id=? AND state IN ('QUEUED','RUNNING') ORDER BY id LIMIT 1",Long.class,capabilities.environmentId());
                if(candidates.isEmpty())return;
                long id=candidates.get(0);
                boolean claimed=transaction(()->{
                    var task=row(id,true);
                    if(!Set.of("QUEUED","RUNNING").contains(task.get("state")))return false;
                    jdbc.update("UPDATE creator_wallpaper_publication SET state='RUNNING',attempt=attempt+1,error_code=NULL,error_message=NULL,retryable=FALSE WHERE id=?",id);
                    return true;
                });
                if(!claimed)return;
                try{execute(id);}catch(Exception failure){fail(id,failure);}
            }finally{advisory(connection,"SELECT RELEASE_LOCK('qj_creator_wallpaper_publication')");}
        }catch(SQLException e){LOG.warn("Creator publication queue cannot acquire database lock",e);}
    }

    private void execute(long taskId) {
        Request request=decode(parse(row(taskId,false).get("request_json")));
        step(taskId,"WALLPAPER",()->{
            validateInput(request);
            AdminWallpaperDetail wallpaper;
            if(request.wallpaperId()==null)wallpaper=wallpapers.create(request.metadata());
            else wallpaper=wallpapers.update(Ids.parse(request.wallpaperId(),"wallpaperId"),request.expectedWallpaperVersion(),request.metadata());
            long id=Ids.parse(wallpaper.id(),"wallpaperId");
            jdbc.update("INSERT INTO creator_wallpaper_link(environment_id,client_project_key,wallpaper_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE wallpaper_id=wallpaper_id",request.environmentId(),request.clientProjectKey(),id);
            jdbc.update("UPDATE creator_wallpaper_publication SET wallpaper_id=?,wallpaper_version=? WHERE id=?",id,wallpaper.version(),taskId);
            return wallpaper.id();
        });
        // A resumed task may have completed every business step before an external edit.
        // Check its persisted version before any expensive media preparation.
        transaction(()->lockTarget(taskId));
        if(request.iosAcquisition()!=null)step(taskId,"IOS_CONFIGURATION",()->{
            long wallpaperId=lockTarget(taskId);ios.update(wallpaperId,request.iosAcquisition());return Long.toString(wallpaperId);
        });
        var ids=new ArrayList<>(request.retainResourceVersionIds());
        for(int i=0;i<request.resources().size();i++) {
            Resource resource=request.resources().get(i);
            String variantId=step(taskId,"VARIANT_"+i,()->{
                long wallpaperId=lockTarget(taskId);
                var variants=wallpapers.get(wallpaperId).variants();
                var existing=variants.stream().filter(v->v.platform()==resource.platform() && v.resourceType()==resource.resourceType()).findFirst();
                AdminWallpaperVariant variant;
                if(existing.isEmpty())variant=wallpapers.createVariant(wallpaperId,number(row(taskId,false),"wallpaper_version"),new VariantWriteRequest(resource.platform(),resource.resourceType(),null,List.of(),true));
                else {
                    variant=existing.get();
                    if(!variant.enabled())variant=wallpapers.updateVariant(Ids.parse(variant.id(),"variantId"),variant.version(),new VariantWriteRequest(variant.platform(),variant.resourceType(),variant.minimumOsVersion(),variant.capabilityRequirements(),true));
                }
                recordVersion(taskId,wallpaperId);return variant.id();
            });
            String versionId=step(taskId,"RESOURCE_"+i,()->{
                long wallpaperId=lockTarget(taskId);
                long variant=Ids.parse(variantId,"variantId");
                var published=matchingPublishedVersion(variant,resource);
                if(!published.isEmpty())return Long.toString(published.get(0));
                int next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM resource_version WHERE variant_id=?",Integer.class,variant);
                long admin=number(row(taskId,false),"created_by_admin_id");
                AdminResourceVersion version;
                if(resource.resourceType()==ResourceType.LAYER_PARALLAX) {
                    String requestId=UUID.nameUUIDFromBytes(("creator-publication:"+taskId).getBytes(StandardCharsets.UTF_8)).toString();
                    version=parallax.createVersion(variant,next,Ids.parse(resource.sourcePackageId(),"sourcePackageId"),admin,requestId).value();
                }
                else version=wallpapers.createResourceVersion(variant,new CreateResourceVersionRequest(next,null,List.of(new CreateResourceBindingRequest(resource.assetId(),AssetRole.valueOf(role(resource.resourceType())),0))),admin);
                recordVersion(taskId,wallpaperId);return version.id();
            });
            ids.add(versionId);
        }
        if(request.action()==Action.PUBLISH) {
            var preflight=checks.check(number(row(taskId,false),"wallpaper_id"),ids);
            if(!preflight.canPrepare())checks.requirePublishable(number(row(taskId,false),"wallpaper_id"),ids);
            for(String id:ids) {
                stage(taskId,"PREPARE_"+id);
                long version=Ids.parse(id,"resourceVersionId");
                // Existing publishers reuse already committed packages. Their failure state commits separately.
                moving.prepareForPublication(version);live.prepareForPublication(version);packages.prepareForPublication(version);
                step(taskId,"PREPARED_"+id,()->{lockTarget(taskId);return id;});
            }
        }
        transaction(()->{
            var task=row(taskId,true);
            if("SUCCEEDED".equals(task.get("state")))return null;
            long wallpaperId=lockTarget(taskId);
            AdminWallpaperDetail wallpaper=request.action()==Action.PUBLISH ? wallpapers.publish(wallpaperId,number(task,"wallpaper_version"),new PublishWallpaperRequest(ids)) : wallpapers.get(wallpaperId);
            var result=new Result(wallpaper.id(),wallpaper.status(),wallpaper.version(),List.copyOf(ids),wallpaper.previewGenerationStatus());
            jdbc.update("UPDATE creator_wallpaper_publication SET state='SUCCEEDED',stage='COMPLETED',wallpaper_version=?,result=CAST(? AS JSON),error_code=NULL,error_message=NULL,retryable=FALSE WHERE id=?",wallpaper.version(),write(result),taskId);
            return null;
        });
    }

    private List<Long> matchingPublishedVersion(long variant,Resource resource) {
        if(resource.resourceType()==ResourceType.LAYER_PARALLAX)return jdbc.queryForList("""
                SELECT id FROM resource_version
                WHERE variant_id=? AND status='PUBLISHED' AND source_package_id=?
                """,Long.class,variant,Ids.parse(resource.sourcePackageId(),"sourcePackageId"));
        return jdbc.queryForList("""
                SELECT r.id FROM resource_version r
                WHERE r.variant_id=? AND r.status='PUBLISHED' AND r.source_package_id IS NULL
                  AND (SELECT COUNT(*) FROM resource_binding b WHERE b.resource_version_id=r.id)=1
                  AND EXISTS (SELECT 1 FROM resource_binding b WHERE b.resource_version_id=r.id
                              AND b.asset_id=? AND b.role=? AND b.ordinal=0)
                """,Long.class,variant,Ids.parse(resource.assetId(),"assetId"),role(resource.resourceType()));
    }

    private String step(long id,String key,Supplier<String> action) {
        stage(id,key);
        return transaction(()->{
            var task=row(id,true);var steps=(ObjectNode)parse(task.get("steps"));
            if(steps.has(key))return steps.get(key).asText();
            String value=action.get();steps.put(key,value);
            jdbc.update("UPDATE creator_wallpaper_publication SET steps=CAST(? AS JSON) WHERE id=?",write(steps),id);
            return value;
        });
    }
    private void stage(long id,String value){jdbc.update("UPDATE creator_wallpaper_publication SET stage=? WHERE id=? AND state='RUNNING'",value,id);}
    private long lockTarget(long taskId) {
        var task=row(taskId,false);long id=number(task,"wallpaper_id");
        Long current=jdbc.queryForObject("SELECT lock_version FROM wallpaper WHERE id=? FOR UPDATE",Long.class,id);
        requireVersion(current,number(task,"wallpaper_version"));return id;
    }
    private void recordVersion(long taskId,long wallpaperId){jdbc.update("UPDATE creator_wallpaper_publication SET wallpaper_version=? WHERE id=?",wallpapers.get(wallpaperId).version(),taskId);}
    private void fail(long id,Exception failure) {
        String code=failure instanceof ApiException api?api.code():"PUBLICATION_PROCESSING_FAILED";
        boolean needsInput=Set.of("VERSION_CONFLICT","DOMAIN_RULE_VIOLATION","VALIDATION_FAILED","DUPLICATE_SLUG","CATEGORY_NOT_FOUND","ASSET_NOT_READY","ASSET_NOT_FOUND","PARALLAX_PACKAGE_REQUIRED","CREATOR_WALLPAPER_LINK_CONFLICT","PUBLICATION_CHECK_FAILED","IOS_SOURCE_MP4_REQUIRED","DYNAMIC_SOURCE_FORMAT_INVALID","DYNAMIC_SOURCE_DURATION_INVALID","IOS_LIVE_PHOTO_FRAME_COUNT_INVALID","PUBLICATION_SELECTION_INCOMPLETE","STATE_CONFLICT").contains(code);
        String message=failure instanceof ApiException?failure.getMessage():"上架处理失败；已保存的草稿和资源步骤可恢复";
        if(message.length()>512)message=message.substring(0,512);
        jdbc.update("UPDATE creator_wallpaper_publication SET state=?,error_code=?,error_message=?,retryable=? WHERE id=? AND state='RUNNING'",needsInput?"NEEDS_INPUT":"FAILED",code,message,!needsInput,id);
        LOG.warn("Creator publication {} failed at persisted stage; code={}",id,code,failure);
    }
    private Map<String,Object> requireAsset(long id,String purpose) {
        var rows=jdbc.queryForList("SELECT purpose,validation_status,mime_type FROM asset WHERE id=? AND deleted_at IS NULL",id);
        if(rows.isEmpty() || !"READY".equals(rows.get(0).get("validation_status")) || !purpose.equals(rows.get(0).get("purpose")))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ASSET_NOT_READY","文件不存在、未就绪或上传用途不匹配："+purpose);
        return rows.get(0);
    }
    private Map<String,Object> row(long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM creator_wallpaper_publication WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty() || !capabilities.environmentId().equals(rows.get(0).get("environment_id")))throw error(HttpStatus.NOT_FOUND,"CREATOR_PUBLICATION_NOT_FOUND","上架任务不存在");
        return rows.get(0);
    }
    Task view(Map<String,Object> row) {
        var input=parse(row.get("request_json"));
        Long wallpaperVersion=null;
        if(row.get("wallpaper_version")!=null)wallpaperVersion=number(row,"wallpaper_version");
        else if(input.path("expectedWallpaperVersion").isNumber())wallpaperVersion=input.path("expectedWallpaperVersion").asLong();
        var steps=new ArrayList<Step>();parse(row.get("steps")).fields().forEachRemaining(e->steps.add(new Step(e.getKey(),e.getValue().asText())));
        Result result=null;
        try{if(row.get("result")!=null)result=mapper.treeToValue(parse(row.get("result")),Result.class);}
        catch(Exception e){throw new IllegalStateException("Invalid publication result",e);}
        return new Task(Long.toString(number(row,"id")),row.get("state").toString(),row.get("stage").toString(),row.get("environment_id").toString(),
                input.path("clientProjectKey").asText(),Action.valueOf(input.path("action").asText()),input.path("metadata").path("title").asText(),
                row.get("wallpaper_id")==null?input.path("wallpaperId").asText(null):Long.toString(number(row,"wallpaper_id")),
                wallpaperVersion,
                (int)number(row,"attempt"),List.copyOf(steps),result,(String)row.get("error_code"),(String)row.get("error_message"),bool(row.get("retryable")),time(row.get("created_at")),time(row.get("updated_at")));
    }
    private static java.time.Instant time(Object value) {
        return value instanceof Timestamp timestamp?timestamp.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);
    }
    private JsonNode parse(Object value){try{return value==null?com.fasterxml.jackson.databind.node.NullNode.instance:mapper.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("Invalid stored publication JSON",e);}}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("无效 JSON");}}
    String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(mapper.valueToTree(value))).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()){var object=mapper.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(name->object.set(name,canonical(node.get(name))));return object;}
        if(node.isArray()){var array=mapper.createArrayNode();node.forEach(n->array.add(canonical(n)));return array;}return node;
    }
    private <T>T transaction(Supplier<T> callback){return tx.execute(s->callback.get());}
    private boolean advisory(Connection connection,String sql)throws SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){return result.next() && result.getInt(1)==1;}}
    private static String role(ResourceType type){return switch(type){case STATIC_IMAGE -> "STATIC_IMAGE";case VIDEO -> "VIDEO";case LIVE_PHOTO -> "LIVE_PHOTO_SOURCE";case MOVING_PHOTO -> "MOVING_PHOTO_SOURCE";default -> throw invalid("4D 必须使用源包接口");};}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static boolean bool(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private static void requireVersion(long actual,long expected){if(actual!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","商品被其他页面修改，请重新读取并确认更新");}
    private static ApiException invalid(String message){return error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
    private static ApiException environmentMismatch(){return error(HttpStatus.CONFLICT,"CREATOR_ENVIRONMENT_MISMATCH","目标环境与当前 API 不一致，禁止自动切换环境");}
    private static ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
}
