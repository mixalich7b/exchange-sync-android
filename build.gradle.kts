plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("verifyBootstrap") {
    group = "verification"
    description = "Compiles, unit tests, lints, and assembles the bootstrap debug application."

    dependsOn(
        ":core:compileKotlin",
        ":core:compileJava",
        ":core:compileTestKotlin",
        ":core:compileTestJava",
        ":app:compileDebugKotlin",
        ":app:compileDebugJavaWithJavac",
        ":app:compileDebugUnitTestKotlin",
        ":app:compileDebugUnitTestJavaWithJavac",
        ":feature:settings:compileDebugKotlin",
        ":feature:settings:compileDebugJavaWithJavac",
        ":feature:settings:compileDebugUnitTestKotlin",
        ":feature:settings:compileDebugUnitTestJavaWithJavac",
        ":infrastructure:compileDebugKotlin",
        ":infrastructure:compileDebugJavaWithJavac",
        ":infrastructure:compileDebugUnitTestKotlin",
        ":infrastructure:compileDebugUnitTestJavaWithJavac",
        ":core:test",
        ":app:testDebugUnitTest",
        ":feature:settings:testDebugUnitTest",
        ":infrastructure:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:settings:lintDebug",
        ":infrastructure:lintDebug",
        ":app:assembleDebug",
    )
}
