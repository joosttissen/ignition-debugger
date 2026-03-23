plugins {
    id("io.ia.sdk.modl") version("0.5.0")
}

val sdk_version by extra("8.3.0")

allprojects {
    version = "0.1.0-SNAPSHOT"
    group = "dev.ignition.debugger"
}

ignitionModule {
    name.set("Ignition Debugger")
    fileName.set("ignition-debugger")
    id.set("dev.ignition.debugger")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Enables step-level Jython debugging from VS Code using the Debug Adapter Protocol.")
    requiredIgnitionVersion.set(sdk_version)
    requiredFrameworkVersion.set("8")

    projectScopes.putAll(mapOf(
        ":common" to "D",
        ":designer" to "D"
    ))

    hooks.putAll(mapOf(
        "dev.ignition.debugger.designer.DesignerHook" to "D"
    ))

    freeModule.set(true)

    /*
     * Set to true to skip signing during development.
     * For production releases, set to false and provide signing credentials.
     */
    skipModlSigning.set(true)
}
