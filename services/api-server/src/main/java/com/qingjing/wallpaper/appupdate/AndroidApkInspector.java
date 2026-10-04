package com.qingjing.wallpaper.appupdate;

import com.android.apksig.ApkVerifier;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StagedObject;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.dongliu.apk.parser.ApkFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
public class AndroidApkInspector {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final FileStorage storage;
    private final String expectedPackage;
    public AndroidApkInspector(FileStorage storage,
            @Value("${qingjing.app-updates.android-package-name:com.qingjing.bizhi}") String expectedPackage) {
        this.storage = storage;
        this.expectedPackage = expectedPackage;
    }
    public ApkMetadata inspect(StagedObject staged) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("qingjing-app-release-", ".apk");
            try (var content = storage.openStaged(staged)) {
                Files.copy(content.inputStream(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Set<String> abis = inspectZip(temporary);
            var verified = new ApkVerifier.Builder(temporary.toFile()).build().verify();
            if (!verified.isVerified() || verified.getSignerCertificates().size() != 1) {
                throw rejected("APK signature verification failed; a single release signer is required");
            }
            String signer = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(verified.getSignerCertificates().get(0).getEncoded()));
            try (var apk = new ApkFile(temporary.toFile())) {
                var meta = apk.getApkMeta();
                if (!expectedPackage.equals(meta.getPackageName())) throw rejected("APK package name does not match this App");
                long code = meta.getVersionCode();
                AppVersionOrder.code(code);
                if (meta.getVersionName() == null || meta.getVersionName().isBlank() || meta.getVersionName().length() > 64) {
                    throw rejected("APK versionName is missing or too long");
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(apk.getManifestXml())));
                Element manifest = document.getDocumentElement();
                if (manifest.hasAttribute("split")) throw rejected("A complete APK is required; split APKs cannot update the App directly");
                var apps = document.getElementsByTagName("application");
                if (apps.getLength() != 1) throw rejected("APK application manifest is invalid");
                Element application = (Element) apps.item(0);
                if (enabled(application.getAttributeNS(ANDROID, "debuggable"))
                        || enabled(application.getAttributeNS(ANDROID, "testOnly"))) {
                    throw rejected("Only release APKs without testOnly/debuggable are accepted");
                }
                int minSdk = Integer.parseInt(meta.getMinSdkVersion());
                return new ApkMetadata(meta.getPackageName(), meta.getVersionName(), code,
                        abis.isEmpty() ? "universal" : String.join(",", abis), signer, minSdk);
            }
        } catch (ApiException known) {
            throw known;
        } catch (Exception invalid) {
            var failure = rejected("APK metadata or signature could not be validated");
            failure.initCause(invalid);
            throw failure;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (java.io.IOException cleanup) { temporary.toFile().deleteOnExit(); }
            }
        }
    }
    private boolean enabled(String value) {
        return "true".equals(value) || "1".equals(value) || value.startsWith("@");
    }
    private Set<String> inspectZip(Path file) throws Exception {
        Set<String> abis = new TreeSet<>();
        Set<String> names = new java.util.HashSet<>();
        try (var zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            int count = 0;
            long expanded = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 100000 || !names.add(entry.getName()) || entry.getSize() < 0
                        || entry.getSize() > 512L * 1024 * 1024 || expanded > 1024L * 1024 * 1024 - entry.getSize()) {
                    throw rejected("APK archive structure exceeds validation limits");
                }
                expanded += entry.getSize();
                if (entry.getName().equals("AndroidManifest.xml") && entry.getSize() > 2L * 1024 * 1024) throw rejected("APK manifest exceeds validation limits");
                if (entry.getName().equals("resources.arsc") && entry.getSize() > 64L * 1024 * 1024) throw rejected("APK resources exceed validation limits");
                if (entry.getName().matches("lib/[^/]+/[^/]+\\.so")) {
                    String abi = entry.getName().split("/")[1];
                    if (!Set.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi)) throw rejected("APK contains an unsupported ABI");
                    abis.add(abi);
                }
            }
            if (!names.contains("AndroidManifest.xml") || !names.contains("classes.dex")) throw rejected("A complete APK is required");
        }
        return abis;
    }
    private ApiException rejected(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "APP_APK_INVALID", message);
    }
    public record ApkMetadata(String packageName, String versionName, long versionCode,
            String abi, String signerSha256, int minSdkVersion) {}
}
