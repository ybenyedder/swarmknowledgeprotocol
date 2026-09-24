// OSP node for Linux (and any JVM ≥ 17) — CLI + LAN HTTP bridge + web console
// packed on top of the pure-JVM osp-lite library. Zero third-party runtime
// dependencies beyond kotlin-stdlib (the Linux counterpart of REQ-NF-01).
//
//   ./gradlew :ospnode:fatJar && java -jar ../linux/ospnode/build/libs/ospnode-all.jar --help
//
// The HTTP surface mirrors the Android app's HttpBridge route for route, so an
// ospbridge tablet and this node peer over the same wire (see README.md).
plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":osp-lite"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.swarmknowledge.ospnode.MainKt")
}

/** Self-contained JAR — the one artifact a Linux box needs (java -jar). */
val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("ospnode")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build { dependsOn(fatJar) }
