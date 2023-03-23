import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

plugins {
    kotlin("multiplatform") version libs.versions.kotlin apply false
    alias(libs.plugins.kotest.multiplatform) apply false
    alias(libs.plugins.buildlogic.multiplatform.library) apply false
    alias(libs.plugins.buildlogic.android.application) apply false
    alias(libs.plugins.buildlogic.android.library) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.nexus.publish)
}

subprojects {
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinProjectExtension> {
            jvmToolchain(11)
        }
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets {
                val commonTest by getting {
                    dependencies {
                        implementation(libs.bundles.test.common)
                    }
                }
            }
            afterEvaluate {
                sourceSets {
                    findByName("jvmTest")?.apply {
                        dependencies {
                            implementation(libs.test.kotest.runner)
                        }
                    }
                }
            }
        }
    }
    listOf(
        "com.android.application",
        "com.android.library"
    ).forEach {
        pluginManager.withPlugin(it) {
            extensions.configure<BaseExtension> {
                compileOptions {
                    // Android JVM toolchain workarounds
                    // https://issuetracker.google.com/issues/260059413
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }
        }
    }

    if (!path.startsWith(":sample") && !path.endsWith(":test")) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        group = "io.github.irgaly.kfswatch"
        afterEvaluate {
            // afterEvaluate for accessing version catalogs
            version = libs.versions.kfswatch.get()
        }
        val emptyJavadocJar = tasks.create<Jar>("emptyJavadocJar") {
            archiveClassifier.set("javadoc")
            destinationDirectory.set(File(buildDir, "libs_emptyJavadoc"))
        }
        extensions.configure<PublishingExtension> {
            afterEvaluate {
                afterEvaluate {
                    // KotlinMultiplatformPlugin は afterEvaluate により Android Publication を生成する
                    // 2 回目の afterEvaluate 以降で Android Publication にアクセスできる
                    publications.withType<MavenPublication>().all {
                        var javadocJar: Task? = emptyJavadocJar
                        var artifactSuffix = "-$name"
                        if (name == "kotlinMultiplatform") {
                            artifactSuffix = ""
                            javadocJar = tasks.findByName("javadocJar") ?: emptyJavadocJar
                        }
                        artifact(javadocJar)
                        artifactId = "${path.split(":").drop(1).joinToString("-")}$artifactSuffix"
                        pom {
                            name.set(artifactId)
                            description.set("Kotlin Multiplatform File System Watcher.")
                            url.set("https://github.com/irgaly/kfswatch")
                            developers {
                                developer {
                                    id.set("irgaly")
                                    name.set("irgaly")
                                    email.set("irgaly@gmail.com")
                                }
                            }
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                }
                            }
                            scm {
                                connection.set("git@github.com:irgaly/kfswatch.git")
                                developerConnection.set("git@github.com:irgaly/kfswatch.git")
                                url.set("https://github.com/irgaly/kfswatch")
                            }
                        }
                    }
                }
            }
        }
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(
                providers.environmentVariable("SIGNING_PGP_KEY").orNull,
                providers.environmentVariable("SIGNING_PGP_PASSWORD").orNull
            )
            if (providers.environmentVariable("CI").isPresent) {
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            // io.github.irgaly staging profile
            stagingProfileId.set("6c098027ed608f")
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}
