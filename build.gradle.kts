plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "jp.vcmc"
version = "1.1.0"

val canvasApiVersion = "26.3.build.1001-stable"
val worldsVersion = "4.5.0"
val chunkyVersion = "1.5.66"

repositories {
    mavenCentral()
    maven("https://maven.canvasmc.io/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.thenextlvl.net/releases")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.canvasmc.canvas:canvas-api:$canvasApiVersion")
    compileOnly("net.thenextlvl:worlds:$worldsVersion")
    compileOnly("org.popcraft:chunky-common:$chunkyVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.canvasmc.canvas:canvas-api:$canvasApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showExceptions = true
        showCauses = true
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    // Kotlin 標準ライブラリを同梱する。Paper のプラグインローダーはプラグインごとに分離されるためリロケートは不要。
    exclude("META-INF/*.kotlin_module")
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
