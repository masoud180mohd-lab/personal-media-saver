---
name: android-ui-ux-design
description: Comprehensive expert guidelines for Android and Jetpack Compose UI/UX design. Use when crafting beautiful, modern, accessible, and intuitive user interfaces, implementing Material 3 design systems, typography hierarchies, tonal color palettes, dark-first styling, and edge-to-edge layouts.
---

# Android & Jetpack Compose UI/UX Design Specialist

This skill provides expert guidelines, design tokens, architectural patterns, and practical implementation blueprints for creating world-class, premium Android user interfaces with Jetpack Compose and Material Design 3 (M3).

---

## 1. Design Language: "Clean Utility" & Modern M3

Modern utility apps (e.g., downloaders, media players, tools) demand a calm, confident, ad-free aesthetic:

- **Tonal Hierarchy**: Avoid flat monochrome or pure black/white extremes. Use layered M3 tonal surfaces (`surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceBright`).
- **Dark-First Elegance**:
  - Dark background base: `#0B0E13` or `#121316`
  - Cards & containers: `#161B24` to `#1D232E`
  - Content on surface: `#E2E4E9` (high emphasis) and `#9EA3AE` (medium emphasis)
- **Tonal Error Feedback**: Never use harsh, jarring full-width red banners. Use subtle, rounded `errorContainer` cards with circular icon backdrops and clear remediation actions (e.g., "Retry", "Open Settings").
- **Brand Accent Rule**: External brand colors (YouTube red, TikTok cyan, WhatsApp green, Instagram pink) must strictly serve as *accents* (selection rings, pill badges, icon tints), never as overwhelming full surface fills.

---

## 2. Spatial Grid & Layout Rhythm

Adhere strictly to an **8dp grid system**:

- **Screen Margins**: `16dp` standard on phones; `24dp` or adaptive multi-column on tablets/foldables.
- **Card Spacing & Gaps**: `12dp` to `16dp` between content cards.
- **Internal Card Padding**: `14dp` to `16dp` padding.
- **Touch Targets**: Minimum **`48dp x 48dp`** bounding box for all interactive elements (`IconButton`, chips, clickable rows) to prevent touch misses (Fitts's Law).
- **Corner Radii Hierarchy**:
  - Small chips & tags: `10dp`
  - Thumbnails: `16dp`
  - Cards & dialogs: `20dp` to `24dp`
  - Bottom sheets: `28dp` top corners
  - Buttons: Pill (`CircleShape`) for modern, tactile feel

---

## 3. Typography Hierarchy & Readability

```kotlin
// Compose Typography Scale Best Practices
val Typography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum" // Critical for tabular numbers (timers, speeds, percentages)
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)
```

- **Line Clamping**: Titles should clamp to 1 or 2 lines using `maxLines = 2` and `overflow = TextOverflow.Ellipsis`.
- **Contrast Ratios**: Maintain at least **4.5:1** contrast for normal body text and **3:1** for large headlines against their container surfaces (WCAG 2.1 AA).

---

## 4. State-Driven UX Patterns

Every UI screen must explicitly handle the 4 fundamental lifecycle states:

### A. Loading States: Skeleton Shimmers over Spinners
Instead of a single blocking center spinner, use content-shaped shimmer placeholders to convey structure:
```kotlin
@Composable
fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val isReduceMotion = rememberIsReduceMotionEnabled()
    if (isReduceMotion) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
    } else {
        // Linear animated gradient sweep
    }
}
```

### B. Empty States: Clear, Supportive, Actionable
- Center a 72dp tonal circle with a themed icon (`surfaceContainerHigh`).
- Add a bold headline, 2-line helpful explanation, and an optional filled primary CTA button ("Go to Downloads", "Add URL").

### C. Error States: Contextual & Inline
- Position errors directly adjacent to the input or action that failed.
- Include a retry icon button or action link.

### D. Success Feedback: Floating Snackbars
- Provide floating tonal snackbars (`16dp` corner radius, leading icon, elevated above navigation bar).

---

## 5. Edge-to-Edge & System Insets
- Call `enableEdgeToEdge()` in `MainActivity.onCreate()`.
- Use `Scaffold` inner padding: `Modifier.padding(innerPadding)`.
- Use `WindowInsets.navigationBars` and `WindowInsets.statusBars` where custom immersive content (e.g. video player or media viewer) is displayed.

---

## 6. Accessibility & Inclusivity Checklist
- [ ] Minimum 48dp touch targets on all clickables.
- [ ] Meaningful `contentDescription` on non-decorative icons (`null` on purely decorative ones).
- [ ] Dynamic font scaling support (use `.sp` for text, `.dp` for layouts).
- [ ] Respect system "Reduce motion" / "Remove animations" setting.
- [ ] Test in both Dark and Light themes with dynamic color enabled and disabled.
