package com.compuware.devops.util

/* 
    Pipeline execution specific and server specific parameters which are use throughout the pipeline
*/
class PipelineConfig implements Serializable
{
    def steps

/* Environment specific settings, which differ between Jenkins servers and applications, but not between runs */
    public String gitTargetBranch   = "CONS"
    public String gitBranch         = "master"
    public String sqScannerName     = "scanner" //"Scanner" //"scanner" 
    public String sqServerName      = "localhost"  //"CWCC" //"localhost"  
    public String sqServerUrl       = 'http://sonarqube.nasa.cpwr.corp:9000'
    public String mfSourceFolder    = "MF_Source"
    public String xlrTemplate       = "A Release from Jenkins" //"A Release from Jenkins - RNU" //"A Release from Jenkins"
    public String xlrUser           = "admin"    //"xebialabs" //"admin"                           
    public String tttFolder         = "tests"	
    public String ispwUrl           = "http://cwcc.compuware.com:2020"
    public String ispwRuntime       = "ispw"		 

/* Runtime specific settings, which differ runs and get passed as parameters or determined during execution */
    public String ispwStream
    public String ispwApplication
    public String ispwRelease
    public String ispwContainer
    public String ispwContainerType
    public String ispwSrcLevel
    public String ispwTargetLevel
    public String ispwOwner         
    public String applicationPathNum

    public String gitProject        
    public String gitCredentials    
    public String gitURL            
    public String gitTttRepo        

    public String cesTokenId        
    public String cesTokenClear     
    public String hciConnId         
    public String hciTokenId        
    public String ccRepository      

    public String tttJcl 
      
    public String mailRecipient 

    def PipelineConfig(steps, params)
    {
        this.steps              = steps

        steps.echo "Instancialisation"

        this.ispwStream         = params.ISPW_Stream
        this.ispwApplication    = params.ISPW_Application
        this.ispwRelease        = params.ISPW_Release
        this.ispwContainer      = params.ISPW_Container
        this.ispwContainerType  = params.ISPW_Container_Type
        this.ispwOwner          = params.ISPW_Owner        
        this.ispwSrcLevel       = params.ISPW_Src_Level

        steps.echo "After ISPW " + ispwStream

        this.applicationPathNum = ispwSrcLevel.charAt(ispwSrcLevel.length() - 1)
        this.ispwTargetLevel    = "QA" + applicationPathNum
        this.tttJcl             = "Runner_PATH" + applicationPathNum + ".jcl"

        steps.echo "After PathNum"

        this.gitProject         = params.Git_Project
        this.gitCredentials     = params.Git_Credentials
        this.gitURL             = "https://github.com/${gitProject}"
        this.gitTttRepo         = "${ispwStream}_${ispwApplication}_Unit_Tests.git"

        steps.echo "After Git"

        this.cesTokenId         = params.CES_Token
        this.cesTokenClear      = params.CES_Token
        this.hciConnId          = params.HCI_Conn_ID
        this.hciTokenId         = params.HCI_Token
        this.ccRepository       = params.CC_repository

        steps.echo "After Tokens"

        MailList mailList       = new MailList()
        this.mailRecipient      = mailList.getEmail(ispwOwner)

    }

}