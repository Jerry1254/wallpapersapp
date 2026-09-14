package com.qingjing.wallpaper.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public enum DevicePlatform { ANDROID, IOS, HARMONYOS, H5_TEST }

    public enum CredentialType { PLATFORM_PUBLIC_KEY, H5_TEST_SECRET }

    public enum ChallengeAlgorithm { ED25519, ECDSA_P256_SHA256, RSA_SHA256, HMAC_SHA256 }

    public record DeviceRegistrationRequest(
            @NotNull DevicePlatform platform,
            @NotBlank @Size(max = 64) String appInstallScope,
            @NotNull CredentialType credentialType,
            @Size(min = 64, max = 8192) String publicKeyPem,
            @NotBlank @Size(min = 16, max = 8192) String evidenceToken) {
    }

    public record DeviceRegistrationResponse(
            String credentialKeyId,
            CredentialType credentialType,
            String credentialSecret,
            Instant createdAt) {
    }

    public record CreateChallengeRequest(@NotBlank String credentialKeyId) {
    }

    public record DeviceSessionChallenge(
            String challengeId,
            String nonce,
            ChallengeAlgorithm algorithm,
            Instant expiresAt) {
    }

    public record CreateDeviceSessionRequest(
            @NotBlank String credentialKeyId,
            @NotBlank String challengeId,
            @NotNull Instant clientTimestamp,
            @NotBlank @Size(min = 16, max = 2048) String proof) {
    }

    public record DeviceSession(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            DevicePlatform platform) {
    }
}
