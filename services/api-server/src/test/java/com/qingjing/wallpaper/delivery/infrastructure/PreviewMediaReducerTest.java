package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PreviewMediaReducerTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final PreviewMediaReducer reducer=new PreviewMediaReducer(mapper,"unused");
    private static byte[] png(int width,int height,boolean alpha) throws Exception {
        BufferedImage image=new BufferedImage(width,height,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        try { if(!alpha) { graphics.setColor(new Color(20,50,90));graphics.fillRect(0,0,width,height); } }
        finally { graphics.dispose(); }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);image.flush();return bytes.toByteArray();
    }
    @Test void staticPreviewHasReducedDimensionsAndVisibleMarks() throws Exception {
        byte[] source=png(2048,4096,false);
        var result=reducer.reduce("STATIC_IMAGE",List.of(new SecurePackageCodec.Payload("STATIC_IMAGE",0,"image/png",source)),
            Map.of("STATIC_IMAGE:0",new PackageMediaInspector.Media(2048,4096,false)));
        assertThat(result).hasSize(1);assertThat(result.get(0).mimeType()).isEqualTo("image/jpeg");assertThat(result.get(0).content()).isNotEqualTo(source);
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(result.get(0).content()));
        try {
            assertThat(image.getWidth()).isEqualTo(640);assertThat(image.getHeight()).isEqualTo(1280);
            // Source is a uniform field; visible spatial variation must come from the preview mark.
            Set<Integer> colors=new HashSet<>();for(int y=0;y<image.getHeight();y+=5) for(int x=0;x<image.getWidth();x+=5) colors.add(image.getRGB(x,y));
            assertThat(colors.size()).isGreaterThan(20);
        } finally { image.flush(); }
    }
    @Test void allParallaxLayersShareReducedCanvasAndRetainTransparencyAndSensorConfiguration() throws Exception {
        byte[] background=png(768,1536,false),foreground=png(768,1536,true);
        byte[] config="""
            {"canvas":{"width":768,"height":1536},"sensor":{"maxAngle":10,"smoothing":0.2,"strength":1},"layers":[{"role":"BACKGROUND","ordinal":0,"depth":0,"scale":1.1,"opacity":1,"blendMode":"normal"},{"role":"FOREGROUND","ordinal":0,"depth":1,"scale":1.1,"opacity":1,"blendMode":"normal"}]}""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var sources=List.of(new SecurePackageCodec.Payload("BACKGROUND",0,"image/png",background),new SecurePackageCodec.Payload("FOREGROUND",0,"image/png",foreground),new SecurePackageCodec.Payload("PARALLAX_CONFIG",0,"application/json",config));
        var result=reducer.reduce("LAYER_PARALLAX",sources,Map.of("BACKGROUND:0",new PackageMediaInspector.Media(768,1536,false),"FOREGROUND:0",new PackageMediaInspector.Media(768,1536,true)));
        assertThat(result).hasSize(3);
        for(var item:result) if(!item.role().equals("PARALLAX_CONFIG")) {
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(item.content()));
            try {
                assertThat(image.getWidth()).isEqualTo(640);assertThat(image.getHeight()).isEqualTo(1280);
                if(item.role().equals("FOREGROUND")) {
                    assertThat(image.getColorModel().hasAlpha()).isTrue();boolean transparent=false,marked=false;
                    for(int y=0;y<image.getHeight();y+=4) for(int x=0;x<image.getWidth();x+=4) { int alpha=image.getRGB(x,y)>>>24;transparent|=alpha==0;marked|=alpha>0; }
                    assertThat(transparent).isTrue();assertThat(marked).isTrue();
                }
            } finally { image.flush(); }
        }
        var transformed=mapper.readTree(result.stream().filter(p->p.role().equals("PARALLAX_CONFIG")).findFirst().orElseThrow().content());
        assertThat(transformed.path("canvas").path("width").asInt()).isEqualTo(640);
        assertThat(transformed.path("sensor")).isEqualTo(mapper.readTree(config).path("sensor"));
        assertThat(transformed.path("layers")).isEqualTo(mapper.readTree(config).path("layers"));
    }
    @Test void extremeAspectRatiosStayBoundedAndInvalidDimensionsFail() {
        assertThat(PreviewMediaReducer.target(512,4096)).containsExactly(512,1280);
        assertThat(PreviewMediaReducer.target(4096,512)).containsExactly(1280,512);
        assertThatThrownBy(()->PreviewMediaReducer.target(0,512)).isInstanceOf(RuntimeException.class);
    }
}
