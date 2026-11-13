# Proguard rules for Agentic Workspace
# Core model & serialization rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep data models
-keep class dev.agentic.core.model.** { *; }
