package com.qingjing.wallpaper.risk;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

class RiskContractTest {
    @Test @SuppressWarnings("unchecked") void securityOperationsRequireSeparateAdminOrDeviceIdentity() throws Exception {
        Map<String,Object> doc=new Yaml().load(Files.readString(Path.of("../../contracts/openapi/openapi.yaml")));
        var paths=(Map<String,Map<String,Object>>)doc.get("paths");
        var contract=new HashSet<String>();var actual=new HashSet<String>();
        paths.forEach((path,item)->{
            if(!path.contains("/security/")) return;
            item.forEach((verb,value)->{
                if(!Set.of("get","post","put","delete").contains(verb))return;
                contract.add(verb.toUpperCase()+" /api/v1"+path);
                var op=(Map<String,Object>)value;var auth=(List<Map<String,Object>>)op.get("security");
                String identity=path.startsWith("/admin/")?"adminCookie":"deviceBearer";
                assertThat(auth).anySatisfy(r->assertThat(r).containsKey(identity));
                if(path.startsWith("/admin/") && !verb.equals("get")) assertThat(auth).anySatisfy(r->assertThat(r).containsKey("adminCsrf"));
            });
        });
        for(Class<?> type:List.of(AdminSecurityController.class,DeviceSecurityController.class)) {
            var root=AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
            for(var method:type.getDeclaredMethods()) {
                var mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
                if(mapping!=null)for(var verb:mapping.method())actual.add(verb.name()+" "+root.path()[0]+mapping.path()[0]);
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(contract);
    }
}
