package org.hypen.GRpcServ;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;


@Mojo(name = "protobuf-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ProtobufGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

    @Override
    public void execute() {
        try {
            String osClassifier = (String) session.getSystemProperties().get("os.detected.classifier");
            getLog().info("OS Classifier:... " + osClassifier);

            executeMojo(
                    plugin(
                            groupId("org.xolstice.maven.plugins"),
                            artifactId("protobuf-maven-plugin"),
                            version("0.6.1")
                    ),
                    goal("compile"),
                    configuration(
                            element(name("protocExecutable"), ""),
                            element(name("protocArtifact"), "com.google.protobuf:protoc:3.21.7:exe:" + osClassifier),
                            element(name("protoSourceRoot"), project.getBasedir() + "/target/generated-sources/proto")
                    ),
                    executionEnvironment(project, session, pluginManager)
            );

            executeMojo(
                    plugin(
                            groupId("org.xolstice.maven.plugins"),
                            artifactId("protobuf-maven-plugin"),
                            version("0.6.1")
                    ),
                    goal("compile-custom"),
                    configuration(
                            element(name("protocExecutable"), ""),
                            element(name("pluginId"), "grpc-java"),
                            element(name("protocArtifact"), "com.google.protobuf:protoc:3.21.7:exe:" + osClassifier),
                            element(name("pluginArtifact"), "io.grpc:protoc-gen-grpc-java:1.55.1:exe:" + osClassifier),
                            element(name("protoSourceRoot"), project.getBasedir() + "/target/generated-sources/proto")
                    ),
                    executionEnvironment(project, session, pluginManager)
            );
            getLog().info("Protobuf plugin executed successfully");
        } catch (Exception e) {
            getLog().error("Error executing GRpc plugin", e);
        }
    }
}
