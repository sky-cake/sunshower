# Required for optimization.packageScope: allows R8 to widen access modifiers when
# repackaging classes, otherwise repackaged classes can extend package-private
# superclasses from another package and throw IllegalAccessError at runtime.
-allowaccessmodification
