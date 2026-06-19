# Keep Flutter plugin registrants
-keep class io.flutter.** { *; }
-keep class io.flutter.plugin.** { *; }

# Keep our Kotlin classes referenced from manifest / channels
-keep class com.recorderzy.app.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Flutter's deferred-components support references the Play Core split-install
# library, which we don't ship. Tell R8 these references are intentional and
# safe to drop - they're only needed if the user is building an App Bundle
# with deferred components, which we are not.
-dontwarn com.google.android.play.core.**
-dontwarn com.google.android.play.**

# AndroidX accessibility / lifecycle runtime helpers may shed inner classes.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
