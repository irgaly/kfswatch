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
            jvmToolchain(17)
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
    if (!path.startsWith(":sample") && !path.endsWith(":test")) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        group = "io.github.irgaly.kfswatch"
        afterEvaluate {
            // afterEvaluate for accessing version catalogs
            version = libs.versions.kfswatch.get()
        }
        val emptyJavadocJar = tasks.create<Jar>("emptyJavadocJar") {
            archiveClassifier = "javadoc"
            destinationDirectory = File(buildDir, "libs_emptyJavadoc")
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
                            name = artifactId
                            description = "Kotlin Multiplatform File System Watcher."
                            url = "https://github.com/irgaly/kfswatch"
                            developers {
                                developer {
                                    id = "irgaly"
                                    name = "irgaly"
                                    email = "irgaly@gmail.com"
                                }
                            }
                            licenses {
                                license {
                                    name = "The Apache License, Version 2.0"
                                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                                }
                            }
                            scm {
                                connection = "git@github.com:irgaly/kfswatch.git"
                                developerConnection = "git@github.com:irgaly/kfswatch.git"
                                url = "https://github.com/irgaly/kfswatch"
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
        tasks.withType<PublishToMavenRepository>().configureEach {
            mustRunAfter(tasks.withType<Sign>())
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId = libs.versions.kfswatch.get()
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl =
                uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
}
