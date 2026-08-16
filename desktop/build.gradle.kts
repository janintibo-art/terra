plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.terra.desktop.MainKt")
}

// LWJGL 3.3.6 plutôt que la 3.4.x : la branche 3.4 vise le JDK 25 et son
// API FFM, alors que tout le projet est en JDK 17. Monter le JDK du seul
// module :desktop créerait deux toolchains à maintenir pour un gain nul —
// on n'utilise ici que GLFW et OpenGL, stables depuis des années.
val lwjglVersion = "3.3.6"

// Les binaires natifs dépendent de la plateforme. On embarque Windows ET
// Linux : quelques mégaoctets, sans importance ici (l'utilisateur a
// explicitement écarté la contrainte de taille), et cela permet de lancer
// le même jar sur le runner Linux comme sur une machine Windows.
val lwjglNatives = listOf("natives-windows", "natives-linux")

dependencies {
    implementation(project(":core"))
    implementation(project(":sim"))

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    for (native in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$native")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$native")
        runtimeOnly("org.lwjgl:lwjgl-opengl::$native")
    }

    testImplementation(kotlin("test-junit"))
}

/**
 * Rassemble le jar et toutes ses dépendances dans un dossier, prêt pour
 * jpackage. Une tâche Copy plutôt qu'un « fat jar » : jpackage préfère un
 * chemin de classes explicite, et un jar unique compliquerait le
 * chargement des binaires natifs de LWJGL.
 */
// Nom de jar figé : jpackage reçoit « --main-jar desktop.jar » et une
// version dans le nom de fichier ferait échouer le packaging sans que le
// message ne dise pourquoi.
tasks.jar {
    archiveFileName.set("desktop.jar")
}

val collectRuntime by tasks.registering(Copy::class) {
    dependsOn(tasks.named("jar"))
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("runtime-libs"))
}

/**
 * Produit Terra.exe et son runtime Java embarqué.
 *
 * Type `app-image` et non `exe` : le type `exe` exige WiX Toolset pour
 * fabriquer un installateur, une dépendance externe de plus sur le runner
 * et une source de panne. `app-image` donne un dossier contenant
 * directement Terra.exe, que la CI archive en zip — l'utilisateur
 * décompresse et lance, sans installation.
 *
 * Ne s'exécute que sur Windows ; ailleurs la tâche est ignorée plutôt que
 * de faire échouer le build.
 */
val packageExe by tasks.registering(Exec::class) {
    dependsOn(collectRuntime)
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile
    val libsDir = layout.buildDirectory.dir("runtime-libs").get().asFile
    val version = "1.0.0"

    onlyIf {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        if (!windows) logger.lifecycle("packageExe ignoré : Windows requis")
        windows
    }

    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    val javaHome = System.getProperty("java.home")
    commandLine(
        "$javaHome/bin/jpackage",
        "--type", "app-image",
        "--name", "Terra",
        "--app-version", version,
        "--input", libsDir.absolutePath,
        "--main-jar", "desktop.jar",
        "--main-class", "com.terra.desktop.MainKt",
        "--dest", outDir.absolutePath,
        // Sans cette option, la fenêtre console reste ouverte derrière le
        // jeu. On la garde pour l'instant : les journaux de démarrage sont
        // le seul diagnostic disponible tant qu'il n'y a pas de HUD.
        "--java-options", "-Xmx2g"
    )
}
