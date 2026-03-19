# Frontend Architecture Guide

## Scope
- Module: frontend (JavaFX)
- Main entry: com.example.frontend.DashboardApp
- Primary screen controller: com.example.frontend.controller.DashboardController

## High-level Structure
- View:
  - FXML: basic shells (login, dashboard)
  - Java-built dynamic layouts in DashboardController
- ViewModel:
  - DashboardViewModel for UI state and preferences
- UI Service:
  - DashboardUIService for visual behavior (zoom, fit-all, toast, visibility)
- Interaction Service:
  - DashboardInteractionService for drag/resize handlers
- Feature Modules:
  - DashboardClassroomModule
  - DashboardWidgetModule
  - DashboardRealtimeModule
  - DashboardManagementModule
  - DashboardCanvasModule
- Data/Network:
  - DashboardService (REST operations)
  - DashboardDataService (refresh snapshot and classroom list)
  - ApiClient (HTTP + JSON)

## Runtime Flow
1. DashboardApp loads login scene and applies global stylesheet/theme.
2. DashboardController initializes ViewModel, top bar, tabs, and modules.
3. Scheduler triggers periodic refresh snapshot updates.
4. Realtime module delivers websocket alerts and refresh hints.
5. Canvas and interaction service manage user drag/resize updates.
6. Service layer persists panel/widget positions via backend APIs.

## State Ownership
- DashboardViewModel owns:
  - zoomScale, snapToGrid, layoutLocked
  - dashboardOnlyMode, focusMode, editMode
  - selectedDashboardId
  - cached lists (classrooms, dashboards, panels)
- DashboardController should prefer ViewModel getters/setters rather than ad-hoc local state.

## Current Refactoring Status
- Completed:
  - drag/resize logic centralized through DashboardInteractionService
  - right-pane and left-pane builder methods split for readability
  - network panel rendering split into helper methods
  - unit/integration-style tests added for ViewModel, UI service, interaction service, data service
- In progress:
  - further decomposition of DashboardController cell rendering and panel orchestration

## Recommended Next Steps
1. Move remaining large UI blocks into dedicated builder classes.
2. Keep controller as composition root + event routing only.
3. Expand tests around controller workflows and module boundaries.
4. Keep performance baseline updates in scripts/profile-dashboard.latest.json.
