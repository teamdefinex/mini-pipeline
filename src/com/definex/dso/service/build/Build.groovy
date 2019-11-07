#!/usr/bin/env groovy
package com.definex.dso.service.build

interface Build {
    void buildArtifact()
    void buildImage()
}