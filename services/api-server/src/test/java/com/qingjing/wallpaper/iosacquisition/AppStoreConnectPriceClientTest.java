package com.qingjing.wallpaper.iosacquisition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppStoreConnectPriceClientTest {
    final List<HttpRequest> requests = new ArrayList<>();
    final HttpClient http = mock(HttpClient.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    String manual = prices("1.00", "CNY", true), automatic = prices("2.00", "CNY", true);
    int status = 200;
    final KeyPair key;
    final AppStoreConnectPriceClient client;

    AppStoreConnectPriceClientTest() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        key = generator.generateKeyPair();
        var properties = new IosPricingProperties();
        properties.setEnabled(true); properties.setIssuerId("00000000-0000-4000-8000-000000000001"); properties.setKeyId("TESTKEY001");
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        var acquisition = new IosAcquisitionProperties(); acquisition.setAppAppleId(123L);
        client = new AppStoreConnectPriceClient(properties, acquisition, new ObjectMapper(), http, clock);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); requests.add(request);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            String path = request.uri().getPath();
            String body = path.endsWith("inAppPurchasesV2")
                    ? "{\"data\":[{\"id\":\"1234\",\"attributes\":{\"productId\":\"com.test.wallpaper\",\"inAppPurchaseType\":\"NON_CONSUMABLE\"}}]}"
                    : path.endsWith("iapPriceSchedule") ? "{\"data\":{\"id\":\"1234\"}}"
                    : path.endsWith("manualPrices") ? manual : automatic;
            when(response.body()).thenReturn(body);
            return response;
        });
    }

    @Test void readsCustomerPriceInsteadOfProceedsAndSignsOnlyReadRequests() {
        var price = client.currentChinaPrice("com.test.wallpaper");
        assertThat(price.customerPrice().toPlainString()).isEqualTo("1.00");
        assertThat(price.appleInAppPurchaseId()).isEqualTo("1234");
        assertThat(requests).hasSize(3);
        for (var request : requests) {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getHost()).isEqualTo("api.appstoreconnect.apple.com");
            var token = JWT.require(Algorithm.ECDSA256((ECPublicKey)key.getPublic(), null))
                    .acceptExpiresAt(100_000_000).acceptIssuedAt(100_000_000).build()
                    .verify(request.headers().firstValue("Authorization").orElseThrow().substring(7));
            assertThat(token.getAudience()).containsExactly("appstoreconnect-v1");
            assertThat(token.getExpiresAtAsInstant()).isEqualTo(clock.instant().plusSeconds(120));
            assertThat(token.getClaim("scope").asArray(String.class)).containsExactly("GET " + request.uri().getRawPath()
                    + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery()));
        }
    }

    @Test void fallsBackToAutomaticallyGeneratedChinaPriceAndNeverUsesScheduledFuturePrice() {
        manual = prices("99.00", "CNY", false);
        assertThat(client.currentChinaPrice("com.test.wallpaper").customerPrice().toPlainString()).isEqualTo("2.00");
        assertThat(requests).hasSize(4);
        automatic = prices("88.00", "CNY", false);
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("NO_CURRENT_PRICE");
    }

    @Test void rejectsWrongCurrencyUnknownProductsAndAmbiguousAmounts() {
        manual = prices("1.00", "USD", true);
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("CURRENCY_MISMATCH");
        manual = prices("1.001", "CNY", true);
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("INVALID_RESPONSE");
        assertThatThrownBy(() -> client.currentChinaPrice("com.other.wallpaper")).hasMessage("PRODUCT_NOT_FOUND");
    }

    @Test void refusesToSendCredentialsToUntrustedPaginationLinks() {
        manual = prices("1.00", "CNY", true).replace("\"links\":{}", "\"links\":{\"next\":\"https://attacker.invalid/v1/inAppPurchasePriceSchedules/1234/manualPrices?page=2\"}");
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("INVALID_PAGINATION");
        assertThat(requests).hasSize(3);
    }

    @Test void exposesOnlySanitizedAuthenticationAndRateLimitErrors() {
        status = 403;
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("APPLE_AUTH_FAILED");
        status = 429;
        assertThatThrownBy(() -> client.currentChinaPrice("com.test.wallpaper")).hasMessage("APPLE_RATE_LIMITED");
    }

    static String prices(String price, String currency, boolean current) {
        return """
                {"data":[{"id":"price","attributes":{"startDate":%s,"endDate":null},
                "relationships":{"territory":{"data":{"id":"CHN"}},"inAppPurchasePricePoint":{"data":{"id":"point"}}}}],
                "included":[{"type":"inAppPurchasePricePoints","id":"point","attributes":{"customerPrice":"%s","proceeds":"0.84"}},
                {"type":"territories","id":"CHN","attributes":{"currency":"%s"}}],"links":{}}
                """.formatted(current ? "null" : "\"2027-01-01\"", price, currency);
    }
}
