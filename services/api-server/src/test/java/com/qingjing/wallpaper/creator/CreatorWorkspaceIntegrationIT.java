package com.qingjing.wallpaper.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.FileStorage;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class CreatorWorkspaceIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("creator_workspace_test").withUsername("creator_test").withPassword(UUID.randomUUID().toString());
    static final ObjectMapper JSON=new ObjectMapper();
    static JdbcTemplate jdbc;
    static CreatorWorkspaceService workspace;
    @BeforeAll static void database()throws Exception{
        var datasource=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(datasource.getConnection(),true));
        workspace=new CreatorWorkspaceService(jdbc,JSON,mock(FileStorage.class));
    }
    @BeforeEach void clearCreatorFixtures(){
        jdbc.update("DELETE FROM creator_workspace_task");
        jdbc.update("DELETE FROM creator_workspace_reference");
        jdbc.update("DELETE FROM creator_workspace_record");
        jdbc.update("DELETE FROM creator_workspace_media");
    }
    @Test void largeSnapshotsCanBeListedWithSmallMySqlSortBuffer(){
        jdbc.execute("SET SESSION sort_buffer_size=32768");
        String large="x".repeat(512*1024);
        for(String id:List.of("qa-c","qa-a","qa-b")){
            var body=JSON.createObjectNode();body.putObject("record").put("id",id).put("name",id).put("thumbnail",large);
            body.putArray("mediaIds");workspace.save("projects",id,0,body);
        }
        var first=workspace.records("projects","",2);
        assertThat(ids(first)).containsExactly("qa-a","qa-b");
        assertThat(first.get("nextCursor")).isEqualTo("qa-b");
        var last=workspace.records("projects","qa-b",2);
        assertThat(ids(last)).containsExactly("qa-c");
        assertThat(last.get("nextCursor")).isNull();
        var row=(Map<?,?>)((List<?>)last.get("items")).get(0);
        assertThat(((JsonNode)row.get("record")).path("thumbnail").asText()).isEqualTo(large);
    }
    private List<String> ids(Map<String,Object> page){
        return ((List<?>)page.get("items")).stream().map(value->((JsonNode)((Map<?,?>)value).get("record")).path("id").asText()).toList();
    }
    private Map<String,Object> preparing()throws Exception{
        var body=JSON.createObjectNode();body.putObject("record").put("id","task-project").put("name","任务恢复验收");body.putArray("mediaIds");workspace.save("projects","task-project",0,body);
        for(String type:List.of("bundle","video"))jdbc.update("INSERT IGNORE INTO creator_workspace_media(id,filename,media_type,mime_type,storage_key,size_bytes,sha256,metadata) VALUES(?,?,?,?,?,1,?, '{}')",type,type+".test",type,"application/octet-stream","fixture/"+type,"0".repeat(64));
        return workspace.createTask(JSON.readTree("{\"id\":\""+UUID.randomUUID()+"\",\"projectId\":\"task-project\",\"type\":\"CONTENT_RENDER\",\"input\":{\"name\":\"冻结内容\",\"fps\":30,\"duration\":10}}"));
    }
    private Map<String,Object> action(Map<String,Object> task,String action)throws Exception{
        return workspace.action((String)task.get("id"),JSON.readTree("{\"action\":\""+action+"\",\"payloadMediaId\":\"bundle\"}"));
    }
    private JsonNode report(Map<String,Object> task,String state){
        var n=JSON.createObjectNode();n.put("runnerId",(String)jdbc.queryForObject("SELECT runner_id FROM creator_workspace_task WHERE id=?",String.class,task.get("id")));n.put("leaseToken",(String)task.get("leaseToken"));n.put("attempt",(Integer)task.get("attempt"));n.put("state",state);n.putArray("outputMediaIds").add("video");return n;
    }
    @Test void expiredWorkerCannotCompleteAndRetryRetainsFrozenInput()throws Exception{
        var original=preparing();action(original,"enqueue");var first=workspace.claim(UUID.randomUUID().toString());var stale=report(first,"SUCCEEDED");
        jdbc.update("UPDATE creator_workspace_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",first.get("id"));
        assertThat(workspace.claim(UUID.randomUUID().toString())).isEmpty();
        var interrupted=workspace.task((String)first.get("id"));assertThat(interrupted.get("state")).isEqualTo("INTERRUPTED");assertThat(((JsonNode)interrupted.get("outputMediaIds")).isEmpty()).isTrue();
        assertThatThrownBy(()->workspace.report((String)first.get("id"),stale)).hasMessageContaining("租约已过期");
        action(first,"retry");var retry=workspace.claim(UUID.randomUUID().toString());assertThat(retry.get("id")).isEqualTo(first.get("id"));assertThat(retry.get("attempt")).isEqualTo(2);assertThat(retry.get("input")).isEqualTo(original.get("input"));assertThat(retry.get("payloadMediaId")).isEqualTo("bundle");
        assertThat(workspace.report((String)retry.get("id"),report(retry,"SUCCEEDED")).get("state")).isEqualTo("SUCCEEDED");
    }
    @Test void cancellationWinsBeforeQueueAndOverLateWorkerSuccess()throws Exception{
        var task=preparing();assertThat(action(task,"cancel").get("state")).isEqualTo("CANCELLED");assertThat(workspace.claim(UUID.randomUUID().toString())).isEmpty();
        task=preparing();action(task,"enqueue");assertThat(action(task,"cancel").get("state")).isEqualTo("CANCELLED");assertThat(workspace.claim(UUID.randomUUID().toString())).isEmpty();
        task=preparing();action(task,"enqueue");var claimed=workspace.claim(UUID.randomUUID().toString());assertThat(action(claimed,"cancel").get("state")).isEqualTo("CANCEL_REQUESTED");
        var finalTask=workspace.report((String)claimed.get("id"),report(claimed,"SUCCEEDED"));assertThat(finalTask.get("state")).isEqualTo("CANCELLED");assertThat(((JsonNode)finalTask.get("outputMediaIds")).isEmpty()).isTrue();
    }
    @Test void closingPreparationCannotFailAlreadyQueuedOrCompletedWork()throws Exception{
        var failed=preparing();assertThat(action(failed,"fail").get("state")).isEqualTo("FAILED");
        assertThatThrownBy(()->action(failed,"retry")).hasMessageContaining("重新打开编辑页面");
        var task=preparing();action(task,"enqueue");assertThat(action(task,"fail").get("state")).isEqualTo("QUEUED");var claimed=workspace.claim(UUID.randomUUID().toString());workspace.report((String)claimed.get("id"),report(claimed,"SUCCEEDED"));assertThat(action(claimed,"fail").get("state")).isEqualTo("SUCCEEDED");
    }
}
