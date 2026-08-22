# Keep generic signatures for libraries that rely on reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Hilt generated components
# Hilt/Dagger generated component graph.
#
# Deliberately kept, though Hilt ships its own consumer rules and nothing in
# this app reflects over `dagger.hilt`. Dropping it saves ~50 KB but changes
# what R8 full mode strips: `…SingletonC$ActivityRetainedCBuilder` goes from
# member-pruned to fully removed, and `ActivityCImpl`/`ActivityRetainedCImpl`
# lose members. Those are on the live injection path (MainActivity is
# @AndroidEntryPoint, the ViewModels are @HiltViewModel), and a wrong guess
# there is a release-only crash at Activity creation, not a build failure.
# Not worth 50 KB without an on-device check.
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
