package com.qingjing.wallpaper.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read models use all stored tasks, independently of the legacy recent-jobs endpoint. */
@Component
public class DistributionQueries {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DistributionService service;
    static final Set<String> STATES=Set.of("queued","running","submitting","submitted","published","failed","uncertain","needs_input","cancelled");
    DistributionQueries(JdbcTemplate db,ObjectMapper json,DistributionService service){this.db=db;this.json=json;this.service=service;}
    record Filter(String where,List<Object> args,Instant from,Instant to){}
    static Instant instant(String value){try{return Instant.parse(value);}catch(Exception e){throw DistributionService.bad("日期格式不正确");}}
    static int number(String value,int fallback,int max){if(value==null)return fallback;try{int n=Integer.parseInt(value);if(n>=1&&n<=max)return n;}catch(Exception ignored){}throw DistributionService.bad("分页参数不正确");}
    static Filter filter(Map<String,String> q,boolean overview){
        var allowed=overview?Set.of("platform","accountId","from","to"):Set.of("platform","accountId","from","to","status","type","keyword","batchId","workKey","page","pageSize");
        if(!allowed.containsAll(q.keySet()))throw DistributionService.bad("查询参数不正确");
        var conditions=new ArrayList<String>();var args=new ArrayList<Object>();
        for(String key:List.of("platform","accountId","status","type","batchId","workKey")){
            String value=q.getOrDefault(key,"");if(value.isEmpty())continue;
            if(key.equals("platform")&&!Set.of("douyin","xhs").contains(value)||key.equals("status")&&!STATES.contains(value)||key.equals("type")&&!Set.of("image","video").contains(value))throw DistributionService.bad("筛选条件不正确");
            if(key.equals("accountId")||key.equals("batchId"))DistributionService.uuid(value);
            if(key.equals("workKey")&&!value.matches("[a-f0-9]{64}"))throw DistributionService.bad("作品标识不正确");
            conditions.add(switch(key){case "platform"->"a.platform=?";case "accountId"->"j.account_id=?";case "status"->"j.status=?";case "type"->"JSON_UNQUOTE(JSON_EXTRACT(j.payload,'$.type'))=?";case "workKey"->WORK_KEY+"=?";default->"j.batch_id=?";});args.add(value);
        }
        Instant to=q.containsKey("to")?instant(q.get("to")):overview?Instant.now():null;
        Instant from=q.containsKey("from")?instant(q.get("from")):overview?to.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(29).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant():null;
        if(from!=null&&to!=null&&(!from.isBefore(to)||overview&&Duration.between(from,to).compareTo(Duration.ofDays(366))>0))throw DistributionService.bad("日期范围需在 1–366 天内");
        if(from!=null){conditions.add("j.created_at>=?");args.add(Timestamp.from(from));}
        if(to!=null){conditions.add("j.created_at<?");args.add(Timestamp.from(to));}
        String keyword=q.getOrDefault("keyword","").trim();
        if(keyword.codePointCount(0,keyword.length())>200)throw DistributionService.bad("搜索内容过长");
        if(!keyword.isEmpty()){conditions.add("LOCATE(?,CONCAT_WS(' ',JSON_UNQUOTE(JSON_EXTRACT(j.payload,'$.title')),JSON_EXTRACT(j.payload,'$.source.assets[*].workName'),a.display_name,a.nickname,j.message))>0");args.add(keyword);}
        return new Filter(conditions.isEmpty()?"":" WHERE "+String.join(" AND ",conditions),args,from,to);
    }
    private static final String JOIN=" FROM creator_publish_job j JOIN creator_social_account a ON a.id=j.account_id";
    // Caption/cover choices may differ by account; the ordered media defines the work.
    private static final String WORK_KEY="SHA2(CAST(JSON_ARRAY(JSON_EXTRACT(j.payload,'$.type'),COALESCE(JSON_EXTRACT(j.payload,'$.mediaIds'),JSON_ARRAY(j.id))) AS CHAR),256)";
    @Transactional(readOnly=true)
    public Map<String,Object> works(Map<String,String> q){
        var criteria=new HashMap<>(q);String result=criteria.remove("result");
        Filter f=filter(criteria,false);int page=number(q.get("page"),1,100000),size=number(q.get("pageSize"),20,50);
        String having=switch(result==null?"":result){
            case ""->"";
            case "published"->" HAVING SUM(j.status='published')=COUNT(*)";
            case "attention"->" HAVING SUM(j.status IN ('failed','needs_input','submitted','uncertain'))>0";
            case "pending"->" HAVING SUM(j.status IN ('queued','running','submitting'))>0";
            case "cancelled"->" HAVING SUM(j.status='cancelled')=COUNT(*)";
            default->throw DistributionService.bad("发布情况筛选不正确");
        };
        String selected=JOIN+" JOIN (SELECT j.batch_id,"+WORK_KEY+" work_key"+JOIN+f.where+" GROUP BY j.batch_id,work_key) matched ON j.batch_id=matched.batch_id AND "+WORK_KEY+"=matched.work_key";
        String groupedQuery="SELECT j.batch_id,matched.work_key,MIN(j.created_at) created_at"+selected+" GROUP BY j.batch_id,matched.work_key"+having;
        Long total=db.queryForObject("SELECT COUNT(*) FROM ("+groupedQuery+") counted",Long.class,f.args.toArray());
        var args=new ArrayList<>(f.args);args.add(size);args.add((long)(page-1)*size);
        var matched=db.queryForList(groupedQuery+" ORDER BY created_at DESC,j.batch_id,matched.work_key LIMIT ? OFFSET ?",args.toArray());
        var conditions=new ArrayList<String>();var ids=new ArrayList<Object>();
        for(var row:matched){conditions.add("(j.batch_id=? AND "+WORK_KEY+"=?)");ids.add(row.get("batch_id"));ids.add(row.get("work_key"));}
        var grouped=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:matched){var item=new LinkedHashMap<String,Object>();item.put("batchId",row.get("batch_id"));item.put("workKey",row.get("work_key"));item.put("jobs",new ArrayList<Map<String,Object>>());grouped.put(row.get("batch_id")+":"+row.get("work_key"),item);}
        if(!conditions.isEmpty())db.query("SELECT j.*,"+DistributionService.ACCOUNT_LABEL+" AS display_name,a.platform,a.platform_user_id,"+WORK_KEY+" work_key"+JOIN+" WHERE "+String.join(" OR ",conditions)+" ORDER BY j.created_at,j.batch_position,j.id",r->{
            @SuppressWarnings("unchecked") var jobs=(List<Map<String,Object>>)grouped.get(r.getString("batch_id")+":"+r.getString("work_key")).get("jobs");jobs.add(service.job(r,0));
        },ids.toArray());
        for(var item:grouped.values()){
            @SuppressWarnings("unchecked") var jobs=(List<Map<String,Object>>)item.remove("jobs");
            var first=jobs.get(0);JsonNode post=(JsonNode)first.get("post");var workNames=new LinkedHashSet<String>();
            for(JsonNode asset:post.path("source").path("assets"))if(!asset.path("workName").asText("").isEmpty())workNames.add(asset.path("workName").asText());
            var counts=new TreeMap<String,Long>();STATES.forEach(state->counts.put(state,0L));var accountIds=new HashSet<Object>();
            for(var job:jobs){String state=(String)job.get("status");counts.put(state,counts.getOrDefault(state,0L)+1);accountIds.add(job.get("accountId"));}
            item.put("title",workNames.size()==1?workNames.iterator().next():post.path("title").asText("未命名内容"));item.put("type",post.path("type").asText("image"));
            item.put("thumbnailMediaId",post.path("coverId").asText("").isEmpty()?post.path("mediaIds").path(0).asText(""):post.path("coverId").asText());
            item.put("accountCount",accountIds.size());item.put("taskCount",jobs.size());item.put("counts",counts);
            for(String key:List.of("createdAt","dueAt","scheduled"))item.put(key,first.get(key));
        }
        // Filters choose matching works; every member is returned for truthful counts.
        return Map.of("items",new ArrayList<>(grouped.values()),"total",total,"page",page,"pageSize",size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> jobs(Map<String,String> q){
        Filter f=filter(q,false);int page=number(q.get("page"),1,100000),size=number(q.get("pageSize"),20,100);
        Long total=db.queryForObject("SELECT COUNT(*)"+JOIN+f.where,Long.class,f.args.toArray());
        var args=new ArrayList<>(f.args);args.add(size);args.add((long)(page-1)*size);
        var rows=db.query("SELECT j.*,"+DistributionService.ACCOUNT_LABEL+" AS display_name,a.platform,a.platform_user_id"+JOIN+f.where+" ORDER BY j.created_at DESC,j.batch_id,j.batch_position,j.id LIMIT ? OFFSET ?",service::job,args.toArray());
        return Map.of("items",rows,"total",total,"page",page,"pageSize",size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> overview(Map<String,String> q){
        Filter f=filter(q,true);
        var counts=new TreeMap<String,Long>();STATES.forEach(key->counts.put(key,0L));
        db.query("SELECT j.status,COUNT(*) n"+JOIN+f.where+" GROUP BY j.status",r->{counts.put(r.getString("status"),r.getLong("n"));},f.args.toArray());
        var daily=db.queryForList("SELECT DATE(CONVERT_TZ(j.created_at,'+00:00','+08:00')) AS date,COUNT(*) AS total,SUM(j.status='published') AS published,SUM(j.status IN ('failed','needs_input','uncertain')) AS needsAttention"+JOIN+f.where+" GROUP BY date ORDER BY date",f.args.toArray());
        var accounts=db.queryForList("SELECT a.id AS accountId,"+DistributionService.ACCOUNT_LABEL+" AS name,a.platform,a.archived,COUNT(*) AS total,SUM(j.status='published') AS published,SUM(j.status IN ('submitted','uncertain')) AS awaiting,SUM(j.status IN ('failed','needs_input')) AS needsAttention"+JOIN+f.where+" GROUP BY a.id ORDER BY total DESC,a.id",f.args.toArray());
        // Snapshot values are not summed across days; deltas require both endpoints to contain the key.
        var metricArgs=new ArrayList<Object>();metricArgs.add(Timestamp.from(f.to));metricArgs.add(Timestamp.from(f.from));
        String accountWhere=" WHERE a.archived=FALSE";
        if(!q.getOrDefault("platform","").isEmpty()){accountWhere+=" AND a.platform=?";metricArgs.add(q.get("platform"));}
        if(!q.getOrDefault("accountId","").isEmpty()){accountWhere+=" AND a.id=?";metricArgs.add(q.get("accountId"));}
        var metrics=db.query("SELECT a.id,"+DistributionService.ACCOUNT_LABEL+" AS display_name,a.platform,m.metrics,m.collected_at,m.source_url,b.metrics AS baseline,b.collected_at AS baseline_at FROM creator_social_account a LEFT JOIN creator_account_metrics m ON m.id=(SELECT id FROM creator_account_metrics WHERE account_id=a.id AND collected_at<? ORDER BY collected_at DESC,id DESC LIMIT 1) LEFT JOIN creator_account_metrics b ON b.id=(SELECT id FROM creator_account_metrics WHERE account_id=a.id AND collected_at<=? ORDER BY collected_at DESC,id DESC LIMIT 1)"+accountWhere+" ORDER BY a.created_at",(r,i)->{
            var row=new LinkedHashMap<String,Object>();row.put("accountId",r.getString("id"));row.put("name",r.getString("display_name"));row.put("platform",r.getString("platform"));row.put("collectedAt",r.getTimestamp("collected_at"));row.put("baselineAt",r.getTimestamp("baseline_at"));row.put("sourceUrl",r.getString("source_url"));
            try{var latest=json.readTree(r.getString("metrics")==null?"{}":r.getString("metrics"));var baseline=json.readTree(r.getString("baseline")==null?"{}":r.getString("baseline"));var delta=new TreeMap<String,Long>();for(String key:List.of("followers","plays","likes","comments","favorites"))if(latest.has(key)&&baseline.has(key))delta.put(key,latest.path(key).longValue()-baseline.path(key).longValue());row.put("metrics",latest);row.put("delta",delta);}catch(Exception e){throw new IllegalStateException("Invalid stored metrics",e);}return row;
        },metricArgs.toArray());
        return Map.of("from",f.from,"to",f.to,"total",counts.values().stream().mapToLong(Long::longValue).sum(),"statuses",counts,"daily",daily,"accounts",accounts,"metrics",metrics,"timeZone","Asia/Shanghai");
    }
}
