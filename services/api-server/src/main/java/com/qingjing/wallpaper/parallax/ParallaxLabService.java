package com.qingjing.wallpaper.parallax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.catalog.AdminContentDtos.*;
import com.qingjing.wallpaper.catalog.AdminWallpaperService;
import com.qingjing.wallpaper.delivery.SecurePackagePublisher;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Atomic internal-tool save: only config bytes change; images and layer order remain immutable. */
@Service
public class ParallaxLabService {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final ParallaxStorageCleanup cleanup;
    private final ParallaxConfigEnvelopeValidator configs;
    private final ObjectMapper mapper;
    private final AdminWallpaperService wallpapers;
    private final SecurePackagePublisher packages;

    public ParallaxLabService(JdbcTemplate jdbc,FileStorage storage,ParallaxStorageCleanup cleanup,
            ParallaxConfigEnvelopeValidator configs,ObjectMapper mapper,AdminWallpaperService wallpapers,
            SecurePackagePublisher packages) {
        this.jdbc=jdbc;this.storage=storage;this.cleanup=cleanup;this.configs=configs;this.mapper=mapper;
        this.wallpapers=wallpapers;this.packages=packages;
    }

    @Transactional
    public ParallaxLabDtos.SaveResult save(long wallpaperId,long baseVersionId,JsonNode config,long adminId) {
        var bases=jdbc.query("""
                SELECT rv.id,rv.variant_id,rv.version_no,w.lock_version
                FROM resource_version rv
                JOIN wallpaper_variant v ON v.id=rv.variant_id
                JOIN wallpaper w ON w.id=v.wallpaper_id
                WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED'
                  AND v.platform='ANDROID' AND v.resource_type='LAYER_PARALLAX'
                FOR UPDATE
                """,(rs,n)->new Base(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getLong(4)),baseVersionId,wallpaperId);
        if(bases.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","配置已经被其他设备更新，请刷新后继续");
        Base base=bases.get(0);
        var bindings=jdbc.query("""
                SELECT b.asset_id,b.role,b.ordinal,a.storage_key,a.size_bytes
                FROM resource_binding b JOIN asset a ON a.id=b.asset_id
                WHERE b.resource_version_id=? AND a.deleted_at IS NULL AND a.validation_status='READY'
                ORDER BY b.role,b.ordinal
                """,(rs,n)->new Binding(rs.getLong(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getLong(5)),baseVersionId);
        var layers=bindings.stream().filter(it->!it.role().equals("PARALLAX_CONFIG")).toList();
        var oldConfig=bindings.stream().filter(it->it.role().equals("PARALLAX_CONFIG")&&it.ordinal()==0).toList();
        if(oldConfig.size()!=1||layers.size()<2||layers.size()>12)throw invalid();
        byte[] oldBytes=read(oldConfig.get(0));
        byte[] nextBytes;
        try { nextBytes=mapper.writeValueAsBytes(config); }
        catch(Exception error){throw invalid();}
        ParallaxConfigEnvelopeValidator.Envelope oldEnvelope,nextEnvelope;
        try { oldEnvelope=configs.validate(oldBytes,layers.size());nextEnvelope=configs.validate(nextBytes,layers.size()); }
        catch(RuntimeException error){throw invalid();}
        if(oldEnvelope.formatVersion()!=2||nextEnvelope.formatVersion()!=2||oldEnvelope.width()!=nextEnvelope.width()
                ||oldEnvelope.height()!=nextEnvelope.height()||oldEnvelope.layerCount()!=nextEnvelope.layerCount())throw invalid();

        long configAsset=createConfigAsset(nextBytes,adminId);
        int versionNo=Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM resource_version WHERE variant_id=?",Integer.class,base.variantId()));
        List<CreateResourceBindingRequest> requests=new ArrayList<>();
        for(var layer:layers)requests.add(new CreateResourceBindingRequest(Long.toString(layer.assetId()),AssetRole.valueOf(layer.role()),layer.ordinal()));
        requests.add(new CreateResourceBindingRequest(Long.toString(configAsset),AssetRole.PARALLAX_CONFIG,0));
        var version=wallpapers.createResourceVersion(base.variantId(),new CreateResourceVersionRequest(versionNo,null,requests),adminId);
        long versionId=Long.parseLong(version.id());
        packages.build(versionId);
        var selected=jdbc.queryForList("""
                SELECT rv.id FROM resource_version rv JOIN wallpaper_variant v ON v.id=rv.variant_id
                WHERE v.wallpaper_id=? AND rv.status='PUBLISHED' AND rv.id<>? ORDER BY v.id
                """,Long.class,wallpaperId,baseVersionId).stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        selected.add(Long.toString(versionId));
        wallpapers.publish(wallpaperId,base.wallpaperLock(),new PublishWallpaperRequest(selected));
        return new ParallaxLabDtos.SaveResult(Long.toString(versionId),versionNo,2);
    }

    private long createConfigAsset(byte[] bytes,long adminId) {
        if(bytes.length<1||bytes.length>ParallaxConfigEnvelopeValidator.MAX_BYTES)throw invalid();
        StagedObject staged=storage.stage(new ByteArrayInputStream(bytes),ParallaxConfigEnvelopeValidator.MAX_BYTES);
        StoredObject object;
        try { object=storage.commit(staged,"json"); }
        catch(RuntimeException error){cleanup.discard(staged);throw error;}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)cleanup.delete(object);
            }
        });
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement("""
                    INSERT INTO asset(storage_key,original_filename,mime_type,file_extension,purpose,size_bytes,sha256,validation_status,created_by_admin_id)
                    VALUES(?,'config.json','application/json','json','PARALLAX_CONFIG',?,?,'READY',?)
                    """,Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,object.storageKey().value());statement.setLong(2,object.sizeBytes());
            statement.setString(3,object.sha256());statement.setLong(4,adminId);return statement;
        },keys);
        Number key=keys.getKey();if(key==null)throw new IllegalStateException("Config asset insert returned no identifier");
        return key.longValue();
    }
    private byte[] read(Binding binding) {
        if(binding.size()<1||binding.size()>ParallaxConfigEnvelopeValidator.MAX_BYTES)throw invalid();
        try(var content=storage.open(new StorageKey(binding.storageKey()))) {
            if(content.sizeBytes()!=binding.size())throw invalid();
            byte[] bytes=content.inputStream().readNBytes((int)binding.size()+1);
            if(bytes.length!=binding.size())throw invalid();return bytes;
        }catch(java.io.IOException error){throw invalid();}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"CONFIG_INVALID","配置结构发生变化，无法保存");}
    private record Base(long id,long variantId,int versionNo,long wallpaperLock){}
    private record Binding(long assetId,String role,int ordinal,String storageKey,long size){}
}
