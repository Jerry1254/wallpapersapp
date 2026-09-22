package com.qingjing.wallpaper.parallax;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

public final class ParallaxFixtures {
    private ParallaxFixtures() {}
    public static Map<String, byte[]> files(int count) throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();
        List<Map<String,Object>> layers=new ArrayList<>();
        for(int i=1;i<=count;i++) {
            files.put(String.format(Locale.ROOT,"layers/%02d.png",i),image("png",i<count,512));
            layers.add(Map.of("index",i,"offsetXPercent",i==count?3:8,"offsetYPercent",i==count?2:6,
                    "initialOffsetXPercent",0,"initialOffsetYPercent",0,"direction",i==count?"reverse":"follow",
                    "scale",i==count?1:1.18,"opacity",1,"blendMode","normal"));
        }
        files.put("config.json",new ObjectMapper().writeValueAsBytes(Map.of("formatVersion",2,"canvas",Map.of("width",512,"height",512),
                "motion",Map.of("maxAngleX",75,"maxAngleY",75),"layers",layers)));
        return files;
    }
    public static byte[] image(String format,boolean alpha,int size) throws Exception {
        BufferedImage image;
        if(alpha && format.equals("png")) {
            byte[] red={0,(byte)255},green={0,(byte)165},blue={0,0},opacity={0,(byte)255};
            image=new BufferedImage(size,size,BufferedImage.TYPE_BYTE_INDEXED,
                    new IndexColorModel(8,2,red,green,blue,opacity));
        } else image=new BufferedImage(size,size,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(java.awt.Color.ORANGE);graphics.fillOval(80,80,180,180);graphics.dispose();
        try(var out=new ByteArrayOutputStream()){ImageIO.write(image,format,out);return out.toByteArray();}
        finally{image.flush();}
    }
    public static byte[] zip(Map<String,byte[]> files) throws Exception {return zip(files,false);}
    public static byte[] zip(Map<String,byte[]> files,boolean stored) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)) {
            for(var file:files.entrySet()) {
                var entry=new ZipEntry(file.getKey());entry.setTime(315532800000L);
                if(stored){var crc=new CRC32();crc.update(file.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(file.getValue().length);entry.setCrc(crc.getValue());}
                zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
