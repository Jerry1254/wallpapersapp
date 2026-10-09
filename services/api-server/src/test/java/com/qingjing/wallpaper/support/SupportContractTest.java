package com.qingjing.wallpaper.support;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

class SupportContractTest {
    @Test @SuppressWarnings("unchecked") void everySupportOperationHasItsControllerAndAuthenticationContract() throws Exception {
        Map<String,Object> document = new Yaml().load(Files.readString(Path.of("../../contracts/openapi/openapi.yaml")));
        Map<String,Map<String,Object>> paths = (Map<String,Map<String,Object>>) document.get("paths");
        var contract = new HashSet<String>(); var actual = new HashSet<String>();
        paths.forEach((path,item) -> {
            if (!path.contains("/support/")) return;
            item.forEach((verb,value) -> {
                if (!Set.of("get","post","put","delete").contains(verb)) return;
                contract.add(verb.toUpperCase()+" /api/v1"+path);
                var operation = (Map<String,Object>) value;
                List<Map<String,Object>> security = (List<Map<String,Object>>) operation.get("security");
                if (path.startsWith("/admin/")) {
                    assertThat(security).anySatisfy(rule -> assertThat(rule).containsKey("adminCookie"));
                    if (!verb.equals("get")) assertThat(security).anySatisfy(rule -> assertThat(rule).containsKey("adminCsrf"));
                } else if (path.startsWith("/device/")) assertThat(security).anySatisfy(rule -> assertThat(rule).containsKey("deviceBearer"));
                else assertThat(security).anySatisfy(rule -> assertThat(rule).containsKey("supportMediaTicket"));
            });
        });
        for (Class<?> type : List.of(DeviceSupportController.class,AdminSupportController.class,SupportFileController.class)) {
            var root = AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
            for (var method : type.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
                if (mapping != null) for (var verb : mapping.method()) actual.add(verb.name()+" "+root.path()[0]+mapping.path()[0]);
            }
        }
        assertThat(actual).isNotEmpty().containsExactlyInAnyOrderElementsOf(contract);
    }
}
