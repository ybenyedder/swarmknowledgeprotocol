// Toolchain pinned to the one already used by ../sby/LLMProvider and
// ../sby/harnessDroid (AGP 8.5.1 / Kotlin 1.9.0 / Gradle 8.7) so the modules
// can be dropped into either project without a version bump.
plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.0" apply false
}
