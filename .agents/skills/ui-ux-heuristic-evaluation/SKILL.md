---
name: ui-ux-heuristic-evaluation
description: Expert UI/UX heuristic evaluation and usability auditing for mobile applications. Use when reviewing UI designs, identifying usability friction, applying Jakob Nielsen's 10 Usability Heuristics, Fitts's Law, Hick's Law, and optimizing user flows.
---

# Mobile UI/UX Heuristic Evaluation & Usability Auditor

This skill provides a rigorous framework for conducting usability audits, evaluating interfaces against Jakob Nielsen's 10 Usability Heuristics, and applying cognitive psychology laws to mobile apps.

---

## 1. Jakob Nielsen's 10 Usability Heuristics for Mobile

### 1. Visibility of System Status
- The app should always keep users informed about what is going on through appropriate feedback within a reasonable time.
- *Mobile Rule*: Always display live progress bars, speed metrics, ETA, and connection status during background or network tasks. Never show an unadorned spinner without explanatory text if an operation exceeds 1 second.

### 2. Match Between System and the Real World
- The system should speak the users' language with words, phrases, and concepts familiar to the user.
- *Mobile Rule*: Use human-readable file sizes ("14.2 MB" instead of raw byte counts), friendly durations ("2m 15s" instead of "135s"), and real-world concepts ("Gallery", "Save", "History").

### 3. User Control and Freedom
- Users often make mistakes and need a clearly marked "emergency exit" to leave the unwanted state.
- *Mobile Rule*: Support immediate cancellation on long-running downloads, provide "Undo" snackbars for destructive actions, and ensure back-gesture navigation is predictive and intuitive.

### 4. Consistency and Standards
- Users should not have to wonder whether different words, situations, or actions mean the same thing. Follow platform conventions.
- *Mobile Rule*: Follow Material Design 3 guidelines for Android. Bottom navigation bars for primary destinations, top app bar for screen context, floating snackbars for temporary feedback.

### 5. Error Prevention
- Even better than good error messages is a careful design which prevents a problem from occurring in the first place.
- *Mobile Rule*: Disable action buttons when prerequisites are missing (e.g. disable "Fetch" when URL input is empty), sanitize input URLs automatically (trim whitespace, strip tracking query parameters).

### 6. Recognition Rather than Recall
- Minimize the user's memory load by making elements, actions, and options visible.
- *Mobile Rule*: Show thumbnail previews of videos and images before requiring the user to press "Download". Provide a "Paste" button so users don't have to manually remember clipboard contents.

### 7. Flexibility and Efficiency of Use
- Accelerators—unseen by novice users—may speed up the interaction for the expert user.
- *Mobile Rule*: Support multi-selection in grids (batch saving), quick-filter chips (All, Photos, Videos), and direct share sheet integration (`ACTION_SEND`).

### 8. Aesthetic and Minimalist Design
- Interfaces should not contain information that is irrelevant or rarely needed. Every extra unit of information competes with the relevant units.
- *Mobile Rule*: "Clean Utility" aesthetic. No clutter, no ads, no aggressive banners. Use tonal surfaces to establish depth instead of heavy borders and drop shadows.

### 9. Help Users Recognize, Diagnose, and Recover from Errors
- Error messages should be expressed in plain language (no stack traces), precisely indicate the problem, and constructively suggest a solution.
- *Mobile Rule*: Instead of `NullPointerException: socket timeout`, show `"Network connection timed out. Check your connection."` with an inline `[Retry]` button.

### 10. Help and Documentation
- Even though it is better if the system can be used without documentation, it may be necessary to provide help.
- *Mobile Rule*: Provide clear 3-line privacy explanations on permission grant screens, and concise subtitles under settings rows.

---

## 2. Fundamental Cognitive Laws in Mobile UI

- **Fitts's Law**: The time to acquire a target is a function of the distance to and size of the target.
  - *Application*: Interactive elements must have a touch target of at least `48dp x 48dp`. Primary actions (e.g., Download button) should be full-width and placed in the lower thumb-friendly zone of the screen.
- **Hick's Law**: The time it takes to make a decision increases logarithmically with the number and complexity of choices.
  - *Application*: Limit top-level tabs to 4-5 items. Limit stream quality selection to sensible presets (1080p, 720p, Audio) rather than exposing 30 raw stream permutations.
- **Miller's Law**: The average person can only keep 7 (plus or minus 2) items in their working memory.
  - *Application*: Chunk download progress into distinct bite-sized metrics (Percentage, Speed, ETA, Size).
- **Jakob's Law**: Users spend most of their time on other apps, meaning they prefer your app to work the same way as all the other apps they already know.
  - *Application*: Use standard Android navigation bars, back-gesture support, and recognizable Material Symbols icons.
