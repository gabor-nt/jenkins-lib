def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    final String SYSTEM_TEST_STAGE = "System test"

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def kubeConfig = params.KUBE_CONFIG
    def dockerRepo = params.DOCKER_URL

    def mergeRequestBuild = env.gitlabMergeRequestId != null
    echo "mergeRequestBuild: ${mergeRequestBuild}"

    def onlyMock = config.onlyMock ?: false

    def deployToDevAndProd = !(mergeRequestBuild || onlyMock)
    echo "deployToDevAndProd: ${deployToDevAndProd}"

    String project
    String buildVersion
    def scmVars
    def getVersion = mergeRequestBuild ? {this.getMRVersion(env,currentBuild)} : {this.getBJVersion(config)}


    timestamps {
        withSlackNotificatons() {
            gitlabBuilds(builds: ["Build", SYSTEM_TEST_STAGE]) {
                podTemplate(
                        name: 'sa-secret',
                        serviceAccount: 'digitaldealer-serviceaccount',
                        envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
                        volumes: [
                                secretVolume(secretName: 'jenkins-maven-settings', mountPath: '/home/jenkins/.m2'),
                                persistentVolumeClaim(claimName: 'jenkins-m2-cache', mountPath: '/home/jenkins/.mvnrepository'),
                                secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                                secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
                        ]) {
                    systemtestStage.hello()

                    mavenNode(maven: 'stakater/pipeline-tools:1.11.0') {
                        container(name: 'maven') {
                            // Ensure "jenkins" user is the owner of mounted Maven repository
                            // As the default owner in the volume would be "root" unless changed once
                            stage("Change Ownership") {
                                def mvnRepository = "/home/jenkins/.mvnrepository"
                                // Run chown only if the directory is not already owned by the jenkins user
                                sh """
                                    MVN_DIR_OWNER=\$(ls -ld ${mvnRepository} | awk '{print \$3}')
                                    if [ \${MVN_DIR_OWNER} != '10000' ];
                                    then
                                    chown 10000 -R /home/jenkins/.mvnrepository
                                    fi
                                """
                            }
                        }
                    }

                    gitlabCommitStatus(name: "Build") {
                        mavenNode(
                                mavenImage: 'stakater/maven-jenkins:3.5.4-0.6',
                                javaOptions: '-Duser.home=/home/jenkins -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -Dsun.zip.disableMemoryMapping=true -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90',
                                mavenOpts: '-Duser.home=/home/jenkins -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn') {

                            container(name: 'maven') {

                                stage("checkout") {
                                    scmVars = checkout scm
                                    def pom = readMavenPom file: 'pom.xml'
                                    project = pom.artifactId

                                    buildVersion = getVersion()
                                    // TODO read this from pom

                                    currentBuild.displayName = "${buildVersion}"
                                }

                                stage('build') {
                                    sh "git checkout -b ${env.JOB_NAME}-${buildVersion}"
                                    sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${buildVersion}"
                                    sh "mvn deploy"
                                }

                                stage('push docker image') {
                                    sh "mvn fabric8:push -Ddocker.push.registry=${dockerRepo}"
                                }

                            }
                        }
                    }

                    gitlabCommitStatus(name: SYSTEM_TEST_STAGE) {
                        systemtestStage([microservice: [name: project, version: buildVersion]], mergeRequestBuild)
                    }

                    // tag or rollback
                    tag(buildVersion)

//                    if (deployToDevAndProd) {
//                        stage("Deploy to dev") {
//                            build job: "${project}-dev-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
//                        }
//
//                        stage('Deploy to prod') {
//                            build job: "${project}-prod-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
//                        }
//                    }
                }
            }
        }
    }
}

private void tag(String buildVersion) {
    if (!mergeRequestBuild) {
        withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
            def gitUrl = scmVars.GIT_URL.substring(8)
            sh """
                git config user.name "${scmVars.GIT_AUTHOR_NAME}"
                git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                git tag -am "By ${currentBuild.projectName}" v${buildVersion}
                #git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${gitUrl} v${buildVersion}
            """
        }
    }
}

String getBJVersion(config) {
    def versionPrefix = config.VERSION_PREFIX ?: "1.4"
    int version_last = sh(
            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
            returnStdout: true
    )
    return "${versionPrefix}.${version_last + 1}"
}

String getMRVersion(env, currentBuild) {
    def branchName = env.gitlabSourceBranch
    def buildNumber = currentBuild.number
    return "${branchName}-${buildNumber}"
}
