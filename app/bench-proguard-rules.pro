# R8 rules for the release build under -PbenchPackage. The androidTest APK links against the
# app by class and member name, so the bench build keeps names unobfuscated and keeps the
# classes and members its tests read. Shrinking and optimization stay enabled for everything
# else, and the packaged baseline profile is unchanged.
-dontobfuscate

-keep class rkr.simplekeyboard.inputmethod.R$* { *; }
-keep class rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat { *; }
-keep class rkr.simplekeyboard.inputmethod.keyboard.Key { *; }
-keep class rkr.simplekeyboard.inputmethod.keyboard.Keyboard { *; }
-keep class rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher { *; }
-keep class rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView { *; }
-keep class rkr.simplekeyboard.inputmethod.latin.common.Constants { *; }
-keep class rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripView { *; }

# Kotlin runtime members the Java test sources call directly or through Kotlin classes.
-keep class kotlin.TuplesKt { *; }
-keep class kotlin.Pair { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.jvm.internal.StringCompanionObject { *; }
-keep class kotlin.jvm.internal.Ref$* { *; }
-keep class kotlin.text.Regex { *; }
-keep class kotlin.text.StringsKt { *; }
