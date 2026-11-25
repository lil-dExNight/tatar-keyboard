# R8 rules for the androidTest APK built against the -PbenchPackage release variant. The test
# APK carries only test code and is loaded by the platform runner, so shrinking it saves nothing
# and could drop classes the runner reaches by name.
-dontshrink
-dontobfuscate
