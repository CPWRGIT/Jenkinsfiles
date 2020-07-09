package com.compuware.devops.util

/* 
    Pipeline execution specific and server specific parameters which are use throughout the pipeline
*/
class PipelineConfig implements Serializable
{
    def steps
    def mailListLines
    def mailListMap = [:]

    private String configPath           = 'pipeline'            // Path containing config files after downloading them from Git Hub Repository 'config\\pipeline'
    private String pipelineConfigFile   = 'pipeline.yml'        // Config file containing pipeline configuration

    private String workspace

    private params

/* Environment specific settings, which differ between Jenkins servers and applications, but not between runs */
    public String gitTargetBranch                               // Used for synchronizing TTT project stored in Git with programs stored in ISPW
    public String gitBranch                                     // Used for synchronizing TTT project stored in Git with programs stored in ISPW
    
    public String sqScannerName                                 // Sonar Qube Scanner Tool name as defined in "Manage Jenkins" -> "Global Tool Configuration" -> "SonarQube scanner"
    public String sqServerName                                  // Sonar Qube Scanner Server name as defined in "Manage Jenkins" -> "Configure System" -> "SonarQube servers"
    public String sqServerUrl                                   // URL to the SonarQube server
    public String sqHttpRequestAuthHeader                       // Value for Authorization header for http Requests to SonarQube
    public String xaTesterUrl                                   // URL to the XATester repository
    public String xaTesterEnvId                                 // XATester Environment ID
    public String mfSourceFolder                                // Folder containing sources after downloading from ISPW
    public String xlrTemplate                                   // XL Release template to start
    public String xlrUser                                       // XL Release user to use
    public String tttFolder                                     // Folder containing TTT projects after downloading from Git Hub
    public String ispwUrl                                       // ISPW/CES URL for native REST API calls
    public String ispwRuntime                                   // ISPW Runtime

/* Runtime specific settings, which differ runs and get passed as parameters or determined during execution */
    public String ispwStream
    public String ispwApplication
    public String ispwRelease
    public String ispwAssignment
    public String ispwContainer
    public String ispwContainerType
    public String ispwSrcLevel
    public String ispwTargetLevel
    public String ispwOwner         
    public String applicationPathNum

    public String gitProject        
    public String gitCredentials    
    public String gitUrl            
    public String gitTttRepo        
    public String gitTttUtRepo        
    public String gitTttFtRepo        

    public String cesTokenId        
    public String hciConnId         
    public String hciTokenId        
    public String ccRepository      

    public String tttJcl 
      
    public String mailRecipient 

    public ces  
    public hci  
    public ispw 
    public ttt  
    public git  
    public sonar     
    public xlr  
    public mail = [:]

    def PipelineConfig(steps, workspace, params, mailListLines)
    {
        //this.configGitBranch    = params.Config_Git_Branch
        this.steps              = steps
        this.workspace          = workspace
        this.mailListLines      = mailListLines
        this.params             = params

        this.ispwStream         = params.ISPW_Stream
        this.ispwApplication    = params.ISPW_Application
        this.ispwRelease        = params.ISPW_Release
        this.ispwAssignment     = params.ISPW_Assignment
        this.ispwContainer      = params.ISPW_Set_Id
        this.ispwContainerType  = params.ISPW_Container_Type
        this.ispwOwner          = params.ISPW_Owner        
        this.ispwSrcLevel       = params.ISPW_Src_Level

        this.applicationPathNum = ispwSrcLevel.charAt(ispwSrcLevel.length() - 1)
        this.ispwTargetLevel    = "QA" + applicationPathNum
        this.tttJcl             = "Runner_PATH" + applicationPathNum + ".jcl"
    }

    /* A Groovy idiosynchrasy prevents constructors to use methods, therefore class might require an additional "initialize" method to initialize the class */
    def initialize()
    {
        setServerConfig()

        setMailConfig()    
    }

    /* Read configuration values from pipeline.config file */
    def setServerConfig()
    {
        def configFilePath      = "${configPath}/${pipelineConfigFile}"
        def configFileText      = steps.libraryResource configFilePath
        def tmpConfig           = steps.readYaml(text: configFileText)

        this.gitProject         = tmpConfig.git.project
        this.gitCredentials     = tmpConfig.git.credentials
        
        this.gitUrl             = "https://github.com/${gitProject}"
        this.gitTttRepo         = "${ispwStream}_${ispwApplication}_Unit_Tests.git"
        this.gitTttUtRepo       = "${ispwStream}_${ispwApplication}_Unit_Tests.git"
        this.gitTttFtRepo       = "${ispwStream}_${ispwApplication}_Functional_Tests.git"

        this.cesTokenId         = tmpConfig.ces.jenkinsToken
        this.hciConnId          = tmpConfig.hci.connectionId
        this.hciTokenId         = tmpConfig.hci.hostToken
        this.ccRepository       = tmpConfig.ttt.cocoRepository

        this.sqScannerName      = tmpConfig.sonar.scannerName 
        this.sqServerName       = tmpConfig.sonar.serverHost
        this.sqServerUrl        = tmpConfig.sonar.serverUrl 
        this.xaTesterUrl        = tmpConfig.ttt.ftServerUrl 
        this.mfSourceFolder     = tmpConfig.ispw.localFolder
        this.xlrTemplate        = tmpConfig.xlr.template
        this.xlrUser            = tmpConfig.xlr.user 
        this.tttFolder          = tmpConfig.ttt.utFolder
        this.ispwUrl            = tmpConfig.ispw.url 
        this.ispwRuntime        = tmpConfig.ispw.runtime 
        this.gitBranch          = tmpConfig.ttt.gitBranch
        this.xaTesterEnvId      = tmpConfig.ttt.ftEnvironment

        this.ces                        = tmpConfig.ces
        this.hci                        = tmpConfig.hci
        this.ispw                       = tmpConfig.ispw
        this.ttt                        = tmpConfig.ttt
        this.git                        = tmpConfig.git
        this.sonar                      = tmpConfig.sonar      
        this.xlr                        = tmpConfig.xlr

        this.ispw.stream                = params.ISPW_Stream
        this.ispw.application           = params.ISPW_Application
        this.ispw.release               = params.ISPW_Release
        this.ispw.assignment            = params.ISPW_Assignment
        this.ispw.container             = params.ISPW_Set_Id
        this.ispw.containerType         = params.ISPW_Container_Type
        this.ispw.owner                 = params.ISPW_Owner        
        this.ispw.srcLevel              = params.ISPW_Src_Level

        this.ispw.applicationPathNum    = ispwSrcLevel.charAt(ispwSrcLevel.length() - 1)
        this.ispw.targetLevel           = "QA" + applicationPathNum

        this.ttt.runnerJcl              = "Runner_PATH" + applicationPathNum + ".jcl"

        this.git.url                    = "${this.git.server}/${this.git.project}"
        this.git.tttRepo                = "${this.ispw.stream}_${this.ispw.application}_Unit_Tests.git"
        this.git.tttUtRepo              = "${this.ispw.stream}_${this.ispw.application}_Unit_Tests.git"
        this.git.tttFtRepo              = "${this.ispw.stream}_${this.ispw.application}_Functional_Tests.git"

    }

    /* Read list of email addresses from config file */
    def setMailConfig()
    {        
        def lineToken
        def tsoUser
        def emailAddress

        mailListLines.each
        {
            lineToken       = it.toString().tokenize(":")
            tsoUser         = lineToken.get(0).toString()
            emailAddress    = lineToken.get(1).toString().trim()

            this.mailListMap."${tsoUser}" = "${emailAddress}"
        }

        this.mailRecipient  = mailListMap[(ispwOwner.toUpperCase())]
        this.mail.recipient = mailListMap[(ispwOwner.toUpperCase())]
    }
}