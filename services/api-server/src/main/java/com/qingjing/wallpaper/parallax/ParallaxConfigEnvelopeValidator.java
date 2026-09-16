package com.qingjing.wallpaper.parallax;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Validates only the stable transport envelope shared by upload and package delivery.
 * Motion and rendering fields remain opaque bytes owned by the simulator and clients.
 */
@Component
public class ParallaxConfigEnvelopeValidator {
    public static final int MAX_BYTES=65536;
    private final ObjectMapper mapper;

    public ParallaxConfigEnvelopeValidator(ObjectMapper mapper) { this.mapper=mapper; }

    public Envelope validate(byte[] bytes,int expectedLayerCount) {
        try {
            if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw invalid();
            if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)throw invalid();
            String text=StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var root=mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
            if(root==null||!root.isObject())throw invalid();
            var version=root.path("formatVersion");
            if(!version.isInt()||version.intValue()<2)throw invalid();
            var canvas=root.path("canvas");
            if(!canvas.isObject())throw invalid();
            int width=integer(canvas.path("width"),512,4096);
            int height=integer(canvas.path("height"),512,4096);
            var layers=root.path("layers");
            if(!layers.isArray()||layers.size()<2||layers.size()>12||layers.size()!=expectedLayerCount)throw invalid();
            for(int i=0;i<layers.size();i++) {
                var layer=layers.get(i);
                if(!layer.isObject()||!layer.path("index").isInt()||layer.path("index").intValue()!=i+1)throw invalid();
            }
            return new Envelope(version.intValue(),width,height,layers.size());
        } catch(InvalidEnvelope exception) { throw exception; }
        catch(Exception exception) { throw invalid(); }
    }

    private static int integer(com.fasterxml.jackson.databind.JsonNode value,int min,int max) {
        if(!value.isInt()||value.intValue()<min||value.intValue()>max)throw invalid();
        return value.intValue();
    }

    private static InvalidEnvelope invalid() { return new InvalidEnvelope(); }

    public record Envelope(int formatVersion,int width,int height,int layerCount) {}
    public static final class InvalidEnvelope extends RuntimeException {
        private InvalidEnvelope() { super("Invalid 4D configuration envelope"); }
    }
}
