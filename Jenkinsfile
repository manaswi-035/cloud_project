pipeline {
    agent any

    tools {
        jdk 'JDK17'
        maven 'Maven3'
    }

    stages {
        stage('Build and verify') {
            steps {
                sh 'mvn -B clean verify'
            }
        }
    }
}
