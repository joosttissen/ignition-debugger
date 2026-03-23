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
    implementation(project(":common"))

    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${sdk_version}")
    compileOnly("com.inductiveautomation.ignitionsdk:designer-api:${sdk_version}")
    compileOnly("org.python:jython-standalone:2.7.3")
    compileOnly("org.slf4j:slf4j-api:1.7.36")

    modlImplementation("org.java-websocket:Java-WebSocket:1.5.4")
    modlImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
}
