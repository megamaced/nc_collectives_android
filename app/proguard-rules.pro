# Keep generic signatures for libraries that rely on reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Hilt generated components
-keep class dagger.hilt.** { *; }
