plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // compileOnly: the app's Android Gradle plugin is used at runtime (version from libs.versions.toml)
    compileOnly("com.android.tools.build:gradle-api:9.4.1")
}

gradlePlugin {
    plugins {
        register("noFakeVersionWarning") {
            id = "peppa.no-fake-version-warning"
            implementationClass = "NoFakeVersionWarningPlugin"
        }
    }
}
