#!/usr/bin/env groovy
package com.definex.dso.utils

import com.definex.dso.project.*
import com.definex.dso.utils.Constants
import groovy.transform.Field

@Field HashSet<String> stagesToSkip = new HashSet<>()

def skipStage(stageName) {
    this.stagesToSkip.add(stageName)
}

def wrapPipeline(params, body) {
    timeout(time: 3, unit: 'HOURS') {
        timestamps {
            ansiColor("xterm") {
                wrapNode(resolveAgentLabel(params)) {
                    try {
                        Event.publish("begin")
                        body()
                        currentBuild.result = currentBuild.result ?: 'SUCCESS'
                        Event.publish("success")
                    } catch (e) {
                        if (currentBuild.result != "ABORTED" && currentBuild.result != "NOT_BUILT") {
                            currentBuild.result = "FAILURE"
                        }
                        Event.publish("failed")
                        throw e
                    } finally {
                        Event.publish("completed")
                    }
                }
            }
        }
    }
}

def wrapNode(agentLabel, body) {
    node(agentLabel) {
        body()
    }
}

def wrapStage(stageName, body) {
    stage(stageName) {
        if (this.stagesToSkip.contains(stageName)) {
            echo "Skipping $stageName stage..."
            return
        }
        Event.publish("before" + stageName);
        body()
        Event.publish("after" + stageName);
    }
}

def resolveAgentLabel(params) {
    switch (params.type) {
        case Constants.APPLICATION_TYPE_MAVEN:
            return Constants.AGENT_MAVEN
        case Constants.APPLICATION_TYPE_CELLS:
            return Constants.AGENT_CELLS
        case Constants.APPLICATION_TYPE_DEMO:
            return Constants.AGENT_OTHER
        default:
            throw new IllegalArgumentException("Invalid type")
    }
}

static def getProject(type) {
    if (type == "maven") {
        return new Maven()
    } else if (type == "cells") {
        echo "cells"
        //return new Cells()
    } else if (type == "component-library") {
        echo "component"
        //return new ComponentLibrary()
    } else {
        throw new IllegalArgumentException("Invalid build type")
    }
}

def generateDeploymentParams(params) {
    def deploymentParams = [:]

    deploymentParams.paramEnvironment = "dev"
    deploymentParams.environment = deploymentParams.paramEnvironment == "default" ? params.environment : deploymentParams.paramEnvironment
    deploymentParams.servicePort = params.get("port", 8080)
    deploymentParams.grpcservicePort = params.get("grpcport", 6565)
    deploymentParams.managementServicePort = params.get("managementPort", 8080)
    deploymentParams.imageRegistry = "docker-registry.default.svc:5000/${params.project}"
    deploymentParams.cpuLimit = params.get("cpuLimit", "1000m")
    deploymentParams.cpuRequest = params.get("cpuRequest", "100m")
    deploymentParams.memoryLimit = params.get("memoryLimit", "1280M")
    deploymentParams.memoryRequest = params.get("memoryRequest", "512M")
    deploymentParams.javaOpts = params.get("javaOpts", "-Xms1024m -Xmx1024m")
    deploymentParams.timezone = params.get("timezone", "Europe/Istanbul")
    deploymentParams.replicas = params.get("replicas", (deploymentParams.environment == "prod" ? 2 : 1 ))
    deploymentParams.minReplicas = params.get("minReplicas", (deploymentParams.environment == "prod" ? 2 : 1 ))
    deploymentParams.maxReplicas = params.get("maxReplicas", (deploymentParams.environment == "prod" ? 4 : 2 ))

    return deploymentParams
}