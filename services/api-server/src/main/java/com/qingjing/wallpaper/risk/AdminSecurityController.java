package com.qingjing.wallpaper.risk;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/security")
public class AdminSecurityController {
    private final RiskService risk;
    public AdminSecurityController(RiskService risk) { this.risk=risk; }
    @GetMapping("/policy") public RiskDtos.Policy policy() { return risk.policy(); }
    @PutMapping("/policy") public RiskDtos.Policy policy(@Valid @RequestBody RiskDtos.PolicyUpdate body,HttpServletRequest request) {
        summary(request,Map.of("enabled",body.enabled(),"previousVersion",body.version())); return risk.updatePolicy(body);
    }
    @PutMapping("/rules/{key}") public RiskDtos.Rule rule(@PathVariable String key,@Valid @RequestBody RiskDtos.RuleUpdate body,HttpServletRequest request) {
        summary(request,Map.of("rule",key,"enabled",body.enabled(),"threshold",body.threshold(),"windowSeconds",body.windowSeconds(),"previousVersion",body.version()));
        return risk.updateRule(key,body);
    }
    @GetMapping("/bans") public RiskDtos.BanPage bans(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="ACTIVE") String status,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) { return risk.bans(search,status,page,pageSize); }
    @PostMapping("/bans") public ResponseEntity<Void> ban(@Valid @RequestBody RiskDtos.BanRequest body,HttpServletRequest request) {
        summary(request,Map.of("deviceId",body.deviceId()==null?"":body.deviceId(),"ip",body.ip()==null?"":body.ip(),"reason",body.reason()));
        risk.manualBan(body,admin(request)); return ResponseEntity.status(201).build();
    }
    @PostMapping("/bans/{id}/release") public ResponseEntity<Void> release(@PathVariable String id,@Valid @RequestBody RiskDtos.ReleaseRequest body,HttpServletRequest request) {
        summary(request,Map.of("banId",id,"includeRelated",body.includeRelated(),"reason",body.reason()));
        risk.release(Ids.parse(id,"id"),body,admin(request)); return ResponseEntity.noContent().build();
    }
    @GetMapping("/whitelist") public List<RiskDtos.Whitelist> whitelist() { return risk.whitelist(); }
    @GetMapping("/events") public List<RiskDtos.Event> events() { return risk.events(); }
    @PostMapping("/whitelist") public ResponseEntity<Void> whitelist(@Valid @RequestBody RiskDtos.WhitelistRequest body,HttpServletRequest request) {
        summary(request,Map.of("subjectType",body.subjectType(),"subjectValue",body.subjectValue(),"note",body.note()));
        risk.addWhitelist(body,admin(request)); return ResponseEntity.status(201).build();
    }
    @DeleteMapping("/whitelist/{id}") public ResponseEntity<Void> remove(@PathVariable String id,HttpServletRequest request) {
        summary(request,Map.of("whitelistId",id)); risk.removeWhitelist(Ids.parse(id,"id"),admin(request)); return ResponseEntity.noContent().build();
    }
    private long admin(HttpServletRequest request) { return ((AdminPrincipal)request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL)).id(); }
    private void summary(HttpServletRequest request,Map<String,Object> value) { request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,value); }
}
