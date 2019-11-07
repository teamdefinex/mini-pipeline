#!/usr/bin/env groovy
package com.definex.dso.utils

class Constants {
    public static final String APPLICATION_TYPE_MAVEN = "maven";
    public static final String APPLICATION_TYPE_CELLS = "cells";
    public static final String APPLICATION_TYPE_DEMO = "demo";

    public static final String AGENT_MAVEN = "maven-skopeo";
    public static final String AGENT_CELLS = "nodejs8-cells-skopeo";
    public static final String AGENT_OTHER = "";

    public static final String STAGE_DEMO = "demo";
    public static final String STAGE_BUILD_ARTIFACT = "build";
public static final String STAGE_UNIT_TEST = "unit-test";
}