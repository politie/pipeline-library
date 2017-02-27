package politie.jenkins

def pipelineSteps;

// Constructor, called from PipelineBootstrap.createBuilder().
void initialize() {
    echo 'Initializing PipelineBuilder.'
    pipelineSteps = new JenkinsPipelineSteps()
    pipelineSteps.initialize();
}

def getPipelineSteps() {
    return pipelineSteps
}

void mavenPipeline(String deployType, String nodeLabel, String serviceName, String defaultBranch, boolean enableJacoco) {
    node(nodeLabel) {
        try {
            stage('Checkout') {
                pipelineSteps.cleanWorkspace()
                pipelineSteps.checkout(serviceName, defaultBranch)
            }

            String version = pipelineSteps.determineVersion()
            String branch = pipelineSteps.getBranch()
            stage('Configure') {
                pipelineSteps.setBuildName(version, branch)
                pipelineSteps.setBuildProperties()
            }

            stage('Version') {
                pipelineSteps.updateApplicationVersionMaven(version)
            }

            gitlabCommitStatus('jenkins') {
                stage('Build') {
                    pipelineSteps.runMavenBuild(enableJacoco)

                    if (branch == 'master') {
                        stage('Sonar') {
                            pipelineSteps.runSonarAnalysis();
                        }
                        stage('Publish') {
                            pipelineSteps.deployToNexus();
                        }
                        
                        def commitId = pipelineSteps.getCommitId()
                        pipelineSteps.tagCommit(serviceName, commitId, version)

                        if (deployType != 'library') { // libraries don't need a deployment step
                            stage ('Deploy') {
                                if (deployType == 'application') {
                                    pipelineSteps.deployApplicationWithConfigToDevEnv(serviceName, version, 'springboot');
                                    pipelineSteps.checkDeployedVersion(serviceName, version, Constants.ENVIRONMENT_TENANT);
                                } else if (deployType == 'sparkjob') {
                                    pipelineSteps.deploySparkJobToDevEnv(serviceName, version);
                                } else {
                                    error 'Unknown deployType "' + deployType + '"!'
                                    currentBuild.result = "FAILED"
                                }
                            }
                        }
                    } else {
                        println 'Not on master branch, skipping....'
                    }
                }
            }
        } catch (e) {
            currentBuild.result = "FAILED"
            throw e
        }
    }
}

// Convenience methods for the generic maven pipeline

void mavenApplicationPipeline(String serviceName, String defaultBranch) {
    mavenPipeline('application', 'maven', serviceName, defaultBranch, true)
}

void mavenApplicationPipelineOnCeph(String serviceName, String defaultBranch) {
    mavenPipeline('application', 'ceph', serviceName, defaultBranch, true)
}

void mavenLibraryPipeline(String serviceName, String defaultBranch) {
    mavenPipeline('library', 'maven', serviceName, defaultBranch, true)
}

void mavenLibraryPipelineForRadosConnector(String serviceName, String defaultBranch) {
    mavenPipeline('library', 'ceph', serviceName, defaultBranch, false)
}

void mavenSparkJobPipeline(String serviceName, String defaultBranch) {
    mavenPipeline('sparkjob', 'maven', serviceName, defaultBranch, true)
}

// Return the contents of this script as object so it can be re-used in Jenkinsfiles.
return this;



