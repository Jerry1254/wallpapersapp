package com.qingjing.wallpaper.parallax;

import static com.qingjing.wallpaper.parallax.ParallaxErrors.*;

import com.qingjing.wallpaper.parallax.infrastructure.ParallaxImageInspector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class ParallaxPackageParser {
    public static final long ZIP_LIMIT=100L*1024*1024, EXPANDED_LIMIT=85L*1024*1024, PAYLOAD_LIMIT=64L*1024*1024;
    private final ParallaxConfigEnvelopeValidator configs;
    private final ParallaxImageInspector images;
    public ParallaxPackageParser(ParallaxConfigEnvelopeValidator configs,ParallaxImageInspector images){this.configs=configs;this.images=images;}
    public record Asset(String name,byte[] bytes,ParallaxImageInspector.Image image) {}
    public record Parsed(Asset cover,List<Asset> images,List<ParallaxPackageDtos.Layer> layers,
                         int width,int height,int formatVersion,byte[] configBytes) {}

    public Parsed parse(byte[] zip) {
        if(zip.length>ZIP_LIMIT)throw size("ZIP");
        Map<String,byte[]> files=unzip(zip);
        if(!files.containsKey("cover.jpg")||!files.containsKey("config.json"))throw invalid("ZIP","STRUCTURE_INVALID","ZIP 根目录必须包含 cover.jpg 和 config.json");
        List<String> names=files.keySet().stream().filter(n->n.startsWith("layers/")).sorted().toList();
        int count=names.size();
        if(count<2||count>12)throw invalid("layers/","LAYER_COUNT_INVALID","图层数量必须为 2～12 层");
        byte[] configBytes=files.get("config.json");
        ParallaxConfigEnvelopeValidator.Envelope envelope;
        try { envelope=configs.validate(configBytes,count); }
        catch(ParallaxConfigEnvelopeValidator.InvalidEnvelope error) { throw configError(); }
        int width=envelope.width(),height=envelope.height();
        List<Asset> assets=new ArrayList<>();List<ParallaxPackageDtos.Layer> layers=new ArrayList<>();
        long payloadSize=0;
        for(int i=1;i<=count;i++) {
            String name=names.get(i-1),prefix=String.format(Locale.ROOT,"layers/%02d.",i);
            if(!name.startsWith(prefix))throw invalid(name,"LAYER_SEQUENCE_INVALID","图层必须从 01 连续编号，每个编号只能有一个文件");
            boolean background=i==count;
            byte[] content=files.get(name);var image=images.inspect(name,content);
            if(image.width()!=width||image.height()!=height)throw invalid(name,"DIMENSION_MISMATCH",name+" 尺寸为 "+image.width()+"×"+image.height()+"，与画布 "+width+"×"+height+" 不一致");
            if(!background&&(!image.alpha()||name.endsWith(".jpg")))throw invalid(name,"ALPHA_REQUIRED",name+" 前景必须是保留透明通道的 PNG 或 WebP");
            if(background&&!image.opaque())throw invalid(name,"BACKGROUND_NOT_OPAQUE",name+" 背景所有像素必须完全不透明");
            assets.add(new Asset(name,content,image));
            layers.add(new ParallaxPackageDtos.Layer(i,name,background?"BACKGROUND":"FOREGROUND",background?0:i-1));
            payloadSize+=content.length;
        }
        if(payloadSize+configBytes.length>PAYLOAD_LIMIT)throw size("图层与 config.json");
        byte[] cover=files.get("cover.jpg");var coverImage=images.inspect("cover.jpg",cover);
        return new Parsed(new Asset("cover.jpg",cover,coverImage),List.copyOf(assets),List.copyOf(layers),width,height,envelope.formatVersion(),configBytes);
    }

    private Map<String,byte[]> unzip(byte[] zip) {
        if(zip.length<22||ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).getInt()!=0x04034b50)throw media("ZIP");
        Map<String,Entry> directory=centralDirectory(zip);
        Map<String,byte[]> files=new LinkedHashMap<>();Set<String> seen=new HashSet<>();long expanded=0;
        try(var input=new ZipInputStream(new ByteArrayInputStream(zip),StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            byte[] buffer=new byte[8192];
            while((entry=input.getNextEntry())!=null) {
                String name=entry.getName();Entry expected=directory.get(name);
                if(expected==null||!seen.add(name)||entry.getMethod()!=expected.method())throw structure();
                boolean ignored=name.startsWith("__MACOSX/")||name.equals(".DS_Store")||name.endsWith("/.DS_Store");
                boolean valid=name.equals("cover.jpg")||name.equals("config.json")||name.matches("layers/(0[1-9]|1[0-2])\\.(png|jpg|webp)");
                if(!ignored&&!valid&&!name.equals("layers/"))throw invalid(name,"STRUCTURE_INVALID","ZIP 包含不允许的文件："+name);
                long limit=name.equals("config.json")?65536:name.equals("cover.jpg")?20L*1024*1024:50L*1024*1024;
                if(ignored)limit=EXPANDED_LIMIT;
                ByteArrayOutputStream output=new ByteArrayOutputStream();long entryBytes=0;int read;
                while((read=input.read(buffer))!=-1) {
                    expanded+=read;entryBytes+=read;
                    if(expanded>EXPANDED_LIMIT||entryBytes>limit)throw size(name);
                    if(entry.isDirectory()&&entryBytes>0)throw structure();
                    if(!ignored&&!entry.isDirectory())output.write(buffer,0,read);
                }
                input.closeEntry(); // ZipInputStream checks CRC and data descriptors here.
                if(entryBytes!=expected.size()||entry.getCrc()!=expected.crc()||entry.getCompressedSize()!=expected.compressed())throw structure();
                if(valid)files.put(name,output.toByteArray());
            }
            if(!seen.equals(directory.keySet()))throw structure();
            return files;
        }catch(com.qingjing.wallpaper.shared.web.ApiException e){throw e;}
        catch(Exception e){throw invalid("ZIP","CORRUPT_FILE","ZIP 损坏、CRC 不匹配或无法完整解压");}
    }

    /** Read central metadata too: ZipInputStream alone cannot detect Unix symlinks. No ZIP paths reach the filesystem. */
    private Map<String,Entry> centralDirectory(byte[] zip) {
        try {
            var b=ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);int end=-1;
            for(int p=zip.length-22;p>=Math.max(0,zip.length-65557);p--) {
                if(b.getInt(p)==0x06054b50&&p+22+u16(b,p+20)==zip.length){end=p;break;}
            }
            if(end<0||u16(b,end+4)!=0||u16(b,end+6)!=0)throw structure();
            int count=u16(b,end+10);long offset=u32(b,end+16),length=u32(b,end+12);
            if(count<1||count>64||u16(b,end+8)!=count||offset+length!=end)throw structure();
            Map<String,Entry> result=new LinkedHashMap<>();Set<String> folded=new HashSet<>();Set<Long> locals=new HashSet<>();
            int p=(int)offset;
            for(int i=0;i<count;i++) {
                if(p+46>end||b.getInt(p)!=0x02014b50)throw structure();
                int flags=u16(b,p+8),method=u16(b,p+10),nameLen=u16(b,p+28),extra=u16(b,p+30),comment=u16(b,p+32);
                if(p+46+nameLen+extra+comment>end||u16(b,p+34)!=0||(flags&~0x080e)!=0||(method!=0&&method!=8))throw structure();
                long mode=u32(b,p+38)>>>16;int fileType=(int)mode&0170000;
                if(fileType!=0&&fileType!=0100000&&fileType!=0040000)throw structure();
                String name=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(zip,p+46,nameLen)).toString();
                if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.chars().anyMatch(c->c<32))throw structure();
                for(String component:name.split("/",-1))if(component.equals("..")||component.equals("."))throw structure();
                if(name.contains("//")||!folded.add(name.toLowerCase(Locale.ROOT)))throw structure();
                long local=u32(b,p+42),compressed=u32(b,p+20),size=u32(b,p+24);
                if(!locals.add(local)||local+30>offset||compressed==0xffffffffL||size==0xffffffffL||b.getInt((int)local)!=0x04034b50)throw structure();
                int l=(int)local,ln=u16(b,l+26),le=u16(b,l+28);
                if(u16(b,l+6)!=flags||u16(b,l+8)!=method||local+30+ln+le+compressed>offset||ln!=nameLen)throw structure();
                if(!Arrays.equals(Arrays.copyOfRange(zip,l+30,l+30+ln),Arrays.copyOfRange(zip,p+46,p+46+nameLen)))throw structure();
                result.put(name,new Entry(method,size,compressed,u32(b,p+16)));
                p+=46+nameLen+extra+comment;
            }
            if(p!=end)throw structure();
            return result;
        }catch(com.qingjing.wallpaper.shared.web.ApiException e){throw e;}
        catch(Exception e){throw structure();}
    }
    private static int u16(ByteBuffer b,int p){return Short.toUnsignedInt(b.getShort(p));}
    private static long u32(ByteBuffer b,int p){return Integer.toUnsignedLong(b.getInt(p));}
    private record Entry(int method,long size,long compressed,long crc) {}
    private static com.qingjing.wallpaper.shared.web.ApiException structure(){return invalid("ZIP","STRUCTURE_INVALID","ZIP 目录、路径或压缩格式不符合要求（仅支持普通 STORE/DEFLATE ZIP）");}
    private static com.qingjing.wallpaper.shared.web.ApiException configError(){return invalid("config.json","CONFIG_INVALID","config.json 必须包含有效的格式版本、画布和连续图层索引");}
}
