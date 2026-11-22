# Mobile Math Studio

An Android mathematical workspace with an infinite 2D formula canvas, built-in symbolic CAS solver, interactive graphing engine, custom AGSL Liquid Glass UI, and A4 PDF/image export.

---

## Features

- **Infinite Mathematical Canvas** — Place, drag, pan, and zoom formula cards freely across an unbounded workspace, with multi-canvas management and auto-save.
- **2D Structured Math Editor & Custom Keyboard** — Native editing of fractions, roots, exponents, absolute values, and systems of equations (`{ ... }`), plus LaTeX import/copy and undo/redo history.
- **Built-in CAS Solver (Symja)** — Solves algebraic, trigonometric, and systems of equations/inequalities with both exact symbolic and configurable decimal outputs:
  - Identities with automatic domain restrictions (e.g. `x ∈ (-∞, +∞)`, `x ∈ (-∞, 0) ∪ (0, +∞)` for `x/x = 1`).
  - Interval and union notation for inequalities and interval equations (`x ∈ (-∞, -2) ∪ [5, +∞)`).
  - Periodic infinite trigonometric solution series (`x = πn, n ∈ ℤ`).
- **Interactive Graphing Engine** — Plots explicit (`y = f(x)`, `x = g(y)`), implicit (`F(x, y) = 0`), and piecewise curves with discontinuity/asymptote detection:
  - Domain clipping for systems combining equations and inequalities (e.g. `{ y = x ; x > 3 }`), rendering hollow (`○`) and filled (`●`) boundary markers for strict/non-strict bounds.
  - Half-plane shading with dashed/solid boundaries for standalone inequalities.
- **AGSL Liquid Glass UI** — Custom Android Graphics Shading Language (`RuntimeShader`) refraction and dispersion shaders with batched offscreen compositing, adaptive FPS controller, and Light/Dark themes.
- **PDF & Image Export** — Interactive A4 page layout editor exporting directly to `PDF`, `PNG`, and `JPG`.

## Tech Stack

- **Language:** Kotlin 2.2.10
- **UI:** Jetpack Compose (BOM `2026.02.01`, Material 3)
- **CAS Engine:** Symja (`org.matheclipse:matheclipse-core:3.1.1`)
- **Graphics / Shaders:** Android Graphics Shading Language (AGSL / `RuntimeShader`)
- **Build & Optimization:** Android Gradle Plugin 9.2.1, Gradle 9.4.1, R8 (`isMinifyEnabled = true`, `isShrinkResources = true`)
- **Target Platform:** Android 13+ (`minSdk = 33`, `targetSdk = 36`)

## Building

### Prerequisites

- Android Studio (with JDK 17+ / bundled JetBrains Runtime)
- Android SDK 36

### Commands

```bash
# Run unit tests
./gradlew :app:testDebugUnitTest

# Build debug APK
./gradlew :app:assembleDebug

# Build minified release APK (R8)
./gradlew :app:assembleRelease
```

On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

---

## Русский

**Mobile Math Studio** — Android-приложение для работы с математикой на бесконечном 2D-холсте со встроенным символьным решателем (CAS), интерактивным графическим движком, кастомными AGSL-шейдерами «жидкого стекла» и экспортом в PDF/PNG/JPG.

### Основные возможности

- **Бесконечный математический холст** с перемещением карточек формул, зумом, панорамированием и менеджером полотен.
- **2D-редактор формул и математическая клавиатура** (дроби, корни, степени, модули, системы `{ ... }`, импорт/копирование LaTeX).
- **CAS-солвер на базе Symja** с поддержкой тождеств с учётом ОДЗ, интервальной записи ответов (`x ∈ (-∞, a) ∪ [b, +∞)`) и периодических тригонометрических серий.
- **Интерактивные графики** с определением разрывов и асимптот, обрезкой кривых по неравенствам внутри систем (`{ y = x ; x > 3 }`) с выколотыми/закрашенными граничными точками и закраской полуплоскостей для одиночных неравенств.
- **AGSL Liquid Glass** — кастомные шейдеры преломления и дисперсии с адаптивным контроллером производительности и светлой/тёмной темой.
- **Экспорт в PDF, PNG и JPG** с редактором разметки страниц A4.

### Сборка

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```
