package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminParallaxPackageController {
    private final ParallaxPackageService service;
    public AdminParallaxPackageController(ParallaxPackageService service){this.service=service;}

    @PostMapping(path="/parallax-packages",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ParallaxPackageDtos.SourcePackage> upload(@RequestPart(value="file",required=false) MultipartFile file,HttpServletRequest request) throws IOException {
        if(file==null||file.isEmpty())throw required();
        try(var input=file.getInputStream()) {
            var result=service.importZip(input,file.getOriginalFilename(),admin(request),requestId(request));
            return ResponseEntity.status(result.created()?201:200).body(result.value());
        }
    }
    @PostMapping("/variants/{variantId}/parallax-resource-versions")
    ResponseEntity<com.qingjing.wallpaper.catalog.AdminContentDtos.AdminResourceVersion> version(
            @PathVariable String variantId,@RequestBody ParallaxPackageDtos.VersionRequest body,HttpServletRequest request) {
        if(body.sourcePackageId()==null||body.sourcePackageId().isBlank())throw required();
        if(body.versionNo()==null)throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","缺少 versionNo");
        var result=service.createVersion(Ids.parse(variantId,"variantId"),body.versionNo(),Ids.parse(body.sourcePackageId(),"sourcePackageId"),admin(request),requestId(request));
        return ResponseEntity.status(result.created()?201:200).body(result.value());
    }
    private static ApiException required(){return new ApiException(HttpStatus.BAD_REQUEST,"PARALLAX_PACKAGE_REQUIRED","请选择 4D ZIP 资源包或提供 sourcePackageId");}
    private static long admin(HttpServletRequest r){return ((AdminPrincipal)r.getAttribute(RequestAttributes.ADMIN_PRINCIPAL)).id();}
    private static String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(RequestAttributes.REQUEST_ID));}
}
