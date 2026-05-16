# Keep generic signatures for libraries that rely on reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Hilt generated components
-keep class dagger.hilt.** { *; }

# Optional Markwon image-plugin transitive deps we don't pull in.
# We use ImagesPlugin with only the OkHttp scheme handler — no SVG, no GIF —
# so these classes are referenced by Markwon but never reachable at runtime.
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn pl.droidsonroids.gif.GifDrawable

# Tink references errorprone annotations at compile time only; they're not on
# the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
