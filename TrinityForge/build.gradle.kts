plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.trinityforge"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // Vault's economy API (net.milkbowl.vault.economy.Economy) is only published on JitPack, not
    // Maven Central. mavenLocal() lets --offline builds resolve it from an already-cached ~/.m2 copy
    // without needing network access to JitPack.
    mavenLocal()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Vault soft-dependency (T1, economy bridge): compile-time only, never bundled/shaded. Runtime
    // presence is optional — EconomyBridge stays fully inert when Vault (and thus this class) is
    // absent from the server's classpath. See paper-plugin.yml softdepend.
    // VaultAPI transitively pulls an old org.bukkit:bukkit that conflicts with paper-api's own
    // bukkit capability — excluded because we only need the net.milkbowl.vault.economy.Economy
    // interface, which has no runtime dependency on that transitive artifact.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // EconomyBridgeTest exercises the real net.milkbowl.vault.economy.Economy interface via Mockito
    // (mocking an interface Mockito hasn't loaded requires the class on the test runtime classpath).
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // SQLite JDBC — Apache-2.0. Bundled in the shadow JAR (TrinityForge-*-all.jar) because
    // Paper server does not provide a SQLite driver on its classpath.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Paper API on the test classpath so tests can exercise YamlConfiguration / Bukkit types.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // JUnit 6.0.x: MockBukkit 4.110.0 (the release line whose bundled registry data matches paper-api
    // 1.21.11) pulls in junit-jupiter 6.0.x, so the whole suite runs on the aligned JUnit 6 platform.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")

    // MockBukkit for the projectile-PDC round-trip and combat listener/service integration coverage.
    // 4.110.0 targets paper-api 1.21.11 exactly (matched registry data avoids InternalDataLoadException).
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")

    // Mockito so ItemFactoryTest can isolate ItemFactory from the (final) ItemAssembler it delegates
    // to: ItemFactory's own contract (stamp catalog id before delegating) is unit-testable without
    // exercising ItemAssembler.assemble's real config/AttributeApplier chain, which additionally hits
    // an unimplemented MockBukkit 4.110.0 Material method unrelated to what ItemFactoryTest verifies.
    testImplementation("org.mockito:mockito-core:5.14.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    includeEmptyDirs = false
    exclude("**/*.bak*")
    exclude("**/*.stale-*")
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
    // 再発防止(T3): MockBukkitのUnimplementedOperationException(TestAbortedExceptionのサブクラス)は
    // JUnitにSKIPPEDとして扱われ、FAILEDにならない。アサーションが1つも走らずに「緑」に見える事故
    // (PotionQualityListenerTestで実例あり)を防ぐため、UnintendedSkipGuardListener
    // (src/test/java/com/trinityforge/testsupport)がそうした「気づかれていないスキップ」を
    // build/test-results/unintended-skips.txt に記録する。ここで存在チェックしてビルドを失敗させる。
    // @Disabled や Assumptions.assumeTrue による意図的なスキップはリスナーの対象外なので落ちない。
    doLast {
        val marker = layout.buildDirectory.file("test-results/unintended-skips.txt").get().asFile
        if (marker.exists() && marker.readText().isNotBlank()) {
            throw GradleException(
                "Unintended test skip(s) detected (MockBukkit UnimplementedOperationException " +
                    "silently aborted a test instead of failing it — see " + marker + "):\n" +
                    marker.readText()
            )
        }
    }
}

tasks.shadowJar {
    // Produce TrinityForge-<version>-all.jar — the deployable fat JAR that includes sqlite-jdbc.
    archiveClassifier.set("all")
}

// Thin JAR is build-internal only; never treat it as the server deployable.
tasks.jar {
    archiveClassifier.set("thin")
}

tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Public compile API surface for ArsPaper / EliteMobs forks"
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("com/trinityforge/integration/**")
        include("com/trinityforge/api/**")
        include("com/trinityforge/pdc/**")
        include("com/trinityforge/combat/AttackStats.class")
        include("com/trinityforge/combat/AttackStats\$*.class")
    }
}

tasks.register("releaseAssembly") {
    group = "distribution"
    description = "Stage shadow JAR + API JAR for forks; excludes thin classifier"
    dependsOn(tasks.shadowJar, tasks.named("apiJar"), tasks.jar)
    doLast {
        val dist = layout.buildDirectory.dir("release").get().asFile
        dist.mkdirs()
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(dist)
            rename { "TrinityForge-all.jar" }
        }
        copy {
            from(tasks.named<Jar>("apiJar").get().archiveFile)
            into(dist)
            rename { "TrinityForge-api.jar" }
        }
        // Sync the thin (all-classes, dependency-free) jar into fork libs/ so compileOnly stays
        // ABI-aligned. Forks are first-party and reach deep into TF internals (combat/config/stats/
        // progression/mobs/hate + TrinityForge main), a surface far wider than apiJar's curated
        // public subset — apiJar is insufficient for compilation. Thin jar has all classes and no
        // deps, so compileOnly(files(...)) never shades anything; TF supplies the classes at runtime.
        val forkApiTargets = listOf(
            rootProject.projectDir.resolve("../fork-handoff/arspaper/fork/libs"),
            rootProject.projectDir.resolve("../fork-handoff/elitemobs/elitemobs-fork/libs"),
            rootProject.projectDir.resolve("../fork-handoff/dpschecker/fork/libs")
            // 2026-07-25: ../external/ArsPaper/libs を削除。現行の ArsPaper フォークは
            // fork-handoff/arspaper/fork であり、external/ 側は 7/22 の native 移行以降
            // 誰もビルドしない stale な複製だった(コピー先として残っていたためリポジトリ整理で
            // external/ ごと削除)。ここに再度パスを足すと空ディレクトリが復活する。
        )
        for (target in forkApiTargets) {
            target.mkdirs()
            copy {
                from(tasks.jar.get().archiveFile)
                into(target)
                rename { "TrinityForge.jar" }
            }
        }
        // Guard: thin jar must not be copied into release/
        val thin = tasks.jar.get().archiveFile.get().asFile
        if (File(dist, thin.name).exists()) {
            throw GradleException("thin JAR leaked into release/: " + thin.name)
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
