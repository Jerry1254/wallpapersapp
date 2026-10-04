package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.appupdate.AppReleaseDtos.*;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StagedObject;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.asset.application.StoredObject;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.io.IOException;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AppReleaseService {
    static final String ONLINE_PACKAGE = "com.qingjing.bizhi";
    static final String OFFLINE_PACKAGE = "com.jiyi.wallpaper";
    private static final long MAX_APK_BYTES = 260L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final AndroidApkInspector inspector;
    private final TransactionTemplate transactions;
    public AppReleaseService(JdbcTemplate jdbc, FileStorage storage, AndroidApkInspector inspector,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.inspector = inspector;
        this.transactions = new TransactionTemplate(transactionManager);
    }
    public List<ReleaseView> list(String platform) {
        return list(platform, null);
    }
    public List<ReleaseView> list(String platform, String packageName) {
        AppVersionOrder.platform(platform);
        String selected = selectedPackage(platform, packageName);
        return rows(platform, selected).stream().map(ReleaseRow::view).sorted((a, b) ->
                AppVersionOrder.compare(platform, b.versionName(), b.versionCode(), a.versionName(), a.versionCode())).toList();
    }
    public CheckResult check(String platform, String name, long code, String abi, Integer sdk) {
        return check(platform, name, code, abi, sdk, null);
    }
    public CheckResult check(String platform, String name, long code, String abi, Integer sdk, String packageName) {
        AppVersionOrder.platform(platform);
        AppVersionOrder.code(code);
        if (platform.equals("ios")) AppVersionOrder.normalize(name);
        if (name == null || name.isBlank() || name.length() > 64) throw AppVersionOrder.invalid("versionName is required");
        if (abi != null && !java.util.Set.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi)) throw AppVersionOrder.invalid("abi is invalid");
        if (sdk != null && (sdk < 1 || sdk > 1000)) throw AppVersionOrder.invalid("androidSdk is invalid");
        if (packageName != null && !ONLINE_PACKAGE.equals(packageName)
                && !(platform.equals("android") && OFFLINE_PACKAGE.equals(packageName))) {
            return new CheckResult(platform, false, false, null, null, Instant.now());
        }
        return AppUpdatePolicy.evaluate(platform, name, code, list(platform, packageName), abi, sdk);
    }
    public void requireForDevice(DevicePrincipal principal, String name, String code, String abi, String sdk) {
        String scope = jdbc.queryForObject("SELECT app_install_scope FROM anonymous_device WHERE id=?", String.class, principal.deviceId());
        if (!ONLINE_PACKAGE.equals(scope) && !(principal.platform() == com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform.ANDROID
                && OFFLINE_PACKAGE.equals(scope))) return;
        String platform = switch (principal.platform()) {
            case ANDROID -> "android";
            case IOS -> "ios";
            case HARMONYOS -> "harmony";
            case H5_TEST -> null;
        };
        if (platform != null) requireSupported(platform, name, code, abi, sdk, scope);
    }
    public void requireSupported(String platform, String name, String code, String abi, String sdk) {
        requireSupported(platform, name, code, abi, sdk, null);
    }
    public void requireSupported(String platform, String name, String code, String abi, String sdk, String packageName) {
        List<ReleaseView> releases = list(platform, packageName);
        if (releases.stream().noneMatch(r -> r.status().equals("PUBLISHED") && r.forceUpdate())) return;
        boolean mandatory;
        try {
            long installedCode = Long.parseLong(code);
            AppVersionOrder.code(installedCode);
            if (name == null || name.isBlank()) throw new IllegalArgumentException();
            if (platform.equals("ios")) AppVersionOrder.normalize(name);
            Integer installedSdk = sdk == null ? null : Integer.valueOf(sdk);
            if (installedSdk != null && (installedSdk < 1 || installedSdk > 1000)) throw new IllegalArgumentException();
            if (abi != null && !java.util.Set.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi)) throw new IllegalArgumentException();
            mandatory = AppUpdatePolicy.evaluate(platform, name, installedCode, releases, abi, installedSdk).mandatory();
        } catch (IllegalArgumentException | ApiException invalid) {
            mandatory = true;
        }
        if (mandatory) {
            throw new ApiException(HttpStatus.UPGRADE_REQUIRED, "APP_UPDATE_REQUIRED", "当前版本已不再支持，请更新至最新版本");
        }
    }
    @Transactional
    public ReleaseView createStore(StoreReleaseRequest request, long adminId) {
        String platform = AppVersionOrder.platform(request.platform());
        if (platform.equals("android")) throw AppVersionOrder.invalid("Android releases must be uploaded as an APK");
        String normalized = AppVersionOrder.normalize(request.versionName());
        AppVersionOrder.code(request.versionCode());
        validateStoreUrl(platform, request.storeUrl());
        lockPlatform(platform);
        long id = insert(platform, request.versionName(), request.versionCode(),
                platform.equals("ios") ? normalized : Long.toString(request.versionCode()),
                notes(request.releaseNotes()), request.storeUrl(), null, null, adminId);
        return requireRow(id).view();
    }
    public ReleaseView uploadAndroid(MultipartFile file, String releaseNotes, long adminId) {
        return uploadAndroid(file, releaseNotes, adminId, ONLINE_PACKAGE);
    }
    public ReleaseView uploadAndroid(MultipartFile file, String releaseNotes, long adminId, String packageName) {
        String selected = selectedPackage("android", packageName);
        String notes = notes(releaseNotes);
        String filename = file.getOriginalFilename();
        if (file.isEmpty() || filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            throw AppVersionOrder.invalid("A non-empty .apk file is required");
        }
        StagedObject staged;
        try (var source = file.getInputStream()) { staged = storage.stage(source, MAX_APK_BYTES); }
        catch (IOException failure) { throw new ApiException(HttpStatus.BAD_REQUEST, "APP_APK_INVALID", "APK upload could not be read"); }
        StoredObject stored = null;
        try {
            var metadata = inspector.inspect(staged);
            if (!selected.equals(metadata.packageName())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "APP_APK_INVALID", "APK package name does not match the selected App");
            }
            stored = storage.commit(staged, "apk");
            StoredObject committed = stored;
            return transactions.execute(status -> {
                lockPlatform("android");
                String identity = OFFLINE_PACKAGE.equals(metadata.packageName()) ? "jiyi:" + metadata.versionCode() : Long.toString(metadata.versionCode());
                long id = insert("android", metadata.versionName(), metadata.versionCode(), identity,
                        notes, null, committed, metadata, adminId);
                return requireRow(id).view();
            });
        } catch (RuntimeException failure) {
            if (stored != null) {
                try { storage.delete(stored.storageKey()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        } finally {
            storage.discard(staged);
        }
    }
    @Transactional
    public ReleaseView update(String id, UpdateReleaseRequest request) {
        ReleaseRow row = lockRelease(id);
        if (row.status().equals("DEPRECATED")) throw conflict("Deprecated releases cannot be edited");
        if (row.platform().equals("android")) {
            if (request.storeUrl() != null && !request.storeUrl().isBlank()) throw AppVersionOrder.invalid("Android uses direct APK updates");
        } else validateStoreUrl(row.platform(), request.storeUrl());
        jdbc.update("UPDATE app_release SET release_notes=?, force_update=?, store_url=? WHERE id=?",
                notes(request.releaseNotes()), request.forceUpdate(), row.platform().equals("android") ? null : request.storeUrl(), row.id());
        return requireRow(row.id()).view();
    }
    @Transactional
    public ReleaseView publish(String id) {
        ReleaseRow row = lockRelease(id);
        if (!row.status().equals("DRAFT")) throw conflict("Only draft releases can be published");
        if (!row.platform().equals("android")) validateStoreUrl(row.platform(), row.storeUrl());
        List<ReleaseRow> history = rows(row.platform(), row.packageName()).stream().filter(r -> r.publishedAt() != null).toList();
        for (ReleaseRow prior : history) {
            if (AppVersionOrder.compare(row.platform(), row.versionName(), row.versionCode(), prior.versionName(), prior.versionCode()) <= 0) {
                throw conflict("New releases must increase the published version");
            }
            if (row.platform().equals("android") && (!row.packageName().equals(prior.packageName())
                    || !row.signerSha256().equals(prior.signerSha256()))) {
                throw conflict("APK package name and signing certificate must match previously published releases");
            }
        }
        if (row.platform().equals("android")) {
            try (var content = storage.open(new StorageKey(row.storageKey()))) {
                if (content.sizeBytes() != row.fileSize()) throw conflict("APK file is unavailable or changed");
            } catch (IOException failure) { throw conflict("APK file could not be read"); }
        }
        // iOS/Harmony publication is the administrator's confirmation that this version is available in its store.
        jdbc.update("UPDATE app_release SET status='PUBLISHED', published_at=UTC_TIMESTAMP(6) WHERE id=?", row.id());
        return requireRow(row.id()).view();
    }
    @Transactional
    public ReleaseView deprecate(String id) {
        ReleaseRow row = lockRelease(id);
        if (!row.status().equals("DEPRECATED")) {
            jdbc.update("UPDATE app_release SET status='DEPRECATED', deprecated_at=UTC_TIMESTAMP(6) WHERE id=?", row.id());
        }
        return requireRow(row.id()).view();
    }
    public ReleaseView downloadable(String id) {
        ReleaseRow row = requireRow(Ids.parse(id, "releaseId"));
        if (!row.platform().equals("android") || !row.status().equals("PUBLISHED")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APP_RELEASE_NOT_FOUND", "The published Android package is unavailable");
        }
        return row.view();
    }
    public StoredContent openPackage(String id) {
        ReleaseRow row = requireRow(Ids.parse(id, "releaseId"));
        if (!row.platform().equals("android") || !row.status().equals("PUBLISHED")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APP_RELEASE_NOT_FOUND", "The published Android package is unavailable");
        }
        return storage.open(new StorageKey(row.storageKey()));
    }
    private long insert(String platform, String name, long code, String identity, String notes, String url,
            StoredObject stored, AndroidApkInspector.ApkMetadata apk, long adminId) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO app_release (platform,version_name,version_code,version_identity,release_notes,
                    delivery_type,store_url,storage_key,package_name,sha256,file_size,abi,signer_sha256,min_sdk_version,created_by_admin_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] args = {platform,name,code,identity,notes,apk == null ? "store" : "apk",url,
                    stored == null ? null : stored.storageKey().value(), apk == null ? null : apk.packageName(),
                    stored == null ? null : stored.sha256(), stored == null ? null : stored.sizeBytes(),
                    apk == null ? null : apk.abi(), apk == null ? null : apk.signerSha256(), apk == null ? null : apk.minSdkVersion(),adminId};
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("App release ID was not allocated");
        return key.getKey().longValue();
    }
    private List<ReleaseRow> rows(String platform, String packageName) {
        if (platform.equals("android")) {
            return jdbc.query("SELECT * FROM app_release WHERE platform=? AND package_name=?", this::map, platform, packageName);
        }
        return jdbc.query("SELECT * FROM app_release WHERE platform=?", this::map, platform);
    }
    private String selectedPackage(String platform, String packageName) {
        if (packageName == null || ONLINE_PACKAGE.equals(packageName)) return ONLINE_PACKAGE;
        if (platform.equals("android") && OFFLINE_PACKAGE.equals(packageName)) return OFFLINE_PACKAGE;
        throw AppVersionOrder.invalid("Unsupported App package name");
    }
    private ReleaseRow requireRow(long id) {
        List<ReleaseRow> found = jdbc.query("SELECT * FROM app_release WHERE id=?", this::map, id);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "APP_RELEASE_NOT_FOUND", "App release does not exist");
        return found.get(0);
    }
    private ReleaseRow lockRelease(String id) {
        long parsed = Ids.parse(id, "releaseId");
        lockPlatform(requireRow(parsed).platform());
        return requireRow(parsed);
    }
    private void lockPlatform(String platform) {
        jdbc.queryForObject("SELECT platform FROM app_release_platform WHERE platform=? FOR UPDATE", String.class, platform);
    }
    private String notes(String notes) {
        if (notes == null || notes.isBlank() || notes.length() > 1000) throw AppVersionOrder.invalid("releaseNotes must contain 1 to 1000 characters");
        return notes.strip();
    }
    static void validateStoreUrl(String platform, String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean expectedHost = platform.equals("ios") ? "apps.apple.com".equalsIgnoreCase(host)
                    : "appgallery.huawei.com".equalsIgnoreCase(host);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getPath() == null || uri.getPath().length() < 2) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw AppVersionOrder.invalid("A valid HTTPS App Store / Huawei AppGallery detail URL is required"); }
    }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "APP_RELEASE_CONFLICT", message); }
    private ReleaseRow map(ResultSet rs, int index) throws SQLException {
        Number size = (Number) rs.getObject("file_size");
        Number minSdk = (Number) rs.getObject("min_sdk_version");
        var published = rs.getTimestamp("published_at");
        return new ReleaseRow(rs.getLong("id"),rs.getString("platform"),rs.getString("version_name"),rs.getLong("version_code"),
                rs.getString("release_notes"),rs.getBoolean("force_update"),rs.getString("status"),rs.getString("delivery_type"),
                rs.getString("store_url"),rs.getString("storage_key"),rs.getString("package_name"),rs.getString("sha256"),
                size == null ? null : size.longValue(),rs.getString("abi"),rs.getString("signer_sha256"),minSdk == null ? null : minSdk.intValue(),
                rs.getTimestamp("created_at").toInstant(),published == null ? null : published.toInstant());
    }
    private record ReleaseRow(long id,String platform,String versionName,long versionCode,String releaseNotes,boolean forceUpdate,
            String status,String deliveryType,String storeUrl,String storageKey,String packageName,String sha256,Long fileSize,
            String abi,String signerSha256,Integer minSdkVersion,Instant createdAt,Instant publishedAt) {
        ReleaseView view() {
            return new ReleaseView(Long.toString(id),platform,versionName,versionCode,releaseNotes,forceUpdate,status,deliveryType,
                    storeUrl,packageName,sha256,fileSize,abi,signerSha256,minSdkVersion,
                    platform.equals("android") && status.equals("PUBLISHED") ? "/api/v1/app-updates/packages/" + id : null,createdAt,publishedAt);
        }
    }
}
