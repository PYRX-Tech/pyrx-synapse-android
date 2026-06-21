# consumer-rules.pro — shipped to consumers of the AAR.
# Keep public SDK surface so reflection-based consumers (DI frameworks,
# kotlinx.serialization codegen if added later) don't break under R8.

-keep public class tech.pyrx.synapse.** { public *; }
-keep public interface tech.pyrx.synapse.** { public *; }

# Coroutines — keep continuation classes so suspend-function stack traces stay
# legible in R8-minified consumer builds.
-keepnames class kotlinx.coroutines.** { *; }
