# Keep only the metadata Android framework callbacks and JSON parsing need.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Remove source file names and line tables from public beta/release builds.
-renamesourcefileattribute SourceFile
