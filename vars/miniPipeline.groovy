#!/usr/bin/env groovy
import com.definex.dso.utils.*

def call(body) {

    def config = new Config()
    def helper = new Helper()
    def build = new com.definex.dso.service.build.Helper()

    def params = config.readConfig(body)

    //identify stages

    def project = helper.getProject(params)

    helper.wrapPipeline(params) {
        helper.wrapStage(Constants.STAGE_DEMO) {
            echo 'Hello World'
            sayHello 'Susantez'
        }

        helper.wrapStage(Constants.STAGE_BUILD_ARTIFACT) {
            project.buildArtifact()
        }
    }
}