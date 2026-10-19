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
val macBundleIdentifier = "org.example.pdfsign"
val linuxPackageName = "pdf-sign-tool"

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
// Packaging with jpackage, for the operating system the build runs on
//
//   gradlew jpackageImage      -> build/jpackage/image/   self-contained app that runs without installing:
//                                 "PDF Sign Tool" folder (Windows, Linux) or "PDF Sign Tool.app" (macOS)
//   gradlew jpackageInstaller  -> build/jpackage/installer/   Windows .msi (needs WiX v3, v4 or v5 on PATH),
//                                 macOS .dmg, Linux .deb (needs fakeroot)
//
// Options: -PinstallerType=exe   a setup .exe instead of the .msi; pkg on macOS; rpm on Linux (needs rpmbuild)
//          -PperUserInstall=true install into %LOCALAPPDATA% without administrator rights (Windows)
// ---------------------------------------------------------------------------------------------------------------------

val isWindows = System.getProperty("os.name").startsWith("Windows")
val isMac = System.getProperty("os.name").startsWith("Mac")

// Modules of the bundled Java runtime (found with jdeps, plus the crypto providers loaded via ServiceLoader/reflection).
val runtimeModules = listOfNotNull(
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
    "jdk.crypto.mscapi".takeIf { isWindows }, // SunMSCAPI: Windows certificate store (smart cards with a minidriver)
    "jdk.unsupported",
    "jdk.zipfs",
)

val jpackageDir = layout.buildDirectory.dir("jpackage")
// jpackage links the bundled runtime from its own JDK. Use a plain HotSpot build (downloaded by Gradle if missing):
// a GraalVM JDK would add its 36 MB JIT compiler library to every installation.
val jpackageExecutable = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}.map { it.metadata.installationPath.file(if (isWindows) "bin/jpackage.exe" else "bin/jpackage").asFile.absolutePath }

val jpackageInput by tasks.registering(Sync::class) {
    description = "Collects the application jar and its runtime dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageDir.map { it.dir("input") })
}

// The icon format jpackage takes on this operating system.
val appIcon = jpackageDir.map { it.file(if (isWindows) "app.ico" else if (isMac) "app.icns" else "app.png") }

val generateIcon by tasks.registering(JavaExec::class) {
    description = "Renders the application icon (.ico, .icns or .png) used for the launcher and installer."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.pdfsign.ui.IconExporterKt"
    jvmArgs("-Djava.awt.headless=true")
    argumentProviders.add(CommandLineArgumentProvider { listOf(appIcon.get().asFile.absolutePath) })
    outputs.file(appIcon)
}

// jpackage refuses to overwrite its output, and jlink marks some runtime files read-only: let Gradle delete them.
val cleanJpackageImage by tasks.registering(Delete::class) { delete(jpackageDir.map { it.dir("image") }) }
val cleanJpackageInstaller by tasks.registering(Delete::class) { delete(jpackageDir.map { it.dir("installer") }) }

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained application image with jpackage."
    dependsOn(jpackageInput, generateIcon, cleanJpackageImage)

    val input = jpackageDir.map { it.dir("input") }
    val dest = jpackageDir.map { it.dir("image") }
    val mainJar = tasks.jar.flatMap { it.archiveFileName }
    val mainClass = application.mainClass
    val appVersion = version.toString()
    inputs.dir(input)
    inputs.file(appIcon)
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
            "--icon", appIcon.get().asFile.absolutePath,
            "--java-options", "-Xms16m -Xmx768m -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED",
            "--dest", dest.get().asFile.absolutePath,
        )
        if (isMac) args("--mac-package-identifier", macBundleIdentifier)
    }
}

val jpackageInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds an installer with jpackage: .msi (or -PinstallerType=exe) on Windows, .dmg on macOS, .deb on Linux."
    dependsOn(jpackageImage, cleanJpackageInstaller)

    val installerType = providers.gradleProperty("installerType").getOrElse(if (isWindows) "msi" else if (isMac) "dmg" else "deb")
    val perUser = providers.gradleProperty("perUserInstall").map { it.toBoolean() }.getOrElse(false)
    val image = jpackageDir.map { it.dir(if (isMac) "image/$appName.app" else "image/$appName") }
    val dest = jpackageDir.map { it.dir("installer") }
    val appVersion = version.toString()
    inputs.dir(image)
    outputs.dir(dest)

    if (isWindows) {
        // WiX v4/v5 installed with `dotnet tool install --global wix` lives here; it may not be on the PATH Gradle inherited.
        val dotnetTools = File(System.getProperty("user.home"), ".dotnet/tools")
        environment("PATH", listOf(dotnetTools.absolutePath, System.getenv("PATH")).joinToString(File.pathSeparator))
    }

    doFirst {
        executable = jpackageExecutable.get()
        args(
            "--type", installerType,
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", appVendor,
            "--description", appDescription,
            "--app-image", image.get().asFile.absolutePath,
            "--icon", appIcon.get().asFile.absolutePath,
            "--dest", dest.get().asFile.absolutePath,
        )
        if (isWindows) {
            args(
                "--win-upgrade-uuid", windowsUpgradeUuid,
                "--win-menu",
                "--win-menu-group", appName,
                "--win-shortcut",
                "--win-shortcut-prompt",
                "--win-dir-chooser",
            )
            if (perUser) args("--win-per-user-install")
        } else if (!isMac) {
            // Installs into /opt/pdf-sign-tool, with an entry in the desktop's application menu.
            args("--linux-package-name", linuxPackageName, "--linux-shortcut", "--linux-menu-group", "Office")
        }
    }
}
