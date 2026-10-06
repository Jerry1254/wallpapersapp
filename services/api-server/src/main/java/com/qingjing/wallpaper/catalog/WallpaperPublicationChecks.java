package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared read-only checks used both by preflight and by the locked final publication. */
@Service
public class WallpaperPublicationChecks {
    public record Check(String scope, String code, String level, String message,
                        String suggestedAction, boolean preventsPreparation) { }
    public record Result(String wallpaperId, long wallpaperVersion, boolean canPrepare,
                         boolean canPublish, List<Check> checks) { }
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    public WallpaperPublicationChecks(JdbcTemplate jdbc, FileStorage storage) {
        this.jdbc=jdbc; this.storage=storage;
    }

    public Result check(long wallpaperId, List<String> versionIds) {
        var rows=jdbc.queryForList("SELECT * FROM wallpaper WHERE id=?",wallpaperId);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","壁纸不存在");
        var wallpaper=rows.get(0);
        var checks=new ArrayList<Check>();
        String scope="wallpaper:"+wallpaperId;
        if("ARCHIVED".equals(wallpaper.get("status")))block(checks,scope,"WALLPAPER_ARCHIVED","归档壁纸不能上架","EDIT_WALLPAPER",true);
        var categories=jdbc.queryForList("""
                SELECT c.level,c.parent_id,c.deleted_at,p.level AS parent_level,p.deleted_at AS parent_deleted
                FROM category c LEFT JOIN category p ON p.id=c.parent_id WHERE c.id=?
                """,wallpaper.get("category_id"));
        if(categories.isEmpty() || categories.get(0).get("deleted_at")!=null ||
                (number(categories.get(0),"level")==2 && (categories.get(0).get("parent_level")==null ||
                 number(categories.get(0),"parent_level")!=1 || categories.get(0).get("parent_deleted")!=null)))
            block(checks,scope,"CATEGORY_INVALID","分类不存在或已删除","EDIT_WALLPAPER",true);
        if(wallpaper.get("title").toString().isBlank() || wallpaper.get("copyright_note").toString().isBlank())
            block(checks,scope,"METADATA_INVALID","壁纸信息不完整","EDIT_WALLPAPER",true);
        inspectAsset(((Number)wallpaper.get("cover_asset_id")).longValue(),"WALLPAPER_COVER",scope,checks,true);
        var selected=new HashSet<Long>();
        var variants=new HashSet<Long>();
        if(versionIds.isEmpty())block(checks,scope,"RESOURCE_SELECTION_EMPTY","至少选择一个资源版本","SELECT_RESOURCES",true);
        for(String text:versionIds) {
            long id=Ids.parse(text,"resourceVersionIds");
            String resourceScope="resourceVersion:"+id;
            if(!selected.add(id)){block(checks,resourceScope,"RESOURCE_SELECTION_DUPLICATE","不能重复选择资源版本","SELECT_RESOURCES",true);continue;}
            var versions=jdbc.queryForList("""
                    SELECT rv.*,v.wallpaper_id,v.platform,v.resource_type,v.enabled
                    FROM resource_version rv JOIN wallpaper_variant v ON v.id=rv.variant_id WHERE rv.id=?
                    """,id);
            if(versions.isEmpty()){block(checks,resourceScope,"RESOURCE_NOT_FOUND","资源版本不存在","SELECT_RESOURCES",true);continue;}
            var version=versions.get(0);
            if(number(version,"wallpaper_id")!=wallpaperId)block(checks,resourceScope,"RESOURCE_OWNER_MISMATCH","资源版本不属于该壁纸","SELECT_RESOURCES",true);
            if(!variants.add(number(version,"variant_id")))block(checks,resourceScope,"VARIANT_SELECTION_DUPLICATE","同一个变体只能选一个版本","SELECT_RESOURCES",true);
            if(!bool(version.get("enabled")))block(checks,resourceScope,"VARIANT_DISABLED","资源变体已停用","ENABLE_VARIANT",true);
            if(!Set.of("READY","PUBLISHED").contains(version.get("status")))block(checks,resourceScope,"RESOURCE_VERSION_NOT_READY","资源版本未通过校验","REPLACE_RESOURCE",true);
            String platform=version.get("platform").toString(),type=version.get("resource_type").toString();
            if(!legalPair(platform,type)){block(checks,resourceScope,"VARIANT_PAIR_INVALID","不支持的平台和资源类型组合","REPLACE_RESOURCE",true);continue;}
            // Lab config edits legally create versions without a source ZIP. Their bound files and
            // formal package are checked below; only imported versions have an additional ZIP to verify.
            if(type.equals("LAYER_PARALLAX") && version.get("source_package_id")!=null) {
                    var sources=jdbc.queryForList("SELECT * FROM parallax_source_package WHERE id=?",version.get("source_package_id"));
                    if(sources.isEmpty() || !"READY".equals(sources.get(0).get("status")))block(checks,resourceScope,"PARALLAX_SOURCE_MISSING","4D 源包不存在或未就绪","REPLACE_RESOURCE",true);
                    else if(!readable(sources.get(0).get("storage_key"),sources.get(0).get("size_bytes"),sources.get(0).get("sha256")))block(checks,resourceScope,"ASSET_CONTENT_INVALID","4D 原始 ZIP 不可读，或哈希不一致","REPLACE_RESOURCE",true);
            }
            var bindings=jdbc.queryForList("SELECT role,ordinal,asset_id FROM resource_binding WHERE resource_version_id=? ORDER BY role,ordinal",id);
            Set<String> required=switch(type){
                case "VIDEO" -> Set.of("VIDEO");
                case "STATIC_IMAGE" -> Set.of("STATIC_IMAGE");
                case "LIVE_PHOTO" -> Set.of("LIVE_PHOTO_SOURCE");
                case "MOVING_PHOTO" -> Set.of("MOVING_PHOTO_SOURCE");
                default -> Set.of("BACKGROUND","FOREGROUND","PARALLAX_CONFIG");
            };
            for(String role:required) {
                var matching=bindings.stream().filter(b->role.equals(b.get("role"))).toList();
                boolean valid=role.equals("FOREGROUND") ? matching.size()>=1 && matching.size()<=11 : matching.size()==1;
                for(int ordinal=0;ordinal<matching.size();ordinal++)valid &= number(matching.get(ordinal),"ordinal")==ordinal;
                if(!valid)block(checks,resourceScope,"RESOURCE_BINDINGS_INVALID","资源绑定不完整："+role,"REPLACE_RESOURCE",true);
            }
            for(var binding:bindings) {
                String role=binding.get("role").toString();
                if(!role.equals("COVER") && !required.contains(role))block(checks,resourceScope,"RESOURCE_BINDINGS_INVALID","资源包含不支持的绑定","REPLACE_RESOURCE",true);
                var asset=inspectAsset(number(binding,"asset_id"),role,resourceScope,checks,false);
                if(asset!=null && type.equals("LIVE_PHOTO") && role.equals("LIVE_PHOTO_SOURCE") && !"video/mp4".equals(asset.get("mime_type")))
                    block(checks,resourceScope,"IOS_SOURCE_MP4_REQUIRED","iOS 正式交付需要源 MP4，不能使用 MOV 或派生预览替代","REPLACE_RESOURCE",true);
                if(asset!=null && type.equals("VIDEO") && role.equals("VIDEO") &&
                        (asset.get("duration_ms")==null || number(asset,"duration_ms")<=0 || number(asset,"duration_ms")>30000))
                    block(checks,resourceScope,"ANDROID_DURATION_INVALID","Android 视频时长必须大于 0 且不超过 30 秒","REPLACE_RESOURCE",true);
            }
            if(Set.of("ANDROID","UNIVERSAL").contains(platform)) {
                inspectPackage("secure_resource_package",id,resourceScope,checks,"DELIVERY_PACKAGE_PENDING");
                inspectPackage("preview_resource_package",id,resourceScope,checks,"PREVIEW_PACKAGE_PENDING");
            } else inspectDynamicPackage(type.equals("LIVE_PHOTO")?"live_photo_package":"moving_photo_package",id,resourceScope,checks);
        }
        // The existing FREE/REDEEM configuration continues to be valid without an iOS IAP mapping.
        // If a mapping exists, a disabled one is a warning; it must never silently be enabled here.
        boolean ios=versionIds.stream().anyMatch(id->jdbc.queryForObject("SELECT COUNT(*) FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE r.id=? AND v.platform='IOS'",Integer.class,Ids.parse(id,"resourceVersionIds"))>0);
        if(ios && jdbc.queryForObject("SELECT COUNT(*) FROM ios_product_mapping WHERE wallpaper_id=? AND enabled=FALSE",Integer.class,wallpaperId)>0)
            checks.add(new Check(scope,"IOS_ACQUISITION_DISABLED","WARNING","iOS 非消耗型购买配置未启用；仍沿用当前 FREE/REDEEM 获取规则","CHECK_IOS_CONFIGURATION",false));
        return new Result(Long.toString(wallpaperId),number(wallpaper,"lock_version"),
                checks.stream().noneMatch(Check::preventsPreparation),checks.stream().noneMatch(c->c.level().equals("BLOCKING")),List.copyOf(checks));
    }

    public void requirePublishable(long wallpaperId,List<String> versionIds) {
        var result=check(wallpaperId,versionIds);
        if(!result.canPublish())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PUBLICATION_CHECK_FAILED",
                "壁纸未满足发布条件",result.checks().stream().filter(c->c.level().equals("BLOCKING"))
                .map(c->new ApiException.ErrorDetail(c.scope(),c.code()+": "+c.message())).toList());
    }

    private Map<String,Object> inspectAsset(long id,String purpose,String scope,List<Check> checks,boolean cover) {
        var rows=jdbc.queryForList("SELECT * FROM asset WHERE id=?",id);
        if(rows.isEmpty()){block(checks,scope,"ASSET_NOT_FOUND","文件记录不存在","REPLACE_RESOURCE",true);return null;}
        var asset=rows.get(0);
        String mime=asset.get("mime_type").toString();
        boolean mimeValid=switch(purpose){
            case "PARALLAX_CONFIG" -> mime.equals("application/json");
            case "VIDEO","LIVE_PHOTO_SOURCE" -> Set.of("video/mp4","video/quicktime").contains(mime);
            case "MOVING_PHOTO_SOURCE" -> mime.equals("video/mp4");
            case "FOREGROUND" -> Set.of("image/png","image/webp").contains(mime);
            default -> Set.of("image/jpeg","image/png","image/webp").contains(mime);
        };
        if(asset.get("deleted_at")!=null || !"READY".equals(asset.get("validation_status")) || !mimeValid ||
                (Set.of("WALLPAPER_COVER","LIVE_PHOTO_SOURCE","MOVING_PHOTO_SOURCE").contains(purpose) && !purpose.equals(asset.get("purpose"))))
            block(checks,scope,cover?"COVER_INVALID":"ASSET_NOT_READY","文件状态、用途或格式不符合要求","REPLACE_RESOURCE",true);
        if(!readable(asset.get("storage_key"),asset.get("size_bytes"),asset.get("sha256")))
            block(checks,scope,"ASSET_CONTENT_INVALID","文件不可读，或大小与 SHA-256 不一致","REPLACE_RESOURCE",true);
        return asset;
    }
    private void inspectPackage(String table,long id,String scope,List<Check> checks,String pending) {
        var rows=jdbc.queryForList("SELECT storage_key,size_bytes,encrypted_sha256 FROM "+table+" WHERE resource_version_id=?",id);
        if(rows.isEmpty()) {
            if(pending.equals("PREVIEW_PACKAGE_PENDING"))checks.add(new Check(scope,pending,"WARNING","预览将由现有发布后队列生成，正式交付包不受影响","WAIT_FOR_PREVIEW",false));
            else block(checks,scope,pending,"交付包尚未准备完成","PREPARE_RESOURCE",false);
        }
        else if(!readable(rows.get(0).get("storage_key"),rows.get(0).get("size_bytes"),rows.get(0).get("encrypted_sha256")))
            block(checks,scope,"PACKAGE_CONTENT_INVALID","交付或预览包不可读，或哈希不一致","REPAIR_PACKAGE",true);
    }
    private void inspectDynamicPackage(String table,long id,String scope,List<Check> checks) {
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE resource_version_id=?",id);
        if(rows.isEmpty() || !"READY".equals(rows.get(0).get("status"))) {
            block(checks,scope,table.equals("live_photo_package")?"LIVE_PHOTO_PENDING":"MOVING_PHOTO_PENDING","动态照片派生资源尚未就绪","PREPARE_RESOURCE",false);return;
        }
        var row=rows.get(0);
        String image=table.equals("live_photo_package")?"photo":"poster";
        for(String prefix:List.of(image,"video"))if(!readable(row.get(prefix+"_storage_key"),row.get(prefix+"_size_bytes"),row.get(prefix+"_sha256")))
            block(checks,scope,"PACKAGE_CONTENT_INVALID","动态照片文件不可读，或哈希不一致","REPAIR_PACKAGE",true);
    }
    private boolean readable(Object key,Object size,Object hash) {
        if(key==null || size==null || hash==null)return false;
        try(var content=storage.open(new StorageKey(key.toString()))) {
            long expected=((Number)size).longValue();
            if(expected<1 || content.sizeBytes()!=expected)return false;
            var digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[64*1024];long count=0;int n;
            while((n=content.inputStream().read(buffer))!=-1){count+=n;if(count>expected)return false;digest.update(buffer,0,n);}
            return count==expected && HexFormat.of().formatHex(digest.digest()).equals(hash.toString());
        }catch(Exception e){return false;}
    }
    public static boolean legalPair(String platform,String type) {
        return switch(platform){
            case "ANDROID" -> Set.of("VIDEO","LAYER_PARALLAX").contains(type);
            case "IOS" -> type.equals("LIVE_PHOTO");
            case "HARMONYOS" -> type.equals("MOVING_PHOTO");
            case "UNIVERSAL" -> type.equals("STATIC_IMAGE");
            default -> false;
        };
    }
    private static void block(List<Check> checks,String scope,String code,String message,String action,boolean preparation) {
        checks.add(new Check(scope,code,"BLOCKING",message,action,preparation));
    }
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static boolean bool(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
}
