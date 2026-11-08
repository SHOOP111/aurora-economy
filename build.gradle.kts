import java.util.zip.ZipFile

plugins {
    java
    jacoco
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.aurora"
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0")
require(version.toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))) {
    "releaseVersion must be a safe semantic version, e.g. 1.0.0 or 1.0.1-rc.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.54.1.0") { exclude(group = "org.slf4j", module = "slf4j-api") }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging { events("passed", "skipped", "failed") }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest { attributes["Multi-Release"] = "true" }
    // SQLite contains native resources. Do not relocate or minimize its driver.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyPluginJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    doLast {
        ZipFile(tasks.shadowJar.get().archiveFile.get().asFile).use { zip ->
            val required = listOf("plugin.yml", "config.yml", "dev/aurora/economy/paper/AuroraEconomyPlugin.class",
                "org/sqlite/JDBC.class", "META-INF/services/java.sql.Driver",
                "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so", "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll")
            required.forEach { check(zip.getEntry(it) != null) { "Packaged plugin is missing $it" } }
            check(zip.getEntry("org/bukkit/Bukkit.class") == null) { "Paper API must not be bundled" }
            check(zip.getEntry("net/milkbowl/vault/economy/Economy.class") == null) { "Vault API must not be bundled" }
            val descriptor = zip.getInputStream(zip.getEntry("plugin.yml")).bufferedReader().use { it.readText() }
            check(descriptor.contains("version: '${project.version}'")) { "Plugin version was not expanded" }
        }
    }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching {
        include("dev/aurora/economy/core/**", "dev/aurora/economy/storage/**")
    })
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.65".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn(verifyPluginJar, tasks.jacocoTestCoverageVerification) }
tasks.jar { archiveClassifier.set("thin") }
tasks.build { dependsOn(tasks.shadowJar) }
