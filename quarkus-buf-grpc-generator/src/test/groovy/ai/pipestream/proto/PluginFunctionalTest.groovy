package ai.pipestream.proto

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class PluginFunctionalTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "rootProject.name = 'test-project'"
    }

    def "plugin can be applied without errors"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('tasks', '--group=protobuf')
            .build()

        then:
        result.output.contains('fetchProtos')
        result.output.contains('prepareGenerators')
        result.output.contains('generateProtos')
        result.output.contains('buildDescriptors')
        result.output.contains('cleanProtos')
    }

    def "can configure modules via DSL"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            pipestreamProtos {
                modules {
                    register("test-module") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir, 'build/protos/export/test-module').exists()
    }

    def "can configure multiple modules"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            pipestreamProtos {
                modules {
                    register("module1") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                    register("module2") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        new File(testProjectDir, 'build/protos/export/module1').exists()
        new File(testProjectDir, 'build/protos/export/module2').exists()
    }

    def "prepareGenerators creates buf.gen.yaml with absolute paths"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        bufGenYaml.exists()

        def content = bufGenYaml.text
        content.contains('buf.build/protocolbuffers/java')
        content.contains('buf.build/grpc/java')
        content.contains('protoc-gen-mutiny')
        // Verify absolute paths
        content.contains(testProjectDir.absolutePath)
    }

    def "prepareGenerators creates mutiny wrapper script"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def mutinyScript = new File(testProjectDir, 'build/tmp/protoc-plugins/protoc-gen-mutiny')
        mutinyScript.exists()
        mutinyScript.canExecute()
        mutinyScript.text.contains('MutinyGrpcGenerator')
    }

    def "cleanProtos removes all generated artifacts"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        // First generate some artifacts
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators')
            .build()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('cleanProtos')
            .build()

        then:
        result.task(":cleanProtos").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir, 'build/protos/export').exists()
        !new File(testProjectDir, 'build/buf.gen.yaml').exists()
        !new File(testProjectDir, 'build/tmp/protoc-plugins').exists()
    }

    def "can disable gRPC generation"() {
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
                generateGrpc = false
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        content.contains('buf.build/protocolbuffers/java')
        !content.contains('buf.build/grpc/java')
    }

    def "can disable Mutiny generation"() {
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
                generateMutiny = false
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        !content.contains('protoc-gen-mutiny')
        !content.contains('mutiny')
    }

    def "can configure custom Quarkus version"() {
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
                quarkusGrpcVersion = '3.17.0'
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.output.contains('quarkus-grpc-protoc-plugin:3.17.0')
    }

    def "can configure extra plugins"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
                extraPlugins {
                    register("doc") {
                        plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
                        out = "docs"
                        opt = ["markdown,docs.md"]
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        content.contains('buf.build/community/pseudomuto-protoc-gen-doc')
        content.contains('markdown,docs.md')
    }

    def "skips fetchProtos when no modules configured"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.output.contains('No modules configured')
    }
}
