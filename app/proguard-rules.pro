# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep generic signature of Call, Response (R8 full mode strips signatures)
-keepattributes Signature

# Keep annotation default values
-keepattributes AnnotationDefault

# Keep source file names and line numbers for better stack traces
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ========== Kotlin ==========
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ========== Coroutines ==========
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ========== Hilt ==========
-dontwarn com.google.errorprone.annotations.**
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep @dagger.hilt.InstallIn class *
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.qualifiers.* <fields>;
}

# ========== Room ==========
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# Keep Room generated classes
-keep class **_Impl { *; }
-keep class **Dao_Impl { *; }

# ========== Nordic BLE ==========
-keep class no.nordicsemi.android.ble.** { *; }
-keepclassmembers class no.nordicsemi.android.ble.** { *; }

# ========== WorkManager ==========
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.InputMerger
-keep class androidx.work.** { *; }
-keepclassmembers class androidx.work.** { *; }

# ========== Google Play Services ==========
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep Location classes
-keep class com.google.android.gms.location.** { *; }
-keep interface com.google.android.gms.location.** { *; }

# Keep Maps classes
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# ========== Compose ==========
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ========== Data Classes (for serialization) ==========
# Keep all data classes in com.untailed.data package
-keep class com.untailed.data.** { *; }
-keepclassmembers class com.untailed.data.** { *; }

# ========== Enums ==========
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ========== Parcelable ==========
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ========== Serializable ==========
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ========== Timber ==========
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.** { *; }

# ========== Remove Logging in Release ==========
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ========== App Specific ==========
# Keep Application class
-keep class com.untailed.UntailedApplication { *; }

# Keep Service classes
-keep class com.untailed.service.** { *; }

# Keep ViewModel classes
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Keep Repository classes
-keep class com.untailed.data.repository.** { *; }

# Keep Algorithm classes
-keep class com.untailed.algorithm.** { *; }

# ========== Reflection ==========
-keepattributes InnerClasses
-keep class **.R
-keep class **.R$* {
    <fields>;
}

# ========== Native Methods ==========
-keepclasseswithmembernames class * {
    native <methods>;
}
