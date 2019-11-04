#!/usr/bin/env groovy

package com.definex.dso.utils

def readConfig(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def params = [:]
    if (config.containsKey("params")) {
        params = config.params
    }

    return params
}