kotlin.sourceSets {
    val commonMain by getting {
        dependencies {
            api(project(":ktor-network"))
            api(project(":ktor-http:ktor-http-cio"))
        }
    }
    val jvmTest by getting {
        dependencies {
            api(project(":ktor-network:ktor-network-tls:ktor-network-tls-certificates"))
        }
    }
}
