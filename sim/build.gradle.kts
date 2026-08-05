plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api et non implementation : PlanetData expose publiquement Seed et Vec3,
    // qui appartiennent à :core. Les consommateurs de :sim doivent donc voir
    // ces types de manière transitive.
    api(project(":core"))
    testImplementation(kotlin("test-junit"))
}
