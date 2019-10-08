package com.compuware.devops.util
import com.compuware.devops.jclskeleton.*

class TttHelper implements Serializable {

    def script
    def steps
    def pConfig

    JclSkeleton jclSkeleton 

    def listOfScenarios
    def listOfSources
    def listOfPrograms
    def listOfExecutedTargets 

    TttHelper(script, steps, pConfig) 
    {
        this.script                 = script
        this.steps                  = steps
        this.pConfig                = pConfig
        this.listOfExecutedTargets  = []

        jclSkeleton     = new JclSkeleton(steps, script.workspace, pConfig.ispwApplication, pConfig.applicationPathNum)
    }

    /* A Groovy idiosynchrasy prevents constructors to use methods, therefore class might require an additional "initialize" method to initialize the class */
    def initialize(componentList)
    {
        jclSkeleton.initialize()

        listOfPrograms     = componentList
    }

    def loopThruScenarios()
    {
        // Loop through list of programs/components and determine if there are testscenarios available
        // In that case execte the scenarios in a loop and add the component to the list of executed targets
        listOfPrograms.each
        {
            def programName = it
            // Search for any .testscenario file that contains the component name as part of its name
            listOfScenarios = steps.findFiles(glob: '**/'+ it + '*.testscenario')

            steps.echo "Found scenarios " + listOfScenarios.toString()

            listOfScenarios.each
            {
                // Get root node of the path, i.e. the name of the Total Test project
                def scenarioPath        = it.path                                                                   // Fully qualified name of the Total Test Scenario file
                def projectName         = it.path.trim().split("\\\\")[0] + "\\"+ it.path.trim().split("\\\\")[1]   // Total Test Project name is the root folder of the full path to the testscenario 
                def jclFolder           = script.workspace + "\\" + projectName + '\\Unit Test\\JCL'                // Path containing Runner.jcl
                def scenarioFullName    = it.name                                                                   // Get the full name of the testscenario file i.e. "name.testscenario"
                def scenarioName        = it.name.trim().split("\\.")[0]                                            // Get the name of the scenario file without ".testscenario"
                def scenarioTarget      = scenarioName.split("\\_")[0]                                              // Target Program will be the first part of the scenario name (convention)
        
                // Log which 
                steps.echo "*************************\n"        +
                    "Execute Scenario ${scenarioFullName}\n"    +
                    "*************************"
            
                def jclJobCardPath = jclFolder + '\\JobCard.jcl' 

                steps.writeFile(file: jclJobCardPath, text: jclSkeleton.jobCardJcl)

                steps.step([
                    $class:       'TotalTestBuilder', 
                        ccClearStats:   false,                                  // Clear out any existing Code Coverage stats for the given ccSystem and ccTestId
                        ccRepo:         "${pConfig.ccRepository}",
                        ccSystem:       "${pConfig.ispwApplication}", 
                        ccTestId:       "${pConfig.ispwSetId}",               // Jenkins environment variable, resolves to build number, i.e. #177 
                        credentialsId:  "${pConfig.hciTokenId}", 
                        deleteTemp:     true,                                   // (true|false) Automatically delete any temp files created during the execution
                        hlq:            '',                                     // Optional - high level qualifier used when allocation datasets
                        connectionId:   "${pConfig.hciConnId}",    
                        jcl:            "${pConfig.tttJcl}",                    // Name of the JCL file in the Total Test Project to execute
                        projectFolder:  "${projectName}",                       // Name of the Folder in the file system that contains the Total Test Project.  
                        testSuite:      "${scenarioFullName}",                  // Name of the Total Test Scenario to execute
                        useStubs:       true                                    // (true|false) - Execute with or without stubs
                ])   

                listOfExecutedTargets.add(programName)
            }
        }
        
        return listOfExecutedTargets        
    }

    def executeFunctionalTests()
    {
        listOfPrograms.each
        {
            def listOfScenarioFullPaths = steps.findFiles(glob: '**/'+ it + '*.xactx')

            if(listOfScenarioFullPaths.size() > 0)
            {
                steps.totaltest credentialsId:          "${pConfig.hciTokenId}", 
                    environmentId:                      "${pConfig.xaTesterEnvId}", 
                    folderPath:                         "tests/${it}_Functional_Tests", 
                    serverUrl:                          "${pConfig.ispwUrl}", 
                    stopIfTestFailsOrThresholdReached:  false,
                    sonarVersion:                       '6'
            }

            listOfExecutedTargets.add(it)
        }
    }

    def passResultsToJunit()
    {
        // Process the Total Test Junit result files into Jenkins
        steps.junit allowEmptyResults:    true, 
            keepLongStdio:                true,
            healthScaleFactor:            0.0,  
            testResults:                  "TTTUnit/*.xml"
    }

    def passFtResultsToJunit()
    {
        // Process the Total Test Junit result files into Jenkins
        steps.junit allowEmptyResults:    true, 
            keepLongStdio:                true,
            healthScaleFactor:            0.0,  
            testResults:                  "TTTReport/JUnitReport.xml"
    }

    def collectCodeCoverageResults()
    {
        // Code Coverage needs to match the code coverage metrics back to the source code in order for them to be loaded in SonarQube
        // The source variable is the location of the source that was downloaded from ISPW
        def sources="${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}"

        // The Code Coverage Plugin passes it's primary configuration in the string or a file
        def ccproperties = 'cc.sources=' + sources + '\rcc.repos=' + pConfig.ccRepository + '\rcc.system=' + pConfig.ispwApplication  + '\rcc.test=' + pConfig.ispwSetId

        steps.step([
            $class:                   'CodeCoverageBuilder',
                analysisProperties:         ccproperties,           // Pass in the analysisProperties as a string
                analysisPropertiesPath:     '',                     // Pass in the analysisProperties as a file.  Not used in this example
                connectionId:               "${pConfig.hciConnId}", 
                credentialsId:              "${pConfig.hciTokenId}"
        ])
    }

    def cleanUpCodeCoverageResults()
    {
        int testId = Integer.parseInt(script.BUILD_NUMBER) - 1

        steps.echo "Cleaning up Code Coverage results from previous job execution"
        steps.echo "Determined Test ID " + testId

        def cleanupJcl = jclSkeleton.createCleanUpCcRepo(pConfig.ispwApplication, testId.toString())

        steps.topazSubmitFreeFormJcl connectionId:  pConfig.hciConnId, 
            credentialsId:                          pConfig.hciTokenId, 
            jcl:                                    cleanupJcl, 
            maxConditionCode:                       '8'
    }
}