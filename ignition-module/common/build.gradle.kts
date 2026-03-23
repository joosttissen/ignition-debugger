plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

val sdk_version: String by rootProject.extra

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${sdk_version}")
    compileOnly("org.slf4j:slf4j-api:1.7.36")

    modlImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
}
