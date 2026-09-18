# Громкость приложений (VolumeManager)

Мини-приложение: **Volume+/−** → overlay со слайдерами громкости **по приложениям**.

Требования: **Android 13+** (API 33), root (Magisk) **или** Sui/Shizuku.

Два APK (product flavors), ставятся рядом:

| Flavor | applicationId | Backend |
|--------|---------------|---------|
| **root** | `com.volumemanager.app` | Magisk + libsu RootService |
| **sui** | `com.volumemanager.app.sui` | Sui Magisk-модуль или классический Shizuku |

Shared: overlay, accessibility, prefs. Idle (привилегии есть, ничего не играет) — один слайдер `STREAM_MUSIC`.

Готовые сборки: [Releases](https://github.com/lnked/volumeManager/releases).

---

## Установка (с телефона)

Скачай APK из [последнего релиза](https://github.com/lnked/volumeManager/releases/latest):

- `app-root-release.apk` — путь Magisk/root
- `app-sui-release.apk` — путь Sui/Shizuku
- `volumemanager_root-v*.zip` — Magisk-модуль (только для **root**)

Разреши установку из неизвестных источников для браузера/файлового менеджера.

### A) Magisk root

1. Magisk → Modules → Install from storage → `volumemanager_root-v1.0.0.zip` → **Reboot**.
2. Установи `app-root-release.apk`.
3. Открой **Громкость приложений**:
   - «Выдать root» → подтверди su в Magisk.
   - «Открыть спец. возможности» → включи сервис приложения.
4. Статусы должны стать: спец. возможности **вкл**, backend **ok**, сервис **готов**.
5. Нажми Volume+/− — появится overlay.

### B) Sui / Shizuku

1. Поставь backend (один из двух):
   - **Sui** (рекомендуется на Magisk): модуль [RikkaApps/Sui](https://github.com/RikkaApps/Sui) → reboot → менеджер разрешений Sui.
   - **Shizuku**: приложение Shizuku → Start через Wireless debugging / ADB (нужно после каждой перезагрузки).
2. Установи `app-sui-release.apk`.
3. Открой **Громкость приложений (Sui)**:
   - «Выдать / открыть Sui» → разреши приложению.
   - «Открыть спец. возможности» → включи сервис.
4. Статусы: спец. возможности **вкл**, backend **ok**, сервис **готов**.
5. Volume+/− → overlay.

Без привилегий overlay показывает только общий `STREAM_MUSIC`.

### Настройка (чеклист)

| Шаг | Root | Sui/Shizuku |
|-----|------|-------------|
| Magisk-модуль `volumemanager_root` | ✅ | — |
| Sui модуль **или** Shizuku running | — | ✅ |
| APK установлен | root flavor | sui flavor |
| Root / Sui permission | «Выдать root» | «Выдать / открыть Sui» |
| Accessibility | Volume+/− сервис | то же |
| Проверка | Volume+/− → слайдеры | то же |

ADB (если ставишь с компа):

```bash
adb install -r app-root-release.apk
# или
adb install -r app-sui-release.apk
```

---

## Сборка из исходников

```bash
./gradlew :app:assembleRootRelease :app:assembleSuiRelease
# app/build/outputs/apk/root/release/app-root-release.apk
# app/build/outputs/apk/sui/release/app-sui-release.apk
```

Debug:

```bash
./gradlew :app:assembleRootDebug
# app/build/outputs/apk/root/debug/app-root-debug.apk

./gradlew :app:assembleSuiDebug
# app/build/outputs/apk/sui/debug/app-sui-debug.apk
```

Magisk zip (только **root**-путь, модуль `volumemanager_root`):

```bash
bash magisk/package-zip.sh
# out/volumemanager_root-v1.0.0.zip
```

Release включает R8 minify + shrinkResources.

---

## Smoke-test

1. Spotify + YouTube → два слайдера, независимо.
2. Spotify + Unity/OpenSL игра — зафиксировать, реагирует ли на `IPlayer.setVolume` (не все движки слушают).

## Ключевые пути

- `magisk/` — Magisk-модуль root flavor (`volumemanager_root`)
- `app/src/root/.../root/` — libsu RootService
- `app/src/sui/.../shizuku/` + `privileged/` — Shizuku/Sui UserService
- `app/src/main/.../a11y|overlay|data` — shared UI
