#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import jenkins.plugins.http_request.*
import java.net.URL
import com.compuware.devops.config.*
import com.compuware.devops.jclskeleton.*
import com.compuware.devops.util.*

/**
 Helper Methods for the Pipeline Script
*/
PipelineConfig  pConfig
GitHelper       gitHelper
IspwHelper      ispwHelper
TttHelper       tttHelper
SonarHelper     sonarHelper 

String          mailMessageExtension
String          cesToken
def             programStatusList

def initialize(pipelineParams)
{
    // Clean out any previously downloaded source
    dir(".\\") 
    {
        deleteDir()
    }

    def mailListlines
    /* Read list of mailaddresses from "private" Config File */
    /* The configFileProvider creates a temporary file on disk and returns its path as variable */
    configFileProvider(
        [
            configFile(
                fileId: 'MailList', 
                variable: 'mailListFilePath'
            )
        ]
    ) 
    {
        File mailConfigFile = new File(mailListFilePath)

        if(!mailConfigFile.exists())
        {
            steps.error "File - ${mailListFilePath} - not found! \n Aborting Pipeline"
        }

        mailListlines = mailConfigFile.readLines()
    }

    pConfig     = new   PipelineConfig(
                            steps, 
                            workspace,
                            pipelineParams,
                            mailListlines
                        )

    pConfig.initialize()                                            

    gitHelper   = new   GitHelper(
                            steps
                        )

    withCredentials([usernamePassword(credentialsId: "${pConfig.gitCredentials}", passwordVariable: 'gitPassword', usernameVariable: 'gitUsername')]) 
    {
        gitHelper.initialize(gitPassword, gitUsername, pConfig.ispwOwner, pConfig.mailRecipient)
    }

    withCredentials(
        [string(credentialsId: "${pConfig.cesTokenId}", variable: 'cesTokenTemp')]
    ) 
    {
        cesToken = cesTokenTemp
    }

    ispwHelper  = new   IspwHelper(
                            steps, 
                            pConfig
                        )

    tttHelper   = new   TttHelper(
                            this,
                            steps,
                            pConfig
                        )

    sonarHelper = new SonarHelper(this, steps, pConfig)
    sonarHelper.initialize()

    mailMessageExtension    = ''
    programStatusList       = [:]
}

/**
Call method to execute the pipeline from a shared library
@param pipelineParams - Map of paramter/value pairs
*/
def call(Map pipelineParams)
{
    node
    {
        stage("Initialization")
        {
            initialize(pipelineParams) 
        }
                
        /* Download all sources that are part of the container  */
        stage("Retrieve Mainframe Code")
        {
            ispwHelper.downloadSources(pConfig.ispwSrcLevel)
        }
        
        /* Retrieve the Tests from Github that match that ISPWW Stream and Application */
        stage("Execute Unit Tests")
        {            
            def gitUrlFullPath = "${pConfig.gitUrl}/${pConfig.gitTttUtRepo}"
            
            /* Check out unit tests from GitHub */
            gitHelper.checkout(gitUrlFullPath, pConfig.gitBranch, pConfig.gitCredentials, pConfig.tttFolder)

            /* initialize requires the TTT projects to be present in the Jenkins workspace, therefore it can only execute after downloading from GitHub */
            tttHelper.initialize()  

            /* Clean up Code Coverage results from previous run */
            tttHelper.cleanUpCodeCoverageResults()

            /* Execute unit tests */
            tttHelper.loopThruScenarios()
         
            tttHelper.passResultsToJunit()

            /* push results back to GitHub */
            gitHelper.pushResults(pConfig.gitProject, pConfig.gitTttUtRepo, pConfig.tttFolder, pConfig.gitBranch, BUILD_NUMBER)
        }

        /* 
        This stage retrieve Code Coverage metrics from Xpediter Code Coverage for the test executed in the Pipeline
        */ 
        stage("Collect Metrics")
        {
            tttHelper.collectCodeCoverageResults()
        }

        /* 
        This stage pushes the Source Code, Test Metrics and Coverage metrics into SonarQube and then checks the status of the SonarQube Quality Gate.  
        If the SonarQube quality date fails, the Pipeline fails and stops
        */ 
        stage("Check SonarQube Quality Gate") 
        {
            ispwHelper.downloadCopyBooks(workspace)            

            def sonarProjectName
            def componentList    = ispwHelper.getComponents(cesToken, pConfig.ispwContainer, pConfig.ispwContainerType)

            componentList.each
            {
                sonarProjectName = sonarHelper.determineProjectName('UT', it)

                sonarHelper.scan([
                    scanType:           'UT', 
                    scanProgramName:    it,
                    scanProjectName:    sonarProjectName
                    ])

                String sonarGateResult = sonarHelper.checkQualityGate()

                // Evaluate the status of the Quality Gate
                if (sonarGateResult != 'OK')
                {
                    echo "Sonar quality gate failure: ${sonarGateResult} \nfor program ${it}"

                    mailMessageExtension = mailMessageExtension +
                        "\nGenerated code for program ${it} FAILED the Quality gate. \n\nTo review results\n" +
                        "JUnit reports       : ${BUILD_URL}/testReport/ \n\n" +
                        "SonarQube dashboard : ${pConfig.sqServerUrl}/dashboard?id=${sonarProjectName}"

                    programStatusList[it] = 'FAILED'
                }
                else
                {
                    mailMessageExtension = mailMessageExtension +
                        "\nGenerated code for program ${it} PASSED the Quality gate and may be promoted. \n\n" +
                        "SonarQube results may be reviewed at ${pConfig.sqServerUrl}/dashboard?id=${sonarProjectName}\n\n"
                    
                    programStatusList[it] = 'PASSED'
                }   
            }
        }
    }

    return [pipelineResult: currentBuild.result, pipelineMailText: mailMessageExtension, pipelineConfig: pConfig, pipelineProgramStatusList: programStatusList]
}