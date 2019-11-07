package com.definex.dso.project


class Maven extends Project {
    Maven(script, params) {
        super(script, params)
    }

    void setVersion(version) {
        this.version = this.params.version = version
        setImageVersion(version, currentBuild.number, env.OPENSHIFT_ENV)

        configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            shell """mvn -s \"$MAVEN_SETTINGS\" -B -f ${params.appName}-bom/pom.xml versions:set-property -Dproperty=revision -DnewVersion=${version.label}"""
        }
    }

    void buildArtifact() {
        script.echo "build artifact"
        //new com.definex.dso.service.build.Maven().buildArtifact(this.params)
    }

    void buildImage() {

    }

    String getVersion() {
        if (this.version) {
            return this.version
        }
        configFileProvider([configFile(fileId: 'artifactory-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            version = sh(returnStdout: true, script: 'printf \'VER\t${project.version}\' | mvn -s \"$MAVEN_SETTINGS\"  help:evaluate | grep \'^VER\' | cut -f2').trim()
        }
    }
}