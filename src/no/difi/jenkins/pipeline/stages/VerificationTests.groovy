package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.VerificationTestResult

Jira jira
Git git
Docker dockerClient
Maven maven

void script(def params) {
    git.checkoutVerificationBranch()
    if (maven.verificationTestsSupported(params.verificationEnvironment)) {
        VerificationTestResult result = maven.runVerificationTests params.verificationEnvironment, env.stackName
        jira.addComment(
                "Verifikasjonstester utført: [Rapport|${result.reportUrl()}] og [byggstatus|${env.BUILD_URL}]",
        )
        if (!result.success())
            error 'Verification tests failed'
    }
}

void failureScript(def params) {
    cleanup(params)
    jira.addFailureComment()
}

void abortedScript(def params) {
    cleanup(params)
    jira.addAbortedComment()
}

private void cleanup(def params) {
    git.deleteVerificationBranch()
    dockerClient.deletePublished params.verificationEnvironment, env.version
    if (maven.isMavenProject())
        maven.deletePublished params.verificationEnvironment, env.version
    jira.resumeWork()
}
