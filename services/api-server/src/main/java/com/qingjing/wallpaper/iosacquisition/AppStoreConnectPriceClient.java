package com.qingjing.wallpaper.iosacquisition;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import org.springframework.stereotype.Component;

/** Read-only App Store Connect client. Its credentials are never used in StoreKit or returned to clients. */
@Component
public class AppStoreConnectPriceClient implements ApplePriceGateway {
    private static final String BASE = "https://api.appstoreconnect.apple.com";
    private final IosPricingProperties properties;
    private final IosAcquisitionProperties acquisition;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;
    private volatile ECPrivateKey key;

    @org.springframework.beans.factory.annotation.Autowired
    public AppStoreConnectPriceClient(IosPricingProperties properties, IosAcquisitionProperties acquisition, ObjectMapper json) {
        this(properties, acquisition, json, HttpClient.newBuilder().connectTimeout(acquisition.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }
    AppStoreConnectPriceClient(IosPricingProperties properties, IosAcquisitionProperties acquisition,
            ObjectMapper json, HttpClient http, Clock clock) {
        this.properties = properties; this.acquisition = acquisition; this.json = json; this.http = http; this.clock = clock;
    }

    @Override public ChinaPrice currentChinaPrice(String productId) {
        if (!properties.configured() || acquisition.getAppAppleId() == null) throw new PriceFailure("NOT_CONFIGURED");
        String productPath = "/v1/apps/" + acquisition.getAppAppleId() + "/inAppPurchasesV2";
        List<JsonNode> products = pages(productPath + "?filter%5BproductId%5D=" + encode(productId)
                + "&fields%5BinAppPurchases%5D=productId,inAppPurchaseType&limit=200", productPath, new HashMap<>());
        List<JsonNode> matches = products.stream().filter(p -> productId.equals(p.path("attributes").path("productId").asText())).toList();
        if (matches.size() != 1) throw new PriceFailure("PRODUCT_NOT_FOUND");
        JsonNode product = matches.get(0);
        if (!"NON_CONSUMABLE".equals(product.path("attributes").path("inAppPurchaseType").asText()))
            throw new PriceFailure("PRODUCT_TYPE_MISMATCH");
        String appleId = product.path("id").asText();
        if (!appleId.matches("[0-9]+")) throw new PriceFailure("INVALID_RESPONSE");
        String scheduleId = get("/v2/inAppPurchases/" + appleId + "/iapPriceSchedule")
                .path("data").path("id").asText();
        if (!scheduleId.matches("[A-Za-z0-9_-]+")) throw new PriceFailure("INVALID_RESPONSE");
        ChinaPrice manual = currentPrice(appleId, scheduleId, "manualPrices");
        if (manual != null) return manual;
        ChinaPrice automatic = currentPrice(appleId, scheduleId, "automaticPrices");
        if (automatic == null) throw new PriceFailure("NO_CURRENT_PRICE");
        return automatic;
    }

    private ChinaPrice currentPrice(String appleId, String scheduleId, String kind) {
        String path = "/v1/inAppPurchasePriceSchedules/" + scheduleId + "/" + kind;
        Map<String, JsonNode> included = new HashMap<>();
        List<JsonNode> prices = pages(path + "?filter%5Bterritory%5D=CHN&include=inAppPurchasePricePoint,territory"
                + "&fields%5BinAppPurchasePricePoints%5D=customerPrice&fields%5Bterritories%5D=currency&limit=200", path, included);
        List<JsonNode> current = prices.stream().filter(p ->
                "CHN".equals(p.path("relationships").path("territory").path("data").path("id").asText())
                && p.path("attributes").has("startDate") && p.path("attributes").path("startDate").isNull()).toList();
        // Apple identifies the current price with a null startDate. Never pick a future schedule or guess its time zone.
        if (current.isEmpty()) return null;
        if (current.size() != 1) throw new PriceFailure("AMBIGUOUS_CURRENT_PRICE");
        JsonNode territory = included.get("territories/CHN");
        if (territory == null || !"CNY".equals(territory.path("attributes").path("currency").asText()))
            throw new PriceFailure("CURRENCY_MISMATCH");
        String pointId = current.get(0).path("relationships").path("inAppPurchasePricePoint").path("data").path("id").asText();
        JsonNode point = included.get("inAppPurchasePricePoints/" + pointId);
        if (point == null || !point.path("attributes").path("customerPrice").isTextual()) throw new PriceFailure("INVALID_RESPONSE");
        String amount = point.path("attributes").path("customerPrice").asText();
        if (!amount.matches("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?")) throw new PriceFailure("INVALID_RESPONSE");
        BigDecimal value = new BigDecimal(amount).setScale(2);
        if (value.signum() <= 0 || pointId.isBlank() || pointId.length() > 512) throw new PriceFailure("INVALID_RESPONSE");
        return new ChinaPrice(appleId, pointId, value);
    }

    private List<JsonNode> pages(String first, String expectedPath, Map<String, JsonNode> included) {
        List<JsonNode> rows = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        String path = first;
        for (int page = 0; path != null && page < 20; page++) {
            URI uri = URI.create(path.startsWith("/") ? BASE + path : path);
            if (!expectedPath.equals(uri.getPath()) || !seen.add(uri.toString())) throw new PriceFailure("INVALID_PAGINATION");
            JsonNode response = get(uri.toString());
            if (!response.path("data").isArray()) throw new PriceFailure("INVALID_RESPONSE");
            response.path("data").forEach(rows::add);
            response.path("included").forEach(item -> included.put(item.path("type").asText() + "/" + item.path("id").asText(), item));
            JsonNode next = response.path("links").path("next");
            path = next.isNull() || next.isMissingNode() ? null : next.asText();
            if (path != null && path.isBlank()) throw new PriceFailure("INVALID_PAGINATION");
        }
        if (path != null) throw new PriceFailure("PAGINATION_LIMIT");
        return rows;
    }

    private JsonNode get(String path) {
        URI uri = URI.create(path.startsWith("/") ? BASE + path : path);
        if (!"https".equals(uri.getScheme()) || !"api.appstoreconnect.apple.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new PriceFailure("INVALID_PAGINATION");
        try {
            Instant now = clock.instant();
            String token = JWT.create().withHeader(Map.of("typ", "JWT")).withKeyId(properties.getKeyId())
                    .withIssuer(properties.getIssuerId()).withAudience("appstoreconnect-v1")
                    .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(120)))
                    .withArrayClaim("scope", new String[]{"GET " + uri.getRawPath()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())})
                    .sign(Algorithm.ECDSA256(null, privateKey()));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(acquisition.getRequestTimeout()).header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new PriceFailure(switch (response.statusCode()) {
                case 401, 403 -> "APPLE_AUTH_FAILED";
                case 429 -> "APPLE_RATE_LIMITED";
                default -> "APPLE_UNAVAILABLE";
            });
            if (response.body().length() > 4_000_000) throw new PriceFailure("INVALID_RESPONSE");
            return json.readTree(response.body());
        } catch (PriceFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PriceFailure("APPLE_UNAVAILABLE"); }
        catch (Exception e) { throw new PriceFailure("APPLE_UNAVAILABLE"); }
    }
    private ECPrivateKey privateKey() throws Exception {
        if (key == null) synchronized (this) {
            if (key == null) key = AppleCrypto.privateKey(AppleCrypto.secret(properties.getPrivateKey(), properties.getPrivateKeyFile()));
        }
        return key;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
