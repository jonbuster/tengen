# Settings Page MVP Plan

## Status: Implemented — 2026-08-03

## Summary

Add a `/settings` page for browser-local appearance and timestamp preferences. Changes apply immediately, persist across reloads using `localStorage`, and require no backend or database work.

## Implementation Changes

- Add Settings to the authenticated sidebar navigation.
- Store a versioned `AppPreferences` object under `tengen-ui-preferences` with theme mode, preset accent color, and time display mode.
- Preserve the current defaults: light theme, blue accent (`#1976d2`), and local time.
- Load and validate preferences through a React context; use safe defaults for missing, malformed, outdated, or unsupported stored values.
- Generate the MUI theme dynamically for light, dark, and system modes. System mode follows OS changes.
- Apply the selected accent to primary controls and derive the navigation sidebar gradient and readable foreground contrast from it.
- Add theme, accent, time display, live preview, and reset controls to `/settings`.
- Centralize timestamp formatting and apply local/UTC display to Events, Event Details, Deliveries, API Keys, Rule History, and last-updated labels.
- Keep date-filter inputs in browser-local time; the preference changes rendered timestamps only.
- Keep preferences in memory if browser storage is unavailable.

## Interfaces

- Add client-only `ThemeMode`, `AccentKey`, `TimeDisplay`, and versioned `AppPreferences` types.
- Expose preference values and update/reset operations through a React context hook.
- Add a shared `formatTimestamp` helper accepting a value and local/UTC display mode.
- Do not add or modify backend APIs.

## Verification

- Unit tests cover defaults, valid and malformed storage, preference persistence, reset behavior, and local/UTC formatting.
- Existing Event Explorer tests render through the new preferences provider.
- Run frontend unit tests, lint, and production build.

## Assumptions

- Preferences are scoped to the current browser, not an administrator account.
- Accent colors are selected from accessible preset swatches.
- All existing displayed timestamps honor the selected display mode.
- Existing Delivery behavior remains unchanged.
