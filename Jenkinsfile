pipeline {
    agent none
    stages {
        stage('Build') {
            agent { docker 'openjdk:8-jdk' }
            steps {
                sh 'SCOPE_LOG_ROOT_PATH=/opt/bitnami/apps/jenkins/jenkins_home/workspace/scope-demo_ktor_master/logs ./gradlew cleanTest jvmTest --rerun-tasks'
            }
        }
    }

    post {
        always {
            node('master') {
                 archiveArtifacts artifacts: '/opt/bitnami/apps/jenkins/jenkins_home/workspace/scope-demo_ktor_master/logs/scope-*.log'
                 sh 'rm -f scope_*.log'
            }
        }
    }
}
