import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Reproducible, synthetic format examples. Run from repository root with Java 17+. */
class GenerateParallaxExample {
    public static void main(String[] args) throws Exception {
        Path target=Path.of(args.length==0?"apps/admin-web/public/templates":args[0]);
        Files.createDirectories(target);
        for(int count:new int[]{2,3,12}) {
            String config=config(count);
            Files.writeString(target.resolve("parallax-"+count+"-layers-config.json"),config);
            try(var zip=new ZipOutputStream(Files.newOutputStream(target.resolve("parallax-"+count+"-layers.zip")))) {
                BufferedImage cover=new BufferedImage(512,1024,BufferedImage.TYPE_INT_RGB);
                var combined=cover.createGraphics();
                for(int i=count;i>=1;i--) {
                    BufferedImage layer=layer(i,count);combined.drawImage(layer,0,0,null);
                    entry(zip,String.format(Locale.ROOT,"layers/%02d.png",i),encode(layer,"png"));layer.flush();
                }
                combined.dispose();entry(zip,"cover.jpg",encode(cover,"jpg"));cover.flush();
                entry(zip,"config.json",config.getBytes(StandardCharsets.UTF_8));
            }
        }
        System.out.println("Generated 2-, 3- and 12-layer examples in "+target.toAbsolutePath());
    }
    private static BufferedImage layer(int index,int count) {
        var image=new BufferedImage(512,1024,index==count?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        if(index==count) {
            g.setPaint(new GradientPaint(0,0,new Color(25,45,95),0,1024,new Color(128,184,192)));g.fillRect(0,0,512,1024);
            g.setColor(new Color(250,224,162));g.fillOval(320,150,95,95);
        } else {
            float depth=(count-index)/(float)(count-1);
            g.setColor(new Color(20+(int)(30*(1-depth)),70+(int)(70*(1-depth)),90+(int)(65*(1-depth))));
            int top=380+(int)(depth*440);
            g.fillPolygon(new int[]{0,0,110,245,380,512,512},new int[]{1024,top+50,top-90,top+35,top-60,top+80,1024},7);
        }
        g.dispose();return image;
    }
    private static String config(int count) {
        var text=new StringBuilder("{\n  \"formatVersion\": 2,\n  \"canvas\": { \"width\": 512, \"height\": 1024 },\n  \"motion\": { \"maxAngleX\": 75, \"maxAngleY\": 75 },\n  \"layers\": [\n");
        for(int i=1;i<=count;i++) {
            int offset=i==count?3:Math.max(1,9-i);
            text.append(String.format(Locale.ROOT,"    { \"index\": %d, \"offsetXPercent\": %d, \"offsetYPercent\": %d, \"initialOffsetXPercent\": 0, \"initialOffsetYPercent\": 0, \"direction\": \"%s\", \"scale\": %.2f, \"opacity\": 1, \"blendMode\": \"normal\" }%s\n",i,offset,Math.max(1,offset-1),i==count?"reverse":"follow",i==count?1.0:1.18,i<count?",":""));
        }
        return text.append("  ]\n}\n").toString();
    }
    private static byte[] encode(BufferedImage image,String format) throws Exception {
        var out=new ByteArrayOutputStream();if(!ImageIO.write(image,format,out))throw new IllegalStateException("Image writer missing");return out.toByteArray();
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws Exception {
        var entry=new ZipEntry(name);entry.setTime(315532800000L);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
    }
}
