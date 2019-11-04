#!/usr/bin/env groovy
package com.definex.dso.scm

import com.definex.dsoutils.Event

/*
 * Repository Helper functions.
 * helper.groovy
 */

void initNotifyBitbucket(commitId) {
    Event.subscribe("begin", {
        notifyBitbucket(
                commitSha1: commitId
        )
    })
    Event.subscribe("completed", {
        notifyBitbucket(
                commitSha1: commitId
        )
    })
}

/**
 * Copy the repository in the workspace, overriding default timeout and shallow options
 *
 * @param shallow (en/dis)able shallow clone
 */
void checkout(Boolean shallow=false){
    echo "Performing checkout"
    checkout([
            $class: 'GitSCM',
            branches: scm.branches,
            refspec: '+refs/heads/*:refs/remotes/origin/*',
            userRemoteConfigs: scm.userRemoteConfigs,
            extensions: scm.extensions + [[ $class: 'CloneOption', shallow: shallow ]]
    ])
}

/**
 * Get the id from current commit.
 *
 * @return String.
 */
String getCommitId(){
    def log = currentBuild.rawBuild.log
    def match = log =~ /Obtained Jenkinsfile from\s*(\S+)/
    def commitId
    if (match && match[0] && match[0][1]) {
        commitId = match[0][1]
    }
    return commitId
}

/**
 * .
 *
 * @param String branchSource.
 * @param String branchCheck.
 *
 */
void gitRegressionTest(String branchSource, String branchCheck) {
    stage("Git Regression Test"){
        try {
            checkout()
            sh """
          git checkout ${branchSource}
          git merge origin/${branchCheck} --ff-only
        """
        } catch (exception) {
            currentBuild.result = 'FAILURE'
            if (currentBuild.result != null && !currentBuild.result.equalsIgnoreCase("STABLE")) {
                error "Branch ${branchCheck} has changes that are not present in ${branchSource}."
            }
            throw exception
        }
    }
}

/**
 * Change the branch name format.
 *
 * @param String Branch name.
 *
 * @return String with formated branch name.
 *
 * @example
 * <pre>
 * <code>
 *     branchName = "feature/myFeature"
 *
 *     formatBranchName(branchName)
 * </code>
 * </pre>
 *
 */
String formatBranchName(String branchName){
    String formattedName = "${branchName}".replaceAll("/","-")
    return formattedName
}

/**
 * Return repo info.
 *
 * @returns a map of key (bitbucket key) and reposlug (git repository slug).
 *
 * @example Output
 * <pre>
 * <code>
 *     [key:PROJECT_KEY,repoSlug:repo_slug]
 * </code>
 * </pre>
 */
def returnRepoInfo() {
    def log = currentBuild.rawBuild.log
    def match = log =~ /(?m)^Setting origin to .+?\/scm\/([^\/]+)\/(.+).git$/
    if (match && match[0] && match[0][1] && match[0][2]) {
        return [key: match[0][1], repoSlug: match[0][2]]
    } else {
        throw new Exception("Cannot parse git repository from Jenkins log")
    }
}

/**
 * Tag the current git branch.
 *
 * @param tag name for the tag.
 * @param message text added with the tag.
 *
 *
 * @example
 * <pre>
 * <code>
 *     tagBranch("1.0.0", "First Major Version")
 * </code>
 * </pre>
 */
void tagBranch(String tag, String message){
    sh "git tag -a ${tag} -m \"${message}\""

    if (env.jenkins_ssh_credentials != null){
        print("Taggin with jenkins_ssh_credentials")
        sshagent(["${env.jenkins_ssh_credentials}"]){
            sh "git push --tags"
        }
    }
    else{
        print("Taggin with local user")
        sh "git push --tags"
    }
}

def generateVersion(lerna=false) {
    def branch = env.BRANCH_NAME
    def preReleaseTag
    if (branch == "master") {
        preReleaseTag = null
    } else if (branch == "release") {
        preReleaseTag = "beta"
    } else {
        preReleaseTag = "alpha"
    }
    def semverOutput = null, semverVersion = null

    def depth = branch == "master" ? 3 : branch == "release" ? 2 : 1
    try {
        node("semver-generator") {
            withCredentials([usernamePassword(credentialsId: 'gitreadonly',
                    passwordVariable: 'GIT_PASSWORD',
                    usernameVariable: 'GIT_USERNAME')]) {
                def script = "bbsemver " +
                        "--url 'https://gtgit.fw.garanti.com.tr/projects/${env.GIT_PROJECT}/repos/${env.GIT_REPOSITORY}' " +
                        "--username '${GIT_USERNAME}' --password '${GIT_PASSWORD}' " +
                        "--depth ${depth} --branch '${branch}' --json -v"
                if (preReleaseTag) {
                    script += " --pre $preReleaseTag"
                }
                if (lerna) {
                    script += " --lerna"
                }
                semverOutput = sh(returnStdout: true, script: script)
            }
        }
        semverVersion = semverOutput.split("\n").last()
    } catch (err) {
        error "Error getting semver version: $err.message"
    }
    echo "Semver says:\n" + semverOutput
    return readJSON(text: semverVersion)
}

def tagSemver(semver, projectName=null) {
    if (env.TAG_VERSION_IN_GIT != "true") {
        return
    }

    def label = semver.label
    if (!(label =~ /^(\d+)\.(\d+)\.(\d+)([-+](.+))?/)) {
        throw new Exception("Cannot tag semver because semver has unexpected format ($label)")
    }
    if (!env.COMMIT_ID) {
        throw new Exception("Failed getting commit ID to tag semver")
    }

    def semverTag
    if (!projectName) {
        semverTag = "semver-$label"
    } else {
        semverTag = "semver-$projectName@$label"
    }

    //TODO: Convert this to groovy http request to eliminate curl requirement
    echo "Tagging semver..."
    withCredentials([usernamePassword(credentialsId: 'GIT_REST_USER', passwordVariable: 'REST_PASS', usernameVariable: 'REST_USER')]) {
        sh "curl -s -u $REST_USER:$REST_PASS -k -d '{\"name\": \"${semverTag}\"," +
                "\"startPoint\": \"${env.COMMIT_ID}\"}' " +
                "-H \"Content-Type: application/json\" -X POST " +
                "https://gtgit.fw.garanti.com.tr/rest/api/1.0/projects/${env.GIT_PROJECT}/repos/${env.GIT_REPOSITORY}/tags"
    }
}

/**
 * Push the branch to the remote branch.
 *
 * @param merged_branch Branch name for merge (default "origin/develop")
 *
 */
void branchUpdater(String merged_branch="origin/develop") {
    sh 'git fetch'
    String code_changes = sh(script: "git log HEAD..${merged_branch} --oneline", returnStdout: true)
    String merge_std = sh(script: "git merge ${merged_branch} -m \"[Jenkins] Merge branch ${merged_branch} into ${env.BRANCH_NAME}\"", returnStdout: true)
    print code_changes
    if (code_changes=="" ){
        print merge_std
    }
    else{
        sh "git push origin HEAD:${env.BRANCH_NAME}"
    }
}

return this