

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