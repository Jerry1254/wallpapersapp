package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ParallaxPackageReader {
    private final JdbcTemplate jdbc;
    public ParallaxPackageReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public ParallaxPackageDtos.SourcePackage get(long id) {
        var rows=jdbc.query("""
                SELECT id,original_filename,size_bytes,sha256,canvas_width,canvas_height,format_version
                FROM parallax_source_package WHERE id=? AND status='READY'
                """, (rs,n) -> new Row(rs.getLong("id"),rs.getString("original_filename"),rs.getLong("size_bytes"),
                rs.getString("sha256"),rs.getInt("canvas_width"),rs.getInt("canvas_height"),rs.getInt("format_version")),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","4D 源包不存在或不可用");
        var row=rows.get(0);
        var layers=jdbc.query("""
                SELECT layer_index,original_filename,role,ordinal
                FROM parallax_source_layer WHERE source_package_id=? ORDER BY layer_index
                """,(rs,n)->new ParallaxPackageDtos.Layer(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getInt(4)),id);
        return new ParallaxPackageDtos.SourcePackage(Long.toString(id),row.name(),"application/zip",row.size(),row.sha(),
                "READY",row.formatVersion(),new ParallaxPackageDtos.Canvas(row.width(),row.height()),layers);
    }
    public ParallaxPackageDtos.SourcePackage forVersion(long versionId) {
        Long source=jdbc.queryForObject("SELECT source_package_id FROM resource_version WHERE id=?",Long.class,versionId);
        return source==null?null:get(source);
    }
    private record Row(long id,String name,long size,String sha,int width,int height,int formatVersion) {}
}
