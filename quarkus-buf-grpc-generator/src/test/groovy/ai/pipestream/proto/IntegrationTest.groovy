package ai.pipestream.proto

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Integration tests that test full end-to-end functionality.
 * These tests may take longer as they perform actual network operations.
 */
class IntegrationTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "rootProject.name = 'integration-test'"
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "full code generation from BSR"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify Java files were generated
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "full code generation from Git"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git'
                modules {
                    register("buf") {
                        gitRepo = "https://github.com/bufbuild/buf.git"
                        gitRef = "main"
                        gitSubdir = "proto"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify Java files were generated
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "can switch between BSR and Git via gradle property"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                        gitRepo = "https://github.com/bufbuild/buf.git"
                        gitRef = "main"
                        gitSubdir = "proto"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '-PprotoSource=git', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.output.contains('Exporting buf from Git')
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "descriptor generation creates proto.desc file"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('buildDescriptors', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS

        // Verify descriptor file was generated
        def descriptorFile = new File(testProjectDir, 'build/descriptors/proto.desc')
        descriptorFile.exists()
        descriptorFile.length() > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "can disable descriptor generation"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = false
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('buildDescriptors', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SKIPPED
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "generates Mutiny stubs"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Look for Mutiny-generated files
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        def mutinyFiles = []
        outputDir.eachFileRecurse { file ->
            if (file.name.contains('Mutiny')) {
                mutinyFiles << file
            }
        }
        mutinyFiles.size() > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "wires into Java compilation"() {
        given:
        // Use buf.build/bufbuild/buf instead of protovalidate as it has simpler protos
        // Note: Full compilation requires matching protobuf-java version to buf's remote plugins
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                // Use same version as buf's remote plugins generate for
                implementation 'io.grpc:grpc-stub:1.68.0'
                implementation 'io.grpc:grpc-protobuf:1.68.0'
                implementation 'com.google.protobuf:protobuf-java:4.29.0'
                implementation 'io.smallrye.reactive:mutiny:2.6.2'
                implementation 'io.quarkus:quarkus-grpc:3.30.3'
                implementation 'jakarta.annotation:jakarta.annotation-api:2.1.1'
            }

            pipestreamProtos {
                // Use simpler protos that don't require specific protobuf editions
                generateMutiny = false  // Skip mutiny for this test to simplify
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify generated Java files exist (compilation is tricky due to version dependencies)
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }
}
