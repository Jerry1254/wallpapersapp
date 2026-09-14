package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Derived, marked preview media only. Temporary decoder paths stay inside this infrastructure adapter. */
@Component
public final class PreviewMediaReducer {
    private final ObjectMapper mapper;
    private final String ffmpeg;
    public PreviewMediaReducer(ObjectMapper mapper,@Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg) {
        this.mapper=mapper;this.ffmpeg=ffmpeg;
    }
    public List<SecurePackageCodec.Payload> reduce(String type,List<SecurePackageCodec.Payload> validatedSource,
            java.util.Map<String,PackageMediaInspector.Media> dimensions) {
        List<SecurePackageCodec.Payload> result=new ArrayList<>();
        int[] canvas=null;
        for(var payload:validatedSource) {
            if(payload.role().equals("PARALLAX_CONFIG")) continue;
            var source=dimensions.get(payload.role()+":"+payload.ordinal());
            if(source==null) throw invalid();
            int[] target=target(source.width(),source.height());
            if(type.equals("LAYER_PARALLAX")) {
                if(canvas!=null && !java.util.Arrays.equals(canvas,target)) throw invalid();
                canvas=target;
            }
            boolean video=payload.role().equals("VIDEO");
            boolean alpha=payload.role().equals("FOREGROUND");
            byte[] reduced=video?video(payload.content(),target):image(payload.content(),target,alpha);
            if(java.util.Arrays.equals(reduced,payload.content())) throw invalid();
            result.add(new SecurePackageCodec.Payload(payload.role(),payload.ordinal(),video?"video/mp4":alpha?"image/png":"image/jpeg",reduced));
        }
        if(type.equals("LAYER_PARALLAX")) {
            try {
                var source=validatedSource.stream().filter(p->p.role().equals("PARALLAX_CONFIG")).findFirst().orElseThrow();
                ObjectNode config=(ObjectNode)mapper.readTree(source.content());
                ((ObjectNode)config.get("canvas")).put("width",canvas[0]).put("height",canvas[1]);
                result.add(new SecurePackageCodec.Payload("PARALLAX_CONFIG",source.ordinal(),"application/json",mapper.writeValueAsBytes(config)));
            } catch(Exception error) { throw invalid(); }
        }
        return result;
    }
    static int[] target(int width,int height) {
        if(width<1 || height<1 || width>4096 || height>4096) throw invalid();
        double ratio=Math.min(1.0,1280.0/Math.max(width,height));
        // Center-cover extreme aspect ratios while keeping a valid 4D canvas and even H.264 dimensions.
        return new int[]{Math.max(512,(int)(width*ratio)/2*2),Math.max(512,(int)(height*ratio)/2*2)};
    }
    private byte[] image(byte[] source,int[] target,boolean alpha) {
        BufferedImage decoded=null,result=null;
        ImageReader reader=null;
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) return image(convertImage(source,target),target,alpha);
            reader=readers.next();reader.setInput(input,true,true);
            int width=reader.getWidth(0),height=reader.getHeight(0),sample=1;
            if(width>4096 || height>4096) throw invalid();
            while(width/(sample*2)>=target[0] && height/(sample*2)>=target[1]) sample*=2;
            var parameters=reader.getDefaultReadParam();parameters.setSourceSubsampling(sample,sample,0,0);
            decoded=reader.read(0,parameters);
            result=new BufferedImage(target[0],target[1],alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=result.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale=Math.max(target[0]/(double)decoded.getWidth(),target[1]/(double)decoded.getHeight());
                int scaledWidth=(int)Math.ceil(decoded.getWidth()*scale),scaledHeight=(int)Math.ceil(decoded.getHeight()*scale);
                graphics.drawImage(decoded,(target[0]-scaledWidth)/2,(target[1]-scaledHeight)/2,scaledWidth,scaledHeight,null);
                mark(graphics,target[0],target[1]);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(alpha) ImageIO.write(result,"png",bytes);
            else {
                ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();
                try(var output=new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(output);var options=writer.getDefaultWriteParam();
                    options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);options.setCompressionQuality(.4f);
                    writer.write(null,new IIOImage(result,null,null),options);
                } finally { writer.dispose(); }
            }
            return bytes.toByteArray();
        } catch(Exception error) { throw invalid(); }
        finally { if(reader!=null) reader.dispose();if(decoded!=null) decoded.flush();if(result!=null) result.flush(); }
    }
    /** Repeated opaque QJ outlines obscure every source layer; no system font or external asset dependency. */
    private static void mark(Graphics2D graphics,int width,int height) {
        graphics.setComposite(AlphaComposite.SrcOver);graphics.setColor(new Color(230,230,230,210));
        graphics.setStroke(new BasicStroke(3f));
        for(int y=20;y<height;y+=150) for(int x=20;x<width;x+=180) {
            graphics.drawOval(x,y,34,44);graphics.drawLine(x+22,y+30,x+42,y+52);
            Path2D j=new Path2D.Float();j.moveTo(x+48,y);j.lineTo(x+78,y);j.lineTo(x+78,y+34);j.quadTo(x+78,y+54,x+54,y+42);graphics.draw(j);
            graphics.drawLine(x-10,y+66,x+105,y-12);
        }
    }
    private byte[] video(byte[] source,int[] target) {
        Path input=null,output=null,watermark=null;
        try {
            input=Files.createTempFile("qj-preview-source-",".mp4");output=Files.createTempFile("qj-preview-reduced-",".mp4");
            watermark=Files.createTempFile("qj-preview-mark-",".png");Files.write(input,source);
            BufferedImage mark=new BufferedImage(target[0],target[1],BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics=mark.createGraphics();try { mark(graphics,target[0],target[1]); } finally { graphics.dispose(); }
            try { ImageIO.write(mark,"png",watermark.toFile()); } finally { mark.flush(); }
            String filter="[0:v]scale="+target[0]+":"+target[1]+":force_original_aspect_ratio=increase,crop="+target[0]+":"+target[1]+",fps=15[base];[base][1:v]overlay=0:0:format=auto,format=yuv420p[out]";
            Process process=new ProcessBuilder(ffmpeg,"-v","error","-xerror","-nostdin","-y","-protocol_whitelist","file,pipe",
                "-threads","1","-i",input.toString(),"-i",watermark.toString(),"-filter_complex_threads","1","-filter_complex",filter,
                "-map","[out]","-an","-t","30","-c:v","libx264","-threads","1","-preset","fast","-crf","32","-pix_fmt","yuv420p",output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try { if(!process.waitFor(60,TimeUnit.SECONDS) || process.exitValue()!=0) throw invalid(); }
            finally { if(process.isAlive()) { process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS); } }
            if(Files.size(output)<1 || Files.size(output)>SecurePackageCodec.MAX_PAYLOAD_BYTES) throw invalid();
            return Files.readAllBytes(output);
        } catch(InterruptedException error) { Thread.currentThread().interrupt();throw invalid(); }
        catch(Exception error) { throw invalid(); }
        finally { for(Path path:new Path[]{input,output,watermark}) if(path!=null) try { Files.deleteIfExists(path); } catch(IOException ignored) { } }
    }
    private byte[] convertImage(byte[] source,int[] target) {
        Path input=null,output=null;
        try {
            input=Files.createTempFile("qj-preview-image-source-",".bin");output=Files.createTempFile("qj-preview-image-",".png");Files.write(input,source);
            String filter="scale="+target[0]+":"+target[1]+":force_original_aspect_ratio=increase,crop="+target[0]+":"+target[1];
            Process process=new ProcessBuilder(ffmpeg,"-v","error","-xerror","-nostdin","-y","-protocol_whitelist","file,pipe","-threads","1",
                "-i",input.toString(),"-vf",filter,"-frames:v","1","-an","-c:v","png","-threads","1",output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try { if(!process.waitFor(30,TimeUnit.SECONDS) || process.exitValue()!=0) throw invalid(); }
            finally { if(process.isAlive()) { process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS); } }
            if(Files.size(output)<1 || Files.size(output)>8L*1024*1024) throw invalid();
            return Files.readAllBytes(output);
        } catch(InterruptedException error) { Thread.currentThread().interrupt();throw invalid(); }
        catch(Exception error) { throw invalid(); }
        finally { for(Path path:new Path[]{input,output}) if(path!=null) try { Files.deleteIfExists(path); } catch(IOException ignored) { } }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"ASSET_VALIDATION_FAILED","Preview media reduction failed"); }
}
