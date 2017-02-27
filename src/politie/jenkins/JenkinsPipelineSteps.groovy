package politie.jenkins

import politie.jenkins.Constants
import groovy.json.JsonSlurper

// Constructor, called from PipelineBuilder.initialize().
void initialize() {
    echo 'Initializing PipelineSteps.'
}

void cleanWorkspace() {
    sh "echo 'Cleaning workspace'"
    // FIXME the following gets sometimes called from outside a git repository
    // workaround with '|| true' but should be fixed properly
    sh 'git clean -dfx node_modules || true'
    deleteDir()
}

void checkout(String project, String branch) {
    checkout changelog: true, poll: true, scm: [
            $class           : 'GitSCM',
            branches         : [[name: '*/' + branch]],
            browser          : [$class: 'GitLab', repoUrl: Constants.GITLAB_WEB_BASE_URL + project , version: Constants.GITLAB_VERSION ],
            userRemoteConfigs: [[credentialsId: Constants.GITLAB_CREDENTIALS_ID, url: Constants.GITLAB_CHECKOUT_BASE_URL + project + '.git' ]]
    ]
}

String determineVersion() {
    return Constants.MAJOR_VERSION_NUMBER + '.' + env.BUILD_NUMBER;
}

String getBranch() {
    String branchName;

    // When Gitlab triggers the build, we can read the source branch from gitlab.
    if (env.gitlabSourceBranch != null) {
        branchName = env.gitlabSourceBranch
        echo 'Gitlab source branch: ' + branchName
    } else {
        sh "git show-ref | grep `git rev-parse HEAD` | grep remotes | grep -v HEAD | sed -e 's/.*remotes.origin.//' > branch.txt"
        branchName = readFile('branch.txt').trim()
    }

    echo 'Building branch \'' + branchName + '\'.'
    return branchName;
}

String getCommitId() {
    String commitId = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    return commitId;
}

void setBuildName(String version, String branch) {
    currentBuild.displayName = version
    if (branch != 'master') {
        currentBuild.displayName = "# ${env.BUILD_NUMBER} - branch: ${branch} "
    }
}

void setBuildProperties() {
// disabled because this doesn't work (yet with gitlab triggers)
//    properties([pipelineTriggers([]),
//            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '8')),
//            [$class: 'GitLabConnectionProperty', gitLabConnection: '']
//    ])
}


void updateApplicationVersionMaven(String version) {
    sh "echo \'\ninfo.build.version=\'$version >> \$(find . -name application.properties | grep -E -v 'target|src/test') || true"
    sh "mvn -B versions:set -DnewVersion=$version"
}

void runMavenBuild(boolean enableJacoco) {
    // Apache Maven related side notes:
    // --batch-mode : recommended in CI to inform maven to not run in interactive mode (less logs)
    // -V : strongly recommended in CI, will display the JDK and Maven versions in use.
    //      Very useful to be quickly sure the selected versions were the ones you think.
    // -U : force maven to update snapshots each time (default : once an hour, makes no sense in CI).
    // -Dsurefire.useFile=false : useful in CI. Displays test errors in the logs directly (instead of
    //                            having to crawl the workspace files to see the cause).


    if (enableJacoco) {
        sh '''
        export CEPH_CONF=$PWD/src/test/resources/ceph.conf
        mvn -B -V -U -e clean org.jacoco:jacoco-maven-plugin:prepare-agent verify -Dsurefire.useFile=false
       '''
    } else {
        sh '''
        export CEPH_CONF=$PWD/src/test/resources/ceph.conf
        mvn -B -V -U -e clean verify -Dsurefire.useFile=false
       '''
    }

    archiveTestResults()
}

void archiveTestResults() {
    step([$class: 'JUnitResultArchiver', testResults: '**/target/**/TEST*.xml', allowEmptyResults: true])
}

void runSonarAnalysis() {
    //println 'Sonar analysis temporarily disabled';
    println 'Running Sonar analysis';
    sh "mvn -B -V -U -e org.sonarsource.scanner.maven:sonar-maven-plugin:3.2:sonar -Dsonar.host.url='${Constants.SONAR_URL}'"
}

void deployToNexus() {
    println 'Deploying to Nexus';
    sh "mvn -B -V -U -e clean source:jar javadoc:jar deploy -DskipTests"
}

void deployApplicationWithConfigToDevEnv(String serviceName, String version, String applicationType) {
    build job: 'deploy-application-with-config', parameters: [
            [$class: 'StringParameterValue', name: 'APPLICATION', value: serviceName],
            [$class: 'StringParameterValue', name: 'VERSION', value: version],
            [$class: 'StringParameterValue', name: 'APPLICATION_TYPE', value: applicationType]
    ], wait: true
}

void deploySparkJobToDevEnv(String serviceName, String version) {
    build job: 'deploy-spark-job', parameters: [
            [$class: 'StringParameterValue', name: 'APPLICATION', value: serviceName],
            [$class: 'StringParameterValue', name: 'VERSION', value: version]
    ], wait: true
}

def getUrlForEnvironment(String application, String tenant, String environment) {
    return "https://${application}.${environment}.${tenant}" + Constants.ENVIRONMENT_DOMAIN_NAME
}

void checkDeployedVersion(String application, String version, String tenant) {
    def url = getUrlForEnvironment(application, tenant, 'dev') + '/info'

    sh """
        sleep 5s

        echo "Checking health of ${application} version ${version} at ${url}."

        output=\$( curl --insecure ${url} 2>/dev/null || true )
        echo "Healthcheck output: \${output}"

        versionCheck=\$( echo \$output | grep ${version} || true )
        if [ -z "\$versionCheck" ]
        then
            echo "Version ${version} NOT found, deployment failed?"
        else
            echo "Version ${version} found, deployment succeeded."
        fi
    """
}


@NonCPS
def parseJsonText(String jsonText) {
    final slurper = new JsonSlurper()
    return slurper.parseText(jsonText)
}

@NonCPS
def gitlab_getProjectDetails(token, projectName) {
    // Fetch project by project name
    def response = httpRequest customHeaders: [[name: 'PRIVATE-TOKEN', value: token ]],
            url: Constants.GITLAB_API_BASE_URL + '/projects/development%2F' + projectName
    return response
}

@NonCPS
def gitlab_performTag(token, projectId, commitHash, tagName) {
    // Create POST body
    def postBody = """
        {
        "id": "$projectId",
        "tag_name": "$tagName",
        "ref": "$commitHash"
        }
    """
    def tagUrl = Constants.GITLAB_API_BASE_URL + "/projects/${projectId}/repository/tags"

    // Perform tag
    def response = httpRequest contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: postBody,
            customHeaders: [[name: 'PRIVATE-TOKEN', value: token ]], url: tagUrl
    return response
}


def tagCommit(project, commitHash, tag) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: Constants.GITLAB_API_TOKEN_CREDENTIALS_ID,
                      usernameVariable: 'USERNAME', passwordVariable: 'token']]) {

        // Call Gitlab API to get project details, read project id
        def getProjectResponse = gitlab_getProjectDetails(token, project)
        def projectId = parseJsonText(getProjectResponse.content).id

        // Call gitlab API to place a tag on the specific project and commithash
        def performTagResponse = gitlab_performTag(token, projectId, commitHash, tag)
        def tagCreated = parseJsonText(performTagResponse.content)

        println 'Tagged commit ' + tagCreated.commit.id + ' with tag "' + tagCreated.name + '".'
    }
}

// Return the contents of this script as object so it can be re-used in Jenkinsfiles.
return this;
