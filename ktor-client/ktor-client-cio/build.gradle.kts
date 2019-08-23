description = "CIO backend for ktor http client"

val logback_version: String by project.extra

kotlin.sourceSets {
    commonMain {
        dependencies {
            api(project(":ktor-client:ktor-client-core"))
            api(project(":ktor-http:ktor-http-cio"))
            api(project(":ktor-network:ktor-network-tls"))
        }
    }
    commonTest {
        dependencies {
            api(project(":ktor-client:ktor-client-tests"))
        }
    }
    jvmTest {
        dependencies {
            api(project(":ktor-network:ktor-network-tls:ktor-network-tls-certificates"))
        }
    }
}
