# VolumeManager — план

См. Magisk-план (вне репо) и [`.cursor/plans/per-app_volume_android.plan.md`](.cursor/plans/per-app_volume_android.plan.md).

## Кратко

Мини-APK: Volume+/− → overlay → слайдеры громкости **по приложениям**.

- Стек: Kotlin, AccessibilityService, Magisk + libsu RootService, Android 13+
- API: hidden `IPlayer.setVolume` (не публичный `AudioManager` stream volume)
- MVP: Magisk-модуль + root → overlay на volume → persist per package
