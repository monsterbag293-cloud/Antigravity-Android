# Phase 4.1: Mobile UI Adaptation & Touch Interaction Design

## 1. Top Panel Elimination
The desktop application renders a 35px high title menu bar containing the menus `Antigravity`, `Workspace`, `View`, and `Window` with `style="app-region: drag;"`. On mobile devices, this takes up valuable screen real estate and is non-functional.
- **CSS Rule Applied**:
  ```css
  .agy-shell > .shrink-0:has([data-testid="title-menu-bar"]),
  [data-testid="title-menu-bar"],
  div[data-testid="title-menu-bar"] {
    display: none !important;
    height: 0 !important;
    min-height: 0 !important;
    padding: 0 !important;
    margin: 0 !important;
    overflow: hidden !important;
  }
  ```
- **Result**: The mobile header cleanly renders the top action bar (`[Sidebar Toggle] [Project Name] [Back/Forward] [Account Circle]`) with zero wasted pixels.

---

## 2. Left Navigation Panel (Sidebar) Overlay & Mobile Gestures
On desktop, the sidebar docks side-by-side with the main chat view. On a 411px wide mobile viewport (Pixel 10 Pro), docking reduces the chat area to an unusable 155px width.
- **Full-Screen Overlay CSS**:
  ```css
  .agy-shell .flex-1 .relative.flex.w-full.h-full > .flex:first-child {
    position: absolute !important;
    inset: 0 !important;
    width: 100% !important;
    max-width: 100% !important;
    z-index: 50 !important;
    background: #000000 !important;
    box-shadow: 0 0 30px rgba(0,0,0,0.8) !important;
  }
  ```
- **Right-to-Left Swipe Dismissal**:
  A passive touch listener tracks horizontal gestures on the open sidebar. If `dx = startX - currentX > 45px` and vertical drift `dy < 90px`, the listener clicks `document.querySelector('[aria-label="Toggle Sidebar"]')` to smoothly close the drawer.
- **No Edge-Swipe Opening**:
  Edge swipe to open is intentionally omitted because Android 10+ uses horizontal edge swipes from either screen boundary for system back navigation. Opening the sidebar is handled via the dedicated header icon button.
- **Conversation Tap Behavior**:
  When a conversation item is tapped:
  1. `document.activeElement.blur()` is executed immediately.
  2. The sidebar is toggled closed after an 80ms delay.
  3. `document.activeElement.blur()` is re-invoked to ensure the software keyboard does not pop up unintentionally when simply viewing conversation history.

---

## 3. Settings Panel Adaptation
- **Modal Size**: `div[role="dialog"]` is constrained to 100% width and height on mobile screens with zero border-radius.
- **Swipe-to-Close**: Swiping right-to-left across the dialog dismisses the settings screen.
- **Desktop Shortcuts Hiding**:
  `[data-screen="Shortcuts"]` and buttons linking to keyboard shortcuts are hidden via CSS since mobile touch screens do not use physical function keys or desktop key combinations.

---

## 4. Software Keyboard & Chat Input Insets (`imePadding`)
- **Problem**: When `enableEdgeToEdge()` is active, the Android software keyboard (IME) draws on top of the window content, completely covering the chat input and Send button.
- **Solution**: In `JetskiWebViewHost.kt`, the root `Column` container is configured with `Modifier.imePadding()`:
  ```kotlin
  Column(
      modifier = modifier
          .fillMaxSize()
          .imePadding()
          .background(Color(0xFF000000))
  ) { ... }
  ```
- **Behavior**: When the virtual keyboard appears, the Column resizes upward by the exact pixel height of the keyboard, ensuring the chat editor and send controls remain directly above the top edge of the keyboard.

---

## 5. Touchscreen Drag-and-Drop Polyfill
- **Problem**: HTML5 standard Drag-and-Drop (`dragstart`, `dragover`, `drop`) does not natively fire on touch events in mobile WebViews, preventing project and tab ordering.
- **Solution**: A synthetic Drag-and-Drop polyfill is injected with a 300ms press-and-hold threshold:
  - If touch moves > 10px before 300ms, the timer cancels, allowing standard scrolling.
  - If held for 300ms, a synthetic `dragstart` event is dispatched on the target element.
