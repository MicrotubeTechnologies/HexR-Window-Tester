// Versions are pinned rather than floating. A tester whose build changes under
// it is a tester you cannot trust the results of.
//
// AGP 8.13 is the floor for `compileSdk = 36`, which Play now requires. It in
// turn needs Gradle 8.13 or newer - see the pin in build-android.yml.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
