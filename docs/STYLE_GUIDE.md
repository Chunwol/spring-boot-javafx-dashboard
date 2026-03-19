# Frontend Style Guide

## Purpose
This guide defines coding and UI conventions for the JavaFX frontend module.

## Java Code Conventions
- Keep controller methods focused and short.
- Extract repeated UI/event logic into helper methods or services.
- Prefer explicit names over abbreviations.
- Avoid mixing state mutation and rendering logic in one block.
- Keep asynchronous API callbacks minimal and delegate to module/service methods.

## State Management
- Use DashboardViewModel as the single source of truth for dashboard state.
- Persist preference-backed state through ViewModel methods.
- Do not reintroduce parallel local fallback fields unless required.

## UI Construction
- Prefer reusable helper builders for repetitive button/toggle/menu setup.
- Use consistent button styles via createActionButton / createIconActionButton.
- Keep inline style strings concise; move stable style rules to CSS when possible.

## Theme and Visual System
- Global theme stack:
  - AtlantaFX PrimerLight (user-agent stylesheet)
  - dashboard-theme.css (project stylesheet)
- Existing UI libraries:
  - ControlsFX for enhanced controls and validation
  - Ikonli MaterialDesign2 pack for icons

## Interaction Patterns
- Drag/resize behavior must go through DashboardInteractionService.
- Snap/grid behavior should read from ViewModel state.
- Persist position/size updates on release events only.

## Testing Guidelines
- Required checks after frontend changes:
  - gradlew :frontend:classes
  - gradlew :frontend:test
- For UI helper/service changes, add or update unit tests.
- For asynchronous service flows, verify fallback paths explicitly.

## Documentation and Profiling
- Update docs/ENGINEERING_HANDOFF_2026-03-18.md with progress deltas.
- Maintain docs/PERFORMANCE_BASELINE.md with latest representative metrics.
- Use scripts/profile-dashboard.ps1 for reproducible API latency baseline.
