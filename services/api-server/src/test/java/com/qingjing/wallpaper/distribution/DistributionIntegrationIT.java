package com.qingjing.wallpaper.distribution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class DistributionIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("distribution_test").withUsername("distribution_test").withPassword(UUID.randomUUID().toString());
    static JdbcTemplate db;
    static TransactionTemplate tx;
    static final ObjectMapper JSON=new ObjectMapper();
    static final String RUNNER=UUID.randomUUID().toString(),LEGACY=UUID.randomUUID().toString(),LEGACY_SECOND=UUID.randomUUID().toString(),LEGACY_BATCH=UUID.randomUUID().toString();
    DistributionService service;
    FileStorage storage;
    DistributionQueries queries;
    @BeforeAll static void database(){
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl()+(MYSQL.getJdbcUrl().contains("?")?"&":"?")+"serverTimezone=UTC",MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("25").load().migrate();
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.update("INSERT INTO creator_social_account(id,platform,display_name,runner_id,login_status) VALUES(?,'xhs','已有账号',?,'ready')",LEGACY,RUNNER);
        db.update("INSERT INTO creator_social_account(id,platform,display_name,runner_id,login_status) VALUES(?,'xhs','第二个旧账号',?,'ready')",LEGACY_SECOND,RUNNER);
        db.update("INSERT INTO creator_publish_batch(id,request_hash) VALUES(?,?)",LEGACY_BATCH,"f".repeat(64));
        for(String account:new String[]{LEGACY,LEGACY_SECOND})db.update("INSERT INTO creator_publish_job(id,batch_id,account_id,payload,status,due_at) VALUES(?,?,?,'{}','cancelled',CURRENT_TIMESTAMP(3))",UUID.randomUUID().toString(),LEGACY_BATCH,account);
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @BeforeEach void setup(){storage=mock(FileStorage.class);service=new DistributionService(db,JSON,storage);queries=new DistributionQueries(db,JSON,service);}
    ObjectNode obj(){return JSON.createObjectNode();}
    String account(){return (String)service.createAccount(obj().put("platform","xhs").put("name","备注").put("runnerId",RUNNER)).get("id");}
    ObjectNode identity(String value){return obj().put("platformUserId","handle:"+value).put("nickname","真实昵称").put("avatarUrl","");}
    ObjectNode update(String value){ObjectNode n=obj().put("status","ready").put("runnerId",RUNNER);n.set("identity",identity(value));return n;}
    Map<String,Object> bind(String id,String value){return tx.execute(s->service.updateAccount(id,update(value)));}
    @Test void migratesLegacyAccountsWithoutDiscardingTheirRecords(){assertThat(service.requireAccount(LEGACY)).containsEntry("status","unverified").containsEntry("platformUserId",null);}
    @Test void migratesLegacyJobsAndPreservesTheirAccountsAndPayloads(){
        var jobs=service.jobsForBatch(LEGACY_BATCH);
        assertThat(jobs).hasSize(2);assertThat(jobs.stream().map(j->j.get("accountId"))).containsExactlyInAnyOrder(LEGACY,LEGACY_SECOND);
        assertThat(jobs.stream().map(j->j.get("batchPosition"))).containsExactly(0,1);
        assertThat(jobs.stream().map(j->j.get("status"))).containsOnly("cancelled");
    }
    @Test void rejectsAccountSwapDuplicateIdentityAndWrongComputer(){
        String a=account(),b=account(),value=UUID.randomUUID().toString();bind(a,value);
        assertThatThrownBy(()->bind(a,"wrong")).isInstanceOf(ApiException.class).hasMessageContaining("不一致");
        assertThatThrownBy(()->bind(b,value)).isInstanceOf(ApiException.class).hasMessageContaining("已经添加");
        ObjectNode wrongRunner=update(value).put("runnerId",UUID.randomUUID().toString());
        assertThatThrownBy(()->tx.execute(s->service.updateAccount(a,wrongRunner))).isInstanceOf(ApiException.class).hasMessageContaining("原来登录的电脑");
        assertThat(service.requireAccount(a)).containsEntry("platformUserId","handle:"+value).containsEntry("nickname","真实昵称");
        assertThat(service.requireAccount(b)).containsEntry("platformUserId",null);
    }
    @Test void aReadyStatusWithoutFreshIdentityIsRejected(){
        String a=account();assertThatThrownBy(()->tx.execute(s->service.updateAccount(a,obj().put("status","ready").put("runnerId",RUNNER)))).isInstanceOf(ApiException.class);
    }
    @Test void completeHistoryPaginatesBeyondTwoHundredAndKeepsArchivedAccounts(){
        String a=account(),batch=UUID.randomUUID().toString();
        db.update("INSERT INTO creator_publish_batch(id,request_hash) VALUES(?,?)",batch,"e".repeat(64));
        for(int i=0;i<205;i++)db.update("INSERT INTO creator_publish_job(id,batch_id,account_id,payload,status,due_at,created_at,batch_position) VALUES(?,?,?,?,'published','2026-10-01 00:00:00','2026-10-01 00:00:00',?)",UUID.randomUUID().toString(),batch,a,obj().put("title","字面%_"+i).put("type","image").toString(),i);
        service.archiveAccount(a);
        var q=Map.of("accountId",a,"keyword","%_","page","11","pageSize","20","from","2026-10-01T00:00:00Z","to","2026-10-02T00:00:00Z");
        var page=queries.jobs(q);assertThat(page.get("total")).isEqualTo(205L);assertThat((List<?>)page.get("items")).hasSize(5);
        var overview=queries.overview(Map.of("accountId",a,"from",q.get("from"),"to",q.get("to")));
        assertThat(overview.get("total")).isEqualTo(205L);assertThat(((Map<?,?>)overview.get("statuses")).get("published")).isEqualTo(205L);
        assertThat((List<?>)overview.get("accounts")).hasSize(1);
        assertThat(queries.jobs(Map.of("accountId",a,"type","video")).get("total")).isEqualTo(0L);
    }
    @Test void metricsCompareDatedSnapshotsAndDoNotTurnMissingValuesIntoZero(){
        String a=account();
        db.update("INSERT INTO creator_account_metrics(account_id,metrics,source_url,collected_at) VALUES(?,?,'https://creator.xiaohongshu.com/new/home',?)",a,"{\"followers\":100,\"likes\":5}","2026-09-30 00:00:00");
        db.update("INSERT INTO creator_account_metrics(account_id,metrics,source_url,collected_at) VALUES(?,?,'https://creator.xiaohongshu.com/new/home',?)",a,"{\"followers\":90,\"plays\":200}","2026-10-02 00:00:00");
        db.update("INSERT INTO creator_account_metrics(account_id,metrics,source_url,collected_at) VALUES(?,?,'https://creator.xiaohongshu.com/new/home',?)",a,"{\"followers\":999}","2026-10-05 00:00:00");
        var overview=queries.overview(Map.of("accountId",a,"from","2026-10-01T00:00:00Z","to","2026-10-03T00:00:00Z"));
        var metric=(Map<?,?>)((List<?>)overview.get("metrics")).get(0);
        assertThat((Map<?,?>)metric.get("delta")).hasSize(1);assertThat(((Map<?,?>)metric.get("delta")).get("followers")).isEqualTo(-10L);
        assertThat(((JsonNode)metric.get("metrics")).path("followers").longValue()).isEqualTo(90);
        assertThat(((JsonNode)metric.get("metrics")).has("likes")).isFalse();
        var withoutBaseline=queries.overview(Map.of("accountId",a,"from","2026-09-01T00:00:00Z","to","2026-10-03T00:00:00Z"));
        assertThat((Map<?,?>)((Map<?,?>)((List<?>)withoutBaseline.get("metrics")).get(0)).get("delta")).isEmpty();
    }
    record Source(String project,String file,String media,ObjectNode payload){}
    Source source(){
        String id=UUID.randomUUID().toString(),file="file-"+id;
        db.update("INSERT INTO creator_workspace_media(id,filename,media_type,mime_type,storage_key,size_bytes,sha256,metadata) VALUES(?,'作品.png','image','image/png',?,100,?,'{}')",file,"creator/"+id,"a".repeat(64));
        ObjectNode project=obj().put("id",id).put("name","原项目名称");
        project.putArray("media").addObject().put("id","asset-1").put("name","第一张").put("contentItemId","work-1").putObject("file").put("$creatorFile",file);
        project.putObject("data").putObject("content").putArray("items").addObject().put("id","work-1").put("name","原作品名称").put("taskId","generation-1");
        db.update("INSERT INTO creator_workspace_record(collection_name,id,payload,payload_sha256) VALUES('projects',?,?,?)",id,project.toString(),"b".repeat(64));
        String media=(String)tx.execute(s->service.reuseMedia(obj().put("workspaceMediaId",file))).get("id");
        return new Source(id,file,media,project);
    }
    ObjectNode post(Source source,String title){
        ObjectNode n=obj().put("type","image").put("title",title).put("body","正文");n.putArray("tags");n.putArray("mediaIds").add(source.media);
        n.putObject("source").put("projectId",source.project).put("projectName","客户端伪造名称").putArray("assetIds").add("asset-1");return n;
    }
    @Test void reusesStoredBytesAndRejectsAFileThatDoesNotBelongToSource(){
        Source source=source();assertThat(service.reuseMedia(obj().put("workspaceMediaId",source.file)).get("id")).isEqualTo(source.media);verifyNoInteractions(storage);
        Source other=source();ObjectNode post=post(source,"标题");post.withArray("mediaIds").removeAll().add(other.media);
        assertThatThrownBy(()->service.sourceSnapshot(post.path("source"),post)).isInstanceOf(ApiException.class).hasMessageContaining("内容已变化");
    }
    @Test void keepsIndependentAccountPostsAndFrozenSourcesAcrossRetriesAndRenames(){
        Source source=source();String a=account(),b=account();bind(a,UUID.randomUUID().toString());bind(b,UUID.randomUUID().toString());
        ObjectNode batch=obj().put("id",UUID.randomUUID().toString());ArrayNode entries=batch.putArray("entries");
        entries.addObject().put("accountId",a).set("post",post(source,"账号甲标题").put("visibility","private").put("originality","original").put("declaration","笔记含AI合成内容"));entries.addObject().put("accountId",b).set("post",post(source,"账号乙标题").put("visibility","public").put("originality","not_original"));
        var first=tx.execute(s->service.createBatch(batch));assertThat(first).hasSize(2);
        assertThat(first.stream().map(j->((JsonNode)j.get("post")).path("title").asText())).containsExactlyInAnyOrder("账号甲标题","账号乙标题");
        JsonNode privatePost=first.stream().map(j->(JsonNode)j.get("post")).filter(p->p.path("title").asText().equals("账号甲标题")).findFirst().orElseThrow();
        assertThat(privatePost.path("visibility").asText()).isEqualTo("private");
        assertThat(privatePost.path("originality").asText()).isEqualTo("original");
        assertThat(privatePost.path("declaration").asText()).isEqualTo("笔记含AI合成内容");
        JsonNode snapshot=((JsonNode)first.get(0).get("post")).path("source");
        assertThat(snapshot.path("projectName").asText()).isEqualTo("原项目名称");assertThat(snapshot.path("assets").get(0).path("generationTaskId").asText()).isEqualTo("generation-1");
        source.payload.put("name","后来改名");db.update("UPDATE creator_workspace_record SET payload=? WHERE collection_name='projects' AND id=?",source.payload.toString(),source.project);
        var retry=tx.execute(s->service.createBatch(batch));assertThat(retry.stream().map(j->j.get("id"))).containsExactlyElementsOf(first.stream().map(j->j.get("id")).toList());
        assertThat(((JsonNode)retry.get(0).get("post")).path("source").path("projectName").asText()).isEqualTo("原项目名称");
    }
    @Test void sameAccountCanReceiveDifferentWorksAndRetryReturnsTheSameTasks(){
        Source first=source(),second=source();String a=account();bind(a,UUID.randomUUID().toString());
        ObjectNode batch=obj().put("id",UUID.randomUUID().toString());
        var entries=batch.putArray("entries");
        entries.addObject().put("accountId",a).set("post",post(first,"第一份作品"));
        entries.addObject().put("accountId",a).set("post",post(second,"第二份作品").put("visibility","private"));
        var jobs=tx.execute(s->service.createBatch(batch));assertThat(jobs).hasSize(2);
        assertThat(jobs.stream().map(j->j.get("accountId"))).containsOnly(a);
        assertThat(jobs.stream().map(j->j.get("batchPosition"))).containsExactly(0,1);
        assertThat(jobs.stream().map(j->((JsonNode)j.get("post")).path("title").asText())).containsExactly("第一份作品","第二份作品");
        var retry=tx.execute(s->service.createBatch(batch));
        assertThat(retry.stream().map(j->j.get("id"))).containsExactlyElementsOf(jobs.stream().map(j->j.get("id")).toList());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE batch_id=?",Integer.class,batch.path("id").asText())).isEqualTo(2);
    }
    @Test void duplicateMediaForOneAccountRollsBackTheEntireBatchEvenWithDifferentTitles(){
        Source source=source();String a=account();bind(a,UUID.randomUUID().toString());
        ObjectNode batch=obj().put("id",UUID.randomUUID().toString());
        var entries=batch.putArray("entries");
        entries.addObject().put("accountId",a).set("post",post(source,"第一次"));
        entries.addObject().put("accountId",a).set("post",post(source,"改个标题重复发"));
        assertThatThrownBy(()->tx.execute(s->service.createBatch(batch))).isInstanceOf(ApiException.class).hasMessageContaining("重复发布");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE batch_id=?",Integer.class,batch.path("id").asText())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_batch WHERE id=?",Integer.class,batch.path("id").asText())).isZero();
    }

    JsonNode storedRequest(ObjectNode request) throws Exception {
        return JSON.readTree(db.queryForObject("SELECT request_snapshot FROM creator_publish_batch WHERE id=?",String.class,request.path("id").asText()));
    }
    @Test void restoringMysqlJsonKeyOrderReusesTheBatchButChangedValuesAndArrayOrderConflict() throws Exception {
        String a=readyAccount("xhs");Source first=source(),second=source();
        ObjectNode batch=obj().put("id",UUID.randomUUID().toString()).put("scheduledAt","2030-01-01T04:00:00Z");
        var entries=batch.putArray("entries");
        entries.addObject().put("accountId",a).set("post",post(first,"第一份").put("visibility","private"));
        entries.addObject().put("accountId",a).set("post",post(second,"第二份"));
        var initial=tx.execute(s->service.createBatch(batch));JsonNode restored=storedRequest(batch);
        assertThat(restored.toString()).isNotEqualTo(batch.toString());
        var retry=tx.execute(s->service.createBatch(restored));
        assertThat(retry.stream().map(j->j.get("id"))).containsExactlyElementsOf(initial.stream().map(j->j.get("id")).toList());
        ObjectNode title=restored.deepCopy();((ObjectNode)title.path("entries").get(0).path("post")).put("title","已改变");
        ObjectNode time=restored.deepCopy();time.put("scheduledAt","2030-01-02T04:00:00Z");
        ObjectNode order=restored.deepCopy();ArrayNode list=order.withArray("entries");JsonNode head=list.remove(0);list.add(head);
        ObjectNode settings=restored.deepCopy();((ObjectNode)settings.path("entries").get(0).path("post")).put("visibility","public");
        for(JsonNode changed:List.of(title,time,order,settings))assertThatThrownBy(()->tx.execute(s->service.createBatch(changed))).isInstanceOf(ApiException.class).hasMessageContaining("内容发生变化");
        assertThat(service.jobsForBatch(batch.path("id").asText())).hasSize(2);
    }
    @Test void preSnapshotBatchesRecoverAfterKeyReorderingWithoutChangingFrozenSources() throws Exception {
        String a=readyAccount("xhs");Source source=source();
        ObjectNode batch=validationBatch(a,post(source,"原清单")).put("scheduledAt","2030-01-01T04:00:00Z");
        var initial=tx.execute(s->service.createBatch(batch));JsonNode restored=storedRequest(batch);
        String id=batch.path("id").asText();db.update("UPDATE creator_publish_batch SET request_snapshot=NULL WHERE id=?",id);
        ObjectNode changed=restored.deepCopy();((ObjectNode)changed.path("entries").get(0).path("post")).put("title","不应接受");
        assertThatThrownBy(()->tx.execute(s->service.createBatch(changed))).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("SELECT request_snapshot FROM creator_publish_batch WHERE id=?",String.class,id)).isNull();
        source.payload.put("name","来源已改名");db.update("UPDATE creator_workspace_record SET payload=? WHERE collection_name='projects' AND id=?",source.payload.toString(),source.project);
        var retry=tx.execute(s->service.createBatch(restored));
        assertThat(retry.get(0).get("id")).isEqualTo(initial.get(0).get("id"));
        assertThat(((JsonNode)retry.get(0).get("post")).path("source").path("projectName").asText()).isEqualTo("原项目名称");
        assertThat(storedRequest(batch)).isEqualTo(restored);
    }

    String readyAccount(String platform){
        String id=(String)service.createAccount(obj().put("platform",platform).put("name","边界测试").put("runnerId",RUNNER)).get("id");
        bind(id,UUID.randomUUID().toString());return id;
    }
    String publicationMedia(String type){
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO creator_publish_media(id,filename,media_type,extension,storage_key,size_bytes,sha256) VALUES(?,?,?,?,?,100,?)",id,type.equals("image")?"test.png":"test.mp4",type,type.equals("image")?"png":"mp4","test/"+id,"a".repeat(64));
        return id;
    }
    ObjectNode validationPost(String type,String media,int titleLength){
        ObjectNode p=obj().put("type",type).put("title","字".repeat(titleLength)).put("body","");
        p.putArray("tags");p.putArray("mediaIds").add(media);return p;
    }
    ObjectNode validationBatch(String account,JsonNode post){
        ObjectNode batch=obj().put("id",UUID.randomUUID().toString());
        batch.putArray("entries").addObject().put("accountId",account).set("post",post);return batch;
    }
    void rejectsBatchWithoutPartialRows(ObjectNode batch){
        assertThatThrownBy(()->tx.execute(s->service.createBatch(batch))).isInstanceOf(ApiException.class);
        String id=batch.path("id").asText();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_batch WHERE id=?",Integer.class,id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_job WHERE batch_id=?",Integer.class,id)).isZero();
    }
    @Test void fourFormsEnforceTitleTopicAndCombinedBodyBoundaries(){
        for(String platform:List.of("douyin","xhs"))for(String type:List.of("image","video")){
            String account=readyAccount(platform),media=publicationMedia(type);
            int limit=platform.equals("douyin")&&type.equals("video")?30:20;
            ObjectNode post=validationPost(type,media,limit);
            assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(validationBatch(account,post)))).hasSize(1);
            rejectsBatchWithoutPartialRows(validationBatch(account,post.deepCopy().put("title","字".repeat(limit+1))));
            post.put("body","文".repeat(1000));
            assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(validationBatch(account,post)))).hasSize(1);
            rejectsBatchWithoutPartialRows(validationBatch(account,post.deepCopy().put("body","文".repeat(1001))));
            post.put("body","");for(int i=0;i<10;i++)post.withArray("tags").add("标签"+i);
            assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(validationBatch(account,post)))).hasSize(1);
            ObjectNode eleven=post.deepCopy();eleven.withArray("tags").add("标签10");
            rejectsBatchWithoutPartialRows(validationBatch(account,eleven));
            post.withArray("tags").removeAll().add("壁纸");post.put("body","文".repeat(995));
            assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(validationBatch(account,post)))).hasSize(1);
            rejectsBatchWithoutPartialRows(validationBatch(account,post.deepCopy().put("body","文".repeat(996))));
        }
    }
    @Test void rejectsEveryDraftParameterEvenWhenItsValueIsFalse(){
        String account=readyAccount("xhs"),media=publicationMedia("image");
        for(String key:List.of("draft","saveAsDraft","publishMode")){
            ObjectNode post=validationPost("image",media,1).put(key,false);
            rejectsBatchWithoutPartialRows(validationBatch(account,post));
        }
    }
    @Test void storedMediaSizeLimitsRejectOversizeWithoutCreatingPublicationMedia(){
        for(String type:List.of("image","video")){
            long limit=(type.equals("image")?32L:2048L)*1024*1024;
            String workspace="file-"+UUID.randomUUID();
            db.update("INSERT INTO creator_workspace_media(id,filename,media_type,mime_type,storage_key,size_bytes,sha256,metadata) VALUES(?,?,?,?,?,?,?,'{}')",workspace,type.equals("image")?"test.png":"test.mp4",type,type.equals("image")?"image/png":"video/mp4","test/"+workspace,limit,"b".repeat(64));
            assertThat(service.reuseMedia(obj().put("workspaceMediaId",workspace)).get("size")).isEqualTo(limit);
            String tooLarge="file-"+UUID.randomUUID();
            db.update("INSERT INTO creator_workspace_media(id,filename,media_type,mime_type,storage_key,size_bytes,sha256,metadata) VALUES(?,?,?,?,?,?,?,'{}')",tooLarge,type.equals("image")?"test.png":"test.mp4",type,type.equals("image")?"image/png":"video/mp4","test/"+tooLarge,limit+1,"c".repeat(64));
            assertThatThrownBy(()->service.reuseMedia(obj().put("workspaceMediaId",tooLarge))).isInstanceOf(ApiException.class).hasMessageContaining("大小");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM creator_publish_media WHERE workspace_media_id=?",Integer.class,tooLarge)).isZero();
        }
        assertThatThrownBy(()->service.reuseMedia(obj().put("workspaceMediaId","file-missing"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
    @Test void fiftyJobsAreAcceptedButFiftyOneLeaveNoPartialBatch(){
        String account=readyAccount("xhs");ObjectNode batch=obj().put("id",UUID.randomUUID().toString());
        ArrayNode rows=batch.putArray("entries");
        for(int i=0;i<50;i++)rows.addObject().put("accountId",account).set("post",validationPost("image",publicationMedia("image"),1));
        assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(batch))).hasSize(50);
        ObjectNode over=batch.deepCopy().put("id",UUID.randomUUID().toString());over.withArray("entries").add(rows.get(0).deepCopy());
        rejectsBatchWithoutPartialRows(over);over.withArray("entries").remove(50);
        assertThat(tx.<List<Map<String,Object>>>execute(s->service.createBatch(over))).hasSize(50);
    }
}
