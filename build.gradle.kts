// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// Compiles server-exe/KrankmiliServer.cs with the csc.exe that ships with Windows.
tasks.register<Exec>("buildServerExe") {
    workingDir = file("server-exe")
    commandLine("cmd", "/c", "call", file("server-exe/build.bat").absolutePath)
    isIgnoreExitValue = true
}

// Any app build also (re)compiles the server exe.
project(":app").tasks.configureEach {
    if (name == "assembleDebug" || name == "preBuild") dependsOn(rootProject.tasks.named("buildServerExe"))
}
