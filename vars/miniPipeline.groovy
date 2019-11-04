#!/usr/bin/env groovy
import com.definex.dso.utils.*

def call(body) {

    def config = new Config()
    def helper = new Helper()

    def params = config.readConfig(body)

    helper.wrapPipeline(params) {
        helper.wrapStage("Demo") {
            echo 'Hello World'
            sayHello 'Susantez'
        }
    }
}