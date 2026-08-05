plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation(project(":core"))
    testImplementation(kotlin("test-junit"))
}
