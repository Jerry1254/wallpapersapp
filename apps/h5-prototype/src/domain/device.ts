import type { PageMetadata, PublicMedia, PublicWallpaperSummary } from './catalog';

export interface DeviceCredential {
  credentialKeyId: string;
  credentialSecret: string;
}
export interface DeviceSession {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  platform: 'H5_TEST';
}
export interface DeviceChallenge {
  challengeId: string;
  nonce: string;
  algorithm: 'HMAC_SHA256';
  expiresAt: string;
}
export interface Entitlement {
  id: string;
  wallpaper: PublicWallpaperSummary;
  grantedAt: string;
}
export interface EntitlementPage {
  items: Entitlement[];
  page: PageMetadata;
}
export interface RedemptionResult {
  idempotencyKey: string;
  result: 'GRANTED' | 'ALREADY_OWNED' | 'CODE_NOT_FOUND' | 'CODE_EXHAUSTED' | 'WALLPAPER_UNAVAILABLE' | 'FAILED';
  quotaDelta: number;
  entitlement?: Entitlement | null;
  errorCode?: string | null;
  createdAt: string;
}
export interface RedemptionProcessing {
  status: 'PROCESSING';
  idempotencyKey: string;
}
export type RedemptionResponse = RedemptionResult | RedemptionProcessing;
export interface H5DownloadDescriptor {
  deliveryMode: 'H5_PLACEHOLDER';
  wallpaperId: string;
  cover: PublicMedia;
}
