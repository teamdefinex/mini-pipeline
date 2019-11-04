#!/usr/bin/env groovy
package com.definex.dso.utils

import com.definex.dso.project.*
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
                    //    container("ark-openshift-pod") {
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

def resolveAgentLabel(def params) {
    if (params.type == "maven") {
        return "maven-skopeo"
    } else if (params.type == "cells" || params.type == "component-library") {
        return "nodejs8-cells-skopeo"
    } else {
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

def prepareSonarProject(key, name) {
    echo "Preparing SonarQube project..."
    try {
        def projectIsCreated
        withSonarQubeEnv("sonar-enterprise") {
            projectIsCreated = new SonarQube(env.SONAR_HOST_URL, env.SONAR_AUTH_TOKEN, "")
                    .createProject(key, name)
        }
        if (projectIsCreated) {
            echo "SonarQube project $key is created"
            // Give relevant groups permission to view analysis results on SonarQube interface
            build job: "SonarPermissionSyncer",
                    parameters: [[
                                         $class: 'StringParameterValue',
                                         name: 'PROJECT_KEY',
                                         value: key
                                 ]],
                    quietPeriod: 0,
                    wait: false
        } else {
            echo "SonarQube project $key already exists"
        }
    } catch (err) {
        echo "[WARNING] SonarQube project creation failed: $err.message"
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