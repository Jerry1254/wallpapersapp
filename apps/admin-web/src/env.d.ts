/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DEMO_ADMIN_USERNAME?: string;
  readonly VITE_DEMO_ADMIN_PASSWORD?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
