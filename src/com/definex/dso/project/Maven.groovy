#!/usr/bin/env groovy
package com.definex.dso.project

import groovy.transform.Field

private @Field semanticVersion
private @Field imageVersion
private @Field repositoryHelper = new com.definex.dso.scm.RepoHelper()


def setVersion(params, version) {
    this.semanticVersion = version
    this.imageVersion = generateImageVersion(version, currentBuild.number, env.OPENSHIFT_ENV)

    configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
        shell """mvn -s \"$MAVEN_SETTINGS\" -B -f ${params.appName}-bom/pom.xml versions:set-property -Dproperty=revision -DnewVersion=${version.label}"""
    }
}

def build(params) {
    final def buildTemplate = libraryResource "build-template.yml"
    final def baseImage = "redhat-openjdk18-openshift:1.4"
    writeFile file: 'buildTemplate.yml', text: buildTemplate
    def version

    configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
        shell "mvn -s \"$MAVEN_SETTINGS\" -B -U -Djacoco.destFile=./coverage/jacoco.exec " +
                "org.jacoco:jacoco-maven-plugin:prepare-agent org.jacoco:jacoco-maven-plugin:report " +
                "clean package"
        version = sh(returnStdout: true, script: 'printf \'VER\t${project.version}\' | mvn -s \"$MAVEN_SETTINGS\"  help:evaluate | grep \'^VER\' | cut -f2').trim()
    }

    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/**/*.xml'

    step([$class: 'JUnitTestReportPublisher',
          reportsDirectory: '',
          fileIncludePattern: '**/target/surefire-reports/**/*.xml',
          fileExcludePattern: '',
          markAsUnstable: false,
          copyHTMLInWorkspace: true
    ])

    jacoco execPattern: '**/jacoco.exec'

    openshift.withCluster("nonprod") {
        openshift.withProject(params.project) {
            openshift.apply( openshift.process(readFile(file:'buildTemplate.yml'), "-p APP_NAME=${params.appName} -p BASE_IMAGE=${baseImage}" ) )
            openshift.selector("bc", params.appName).startBuild("--from-file=./${params.appName}-app/target/${params.appName}-app-${version}.jar", "--wait=true")
            openshift.tag("${params.appName}:latest", "${params.appName}:${this.imageVersion}")
        }
    }

    repositoryHelper.tagSemver(this.semanticVersion)
}

def performQualityAndSecurityAnalyses() {
    def repoInfo = repositoryHelper.returnRepoInfo()
    def sonarProjectKey = "${repoInfo.key}:${repoInfo.repoSlug}".toUpperCase()
    def sonarProjectName = "${repoInfo.key}_${repoInfo.repoSlug}".toUpperCase()
    def fortifyReportFileName = "$sonarProjectName-${BUILD_NUMBER}.mbs"
    withSonarQubeEnv("sonar-enterprise") {
        configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            withEnv(['PATH+fortifyPath=/home/jenkins/fortify/bin']){
                shell """sourceanalyzer -64 -Xmx12G -Xms1G -b "$sonarProjectName" -exclude "**/*.js" mvn clean package -s "${MAVEN_SETTINGS}" -q -DskipTests -B -Dfortify.sca.exclude="**/*.js"
                         sourceanalyzer -64 -Xmx12G -Xms1G -b "$sonarProjectName" -export-build-session "$fortifyReportFileName" -appserver-home "$WORKSPACE/$sonarProjectName-$BUILD_NUMBER" """
            }

            if (env.PULL_REQUEST_ID) { // PR analysis
                shell 'mvn -B -s "$MAVEN_SETTINGS" ' +
                        "org.jacoco:jacoco-maven-plugin:prepare-agent org.jacoco:jacoco-maven-plugin:report " +
                        "clean verify sonar:sonar " +
                        "-Dsonar.pullrequest.branch=${env.BRANCH_NAME} " +
                        "-Dsonar.pullrequest.key=${env.PULL_REQUEST_ID} " +
                        "-Dsonar.pullrequest.base=${env.PULL_REQUEST_TARGET_BRANCH} " +
                        "-Dsonar.projectKey=$sonarProjectKey " +
                        "-Dsonar.projectName=$sonarProjectName " +
                        "-Dsonar.pullrequest.bitbucketserver.project=${repoInfo.key} " +
                        "-Dsonar.pullrequest.bitbucketserver.repository=${repoInfo.repoSlug} "
            } else { // Full branch analysis
                shell 'mvn -B -s "$MAVEN_SETTINGS" ' +
                        "org.jacoco:jacoco-maven-plugin:prepare-agent org.jacoco:jacoco-maven-plugin:report " +
                        "clean verify sonar:sonar " +
                        "-Dsonar.projectKey=\"$sonarProjectKey\" " +
                        "-Dsonar.projectName=\"$sonarProjectName\" " +
                        "-Dsonar.branch.name=\"${env.BRANCH_NAME}\" "
            }
        }
    }
}

private def generateImageVersion(semanticVersion, ... suffixes) {
    def version = semanticVersion.label.replace("-SNAPSHOT", "")
    return version + suffixes.collect { "-$it" }.join("")
}

def deploy(params, deploymentParams) {

    def deployTemplate = libraryResource 'java-deploy-template.yml'

    writeFile file: 'deployTemplate.yml', text: deployTemplate

    openshift.withCluster("nonprod") {
        openshift.withProject(params.project) {

            openshift.apply(openshift.process(readFile(file:'deployTemplate.yml')
                    .replace( "\${SERVICE_PORT}", "${deploymentParams.servicePort}").replace("\${MANAGEMENT_SERVICE_PORT}","${deploymentParams.managementServicePort}").replace( "\${GRPC_PORT}", "${deploymentParams.grpcservicePort}"),
                    "-p APP_NAME=${params.appName} -p PROJECT_NAME=${params.project}" +
                            "-p IMAGE_REGISTRY=${deploymentParams.imageRegistry} -p IMAGE_VERSION=${this.imageVersion}" +
                            "-p ENVIRONMENT=${deploymentParams.environment} -p CPU_LIMIT=${deploymentParams.cpuLimit} -p CPU_REQUEST=${deploymentParams.cpuRequest}" +
                            "-p MEMORY_LIMIT=${deploymentParams.memoryLimit} -p MEMORY_REQUEST=${deploymentParams.memoryRequest} -p JAVA_OPTS=${deploymentParams.javaOpts}" +
                            "-p TZ=${deploymentParams.timezone}"))

            def service = openshift.selector("svc","${params.appName}")

            if(service.exists()) {
                def serviceObj = service.object()
                serviceObj.spec.ports[0].name = "${deploymentParams.servicePort}-tcp"
                serviceObj.spec.ports[0].port = deploymentParams.servicePort
                serviceObj.spec.ports[0].targetPort = deploymentParams.servicePort
                //TODO: refactor -> ext method
                if(deploymentParams.managementServicePort > 0) {
                    serviceObj.spec.ports[1] = [name: "${deploymentParams.managementServicePort}-tcp", port: deploymentParams.managementServicePort, targetPort: deploymentParams.managementServicePort]
                }

                openshift.apply(serviceObj)
            } else {
                openshift.selector("dc", "${params.appName}").expose("--port=${deploymentParams.servicePort}","--target-port=${deploymentParams.servicePort}")
                service = openshift.selector("svc","${params.appName}")
                def serviceObj = service.object()
                //TODO: refactor -> ext method
                if(deploymentParams.managementServicePort > 0) {
                    serviceObj.spec.ports[0].name = "${deploymentParams.servicePort}-tcp"
                    serviceObj.spec.ports[1] = [name: "${deploymentParams.managementServicePort}-tcp", port: deploymentParams.managementServicePort, targetPort: deploymentParams.managementServicePort]
                }
                openshift.apply(serviceObj)
            }

            if(deploymentParams.replicas != 1)
            {
                def deploymentConfigObj = openshift.selector("dc", "${params.appName}").object()
                def hpaObj = openshift.selector("hpa", "${params.appName}").object()

                deploymentConfigObj.spec.replicas = deploymentParams.replicas
                hpaObj.spec.maxReplicas = deploymentParams.maxReplicas
                hpaObj.spec.minReplicas = deploymentParams.minReplicas

                openshift.apply(deploymentConfigObj)
                openshift.apply(hpaObj)

            }


            if(openshift.selector("rc", [ app : "${params.appName}"]).exists()) {
                echo "Found running rc, cancelling rollout"
                sleep 5
                openshift.selector("dc", "${params.appName}").rollout().cancel();
            }
            sleep 5
            openshift.selector("dc", params.appName).rollout().latest();
        }
    }
}

def executePerformanceTests(params) {
    def performanceTestFiles = findFiles glob: "${params.appName}-app/src/test/**/*.jmx"
    if (!performanceTestFiles) {
        echo "Performance test (*.jmx) files could not be found. Skipping performance tests..."
        return
    }
    echo "Performance will be executed for: ${performanceTestFiles[0]}"
    stash name: "jmeter-test", includes: "${params.appName}-app/src/test/**/*.jmx"
    node("jmeter") {
        unstash "jmeter-test"
        shell "jmeter -n -t \"${performanceTestFiles[0]}\" -l result.jtl"
        perfReport filterRegex: '', relativeFailedThresholdNegative: 1.2, relativeFailedThresholdPositive: 1.89, relativeUnstableThresholdNegative: 1.8, relativeUnstableThresholdPositive: 1.5, sourceDataFiles: 'result.jtl'
    }
}

def executeFunctionalApiTests(params) {
    configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
        shell "mvn -s \"$MAVEN_SETTINGS\" -fn test -Dtest=KarateRunner -Dmaven.test.failure.ignore=true -DfailIfNoTests=false"
    }

    cucumber buildStatus: 'UNSTABLE',
            fileIncludePattern: '**/target/cucumber-reports/*.json',
            reportdir: "cucumber-reports",
            trendsLimit: 10,
            classifications: [
                    [
                            'key': 'Browser',
                            'value': 'Chrome'
                    ]
            ]
}

def validate(params){
    try {
        openshift.withCluster("nonprod") {
            openshift.withProject("${params.project}") {
                def counter = 0
                def delay = 10
                def maxCounter = 100

                println "Validating deployment of ${params.appName} in project ${params.project}"
                def latestDCVersion =  openshift.selector("dc","${params.appName}").object().status.latestVersion
                def rcName = "${params.appName}-${latestDCVersion}"
                while(counter++ <= maxCounter) {
                    def phase = openshift.selector("rc",rcName).object().metadata.annotations.get('openshift.io/deployment.phase')
                    if(phase == "Complete"){
                        println "Deployment Succeeded!"
                        break
                    } else if(phase == "Cancelled"){
                        error("Deployment Failed")
                        break
                    } else if(counter == maxCounter) {
                        error("Max Validation Attempts Exceeded. Failed Verifying Application Deployment...")
                    }
                    sleep delay
                }
            }
        }
    } catch (error){
        throw error
    }
}


