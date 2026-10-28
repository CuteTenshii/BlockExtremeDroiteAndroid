# kotlinx.serialization and AndroidX ship their own consumer rules; the app
# classes referenced from the manifest are kept by AAPT-generated rules.

# Keep line numbers so release crash traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
