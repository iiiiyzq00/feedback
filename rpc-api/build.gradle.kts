plugins {
    `java-library`
    `maven-publish`
}

group = "com.feedback"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "feedback-platform-rpc-api"
            pom {
                name.set("Feedback Platform RPC API")
                description.set("Java 8 compatible Station feedback RPC contract")
            }
        }
    }
    repositories {
        val repositoryUrl = providers.gradleProperty("rpcApiRepositoryUrl")
            .orElse(providers.environmentVariable("RPC_API_REPOSITORY_URL"))
        if (repositoryUrl.isPresent) {
            maven {
                name = "internal"
                url = uri(repositoryUrl.get())
                credentials {
                    username = providers.environmentVariable("RPC_API_REPOSITORY_USERNAME").orNull
                    password = providers.environmentVariable("RPC_API_REPOSITORY_PASSWORD").orNull
                }
            }
        }
    }
}
