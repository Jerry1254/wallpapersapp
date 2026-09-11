package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminCategory;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CategoryList;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CategoryWriteRequest;

import com.qingjing.wallpaper.shared.web.EntityTags;
import com.qingjing.wallpaper.shared.web.Ids;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/categories")
public class AdminCategoryController {

    private final AdminCategoryService categories;

    public AdminCategoryController(AdminCategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    CategoryList list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return new CategoryList(categories.list(includeDeleted));
    }

    @PostMapping
    ResponseEntity<AdminCategory> create(@Valid @RequestBody CategoryWriteRequest request) {
        AdminCategory created = categories.create(request);
        return ResponseEntity.status(201)
                .header(HttpHeaders.ETAG, EntityTags.of(created.version()))
                .body(created);
    }

    @GetMapping("/{categoryId}")
    ResponseEntity<AdminCategory> get(@PathVariable String categoryId) {
        AdminCategory category = categories.get(Ids.parse(categoryId, "categoryId"));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EntityTags.of(category.version()))
                .body(category);
    }

    @PatchMapping("/{categoryId}")
    ResponseEntity<AdminCategory> update(
            @PathVariable String categoryId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody CategoryWriteRequest request) {
        AdminCategory category = categories.update(
                Ids.parse(categoryId, "categoryId"),
                EntityTags.parseRequired(ifMatch),
                request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EntityTags.of(category.version()))
                .body(category);
    }

    @DeleteMapping("/{categoryId}")
    ResponseEntity<Void> delete(
            @PathVariable String categoryId,
            @RequestHeader("If-Match") String ifMatch) {
        categories.delete(Ids.parse(categoryId, "categoryId"), EntityTags.parseRequired(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
