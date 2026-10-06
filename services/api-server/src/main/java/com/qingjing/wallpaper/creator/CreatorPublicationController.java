package com.qingjing.wallpaper.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.creator.CreatorPublicationDtos.Task;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/creator")
public class CreatorPublicationController {
    private final CreatorCapabilities capabilities;
    private final CreatorPublicationService publications;
    public CreatorPublicationController(CreatorCapabilities capabilities,CreatorPublicationService publications) {
        this.capabilities=capabilities;this.publications=publications;
    }
    @GetMapping("/capabilities")
    CreatorCapabilities.Rules capabilities(){return capabilities.get();}

    @PostMapping("/wallpaper-publications")
    ResponseEntity<Task> submit(@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,HttpServletRequest request) {
        var admin=(AdminPrincipal)request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        Task task=publications.submit(body,key,admin.id());
        return ResponseEntity.accepted().header("Location","/api/v1/admin/creator/wallpaper-publications/"+task.taskId()).body(task);
    }
    @GetMapping("/wallpaper-publications")
    java.util.Map<String,Object> list(@RequestParam(required=false) String clientProjectKey,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return publications.list(clientProjectKey,page,pageSize);
    }
    @GetMapping("/wallpaper-publications/{taskId}")
    Task get(@PathVariable String taskId){return publications.get(Ids.parse(taskId,"taskId"));}

    @PostMapping("/wallpaper-publications/{taskId}/retry")
    ResponseEntity<Task> retry(@PathVariable String taskId){return ResponseEntity.accepted().body(publications.retry(Ids.parse(taskId,"taskId")));}
}
