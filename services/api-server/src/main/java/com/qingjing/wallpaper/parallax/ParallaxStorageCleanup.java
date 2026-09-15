package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.asset.application.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only failed rollback objects are retried; successfully imported packages are never collected. */
@Component
@org.springframework.scheduling.annotation.EnableScheduling
public class ParallaxStorageCleanup {
    private static final Logger LOG=LoggerFactory.getLogger(ParallaxStorageCleanup.class);
    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate durable;
    public ParallaxStorageCleanup(FileStorage storage,JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager){
        this.storage=storage;this.jdbc=jdbc;
        this.durable=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.durable.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void discard(StagedObject object) {
        try{storage.discard(object);}catch(RuntimeException error){enqueue("STAGED",object.token(),object.sizeBytes(),object.sha256(),error);}
    }
    public void delete(StoredObject object) {
        try{storage.delete(object.storageKey());}catch(RuntimeException error){enqueue("OBJECT",object.storageKey().value(),object.sizeBytes(),object.sha256(),error);}
    }
    private void enqueue(String kind,String reference,long size,String sha,RuntimeException error) {
        durable.executeWithoutResult(status -> jdbc.update("INSERT INTO parallax_storage_cleanup(storage_kind,storage_reference,size_bytes,sha256) VALUES(?,?,?,?)",kind,reference,size,sha));
        LOG.warn("Parallax rollback cleanup queued kind={} cause={}",kind,error.getClass().getSimpleName());
    }
    public int retryPending() {
        var pending=jdbc.query("SELECT id,storage_kind,storage_reference,size_bytes,sha256 FROM parallax_storage_cleanup ORDER BY id LIMIT 100",
                (r,n)->new Pending(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)));
        int completed=0;
        for(var item:pending) {
            try {
                if(item.kind().equals("STAGED"))storage.discard(new StagedObject(item.reference(),item.size(),item.sha()));
                else {
                    Long references=jdbc.queryForObject("""
                            SELECT (SELECT COUNT(*) FROM asset WHERE storage_key=?) +
                                   (SELECT COUNT(*) FROM parallax_source_package WHERE storage_key=?)
                            """,Long.class,item.reference(),item.reference());
                    if(references!=null&&references>0)continue;
                    storage.delete(new StorageKey(item.reference()));
                }
                jdbc.update("DELETE FROM parallax_storage_cleanup WHERE id=?",item.id());completed++;
            }catch(RuntimeException e){jdbc.update("UPDATE parallax_storage_cleanup SET attempts=attempts+1 WHERE id=?",item.id());}
        }
        return completed;
    }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void retry() {
        try { retryPending(); }
        catch (RuntimeException error) { LOG.warn("Parallax cleanup retry unavailable cause={}", error.getClass().getSimpleName()); }
    }
    private record Pending(long id,String kind,String reference,long size,String sha){}
}
