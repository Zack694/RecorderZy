# Keep Flutter plugin registrants
-keep class io.flutter.** { *; }
-keep class io.flutter.plugin.** { *; }

# Keep our Kotlin classes referenced from manifest / channels
-keep class com.recorderzy.app.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
