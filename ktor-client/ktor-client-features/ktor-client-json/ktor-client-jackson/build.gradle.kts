val jackson_version: String by project.extra
val jackson_kotlin_version: String by project.extra

kotlin.sourceSets {
    jvmMain {
        dependencies {
            api(project(":ktor-client:ktor-client-features:ktor-client-json"))

            api("com.fasterxml.jackson.core:jackson-databind:$jackson_version")
            api("com.fasterxml.jackson.module:jackson-module-kotlin:$jackson_kotlin_version")
        }
    }
    jvmTest {
        dependencies {
            api(project(":ktor-client:ktor-client-cio"))
            api(project(":ktor-client:ktor-client-features:ktor-client-json:ktor-client-json-tests"))
            api(project(":ktor-features:ktor-gson"))
        }
    }
}
