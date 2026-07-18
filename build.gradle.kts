plugins {
    `java-library`
    `maven-publish`   // Phase 9 discipline from birth: locally installable
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    api("io.github.richeyworks:smokehouse:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "pitboss"
            from(components["java"])
            pom {
                name = "PitBoss"
                description = "The replica-fleet conductor: lag watch, gap re-bootstrap, and the promotion runbook over SmokeHouse replication."
                url = "https://github.com/RicheyWorks/PitBoss"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/PitBoss"
                    connection = "scm:git:https://github.com/RicheyWorks/PitBoss.git"
                }
            }
        }
    }
}
