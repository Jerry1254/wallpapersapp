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
}
