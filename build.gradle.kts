plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "org.example"
// jpackage requires a purely numeric version (1-3 dot-separated numbers).
version = "1.2.0"

val appName = "PDF Sign Tool"
val appVendor = "PDF Sign Tool"
val appDescription = "Signs PDF documents with a smart card on behalf of web applications"
// Never change this: Windows Installer uses it to recognise newer versions as upgrades of the same product.
val windowsUpgradeUuid = "c7fa1663-510b-44aa-b1dd-7d9a0a9199f6"

val itextVersion = "9.8.0"
val ktorVersion = "3.6.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.itextpdf:kernel:$itextVersion")
    implementation("com.itextpdf:forms:$itextVersion")
    implementation("com.itextpdf:sign:$itextVersion")
    implementation("com.itextpdf:bouncy-castle-adapter:$itextVersion")
    implementation("com.itextpdf:svg:$itextVersion") // the company logo in the signature appearance

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("com.formdev:flatlaf:3.7.2")

    implementation("org.slf4j:slf4j-simple:2.0.19")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "org.example.pdfsign.MainKt"
    // FlatLaf loads its native library (window decorations and file dialogs on Windows) with System.load.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to appName,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

// ---------------------------------------------------------------------------------------------------------------------
// Windows packaging with jpackage
//
//   gradlew jpackageImage      -> build/jpackage/image/PDF Sign Tool/   (self-contained app folder, no install needed)
//   gradlew jpackageInstaller  -> build/jpackage/installer/*.msi        (needs WiX v3, v4 or v5 on PATH)
//
// Options: -PinstallerType=exe   build a setup .exe instead of an .msi
//          -PperUserInstall=true install into %LOCALAPPDATA% without administrator rights
// ---------------------------------------------------------------------------------------------------------------------

// Modules of the bundled Java runtime (found with jdeps, plus the crypto providers loaded via ServiceLoader/reflection).
val runtimeModules = listOf(
    "java.base",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.naming",
    "java.sql",
    "java.xml",
    "java.xml.crypto",
    "jdk.accessibility",
    "jdk.charsets",
    "jdk.crypto.cryptoki", // SunPKCS11: smart cards through a PKCS#11 driver
    "jdk.crypto.mscapi",   // SunMSCAPI: Windows certificate store (smart cards with a minidriver)
    "jdk.unsupported",
    "jdk.zipfs",
)

val jpackageDir = layout.buildDirectory.dir("jpackage")
// jpackage links the bundled runtime from its own JDK. Use a plain HotSpot build (downloaded by Gradle if missing):
// a GraalVM JDK would add its 36 MB JIT compiler library to every installation.
val jpackageExecutable = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}.map { it.metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath }

val jpackageInput by tasks.registering(Sync::class) {
    description = "Collects the application jar and its runtime dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageDir.map { it.dir("input") })
}

val generateIcon by tasks.registering(JavaExec::class) {
    description = "Renders the application icon (.ico) used for the launcher and installer."
    val icon = jpackageDir.map { it.file("app.ico") }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.pdfsign.ui.IconExporterKt"
    jvmArgs("-Djava.awt.headless=true")
    argumentProviders.add(CommandLineArgumentProvider { listOf(icon.get().asFile.absolutePath) })
    outputs.file(icon)
}

// jpackage refuses to overwrite its output, and jlink marks some runtime files read-only: let Gradle delete them.
val cleanJpackageImage by tasks.registering(Delete::class) { delete(jpackageDir.map { it.dir("image") }) }
val cleanJpackageInstaller by tasks.registering(Delete::class) { delete(jpackageDir.map { it.dir("installer") }) }

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained Windows application image with jpackage."
    dependsOn(jpackageInput, generateIcon, cleanJpackageImage)
    onlyIf("jpackage packaging targets Windows") { System.getProperty("os.name").startsWith("Windows") }

    val input = jpackageDir.map { it.dir("input") }
    val dest = jpackageDir.map { it.dir("image") }
    val icon = jpackageDir.map { it.file("app.ico") }
    val mainJar = tasks.jar.flatMap { it.archiveFileName }
    val mainClass = application.mainClass
    val appVersion = version.toString()
    inputs.dir(input)
    inputs.file(icon)
    outputs.dir(dest)

    doFirst {
        executable = jpackageExecutable.get()
        args(
            "--type", "app-image",
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", appVendor,
            "--description", appDescription,
            "--input", input.get().asFile.absolutePath,
            "--main-jar", mainJar.get(),
            "--main-class", mainClass.get(),
            "--add-modules", runtimeModules.joinToString(","),
            "--jlink-options", "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6",
            "--icon", icon.get().asFile.absolutePath,
            "--java-options", "-Xms16m -Xmx768m -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED",
            "--dest", dest.get().asFile.absolutePath,
        )
    }
}

val jpackageInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a Windows installer (.msi, or .exe with -PinstallerType=exe) with jpackage and WiX."
    dependsOn(jpackageImage, cleanJpackageInstaller)
    onlyIf("jpackage packaging targets Windows") { System.getProperty("os.name").startsWith("Windows") }

    val installerType = providers.gradleProperty("installerType").getOrElse("msi")
    val perUser = providers.gradleProperty("perUserInstall").map { it.toBoolean() }.getOrElse(false)
    val image = jpackageDir.map { it.dir("image/$appName") }
    val icon = jpackageDir.map { it.file("app.ico") }
    val dest = jpackageDir.map { it.dir("installer") }
    val appVersion = version.toString()
    inputs.dir(image)
    outputs.dir(dest)

    // WiX v4/v5 installed with `dotnet tool install --global wix` lives here; it may not be on the PATH Gradle inherited.
    val dotnetTools = File(System.getProperty("user.home"), ".dotnet/tools")
    environment("PATH", listOf(dotnetTools.absolutePath, System.getenv("PATH")).joinToString(File.pathSeparator))

    doFirst {
        executable = jpackageExecutable.get()
        args(
            "--type", installerType,
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", appVendor,
            "--description", appDescription,
            "--app-image", image.get().asFile.absolutePath,
            "--icon", icon.get().asFile.absolutePath,
            "--win-upgrade-uuid", windowsUpgradeUuid,
            "--win-menu",
            "--win-menu-group", appName,
            "--win-shortcut",
            "--win-shortcut-prompt",
            "--win-dir-chooser",
            "--dest", dest.get().asFile.absolutePath,
        )
        if (perUser) args("--win-per-user-install")
    }
}
