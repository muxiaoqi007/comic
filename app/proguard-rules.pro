# Keep source config serialization metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.example.comic.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.comic.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
