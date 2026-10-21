pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "feedback-platform"

include("backend")
include("rpc-api")
include("sdk")
include("demo")
include("health-demo")
include("child-art-demo")
