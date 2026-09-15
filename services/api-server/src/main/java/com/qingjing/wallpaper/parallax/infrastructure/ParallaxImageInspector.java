package com.qingjing.wallpaper.parallax.infrastructure;

import static com.qingjing.wallpaper.parallax.ParallaxErrors.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Decoder adapter owns temporary paths; no paths leave this boundary. */
@Component
public class ParallaxImageInspector {
    private final String ffmpeg;
    public ParallaxImageInspector(@Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg) { this.ffmpeg=ffmpeg; }
    public record Image(int width,int height,boolean alpha,boolean opaque,String mime) {}

    public Image inspect(String name,byte[] bytes) {
        String extension=name.substring(name.lastIndexOf('.')+1);
        try {
            if(extension.equals("webp")) return webp(name,bytes);
            if(extension.equals("png")) {
                if(bytes.length<33 || ByteBuffer.wrap(bytes).getLong()!=0x89504e470d0a1a0aL) throw media(name);
                for(int p=8;p<bytes.length;) {
                    if(p+12>bytes.length) throw corrupt(name);
                    long size=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,p,4).getInt());
                    if(size>bytes.length-p-12) throw corrupt(name);
                    String chunk=new String(bytes,p+4,4,java.nio.charset.StandardCharsets.US_ASCII);
                    var crc = new java.util.zip.CRC32();
                    crc.update(bytes,p+4,(int)size+4);
                    if(crc.getValue()!=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,p+8+(int)size,4).getInt())) throw corrupt(name);
                    if(chunk.equals("acTL")) throw invalid(name,"STRUCTURE_INVALID",name+" 不能使用 APNG 动画图片");
                    if(chunk.equals("eXIf")) checkTiffOrientation(name,bytes,p+8,(int)size);
                    p+=(int)size+12;
                }
            } else if(extension.equals("jpg")) {
                if(bytes.length<4 || (bytes[0]&255)!=255 || (bytes[1]&255)!=216) throw media(name);
                checkJpegOrientation(name,bytes);
            } else throw media(name);
            try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext()) throw corrupt(name);
                var reader=readers.next();
                try {
                    reader.setInput(stream,false,false);
                    String format=reader.getFormatName();
                    if(!(extension.equals("jpg")?format.equalsIgnoreCase("JPEG"):format.equalsIgnoreCase("PNG"))) throw media(name);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    dimensions(name,width,height);
                    if(reader.getNumImages(true)!=1) throw corrupt(name);
                    // ImageIO can return partially decoded JPEGs with warnings; reject these too.
                    boolean[] warning={false}; reader.addIIOReadWarningListener((r,w)->warning[0]=true);
                    BufferedImage image=reader.read(0);
                    try {
                        if(warning[0]) throw corrupt(name);
                        boolean alpha=image.getColorModel().hasAlpha(), opaque=true;
                        if(alpha) outer: for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                            if((image.getRGB(x,y)>>>24)!=255) { opaque=false; break outer; }
                        }
                        return new Image(width,height,alpha,opaque,extension.equals("jpg")?"image/jpeg":"image/png");
                    } finally { image.flush(); }
                } finally { reader.dispose(); }
            }
        } catch(com.qingjing.wallpaper.shared.web.ApiException e) { throw e; }
        catch(Exception e) { throw corrupt(name); }
    }

    private Image webp(String name,byte[] bytes) throws Exception {
        if(bytes.length<20 || !ascii(bytes,0,4).equals("RIFF") || !ascii(bytes,8,4).equals("WEBP")) throw media(name);
        var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(Integer.toUnsignedLong(buffer.getInt(4))+8!=bytes.length) throw corrupt(name);
        boolean alpha=false; int width=0,height=0,frames=0;
        for(int p=12;p<bytes.length;) {
            if(p+8>bytes.length) throw corrupt(name);
            long length=Integer.toUnsignedLong(buffer.getInt(p+4));
            if(length>bytes.length-p-8 || length+(length&1)>bytes.length-p-8) throw corrupt(name);
            String chunk=ascii(bytes,p,4); int q=p+8;
            if(chunk.equals("ANIM")||chunk.equals("ANMF")) throw invalid(name,"STRUCTURE_INVALID",name+" 不能使用动画 WebP");
            if(chunk.equals("EXIF")) checkTiffOrientation(name,bytes,q,(int)length);
            if(chunk.equals("VP8X")) {
                if(p!=12 || length!=10 || (bytes[q]&2)!=0) throw corrupt(name);
                alpha=(bytes[q]&16)!=0;
                width=1+u24(bytes,q+4);height=1+u24(bytes,q+7);
            } else if(chunk.equals("VP8L")) {
                if(length<5 || (bytes[q]&255)!=47) throw corrupt(name);
                long bits=Integer.toUnsignedLong(buffer.getInt(q+1));
                int w=(int)(bits&16383)+1,h=(int)((bits>>>14)&16383)+1;
                if(width!=0&&(width!=w||height!=h)) throw corrupt(name);
                width=w;height=h;alpha|=((bits>>>28)&1)!=0;frames++;
            } else if(chunk.equals("VP8 ")) {
                if(length<10 || (bytes[q+3]&255)!=157 || (bytes[q+4]&255)!=1 || (bytes[q+5]&255)!=42) throw corrupt(name);
                int w=Short.toUnsignedInt(buffer.getShort(q+6))&16383,h=Short.toUnsignedInt(buffer.getShort(q+8))&16383;
                if(width!=0&&(width!=w||height!=h)) throw corrupt(name);
                width=w;height=h;frames++;
            } else if(chunk.equals("ALPH")) alpha=true;
            p+=8+(int)length+((int)length&1);
        }
        dimensions(name,width,height);
        if(frames!=1) throw corrupt(name);
        Path input=Files.createTempFile("qj-parallax-", ".webp"),output=null;
        try {
            output=Files.createTempFile("qj-parallax-", ".rgba");Files.write(input,bytes);
            var process=new ProcessBuilder(List.of(ffmpeg,"-v","error","-xerror","-nostdin","-y","-protocol_whitelist","file,pipe",
                    "-threads","1","-i",input.toString(),"-frames:v","1","-f","rawvideo","-pix_fmt","rgba",output.toString()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try { if(!process.waitFor(25,TimeUnit.SECONDS)||process.exitValue()!=0) throw corrupt(name); }
            finally { if(process.isAlive()) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);} }
            if(Files.size(output)!=(long)width*height*4) throw corrupt(name);
            boolean opaque=true;
            try(var raw=Files.newInputStream(output)) {
                byte[] block=new byte[16384];int count,offset=0;
                while((count=raw.read(block))!=-1) {
                    for(int i=0;i<count;i++) if(((offset+i)&3)==3 && (block[i]&255)!=255) opaque=false;
                    offset=(offset+count)&3;
                }
            }
            return new Image(width,height,alpha,opaque,"image/webp");
        } finally { Files.deleteIfExists(input);if(output!=null)Files.deleteIfExists(output); }
    }
    private void checkJpegOrientation(String name,byte[] b) {
        int p=2;
        while(p<b.length) {
            if((b[p++]&255)!=255) throw corrupt(name);
            while(p<b.length&&(b[p]&255)==255)p++;
            if(p>=b.length)throw corrupt(name);
            int marker=b[p++]&255;
            if(marker==0xda || marker==0xd9)return;
            if(p+2>b.length)throw corrupt(name);
            int length=((b[p]&255)<<8)|(b[p+1]&255);
            if(length<2||p+length>b.length)throw corrupt(name);
            if(marker==0xe1&&length>=8&&ascii(b,p+2,6).equals("Exif\0\0"))checkTiffOrientation(name,b,p+8,length-8);
            p+=length;
        }
    }
    private void checkTiffOrientation(String name,byte[] bytes,int start,int length) {
        if(length>=6&&ascii(bytes,start,6).equals("Exif\0\0")){start+=6;length-=6;}
        if(length<8)throw corrupt(name);
        String endian=ascii(bytes,start,2);
        if(!endian.equals("II")&&!endian.equals("MM"))throw corrupt(name);
        var b=ByteBuffer.wrap(bytes,start,length).slice().order(endian.equals("II")?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        if(b.getShort(2)!=42)throw corrupt(name);
        long offset=Integer.toUnsignedLong(b.getInt(4));
        if(offset+2>length)throw corrupt(name);
        int count=Short.toUnsignedInt(b.getShort((int)offset));
        if(offset+2+12L*count>length)throw corrupt(name);
        for(int i=0;i<count;i++) {
            int p=(int)offset+2+12*i;
            if(Short.toUnsignedInt(b.getShort(p))==0x112 && (b.getShort(p+2)!=3||b.getInt(p+4)!=1||b.getShort(p+8)!=1))
                throw invalid(name,"DIMENSION_MISMATCH",name+" 请先应用 EXIF 旋转并导出正向图片");
        }
    }
    private static int u24(byte[] b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16);}
    private static String ascii(byte[] b,int p,int n){return new String(b,p,n,java.nio.charset.StandardCharsets.ISO_8859_1);}
    private static void dimensions(String name,int w,int h){if(w<1||h<1||w>4096||h>4096)throw invalid(name,"DIMENSION_MISMATCH",name+" 尺寸必须在 1～4096 像素内");}
    private static com.qingjing.wallpaper.shared.web.ApiException corrupt(String name){return invalid(name,"CORRUPT_FILE",name+" 图片损坏或无法完整解码");}
}
