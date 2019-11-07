#!/usr/bin/env groovy
package com.definex.dso.service.build

def buildArtifact(params) {
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

def buildImage(params) {

}