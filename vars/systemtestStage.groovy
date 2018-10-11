import groovy.json.JsonOutput

final String systemtestJobName = "wip-system-test"

def call(config) {
    stage("System test") {
        def testJob = build job: systemtestJobName, parameters: [[$class: 'StringParameterValue', name: 'config', value: JsonOutput.toJson(config) ]], propagate:false

        // TODO this should be a lib function, see if can be dragged in from jenkins/systemtest
        node {
            String text = "<h2>Regression test</h2><a href=\"${testJob.getAbsoluteUrl()}\">${testJob.getProjectName()} ${testJob.getDisplayName()} - ${testJob.getResult()}</a>"
            rtp(nullAction: '1', parserName: 'HTML', stableText: text, abortedAsStable: true, failedAsStable: true, unstableAsStable: true)
        }

        if( testJob.getResult() != "SUCCESS" ) {
            currentBuild.result = 'blargh'
        }
    }
}


