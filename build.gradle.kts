plugins {
    java
}

group = "id.nusaspigot.sawit"
version = "1.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.momirealms.net/releases/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.nexomc:nexo:1.30.2")
    compileOnly("com.github.LoneDev6:API-ItemsAdder:3.6.3-beta-14")
    compileOnly("net.momirealms:craft-engine-core:0.0.67.11")
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67.11")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // SQLite JDBC is already bundled in Paper 1.21.11 runtime, keep compileOnly + testImplementation
    compileOnly("org.xerial:sqlite-jdbc:3.47.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
    from("nexo-pack") {
        into("nexo-pack")
    }
    from("itemsadder-pack") {
        into("itemsadder-pack")
    }
    from("craftengine-pack") {
        into("craftengine-pack")
    }
}

tasks.test {
    useJUnitPlatform()
}
