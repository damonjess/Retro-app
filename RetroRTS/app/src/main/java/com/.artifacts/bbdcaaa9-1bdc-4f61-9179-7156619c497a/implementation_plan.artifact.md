# Implementation Plan - Modern Game Library UI and Console Categorization

This plan updates the game data structure to explicitly store the console type and implements a modern, grouped library UI using Jetpack Compose sticky headers.

## Proposed Changes

### [Game Data & Persistence]

#### [MODIFY] [GameLibrary.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/GameLibrary.kt)
- Update `save()` to persist `consoleType` in the `library.json` file.
- Update `load()` to restore `consoleType` from JSON, with a fallback to `ConsoleType.detect()`.
- Update `scanGamesFolder()` to detect the console type during initial scanning.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/MainActivity.kt)
- Update `GameEntry` data class to match the requested structure (explicit `consoleType` and `gameId` initialization).

---

### [UI Components]

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/MainActivity.kt)
- **Imports**: Add `androidx.compose.foundation.lazy.items`, `androidx.compose.foundation.ExperimentalFoundationApi`, and `androidx.compose.foundation.lazy.stickyheaders.StickyHeader` (if available, otherwise use `stickyHeader` from `LazyColumn`).
- **LibraryTab**: Refactor to group games by `ConsoleType` and display them in a `LazyColumn` with sticky headers.
- **ConsoleHeader**: Add a new composable for platform-specific section headers with icons.
- **ModernGameCard**: Update/Add the game card design with platform icons and badges, integrating existing launch and remove functionality.

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors after refactoring `GameEntry` and `LibraryTab`.

### Manual Verification
- Deploy to device/emulator.
- Perform a "Rescan" to verify games are detected and categorized correctly.
- Verify sticky headers are displayed and group games by console.
- Verify "Play" and "Remove" functionality still works on the new card design.
- Verify that closing and reopening the app persists the console types in the library.
