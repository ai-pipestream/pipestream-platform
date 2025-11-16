plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.2.2"
    id("maven-publish")
}

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile

group = "ai.pipestream"
version = "4.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    // Apache Snapshots repository for Tika 4.0 nightlies
    maven {
        name = "ApacheSnapshots"
        url = uri("https://repository.apache.org/snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    implementation(libs.tika.core)
    implementation(libs.tika.parsers.standard)
    // Extended parsers for better content extraction
    implementation(libs.tika.parser.scientific)
    // OCR support for images and scanned documents (requires tesseract)
    // Note: OCR module is already included in tika-parsers-standard-package
    // Adding it explicitly here ensures it's available even if not using the full standard package
    implementation(libs.tika.parser.ocr)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        
        // Merge service provider files (META-INF/services/*)
        mergeServiceFiles()
        
        // Handle duplicate entries by excluding them instead of failing
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        
        // Exclude signature files that commonly cause ZIP conflicts
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        
        // Allow large ZIP entries if needed
        isZip64 = true
        
        // Relocate packages to avoid conflicts
        relocate("org.apache.tika", "ai.pipestream.shaded.tika")
        
        // Preserve important manifest entries
        manifest {
            attributes(
                "Implementation-Title" to "Tika 4 Shaded",
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "ai.pipestream",
                "Built-By" to System.getProperty("user.name"),
                "Built-JDK" to System.getProperty("java.version"),
                "Created-By" to "Gradle ${gradle.gradleVersion}"
            )
        }
        
        // Minimize the JAR by removing unused classes (optional, can be enabled if needed)
        // minimize()
    }
    
    build {
        dependsOn(shadowJar)
    }
    
    // Ensure Java 21 compatibility
    withType<JavaCompile> {
        options.release.set(21)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.shadowJar)
            
            pom {
                name.set("Tika 4 Shaded")
                description.set("Shaded version of Apache Tika 4.0 snapshot with core, standard parsers, scientific parser, and OCR parser")
                url.set("https://github.com/ai-pipestream/tika4-shaded")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ai-pipestream/tika4-shaded")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
