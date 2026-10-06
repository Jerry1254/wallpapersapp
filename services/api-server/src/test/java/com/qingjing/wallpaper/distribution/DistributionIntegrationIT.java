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
    static final String RUNNER=UUID.randomUUID().toString(),LEGACY=UUID.randomUUID().toString();
    DistributionService service;
    FileStorage storage;
    @BeforeAll static void database(){
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("25").load().migrate();
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.update("INSERT INTO creator_social_account(id,platform,display_name,runner_id,login_status) VALUES(?,'xhs','已有账号',?,'ready')",LEGACY,RUNNER);
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @BeforeEach void setup(){storage=mock(FileStorage.class);service=new DistributionService(db,JSON,storage);}
    ObjectNode obj(){return JSON.createObjectNode();}
    String account(){return (String)service.createAccount(obj().put("platform","xhs").put("name","备注").put("runnerId",RUNNER)).get("id");}
    ObjectNode identity(String value){return obj().put("platformUserId","handle:"+value).put("nickname","真实昵称").put("avatarUrl","");}
    ObjectNode update(String value){ObjectNode n=obj().put("status","ready").put("runnerId",RUNNER);n.set("identity",identity(value));return n;}
    Map<String,Object> bind(String id,String value){return tx.execute(s->service.updateAccount(id,update(value)));}
    @Test void migratesLegacyAccountsWithoutDiscardingTheirRecords(){assertThat(service.requireAccount(LEGACY)).containsEntry("status","unverified").containsEntry("platformUserId",null);}
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
}
