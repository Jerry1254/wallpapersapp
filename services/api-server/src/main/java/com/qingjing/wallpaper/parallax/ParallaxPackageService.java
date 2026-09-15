package com.qingjing.wallpaper.parallax;

import static com.qingjing.wallpaper.parallax.ParallaxPackageDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.catalog.AdminContentDtos.*;
import com.qingjing.wallpaper.catalog.AdminContentViewReader;
import com.qingjing.wallpaper.catalog.AdminWallpaperService;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ParallaxPackageService {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final ParallaxPackageParser parser;
    private final ParallaxPackageReader reader;
    private final ParallaxStorageCleanup cleanup;
    private final AdminWallpaperService wallpapers;
    private final AdminContentViewReader views;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Semaphore imports=new Semaphore(2);

    public ParallaxPackageService(JdbcTemplate jdbc,FileStorage storage,ParallaxPackageParser parser,
            ParallaxPackageReader reader,ParallaxStorageCleanup cleanup,AdminWallpaperService wallpapers,
            AdminContentViewReader views,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.storage=storage;this.parser=parser;this.reader=reader;this.cleanup=cleanup;
        this.wallpapers=wallpapers;this.views=views;this.mapper=mapper;this.transaction=new TransactionTemplate(manager);
    }

    public Result<SourcePackage> importZip(InputStream input,String filename,long adminId,String requestId) {
        if(!imports.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","正在处理其他资源包，请稍后重试");
        StagedObject staged=null;List<StoredObject> created=new ArrayList<>();boolean succeeded=false;
        try {
            staged=storage.stage(input,ParallaxPackageParser.ZIP_LIMIT);
            String sha=staged.sha256();
            Long existing=find(sha);
            if(existing!=null)return new Result<>(reusable(existing),false);
            ParallaxPackageParser.Parsed parsed;
            try(var source=storage.openStaged(staged)){parsed=parser.parse(source.inputStream().readAllBytes());}
            StoredObject archive=storage.commit(staged,"zip");created.add(archive);staged=null;
            try {
                Result<SourcePackage> result=transaction.execute(status -> {
                    // SHA unique key serializes concurrent imports before any assets are inserted.
                    jdbc.update("""
                            INSERT INTO parallax_source_package(sha256,original_filename,size_bytes,storage_key,canvas_width,canvas_height,status,created_by_admin_id)
                            VALUES(?,?,?,?,?,?,'VALIDATING',?)
                            """,archive.sha256(),safeFilename(filename),archive.sizeBytes(),archive.storageKey().value(),parsed.width(),parsed.height(),adminId);
                    long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
                    long cover=asset(parsed.cover().name(),parsed.cover().bytes(),parsed.cover().image().mime(),"WALLPAPER_COVER",parsed.cover().image().width(),parsed.cover().image().height(),adminId,created);
                    long config=asset("config.json",parsed.internalConfig(),"application/json","PARALLAX_CONFIG",null,null,adminId,created);
                    for(int i=0;i<parsed.layers().size();i++) {
                        var layer=parsed.layers().get(i);var image=parsed.images().get(i);
                        long asset=asset(image.name(),image.bytes(),image.image().mime(),layer.role(),parsed.width(),parsed.height(),adminId,created);
                        jdbc.update("""
                                INSERT INTO parallax_source_layer(source_package_id,layer_index,asset_id,original_filename,role,ordinal,depth,scale,opacity,blend_mode)
                                VALUES(?,?,?,?,?,?,?,?,?,?)
                                """,id,layer.index(),asset,layer.originalFilename(),layer.role(),layer.ordinal(),layer.depth(),layer.scale(),layer.opacity(),layer.blendMode());
                    }
                    jdbc.update("UPDATE parallax_source_package SET cover_asset_id=?,config_asset_id=?,status='READY' WHERE id=?",cover,config,id);
                    audit(adminId,requestId,"IMPORT_PARALLAX_SOURCE",Long.toString(id),Map.of("sourcePackageId",Long.toString(id),"layerCount",parsed.layers().size()));
                    return new Result<>(reader.get(id),true);
                });
                succeeded=true;return result;
            }catch(DuplicateKeyException duplicate) {
                Long id=find(sha);
                if(id==null)throw duplicate;
                return new Result<>(reusable(id),false);
            }
        }catch(java.io.IOException e){throw ParallaxErrors.invalid("ZIP","CORRUPT_FILE","ZIP 无法完整读取");}
        finally {
            try {
                if(staged!=null)cleanup.discard(staged);
                if(!succeeded)for(var object:created)cleanup.delete(object);
            }finally{imports.release();}
        }
    }

    public Result<AdminResourceVersion> createVersion(long variantId,int versionNo,long sourceId,long adminId,String requestId) {
        if(versionNo<1)throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","versionNo 必须大于零");
        return transaction.execute(status -> {
            var variant=views.variantRow(variantId);
            var state=jdbc.queryForObject("SELECT status FROM wallpaper WHERE id=? FOR UPDATE",String.class,variant.wallpaperId());
            jdbc.queryForObject("SELECT id FROM wallpaper_variant WHERE id=? FOR UPDATE",Long.class,variantId);
            if("ARCHIVED".equals(state)||!variant.platform().equals("ANDROID")||!variant.resourceType().equals("LAYER_PARALLAX"))
                throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","只允许为可编辑的 Android 4D 壁纸创建源包版本");
            var source=jdbc.queryForList("SELECT id FROM parallax_source_package WHERE id=? AND status='READY' FOR UPDATE",Long.class,sourceId);
            if(source.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","4D 源包不存在或已回收");
            var previous=jdbc.queryForList("SELECT id,source_package_id FROM resource_version WHERE variant_id=? AND version_no=?",variantId,versionNo);
            if(!previous.isEmpty()) {
                var old=previous.get(0);Object previousSource=old.get("source_package_id");
                if(previousSource!=null&&((Number)previousSource).longValue()==sourceId)
                    return new Result<>(views.resourceVersion(((Number)old.get("id")).longValue()),false);
                throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","该版本号已被另一资源包使用，请刷新后重试");
            }
            List<CreateResourceBindingRequest> bindings=new ArrayList<>(jdbc.query("""
                    SELECT asset_id,role,ordinal FROM parallax_source_layer WHERE source_package_id=? ORDER BY layer_index
                    """,(rs,n)->new CreateResourceBindingRequest(Long.toString(rs.getLong(1)),AssetRole.valueOf(rs.getString(2)),rs.getInt(3)),sourceId));
            if(bindings.size()<2||bindings.size()>12)throw ParallaxErrors.invalid("layers/","LAYER_COUNT_INVALID","源包图层不完整");
            long config=jdbc.queryForObject("SELECT config_asset_id FROM parallax_source_package WHERE id=?",Long.class,sourceId);
            bindings.add(new CreateResourceBindingRequest(Long.toString(config),AssetRole.PARALLAX_CONFIG,0));
            long invalidAssets=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM asset WHERE id IN (
                        SELECT asset_id FROM parallax_source_layer WHERE source_package_id=?
                        UNION SELECT config_asset_id FROM parallax_source_package WHERE id=?
                        UNION SELECT cover_asset_id FROM parallax_source_package WHERE id=?)
                    AND (deleted_at IS NOT NULL OR validation_status<>'READY')
                    """,Long.class,sourceId,sourceId,sourceId);
            if(invalidAssets!=0)throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","源包资产不可用，请重新导入");
            var version=wallpapers.createResourceVersion(variantId,new CreateResourceVersionRequest(versionNo,null,bindings),adminId);
            jdbc.update("UPDATE resource_version SET source_package_id=? WHERE id=?",sourceId,Long.parseLong(version.id()));
            audit(adminId,requestId,"CREATE_PARALLAX_VERSION",version.id(),Map.of("sourcePackageId",Long.toString(sourceId),"variantId",Long.toString(variantId),"versionNo",versionNo));
            return new Result<>(views.resourceVersion(Long.parseLong(version.id())),true);
        });
    }

    private Long find(String sha){var ids=jdbc.queryForList("SELECT id FROM parallax_source_package WHERE sha256=? AND status='READY'",Long.class,sha);return ids.isEmpty()?null:ids.get(0);}
    private SourcePackage reusable(long id) {
        var objects=jdbc.queryForList("""
                SELECT storage_key,size_bytes,status AS validation_status FROM parallax_source_package WHERE id=?
                UNION ALL
                SELECT storage_key,size_bytes,IF(deleted_at IS NULL,validation_status,'DELETED') FROM asset WHERE id IN (
                    SELECT cover_asset_id FROM parallax_source_package WHERE id=?
                    UNION SELECT config_asset_id FROM parallax_source_package WHERE id=?
                    UNION SELECT asset_id FROM parallax_source_layer WHERE source_package_id=?)
                """,id,id,id,id);
        try {
            for(var object:objects) {
                if(!"READY".equals(object.get("validation_status")))throw new IllegalStateException();
                try(var content=storage.open(new StorageKey(object.get("storage_key").toString()))) {
                    if(content.sizeBytes()!=((Number)object.get("size_bytes")).longValue())throw new IllegalStateException();
                }
            }
        }catch(Exception unavailable){throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","该源包的已存资源不可用，请先恢复本地资源存储后重试");}
        return reader.get(id);
    }
    private long asset(String name,byte[] bytes,String mime,String purpose,Integer width,Integer height,long admin,List<StoredObject> created) {
        StagedObject staged=storage.stage(new ByteArrayInputStream(bytes),bytes.length);
        StoredObject object;
        try{object=storage.commit(staged,name.substring(name.lastIndexOf('.')+1));}
        catch(RuntimeException error){cleanup.discard(staged);throw error;}
        created.add(object);
        jdbc.update("""
                INSERT INTO asset(storage_key,original_filename,mime_type,file_extension,purpose,size_bytes,sha256,width_px,height_px,validation_status,created_by_admin_id)
                VALUES(?,?,?,?,?,?,?,?,?,'READY',?)
                """,object.storageKey().value(),name,mime,name.substring(name.lastIndexOf('.')+1),purpose,object.sizeBytes(),object.sha256(),width,height,admin);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    private void audit(long admin,String requestId,String action,String id,Map<String,Object> summary) {
        try{
            jdbc.update("""
                    INSERT INTO audit_event(actor_admin_id,request_id,action,aggregate_type,aggregate_id,result,change_summary)
                    VALUES(?,?,?,'SYSTEM',?,'SUCCEEDED',CAST(? AS JSON))
                    """,admin,requestId,action,id,mapper.writeValueAsString(summary));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private static String safeFilename(String name){String safe=name==null?"wallpaper-4d.zip":name.replace('\\','/');safe=safe.substring(safe.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");return safe.isBlank()?"wallpaper-4d.zip":safe.substring(0,Math.min(255,safe.length()));}
}
