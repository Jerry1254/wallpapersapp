package com.qingjing.wallpaper.parallax;

import static org.assertj.core.api.Assertions.*;
import static com.qingjing.wallpaper.parallax.ParallaxFixtures.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.parallax.infrastructure.ParallaxImageInspector;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParallaxPackageParserTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ParallaxPackageParser parser=new ParallaxPackageParser(new ParallaxConfigEnvelopeValidator(mapper),new ParallaxImageInspector(System.getenv().getOrDefault("QJ_FFMPEG","ffmpeg")));

    @ParameterizedTest @ValueSource(ints={2,3,12})
    void preservesOriginalV2ConfigAndMapsOnlyFileRoles(int count) throws Exception {
        var source=files(count);byte[] config=source.get("config.json");
        var result=parser.parse(zip(source));
        assertThat(result.layers()).hasSize(count);
        assertThat(result.formatVersion()).isEqualTo(2);
        assertThat(result.configBytes()).containsExactly(config);
        for(int i=0;i<count-1;i++) assertThat(result.layers().get(i))
                .extracting(ParallaxPackageDtos.Layer::role,ParallaxPackageDtos.Layer::ordinal)
                .containsExactly("FOREGROUND",i);
        assertThat(result.layers().get(count-1))
                .extracting(ParallaxPackageDtos.Layer::role,ParallaxPackageDtos.Layer::ordinal)
                .containsExactly("BACKGROUND",0);
    }
    @Test void permitsStoredZipAndMacMetadata() throws Exception {
        var files=files(2);files.put("layers/",new byte[0]);files.put("__MACOSX/._cover.jpg",new byte[]{1});files.put("layers/.DS_Store",new byte[]{2});
        assertThat(parser.parse(zip(files,true)).layers()).hasSize(2);
    }
    @Test void acceptsPackagesWithoutCoverAndIgnoresLegacyCoverBytes() throws Exception {
        assertThat(parser.parse(zip(files(2))).layers()).hasSize(2);
        var legacy=files(2);legacy.put("cover.jpg",new byte[]{1,2,3});
        assertThat(parser.parse(zip(legacy)).layers()).hasSize(2);
    }
    @Test void decodesStaticAlphaAndOpaqueWebp() throws Exception {
        var files=files(2);
        files.remove("layers/01.png");
        files.remove("layers/02.png");
        files.put("layers/01.webp",resource("/parallax/static-alpha.webp"));
        files.put("layers/02.webp",resource("/parallax/static-opaque.webp"));
        assertThat(parser.parse(zip(files)).layers()).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"../evil","/evil","layers\\01.png","wrapper/cover.jpg","layers/01.zip","Cover.jpg","layers//01.png","layers/./01.png"})
    void rejectsUnexpectedOrUnsafePaths(String name) throws Exception {
        var files=files(2);files.put(name,new byte[]{0});reject(files);
    }
    @Test void rejectsMissingGapsDuplicateNumbersAndLayerCount() throws Exception {
        for(int count:List.of(1,13)) reject(files(count));
        var files=files(2);files.remove("config.json");reject(files);
        files=files(3);files.remove("layers/02.png");reject(files);
        files=files(2);files.put("layers/01.webp",files.get("layers/01.png"));reject(files);
    }
    @Test void rejectsWrongCanvasAlphaAndBackground() throws Exception {
        var files=files(2);files.put("layers/01.png",image("png",true,513));reject(files);
        files=files(2);files.put("layers/01.png",image("png",false,512));reject(files);
        files=files(2);files.put("layers/02.png",image("png",true,512));reject(files);
    }
    @Test void passesThroughUnknownAlgorithmFieldsAndValues() throws Exception {
        var source=files(2);String config=new String(source.get("config.json"),StandardCharsets.UTF_8);
        String extended=config.replace("\"formatVersion\":2","\"formatVersion\":37")
                .replace("\"offsetXPercent\":8","\"offsetXPercent\":999,\"futureCurve\":{\"name\":\"spring\"}")
                .replace("\"motion\":{","\"futureRoot\":true,\"motion\":{\"futureMotion\":false,");
        source.put("config.json",extended.getBytes(StandardCharsets.UTF_8));
        var parsed=parser.parse(zip(source));
        assertThat(parsed.formatVersion()).isEqualTo(37);
        assertThat(parsed.configBytes()).containsExactly(extended.getBytes(StandardCharsets.UTF_8));
    }
    @Test void rejectsMalformedOrUnsupportedStableStructure() throws Exception {
        String config=new String(files(2).get("config.json"),StandardCharsets.UTF_8);
        for(String bad:List.of("\ufeff"+config,config+"{}",config.replace("\"formatVersion\":2","\"formatVersion\":2,\"formatVersion\":2"),
                config.replace("\"formatVersion\":2","\"formatVersion\":1"),config.replace("\"width\":512","\"width\":511"),
                config.replace("\"index\":2","\"index\":1"))) {
            var files=files(2);files.put("config.json",bad.getBytes(StandardCharsets.UTF_8));reject(files);
        }
    }
    @Test void rejectsEntryAndExpandedBudgets() throws Exception {
        var files=files(2);files.put("config.json",new byte[65537]);reject(files);
        files=files(2);for(int i=0;i<65;i++)files.put("__MACOSX/"+i,new byte[0]);reject(files);
        files=files(2);files.put("__MACOSX/large",new byte[86*1024*1024]);reject(files);
    }
    @Test void rejectsSymlinkEncryptedCrcMismatchAndTruncation() throws Exception {
        byte[] good=zip(files(2),true);
        int central=-1;var b=ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<good.length-4;i++)if(b.getInt(i)==0x02014b50){central=i;break;}
        byte[] link=good.clone();ByteBuffer.wrap(link).order(ByteOrder.LITTLE_ENDIAN).putInt(central+38,0120777<<16);reject(link);
        byte[] encrypted=good.clone();encrypted[6]|=1;encrypted[central+8]|=1;reject(encrypted);
        byte[] corrupt=good.clone();corrupt[70]^=1;reject(corrupt);
        reject(Arrays.copyOf(good,good.length-10));
    }
    @Test void rejectsPngAnimationAndBadCrc() throws Exception {
        var files=files(2);byte[] png=files.get("layers/01.png").clone();png[45]^=1;files.put("layers/01.png",png);reject(files);
        files=files(2);png=files.get("layers/01.png");
        var chunk=ByteBuffer.allocate(20);chunk.putInt(8).put("acTL".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(0);
        var crc=new java.util.zip.CRC32();crc.update(chunk.array(),4,12);chunk.putInt((int)crc.getValue());
        var out=new java.io.ByteArrayOutputStream();out.write(png,0,33);out.write(chunk.array());out.write(png,33,png.length-33);
        files.put("layers/01.png",out.toByteArray());reject(files);
    }
    private byte[] resource(String name) throws Exception {
        try(var stream=Objects.requireNonNull(getClass().getResourceAsStream(name))) { return stream.readAllBytes(); }
    }
    private void reject(Map<String,byte[]> files) throws Exception {reject(zip(files));}
    private void reject(byte[] bytes) {assertThatThrownBy(()->parser.parse(bytes)).isInstanceOf(ApiException.class);}
}
