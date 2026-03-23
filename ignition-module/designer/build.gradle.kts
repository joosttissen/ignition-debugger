plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val sdk_version: String by rootProject.extra

dependencies {
    implementation(project(":common"))

    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${sdk_version}")
    compileOnly("com.inductiveautomation.ignitionsdk:designer-api:${sdk_version}")
    compileOnly("com.inductiveautomation.ignition:client-bootstrap:${sdk_version}")
    compileOnly("org.slf4j:slf4j-api:1.7.36")

    // These are bundled in the .modl via :common's modlImplementation;
    // we only need them here at compile time.
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    compileOnly("org.java-websocket:Java-WebSocket:1.5.4")
}
