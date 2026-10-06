package com.qingjing.wallpaper.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.EntityTags;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/creator")
public class CreatorWorkspaceController {
    private final CreatorWorkspaceService service;
    private final ObjectMapper json;
    CreatorWorkspaceController(CreatorWorkspaceService service,ObjectMapper json){this.service=service;this.json=json;}
    @GetMapping("/records/{collection}") public Object records(@PathVariable String collection,@RequestParam(defaultValue="")String after,@RequestParam(defaultValue="20")int limit){return service.records(collection,after,limit);}
    @GetMapping("/records/{collection}/{id}") public ResponseEntity<?> record(@PathVariable String collection,@PathVariable String id){var value=service.record(collection,id);return ResponseEntity.ok().eTag(EntityTags.of(((Number)value.get("version")).longValue())).body(value);}
    @PutMapping("/records/{collection}/{id}") public ResponseEntity<?> save(@PathVariable String collection,@PathVariable String id,@RequestHeader("If-Match")String version,@RequestBody JsonNode body){var result=service.save(collection,id,EntityTags.parseRequired(version),body);return ResponseEntity.ok().eTag(EntityTags.of(((Number)result.get("version")).longValue())).body(result);}
    @DeleteMapping("/records/{collection}/{id}") public ResponseEntity<?> delete(@PathVariable String collection,@PathVariable String id,@RequestHeader("If-Match")String version){service.remove(collection,id,EntityTags.parseRequired(version));return ResponseEntity.noContent().build();}
    @PostMapping(value="/media",consumes="application/octet-stream") public Object upload(@RequestHeader("X-Media-Id")String id,@RequestHeader("X-Filename")String name,@RequestHeader("X-Media-Metadata")String metadata,HttpServletRequest request)throws IOException {
        if(metadata.length()>16000)throw CreatorWorkspaceService.invalid("素材元数据过大");return service.upload(id,URLDecoder.decode(name,StandardCharsets.UTF_8),json.readTree(URLDecoder.decode(metadata,StandardCharsets.UTF_8)),request.getInputStream());
    }
    @GetMapping("/media/{id}") public Object media(@PathVariable String id){return service.media(id);}
    @GetMapping("/media/{id}/content") public ResponseEntity<StreamingResponseBody> content(@PathVariable String id,@RequestHeader(value="Range",required=false)String range)throws IOException {
        var media=service.media(id);long size=((Number)media.get("sizeBytes")).longValue(),start=0,end=size-1;
        if(range!=null){try{if(!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))throw new IllegalArgumentException();String[] parts=range.substring(6).split("-",-1);if(parts[0].isEmpty())start=Math.max(0,size-Long.parseLong(parts[1]));else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}if(start>=size||start>end)throw new IllegalArgumentException();}catch(RuntimeException e){return ResponseEntity.status(416).header("Content-Range","bytes */"+size).build();}}
        final long offset=start,length=end-start+1;var response=ResponseEntity.status(range==null?200:206).header("Accept-Ranges","bytes").header("Cache-Control","no-store").eTag("\""+media.get("sha256")+"\"").header("Content-Type",(String)media.get("mimeType")).contentLength(length);
        if(range!=null)response.header("Content-Range","bytes "+start+"-"+end+"/"+size);
        return response.body(output->{try(var content=service.content(id)){content.inputStream().skipNBytes(offset);byte[] buffer=new byte[32768];long remaining=length;while(remaining>0){int read=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(read<0)throw new IOException("Creator media truncated");output.write(buffer,0,read);remaining-=read;}}});
    }
    @GetMapping("/tasks") public Object tasks(){return service.tasks();}
    @PostMapping("/tasks") public ResponseEntity<?> create(@RequestBody JsonNode n){return ResponseEntity.accepted().body(service.createTask(n));}
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id){return service.task(id);}
    @PostMapping("/tasks/{id}/action") public Object action(@PathVariable String id,@RequestBody JsonNode n){return service.action(id,n);}
    @PostMapping("/claim") public Object claim(@RequestBody JsonNode n){return service.claim(n.path("runnerId").asText());}
    @PostMapping("/tasks/{id}/report") public Object report(@PathVariable String id,@RequestBody JsonNode n){return service.report(id,n);}
}
