#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import jenkins.plugins.http_request.*
import java.net.URL
import com.compuware.devops.util.*

/**
 Helper Methods for the Pipeline Script
*/

/**
 Determine the ISPW Path Number for use in Total Test
 @param Level - Level Parameter is the Level returned in the ISPW Webhook
*/
/*
def String getPathNum(String level)
{
    return level.charAt(level.length() - 1)
}
*/

/**
Call method to execute the pipeline from a shared library
@param pipelineParams - Map of paramter/value pairs
*/
def call(Map pipelineParams)
{
    node
    {
        /* Initialization */
        PipelineConfig  pConfig     = new   PipelineConfigNew(
                                                steps, 
                                                pipelineParams
                                            )

        echo "After pConfig Instance"

        pConfig.initialize()                                            

        GitHelper       gitHelper   = new   GitHelper(
                                                steps
                                            )

        IspwHelper      ispwHelper  = new   IspwHelper(
                                                steps, 
                                                pConfig
                                                )

        def ResponseContentSupplier response3

        def assignmentList = []

        withCredentials([string(credentialsId: pConfig.cesTokenId, variable: 'cesTokenClear')]) 
        {
            assignmentList = ispwHelper.getAssigmentList(cesTokenClear, pConfig.ispwTargetLevel)
        }

        /* Execution */
        stage("Retrieve Code From ISPW")
        {
            ispwHelper.downloadSources()
        }

        stage("Retrieve Copy Books From ISPW")
        {
            ispwHelper.downloadCopyBooks("${workspace}")
        }

        stage("Retrieve Tests")
        {
            //Retrieve the Tests from Github that match that ISPWW Stream and Application            
            def gitUrlFullPath = "${pConfig.gitUrl}/${pConfig.gitTttRepo}"
            
            gitHelper.gitcheckout(gitUrlFullPath, pConfig.gitBranch, pConfig.gitCredentials, pConfig.tttFolder)
        }

        // findFiles method requires the "Pipeline Utilities Plugin"
        // Get all testscenario files in the current workspace into an array
        def TTTListOfScenarios  = findFiles(glob: '**/*.testscenario')

        // Get all Cobol Sources in the MF_Source folder into an array 
        def ListOfSources       = findFiles(glob: "**/${pConfig.ispwApplication}/${pConfig.mfSourceFolder}/*.cbl")

        // Define a empty array for the list of programs
        def ListOfPrograms      = []
        
        // Determine program names for each source member
        ListOfSources.each
        {
            // The split method uses regex to search for patterns, therefore
            // Backslashes, Dots and Underscores which mean certain patterns in regex need to be escaped 
            // The backslash in Windows paths is duplicated in Java, therefore it need to be escaped twice
            // Trim ./cbl from the Source members to populate the array of program names
            ListOfPrograms.add(it.name.trim().split("\\.")[0])
        }

        /* 
        This stage executes any Total Test Projects related to the mainframe source that was downloaded
        */ 
        stage("Execute related Unit Tests")
        {
            // Loop through all downloaded Topaz for Total Test scenarios
            TTTListOfScenarios.each
            {

                // Get root node of the path, i.e. the name of the Total Test project
                def TTTScenarioPath        = it.path // Fully qualified name of the Total Test Scenario file
                def TTTProjectName         = it.path.trim().split("\\\\")[0] + "\\"+ it.path.trim().split("\\\\")[1]  // Total Test Project name is the root folder of the full path to the testscenario 
                def TTTScenarioFullName    = it.name  // Get the full name of the testscenario file i.e. "name.testscenario"
                def TTTScenarioName        = it.name.trim().split("\\.")[0]  // Get the name of the scenario file without ".testscenario"
                def TTTScenarioTarget      = TTTScenarioName.split("\\_")[0]  // Target Program will be the first part of the scenario name (convention)
        
                // For each of the scenarios walk through the list of source files and determine if the target matches one of the programs
                // In that case, execute the unit test.  Determine if the program name matches the target of the Total Test scenario
                if(ListOfPrograms.contains(TTTScenarioTarget))
                {
                    // Log which 
                    println "*************************"
                    println "Scenario " + TTTScenarioFullName
                    println "Path " + TTTScenarioPath
                    println "Project " + TTTProjectName
                    println "*************************"
                
                    step([
                        $class:       'TotalTestBuilder', 
                            ccClearStats:   false,                // Clear out any existing Code Coverage stats for the given ccSystem and ccTestId
                            ccRepo:         "${pConfig.ccRepository}",
                            ccSystem:       "${pConfig.ispwApplication}", 
                            ccTestId:       "${BUILD_NUMBER}",        // Jenkins environment variable, resolves to build number, i.e. #177 
                            credentialsId:  "${pConfig.hciTokenId}", 
                            deleteTemp:     true,                   // (true|false) Automatically delete any temp files created during the execution
                            hlq:            '',                            // Optional - high level qualifier used when allocation datasets
                            connectionId:   "${pConfig.hciConnId}",    
                            jcl:            "${pConfig.tttJcl}",                  // Name of the JCL file in the Total Test Project to execute
                            projectFolder:  "${TTTProjectName}", // Name of the Folder in the file system that contains the Total Test Project.  
                            testSuite:      "${TTTScenarioFullName}",// Name of the Total Test Scenario to execute
                            useStubs:       true
                    ])                   // (true|false) - Execute with or without stubs
                }
            }

            // Process the Total Test Junit result files into Jenkins
            junit allowEmptyResults:    true, 
                keepLongStdio:          true, 
                testResults:            "TTTUnit/*.xml"
        }

        /* 
        This stage retrieve Code Coverage metrics from Xpediter Code Coverage for the test executed in the Pipeline
        */ 
        stage("Collect Coverage Metrics")
        {
            // Code Coverage needs to match the code coverage metrics back to the source code in order for them to be loaded in SonarQube
            // The source variable is the location of the source that was downloaded from ISPW
            def String sources="${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}"

            // The Code Coverage Plugin passes it's primary configuration in the string or a file
            def ccproperties = 'cc.sources=' + sources + '\rcc.repos=' + pConfig.ccRepository + '\rcc.system=' + pConfig.ispwApplication  + '\rcc.test=' + BUILD_NUMBER

            step([
                $class:                   'CodeCoverageBuilder',
                    analysisProperties:         ccproperties,       // Pass in the analysisProperties as a string
                    analysisPropertiesPath:     '',             // Pass in the analysisProperties as a file.  Not used in this example
                    connectionId:               "${pConfig.hciConnId}", 
                    credentialsId:              "${pConfig.hciTokenId}"
            ])
        }

        /* 
        This stage pushes the Source Code, Test Metrics and Coverage metrics into SonarQube and then checks the status of the SonarQube Quality Gate.  
        If the SonarQube quality date fails, the Pipeline fails and stops
        */ 
        stage("Check SonarQube Quality Gate") 
        {
            // Requires SonarQube Scanner 2.8+
            // Retrieve the location of the SonarQube Scanner.  
            def scannerHome = tool "${pConfig.sqScannerName}";

            withSonarQubeEnv("${pConfig.sqServerName}")       // 'localhost' is the name of the SonarQube server defined in Jenkins / Configure Systems / SonarQube server section
            {
                // Finds all of the Total Test results files that will be submitted to SonarQube
                def TTTListOfResults    = findFiles(glob: 'TTTSonar/*.xml')   // Total Test SonarQube result files are stored in TTTSonar directory

                // Build the sonar testExecutionReportsPaths property
                // Start will the property itself
                def SQ_TestResult       = "-Dsonar.testExecutionReportPaths="    

                // Loop through each result Total Test results file found
                TTTListOfResults.each 
                {
                    def TTTResultName   = it.name   // Get the name of the Total Test results file   
                    SQ_TestResult       = SQ_TestResult + "TTTSonar/" + it.name +  ',' // Append the results file to the property
                }

                // Build the rest of the SonarQube Scanner Properties
                
                // Test and Coverage results
                def SQ_Scanner_Properties   = " -Dsonar.tests=tests ${SQ_TestResult} -Dsonar.coverageReportPaths=Coverage/CodeCoverage.xml"
                // SonarQube project to load results into
                SQ_Scanner_Properties       = SQ_Scanner_Properties + " -Dsonar.projectKey=${JOB_NAME} -Dsonar.projectName=${JOB_NAME} -Dsonar.projectVersion=1.0"
                // Location of the Cobol Source Code to scan
                SQ_Scanner_Properties       = SQ_Scanner_Properties + " -Dsonar.sources=${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}"
                // Location of the Cobol copybooks to scan
                SQ_Scanner_Properties       = SQ_Scanner_Properties + " -Dsonar.cobol.copy.directories=${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}"  
                // File extensions for Cobol and Copybook files.  The Total Test files need that contain tests need to be defined as cobol for SonarQube to process the results
                SQ_Scanner_Properties       = SQ_Scanner_Properties + " -Dsonar.cobol.file.suffixes=cbl,testsuite,testscenario,stub -Dsonar.cobol.copy.suffixes=cpy -Dsonar.sourceEncoding=UTF-8"
                
                // Call the SonarQube Scanner with properties defined above
                bat "${scannerHome}/bin/sonar-scanner" + SQ_Scanner_Properties
            }
        
            // Wait for the results of the SonarQube Quality Gate
            timeout(time: 2, unit: 'MINUTES') {
                
                // Wait for webhook call back from SonarQube.  SonarQube webhook for callback to Jenkins must be configured on the SonarQube server.
                def qg = waitForQualityGate()
                
                // Evaluate the status of the Quality Gate
                if (qg.status != 'OK')
                {
                    echo "Sonar quality gate failure: ${qg.status}"
                    echo "Pipeline will be aborted and ISPW Assignment(s) will be regressed"

                    for(int i = 0; i < assignmentList.size(); i++)
                    {

                        echo "Regress Assignment ${assignmentList[0].toString()}, Level ${pConfig.ispwTargetLevel}"
                        
                        def requestBodyParm = '''{
                            "runtimeConfiguration": "''' + pConfig.ispwRuntime + '''"
                        }'''

                        withCredentials(
                            [string(credentialsId: "${pConfig.cesTokenId}", variable: 'cesToken')]
                        ) 
                        {

                            response3 = steps.httpRequest(
                                url:                    "${pConfig.ispwUrl}/ispw/${pConfig.ispwRuntime}/assignments/${assignmentList[i].toString()}/tasks/regress?level=${pConfig.ispwTargetLevel}",
                                httpMode:               'POST',
                                consoleLogResponseBody: true,
                                contentType:            'APPLICATION_JSON',
                                requestBody:            requestBodyParm,
                                customHeaders:          [[
                                                        maskValue:    true, 
                                                        name:           'authorization', 
                                                        value:          "${cesToken}"
                                                        ]]
                            )
                        }                    
                    }
                        
                    // Email
                    emailext subject:       '$DEFAULT_SUBJECT',
                                body:       '$DEFAULT_CONTENT',
                                replyTo:    '$DEFAULT_REPLYTO',
                                to:         "${pConfig.mailRecipient}"
                    
                    error "Exiting Pipeline" // Exit the pipeline with an error if the SonarQube Quality Gate is failing
                }
            }   
        }

        /* 
        This stage triggers a XL Release Pipeline that will move code into the high levels in the ISPW Lifecycle  
        */ 
        stage("Start release in XL Release")
        {
            // Use the Path Number to determine what QA Path to Promote the code from in ISPW.  This example has seperate QA paths in ISPW Lifecycle (i.e. DEV1->QA1->STG->PRD / DEV2->QA2->STG->PRD)
            def XLRPath = "QA" + PathNum 

            // Trigger XL Release Jenkins Plugin to kickoff a Release
            xlrCreateRelease(
                releaseTitle:       'A Release for $BUILD_TAG',
                serverCredentials:  "${pConfig.xlrUser}",
                startRelease:       true,
                template:           "${pConfig.xlrTemplate}",
                variables:          [
                                        [propertyName:  'ISPW_Dev_level',   propertyValue: "${pConfig.ispwTargetLevel}"], // Level in ISPW that the Code resides currently
                                        [propertyName:  'ISPW_RELEASE_ID',  propertyValue: "${pConfig.ispwRelease}"],     // ISPW Release value from the ISPW Webhook
                                        [propertyName:  'CES_Token',        propertyValue: "${pConfig.cesTokenId}"]
                                    ]
            )
        }        
    }
}