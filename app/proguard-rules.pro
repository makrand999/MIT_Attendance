# ─────────────────────────────────────────────────────────────────────────────
# MIT Attendance — R8 / ProGuard rules
# ─────────────────────────────────────────────────────────────────────────────

# Keep BuildConfig so runtime config reads survive obfuscation.
-keep class com.mit.attendance.BuildConfig { *; }

# General: preserve annotations, signatures, inner classes, and enum methods.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# ── App entry points: Activities, Services, Receivers, Application (manifest-kept) ─
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ── WebView @JavascriptInterface methods (called from JS by name) ──────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Room ────────────────────────────────────────────────────────────────────────
# Entities are referenced by name from schema; DAOs are reflective; DB class is
# generated and instantiated by RoomDatabase.builder().
-keep class com.mit.attendance.model.** { *; }
-keep class com.mit.attendance.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep interface androidx.room.Dao { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * implements androidx.room.Dao { <methods>; }
-dontwarn androidx.room.paging.**

# ── Gson (Retrofit converter) ──────────────────────────────────────────────────
# Gson inspects fields by name; @SerializedName keeps key names but reflective
# field access still needs the fields themselves preserved.
-keepclassmembers,allowobfuscation class com.mit.attendance.model.** { <fields>; }
-keepclassmembers,allowobfuscation class com.mit.attendance.data.api.** { <fields>; }
-keep @com.google.gson.annotations.SerializedName class * { <fields>; }
-keepclassmembernames class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent Gson's Unsafe usage from breaking when classes disappear via R8.
-keep,allowobfuscation,allowshrinking class kotlin.Unit

# ── Retrofit ──────────────────────────────────────────────────────────────────
# Retrofit service interfaces are referenced reflectively. Keep them and every
# annotated method + its parameter types (so Retrofit can build the request).
-keep,allowobfuscation interface com.mit.attendance.data.api.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep @retrofit2.http.* class * { *; }
# Retrofit 2 built-ins.
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class okhttp3.** { *; }
-dontwarn okhttp3.internal.platform.**

# ── OkHttp / Okio (platform-specific classes are sometimes absent) ─────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.Platform { *; }
-keepnames class okhttp3.internal.platform.android.Android10Platform { *; }

# ── kotlinx.serialization ──────────────────────────────────────────────────────
# @Serializable companions and serializers are looked up reflectively.
-keep @kotlinx.serialization.Serializable class com.mit.attendance.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.mit.attendance.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.mit.attendance.model.GithubModels { *; }
-keep @kotlinx.serialization.Serializable class com.mit.attendance.model.TimetableModels { *; }
-keep @kotlinx.serialization.Serializable class com.mit.attendance.model.ChatModels { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** {
    *** Companion;
    *** INSTANCE;
}

# ── Coroutines ─────────────────────────────────────────────────────────────────
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── WorkManager workers (instantiated reflectively) ───────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.mit.attendance.service.** { *; }

# ── Lifecycle / ViewModel (instantiated via factory) ────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { <init>(); <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }

# ── Chaquopy (embedded Python runtime) — JNI bridge, never strip ───────────────
-keep class com.chaquo.** { *; }
-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.android.AndroidPlatform { *; }
-keepclasseswithmembers class com.mit.attendance.auth.GoogleAccountBackupManager { *; }
# Python bridge entry points in NotesActivity (called from native/Python land).
-keepclassmembers class com.mit.attendance.ui.subjects.NotesActivity {
    public *** python*;
    public *** runPython*;
    public *** onPython*;
}
-keep class com.chaquo.python.PyException { *; }

# ── Apache POI (heavy reflection on *Package, *Factory, styles) ────────────────
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.microsoft.schemas.**
-dontwarn schemaorg_apache_xmlbeans.**
-keep class org.apache.poi.** { *; }
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.xmlbeans.** { *; }
# POI looks up these system/factory classes by name via reflection.
-keep class org.apache.poi.ss.usermodel.WorkbookFactory
-keep class org.apache.poi.ss.usermodel.Sheet
-keep class org.apache.poi.ss.usermodel.Row
-keep class org.apache.poi.ss.usermodel.Cell
# StAX / XML factories discovered via ServiceLoader.
-keep class javax.xml.stream.** { *; }
-keep class com.fasterxml.aalto.** { *; }
-keep class stax.** { *; }
# POI's optional curve geometry code touches java.awt; unused on Android.
-dontwarn java.awt.**
# jsoup optionally uses com.google.re2j for regex; absent on Android (it falls back).
-dontwarn com.google.re2j.**

# ── Eclipse Paho MQTT ───────────────────────────────────────────────────────────
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# ── android-pdf-viewer (mhiew fork) ───────────────────────────────────────────
-keep class com.github.mhiew.** { *; }
-keep class com.github.barteksc.** { *; }
-dontwarn com.github.barteksc.**

# ── Markwon (markdown) ─────────────────────────────────────────────────────────
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── sora-editor (Rosemoe) ──────────────────────────────────────────────────────
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# ── Glide ───────────────────────────────────────────────────────────────────────
-keep public class com.bumptech.glide.** { *; }
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }
-dontwarn com.bumptech.glide.**

# ── DataStore / Preferences reflection ─────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── AndroidX security-crypto (uses Conscrypt/Tink reflective lookup) ────────────
-dontwarn androidx.security.crypto.**
-keep class androidx.security.crypto.** { *; }

# ── Log4j OSGi / bnd service-loader plumbing (unused on Android) ────────────────
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.spi.**

# ── Material / AndroidX: silence links to classes removed in newer versions ─────
-dontwarn com.google.android.material.**
-dontwarn androidx.recyclerview.**
-dontwarn androidx.swiperefreshlayout.**

# ── R8 optimizations ────────────────────────────────────────────────────────────
-allowaccessmodification
-overloadaggressively
# Don't preverify — Android handles that at install time.
-dontpreverify
# Keep source file names + line numbers for stack traces.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
# Campus transport and encrypted session use Gson field names across app updates.
-keep class com.mit.attendance.campus.CampusProfile { *; }
-keep class com.mit.attendance.campus.CampusSession { *; }
-keep class com.mit.attendance.campus.CampusCredentials { *; }
-keep class com.mit.attendance.campus.CampusLogin { *; }
-keep class com.mit.attendance.campus.CampusMe { *; }

-keep class com.mit.attendance.campus.CachedCampusAlert { *; }
