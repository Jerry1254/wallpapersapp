package com.qingjing.wallpaper.creator;

import com.qingjing.wallpaper.asset.AdminAssetService;
import com.qingjing.wallpaper.asset.AdminAssetView;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Optional idempotency for the existing upload API; no change to legacy callers. */
@Service
public class CreatorAssetUploads {
    public record Result(AdminAssetView asset,boolean created) { }
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final AdminAssetService assets;
    private final CreatorCapabilities capabilities;
    private final TransactionTemplate tx;
    public CreatorAssetUploads(JdbcTemplate jdbc,FileStorage storage,AdminAssetService assets,
            CreatorCapabilities capabilities,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.storage=storage;this.assets=assets;this.capabilities=capabilities;this.tx=new TransactionTemplate(transactions);
    }
    public Result upload(InputStream input,String name,String mime,AssetPurpose purpose,long adminId,String key) {
        try{key=UUID.fromString(key).toString();}catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Idempotency-Key 必须是 UUID");}
        String requestKey=key;
        var staged=storage.stage(input,purpose.maximumBytes());
        try {
            String hash=hash(purpose+"\n"+staged.sha256()+"\n"+name+"\n"+mime);
            return tx.execute(status->{
                jdbc.update("INSERT INTO creator_wallpaper_upload(environment_id,idempotency_key,request_sha256) VALUES(?,?,?) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key",capabilities.environmentId(),requestKey,hash);
                var row=jdbc.queryForMap("SELECT request_sha256,asset_id FROM creator_wallpaper_upload WHERE environment_id=? AND idempotency_key=? FOR UPDATE",capabilities.environmentId(),requestKey);
                if(!hash.equals(row.get("request_sha256")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","上传请求键已用于不同的文件或用途");
                if(row.get("asset_id")!=null)return new Result(assets.get(((Number)row.get("asset_id")).longValue()),false);
                AdminAssetView asset;
                try(var content=storage.openStaged(staged)){asset=assets.upload(content.inputStream(),name,mime,purpose,adminId);}
                catch(java.io.IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"STORAGE_UNAVAILABLE","上传文件不可读");}
                var descriptor=assets.content(Long.parseLong(asset.id()));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCompletion(int completion){if(completion!=STATUS_COMMITTED)storage.delete(descriptor.storageKey());}
                });
                jdbc.update("UPDATE creator_wallpaper_upload SET asset_id=? WHERE environment_id=? AND idempotency_key=?",Long.parseLong(asset.id()),capabilities.environmentId(),requestKey);
                return new Result(asset,true);
            });
        }finally{storage.discard(staged);}
    }
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
