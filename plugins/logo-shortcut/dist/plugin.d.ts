export interface LogoShortcutPlugin {
  /** Create a home-screen pinned shortcut with the given logo. */
  createShortcut(options: { logoUrl: string; label?: string }): Promise<{ created: boolean }>;
  /** Whether this device/launcher supports pinned shortcuts. */
  isRequestPinShortcutSupported(): Promise<{ supported: boolean }>;
}
