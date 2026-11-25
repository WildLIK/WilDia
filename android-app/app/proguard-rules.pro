# Keep readable stack traces for DiagJournal and offline bug reports
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.obd2.app.**$$serializer { *; }
-keepclassmembers class com.obd2.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.obd2.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room & SQLite entities/DAOs
-keep class * extends androidx.room.RoomDatabase
-keep class com.obd2.app.core.db.** { *; }

# usb-serial-for-android
-keep class com.hoho.android.usbserial.driver.** { *; }

# Reflection helper (PhoneInfo -> android.os.SystemProperties)
-dontwarn android.os.SystemProperties
