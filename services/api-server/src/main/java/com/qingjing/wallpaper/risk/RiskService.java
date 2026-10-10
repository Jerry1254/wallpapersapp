package com.qingjing.wallpaper.risk;

import static com.qingjing.wallpaper.risk.RiskDtos.*;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class RiskService {
    private record Definition(String title, String description, List<String> platforms) {}
    private static final Map<String,Definition> DEFINITIONS = Map.of(
        "DEVELOPER_MODE",new Definition("开发者模式","安卓仅明确读取到开启才封禁；鸿蒙 26+ 使用 SafetyDetect 并由服务器验签，约每 90 分钟检查一次。开关关闭时跳过检测；隐藏、失败、旧系统或额度耗尽不封禁；iOS 不支持。",List.of("ANDROID","HARMONYOS")),
        "USB_DEBUGGING",new Definition("USB／无线调试","安卓仅明确读取到 USB 或无线调试开启才封禁，系统隐藏时不能保证检测；鸿蒙 26+ 由服务器验证 SafetyDetect 的 USB 和 Wi-Fi 位，任一开启即命中，约每 90 分钟检查一次。关闭规则跳过；失败、旧系统或额度耗尽不封禁；iOS 不支持。",List.of("ANDROID","HARMONYOS")),
        "ROOT_JAILBREAK",new Definition("Root／越狱","检查已知异常文件，仅在开关开启时运行；属于本机环境信号。",List.of("ANDROID","IOS")),
        "EMULATOR",new Definition("模拟器","检查本机模拟环境信号，仅在开关开启时运行。",List.of("ANDROID","IOS")),
        "REQUEST_FLOOD",new Definition("高频请求","同一设备或 IP 的业务请求超过窗口内阈值。",List.of("ALL")),
        "BULK_DOWNLOAD",new Definition("批量下载","同一设备或 IP 获取不同壁纸的下载授权超过阈值，重复获取同一壁纸不累加。",List.of("ALL")),
        "INVALID_SIGNATURE",new Definition("持续无效签名","设备已通过身份验证后，持续出现无效请求签名或重放。",List.of("ALL")),
        "RESOURCE_SCAN",new Definition("资源扫描","窗口内持续访问不存在的业务资源；不统计普通网络失败。",List.of("ALL")));
    private final JdbcTemplate jdbc;
    private final RiskCounter counters;
    private final ClientAddress address;
    private final HarmonyAttestationVerifier harmony;
    private final TransactionTemplate transaction;
    private final TransactionTemplate banTransaction;
    public RiskService(JdbcTemplate jdbc, RiskCounter counters, ClientAddress address, PlatformTransactionManager manager,
                       HarmonyAttestationVerifier harmony) {
        this.jdbc=jdbc; this.counters=counters; this.address=address;
        this.harmony=harmony;
        transaction=new TransactionTemplate(manager);
        banTransaction=new TransactionTemplate(manager);
        banTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Policy policy() {
        var p=jdbc.queryForMap("SELECT enabled,lock_version FROM security_policy WHERE id=1");
        var rules=jdbc.query("SELECT * FROM security_rule ORDER BY rule_key",(rs,n)-> {
            String key=rs.getString("rule_key"); var d=DEFINITIONS.get(key);
            var platforms=d.platforms().stream().filter(platform->!platform.equals("HARMONYOS") || harmony.available()).toList();
            return new Rule(key,d.title(),d.description(),rs.getBoolean("enabled"),!platforms.isEmpty(),
                platforms,rs.getInt("threshold_value"),rs.getInt("window_seconds"),rs.getLong("lock_version"));
        });
        Object enabled=p.get("enabled");
        return new Policy(enabled instanceof Boolean b?b:((Number)enabled).intValue()==1,
            ((Number)p.get("lock_version")).longValue(),rules);
    }
    public Policy updatePolicy(PolicyUpdate body) {
        if(jdbc.update("UPDATE security_policy SET enabled=?,lock_version=lock_version+1 WHERE id=1 AND lock_version=?",
            body.enabled(),body.version())!=1) throw conflict();
        return policy();
    }
    public Rule updateRule(String key,RuleUpdate body) {
        Definition d=DEFINITIONS.get(key); if(d==null) throw invalid("检测规则不存在");
        if(body.enabled() && d.platforms().isEmpty()) throw invalid("此检测尚无可用的平台适配，不能启用");
        if(jdbc.update("UPDATE security_rule SET enabled=?,threshold_value=?,window_seconds=?,lock_version=lock_version+1 WHERE rule_key=? AND lock_version=?",
            body.enabled(),body.threshold(),body.windowSeconds(),key,body.version())!=1) throw conflict();
        return policy().rules().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();
    }
    public State state(DevicePrincipal device,String ip) {
        if(blocked(device==null?null:device.deviceId(),ip)) return new State(false,List.of());
        Policy p=policy();
        if(!p.enabled() || device==null || whitelisted(device.deviceId(),ip)) return new State(true,List.of());
        return new State(true,p.rules().stream().filter(r->r.enabled() && r.platforms().contains(device.platform().name()))
            .map(Rule::key).toList());
    }
    public State report(DevicePrincipal device,String ip,Report body) {
        requireAllowed(device.deviceId(),ip);
        State s=state(device,ip);
        for(var e:body.signals().entrySet()) {
            if(!Set.of("DEVELOPER_MODE","USB_DEBUGGING","ROOT_JAILBREAK","EMULATOR").contains(e.getKey()) || e.getValue()==null)
                throw invalid("环境信号不符合要求");
        }
        // Harmony must use the separate Huawei-proof route. A signed installation request alone is insufficient.
        if(device.platform()==com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform.HARMONYOS) return s;
        return applySignals(device,ip,s,body.signals());
    }
    State reportHarmonyVerified(DevicePrincipal device,String ip,Map<String,Signal> signals) {
        requireAllowed(device.deviceId(),ip);
        return applySignals(device,ip,state(device,ip),signals);
    }
    private State applySignals(DevicePrincipal device,String ip,State s,Map<String,Signal> signals) {
        for(String key:s.checks()) if(signals.get(key)==Signal.RISK) {
            automaticBan(device.deviceId(),ip,key,"检测到"+DEFINITIONS.get(key).title());
            return state(device,ip);
        }
        return state(device,ip);
    }
    public boolean blocked(Long device,String ip) {
        Long n=jdbc.queryForObject("""
            SELECT COUNT(*) FROM security_ban WHERE released_at IS NULL AND
            ((subject_type='DEVICE' AND subject_value=?) OR (subject_type='IP' AND subject_value=?))
            """,Long.class,device==null?"":device.toString(),ip==null?"":ip);
        return n!=null && n>0;
    }
    public void requireAllowed(Long device,String ip) { if(blocked(device,ip)) throw unavailable(); }
    public void requireDevice(long device) { requireAllowed(device,null); }
    public String currentIp() {
        var attrs=RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes r?address.of(r.getRequest()):null;
    }
    public void requireCurrentAddress(long device) { requireAllowed(device,currentIp()); }
    public void count(String key,Long device,String ip,String distinct) {
        count(key,device,ip,distinct,true);
    }
    public void count(String key,Long device,String ip,String distinct,boolean countIp) {
        Policy p=policy(); if(!p.enabled() || whitelisted(device,ip)) return;
        Rule rule=p.rules().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();
        if(!rule.enabled()) return;
        boolean over=device!=null && counters.count(rule,"DEVICE:"+device,distinct)>rule.threshold();
        if(countIp && ip!=null) over=counters.count(rule,"IP:"+ip,distinct)>rule.threshold() || over;
        if(over) {
            automaticBan(device,ip,key,rule.title()+"超过阈值 "+rule.threshold()+" / "+rule.windowSeconds()+"秒");
            requireAllowed(device,ip);
        }
    }
    public void download(long device,long wallpaper) {
        var attrs=RequestContextHolder.getRequestAttributes();
        String ip=attrs instanceof ServletRequestAttributes r?address.of(r.getRequest()):null;
        requireAllowed(device,ip); count("BULK_DOWNLOAD",device,ip,Long.toString(wallpaper));
    }
    private boolean whitelisted(Long device,String ip) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM security_whitelist WHERE removed_at IS NULL AND
            ((subject_type='DEVICE' AND subject_value=?) OR (subject_type='IP' AND subject_value=?))
            """,Long.class,device==null?"":device.toString(),ip==null?"":ip)>0;
    }
    private void automaticBan(Long device,String ip,String rule,String reason) {
        banTransaction.executeWithoutResult(s->{
            // Serialize with rule edits: disabled rules cannot add a late ban. Existing bans are never cleared here.
            var enabled=jdbc.queryForObject("SELECT enabled FROM security_policy WHERE id=1 FOR UPDATE",Boolean.class);
            var ruleEnabled=jdbc.queryForObject("SELECT enabled FROM security_rule WHERE rule_key=? FOR UPDATE",Boolean.class,rule);
            if(Boolean.TRUE.equals(enabled) && Boolean.TRUE.equals(ruleEnabled) && !whitelisted(device,ip)) insertBan(device,ip,rule,reason,null);
        });
    }
    public void manualBan(BanRequest body,long admin) {
        Long device=optionalDevice(body.deviceId()); String ip=optionalIp(body.ip());
        if(device==null && ip==null) throw invalid("请填写设备 ID 或 IP");
        transaction.executeWithoutResult(s->insertBan(device,ip,"MANUAL",body.reason().strip(),admin));
    }
    private void insertBan(Long device,String ip,String rule,String reason,Long admin) {
        String group=UUID.randomUUID().toString();
        if(device!=null) insertSubject(group,"DEVICE",device.toString(),device,ip,rule,reason,admin);
        if(ip!=null) insertSubject(group,"IP",ip,device,ip,rule,reason,admin);
    }
    private void insertSubject(String group,String type,String value,Long device,String ip,String rule,String reason,Long admin) {
        jdbc.update("""
            INSERT INTO security_ban(group_id,subject_type,subject_value,origin_device_id,origin_ip,rule_key,reason,banned_by,banned_at)
            VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id
            """,group,type,value,device,ip,rule,reason,admin);
    }
    public BanPage bans(String search,String status,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100 || !Set.of("ACTIVE","RELEASED","ALL").contains(status)) throw invalid("查询参数不符合要求");
        String filter=" WHERE 1=1";
        if(status.equals("ACTIVE")) filter+=" AND b.released_at IS NULL";
        if(status.equals("RELEASED")) filter+=" AND b.released_at IS NOT NULL";
        var args=new ArrayList<Object>();
        if(search!=null && !search.isBlank()) {
            filter+=" AND (b.subject_value=? OR CAST(b.origin_device_id AS CHAR)=? OR b.origin_ip=?)";
            for(int n=0;n<3;n++) args.add(search.strip());
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM security_ban b"+filter,Long.class,args.toArray());
        args.add(pageSize); args.add((long)(page-1)*pageSize);
        var items=jdbc.query("""
            SELECT b.*,d.platform,a.username AS banned_username,r.username AS released_username
            FROM security_ban b LEFT JOIN anonymous_device d ON d.id=b.origin_device_id
            LEFT JOIN admin_account a ON a.id=b.banned_by LEFT JOIN admin_account r ON r.id=b.released_by
            """+filter+" ORDER BY b.id DESC LIMIT ? OFFSET ?",RiskService::ban,args.toArray());
        return new BanPage(items,total,page,pageSize);
    }
    public void release(long id,ReleaseRequest body,long admin) {
        List<Map<String,Object>> released=transaction.execute(s->{
            var rows=jdbc.queryForList("SELECT group_id,lock_version,released_at FROM security_ban WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw invalid("封禁记录不存在");
            var row=rows.get(0);
            if(row.get("released_at")!=null || ((Number)row.get("lock_version")).longValue()!=body.version()) throw conflict();
            String filter=body.includeRelated()?"group_id=?":"id=?";
            Object key=body.includeRelated()?row.get("group_id"):id;
            var subjects=jdbc.queryForList("SELECT subject_type,subject_value FROM security_ban WHERE "+filter+" AND released_at IS NULL",key);
            jdbc.update("UPDATE security_ban SET released_at=UTC_TIMESTAMP(6),released_by=?,release_reason=?,lock_version=lock_version+1 WHERE "+filter+" AND released_at IS NULL",
                admin,body.reason().strip(),key);
            return subjects;
        });
        // An explicit administrator release starts a fresh observation window instead of immediately reusing old counts.
        if(released!=null) for(var subject:released) for(var rule:policy().rules())
            counters.reset(rule,subject.get("subject_type")+":"+subject.get("subject_value"));
    }
    public List<Whitelist> whitelist() {
        return jdbc.query("SELECT * FROM security_whitelist WHERE removed_at IS NULL ORDER BY id DESC",(rs,n)->
            new Whitelist(rs.getString("id"),rs.getString("subject_type"),rs.getString("subject_value"),rs.getString("note"),instant(rs,"created_at")));
    }
    public List<Event> events() {
        return jdbc.query("""
            SELECT e.*,a.username FROM audit_event e LEFT JOIN admin_account a ON a.id=e.actor_admin_id
            WHERE e.aggregate_type='SECURITY_POLICY' ORDER BY e.id DESC LIMIT 100
            """,(rs,n)->new Event(rs.getString("id"),rs.getString("action"),rs.getString("username"),
                rs.getString("result"),rs.getString("change_summary"),instant(rs,"created_at")));
    }
    public void addWhitelist(WhitelistRequest body,long admin) {
        String value=switch(body.subjectType()) {
            case "DEVICE" -> optionalDevice(body.subjectValue()).toString();
            case "IP" -> optionalIp(body.subjectValue());
            default -> throw invalid("白名单类型不符合要求");
        };
        jdbc.update("INSERT INTO security_whitelist(subject_type,subject_value,note,created_by,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            body.subjectType(),value,body.note().strip(),admin);
    }
    public void removeWhitelist(long id,long admin) {
        if(jdbc.update("UPDATE security_whitelist SET removed_at=UTC_TIMESTAMP(6),removed_by=? WHERE id=? AND removed_at IS NULL",admin,id)!=1)
            throw conflict();
    }
    private Long optionalDevice(String value) {
        if(value==null || value.isBlank()) return null;
        long id=Ids.parse(value.strip(),"deviceId");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM anonymous_device WHERE id=?",Long.class,id)!=1) throw invalid("设备 ID 不存在");
        return id;
    }
    private String optionalIp(String value) {
        if(value==null || value.isBlank()) return null;
        try { return ClientAddress.normalize(value.strip()); } catch(IllegalArgumentException e) { throw invalid("IP 格式不正确"); }
    }
    private static Ban ban(ResultSet rs,int n) throws SQLException {
        return new Ban(rs.getString("id"),rs.getString("group_id"),rs.getString("subject_type"),rs.getString("subject_value"),
            rs.getString("origin_device_id"),rs.getString("platform"),rs.getString("origin_ip"),rs.getString("rule_key"),rs.getString("reason"),
            instant(rs,"banned_at"),rs.getString("banned_username"),instant(rs,"released_at"),rs.getString("released_username"),rs.getString("release_reason"),rs.getLong("lock_version"));
    }
    private static Instant instant(ResultSet rs,String key) throws SQLException { var t=rs.getTimestamp(key); return t==null?null:t.toInstant(); }
    public static ApiException unavailable() { return new ApiException(HttpStatus.FORBIDDEN,"ACCESS_UNAVAILABLE","网络异常，请稍后重试"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"SECURITY_INVALID",message); }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","数据已变化，请刷新后重试"); }
}
