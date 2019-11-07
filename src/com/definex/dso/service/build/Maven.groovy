package com.definex.dso.service.build

def buildArtifact(params) {
    configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
        shell "mvn -s \"$MAVEN_SETTINGS\" -B -U -Djacoco.destFile=./coverage/jacoco.exec " +
                "org.jacoco:jacoco-maven-plugin:prepare-agent org.jacoco:jacoco-maven-plugin:report " +
                "clean package"
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
}

def buildImage(params) {
    final String baseImage = "redhat-openjdk18-openshift:1.4"
    final String buildTemplate = libraryResource "build-template.yml"

    writeFile file: 'buildTemplate.yml', text: buildTemplate

    openshift.withCluster("nonprod") {
        openshift.withProject(params.project) {
            openshift.apply( openshift.process(readFile(file:'buildTemplate.yml'), "-p APP_NAME=${params.appName} -p BASE_IMAGE=${baseImage}" ) )
            openshift.selector("bc", params.appName).startBuild("--from-file=./${params.appName}-app/target/${params.appName}-app-${params.version}.jar", "--wait=true")
            openshift.tag("${params.appName}:latest", "${params.appName}:${params.imageVersion}")
        }
    }

    repositoryHelper.tagSemver(params.version)
}