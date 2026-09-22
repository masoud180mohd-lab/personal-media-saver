---
name: compose-motion-and-delight
description: Advanced motion design, micro-interactions, and delight patterns for Jetpack Compose. Use when implementing screen transitions, physics-based springs, gesture animations, shimmers, and accessible motion controls.
---

# Jetpack Compose Motion & Delight Specialist

This skill provides expert patterns for implementing purposeful motion, micro-interactions, and dynamic feedback in Jetpack Compose applications while maintaining 60/120 FPS performance and respecting accessibility preferences.

---

## 1. Core Principles of Purposeful Motion

- **Function First**: Motion should clarify spatial relationships, guide user focus, and communicate status changes—not distract.
- **Micro-duration Scale**:
  - Micro-interactions (icons, toggles): `100ms` – `150ms`
  - Small component changes (chips, menus): `200ms` – `250ms`
  - Screen / modal transitions: `250ms` – `350ms`
- **Physics-Based Springs**: Prefer `spring()` over linear or fixed-duration tweens for natural touch feel:
  ```kotlin
  val springSpec = spring<Float>(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessLow
  )
  ```

---

## 2. Accessible Motion: Reduce Motion

Always check system animation scales so users with motion sensitivities or low-power modes get a calm experience:

```kotlin
@Composable
fun rememberIsReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            animatorScale == 0f
        } catch (_: Exception) {
            false
        }
    }
}
```

---

## 3. Screen & Navigation Transitions

Modern apps should not use abrupt cut-and-paste transitions. Use directional subtle slide + crossfade:

```kotlin
NavHost(
    navController = navController,
    startDestination = Screen.Home.route,
    enterTransition = {
        if (isReduceMotion) fadeIn(tween(0))
        else fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 8 }
    },
    exitTransition = {
        if (isReduceMotion) fadeOut(tween(0))
        else fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 8 }
    },
    popEnterTransition = {
        if (isReduceMotion) fadeIn(tween(0))
        else fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 8 }
    },
    popExitTransition = {
        if (isReduceMotion) fadeOut(tween(0))
        else fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 8 }
    }
)
```

---

## 4. Micro-Interactions & Haptic Feedback

Pair visual feedback with tactile confirmation for critical actions (e.g., download complete, copy link, delete):

```kotlin
val haptic = LocalHapticFeedback.current

IconButton(
    onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onAction()
    }
) {
    Icon(Icons.Rounded.Download, contentDescription = null)
}
```

---

## 5. Smooth Progress & Shimmer Effects

Avoid jumping progress metrics. Use `animateFloatAsState` to smooth out network streaming chunks:

```kotlin
val animatedProgress by animateFloatAsState(
    targetValue = rawProgressFraction,
    animationSpec = if (isReduceMotion) tween(0) else tween(300),
    label = "progressSmoothing"
)

LinearProgressIndicator(
    progress = { animatedProgress },
    strokeCap = StrokeCap.Round
)
```
