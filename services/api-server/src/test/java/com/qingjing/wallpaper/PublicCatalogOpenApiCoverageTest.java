package com.qingjing.wallpaper;

import static org.assertj.core.api.Assertions.assertThat;

import com.qingjing.wallpaper.asset.PublicAssetController;
import com.qingjing.wallpaper.catalog.PublicCatalogController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

class PublicCatalogOpenApiCoverageTest {

    @Test
    void everyFrozenPublicCatalogOperationHasAControllerMapping() throws IOException {
        Set<String> contractOperations = contractOperations();
        Set<String> controllerOperations = controllerOperations(List.of(
                PublicCatalogController.class,
                PublicAssetController.class));

        assertThat(controllerOperations).containsExactlyInAnyOrderElementsOf(contractOperations);
        assertThat(contractOperations).hasSize(4);
    }

    @SuppressWarnings("unchecked")
    private Set<String> contractOperations() throws IOException {
        Path contract = Path.of("../../contracts/openapi/openapi.yaml").normalize();
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        Set<String> operations = new HashSet<>();
        paths.forEach((path, item) -> {
            if (!path.startsWith("/public/")) {
                return;
            }
            item.keySet().stream()
                    .filter(method -> Set.of("get", "post", "patch", "delete").contains(method))
                    .forEach(method -> operations.add(method.toUpperCase() + " /api/v1" + path));
        });
        return operations;
    }

    private Set<String> controllerOperations(List<Class<?>> controllers) {
        Set<String> operations = new HashSet<>();
        for (Class<?> controller : controllers) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    continue;
                }
                String suffix = mapping.path().length == 0 ? "" : mapping.path()[0];
                for (var httpMethod : mapping.method()) {
                    operations.add(httpMethod.name() + " " + prefix + suffix);
                }
            }
        }
        return operations;
    }
}
