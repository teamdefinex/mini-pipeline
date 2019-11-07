#!/usr/bin/env groovy
package com.definex.dso.project

abstract class Project {
    def script, params, version

    Project(script, params) {
        this.script = script
        this.params = params
        script.echo "constructor with params"
    }

    void checkout() {
        new com.definex.dso.service.scm.repository.helper().checkout()
    }
    void setVersion(version) {}
    void buildArtifact() {}
    void buildImage() {}
    void test() {}
    void security() {}
    void deploy() {}
    void validate() {}
    private void setImageVersion(String semanticVersion, String... suffixes) {
        def version = semanticVersion.label.replace("-SNAPSHOT", "")
        this.params.imageVersion = version + suffixes.collect { "-$it" }.join("")
    }
}