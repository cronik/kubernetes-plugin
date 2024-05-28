/**
 * This pipeline describes a ephemerial container job, running Maven and Golang builds
 */

podTemplate {
    node(POD_LABEL) {
        stage('Build a Maven project') {
            git 'https://github.com/jenkinsci/kubernetes-plugin.git'
            ephemeralContainer(image: 'maven') {
                sh 'mvn -B -ntp clean package -DskipTests'
            }
        }
        stage('Build a Golang project') {
            git url: 'https://github.com/hashicorp/terraform.git', branch: 'main'
            ephemeralContainer(image: 'golang') {
                sh '''
          mkdir -p /go/src/github.com/hashicorp
          ln -s `pwd` /go/src/github.com/hashicorp/terraform
          cd /go/src/github.com/hashicorp/terraform && make
        '''
            }
        }
    }
}
