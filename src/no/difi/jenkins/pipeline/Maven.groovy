package no.difi.jenkins.pipeline

import groovy.json.JsonSlurperClassic
import groovy.text.SimpleTemplateEngine

Docker docker

void verify(def options) {
    String settingsFile = settingsFile()
    env.MAVEN_OPTS = options ?: ""
    sh "mvn clean verify -B -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

void deployDockerAndJava(
        def version, def mavenOptions, def parallel,
        def swarmId,
        def javaRepository, def javaUserName, def javaPassword
) {
    currentBuild.description = "Publishing artifacts with version ${version} from commit ${GIT_COMMIT}"
    String settingsFile = null
    withCredentials([usernamePassword(
            credentialsId: docker.registryCredentialsId(swarmId),
            passwordVariable: 'dockerPassword',
            usernameVariable: 'dockerUsername')]
    ) {
        settingsFile = settingsFileWithDockerAndJava docker.registryAddressForSwarm(swarmId), env.dockerUsername, env.dockerPassword, "javaRepository", javaUserName, javaPassword
    }
    deploy version, mavenOptions, parallel, javaRepository, settingsFile
}

void deployJava(
        def version, def mavenOptions, def parallel,
        def javaRepository, def javaUserName, def javaPassword
) {
    currentBuild.description = "Publishing artifacts with version ${version} from commit ${GIT_COMMIT}"
    String settingsFile = settingsFileWithJava "javaRepository", javaUserName, javaPassword
    deploy version, mavenOptions, parallel, javaRepository, settingsFile
}

private void deploy(def version, def mavenOptions, def parallel, def javaRepository, String settingsFile) {
    env.MAVEN_OPTS = mavenOptions ?: ""
    String parallelOptions = parallel ? "-T 1C" : ""
    sh "mvn versions:set -B -DnewVersion=${version}"
    sh "mvn deploy -DdeployAtEnd=true -DaltDeploymentRepository=javaRepository::default::${javaRepository} -B ${parallelOptions} -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

boolean systemTestsSupported() {
    int status = sh(returnStatus: true, script: "[ -e ${WORKSPACE}/system-tests ]")
    if (status == 0) {
        echo "System tests are supported"
        return true
    } else {
        echo "System tests are not supported"
        return false
    }
}

void runSystemTests(def swarmId, def stackName) {
    if (!systemTestsSupported()) return
    def swarmConfig = docker.config.swarms[swarmId as String]
    if (swarmConfig.host == null) {
        echo "No host defined for Docker swarm '${swarmId}' -- skipping tests"
        return
    }
    Map servicePorts = docker.servicePorts(
            swarmConfig.sshKey, swarmConfig.user, swarmConfig.host, stackName,
            'eid-atest-admin', 'eid-atest-idp-app', 'selenium', 'eid-atest-db'
    )
    sh """
        mvn verify -pl system-tests -PsystemTests -B\
        -DadminDirectBaseURL=http://${swarmConfig.host}:${servicePorts.get('eid-atest-admin')}/idporten-admin/\
        -DminIDOnTheFlyUrl=http://${swarmConfig.host}:${servicePorts.get('eid-atest-idp-app')}/minid_filegateway/\
        -DseleniumUrl=http://${swarmConfig.host}:${servicePorts.get('selenium')}/wd/hub\
        -DdatabaseUrl=${swarmConfig.host}:${servicePorts.get('eid-atest-db')}
    """
}

void deletePublished(def version) {
    try {
        echo "Deleting artifacts for rejected version ${version}"
        url = "http://eid-artifactory.dmz.local:8080/artifactory/api/search/gavc?v=${version}&repos=libs-release-local"
        httpresponse = httpRequest url
        response = new JsonSlurperClassic().parseText(httpresponse.content)
        Set<String> toDel = new HashSet<String>()
        response['results'].each{ item ->
            toDel.add(item['uri'].minus('api/storage/').minus(item['uri'].tokenize("/").last()))
        }
        withCredentials([string(credentialsId: 'artifactory', variable: 'artifactory')]) {
            toDel.each{ item ->
                try {
                    httpRequest customHeaders: [[name: 'X-JFrog-Art-Api', value: artifactory, maskValue: true]], httpMode: 'DELETE', url: item
                }
                catch (Exception e){
                    echo e.toString()
                }
            }
        }
    } catch (Exception e) {
        echo e.toString()
    }
}

@NonCPS
private void settingsFileWithDockerAndJava(def dockerServerId, def dockerUserName, def dockerPassword, def javaServerId, def javaUserName, def javaPassword) {
    String settingsTemplate = libraryResource 'mavenSettingsWithDockerAndJava.xml'
    Map binding = [
            'dockerServerId': dockerServerId,
            'dockerUserName': dockerUserName,
            'dockerPassword': dockerPassword,
            'javaServerId': javaServerId,
            'javaUserName': javaUserName,
            'javaPassword': javaPassword
    ]
    String settings = bind settingsTemplate, binding
    writeToFile settings
}

@NonCPS
private void settingsFileWithJava(def javaServerId, def javaUserName, def javaPassword) {
    String settingsTemplate = libraryResource 'mavenSettingsWithJava.xml'
    Map binding = [
            'javaServerId': javaServerId,
            'javaUserName': javaUserName,
            'javaPassword': javaPassword
    ]
    String settings = bind settingsTemplate, binding
    writeToFile settings
}

@NonCPS
private void settingsFile() {
    String settings = libraryResource 'mavenSettings.xml'
    writeToFile settings
}

@NonCPS
private static String bind(String template, Map binding) {
    new SimpleTemplateEngine().createTemplate(template).make(binding).toString()
}

@NonCPS
private String writeToFile(String settings) {
    sh(returnStdout: true, script: "file=/tmp/settings\${RANDOM}.tmp && echo '${settings}' > \${file} && echo \${file}").trim()
}
