// Import the utility functionality.

import jobs.generation.*;

def project = GithubProject
def branch = GithubBranchName
def projectName = Utilities.getFolderName(project)
def projectFolder = projectName + '/' + Utilities.getFolderName(branch)

def static getOSGroup(def os) {
    def osGroupMap = ['Ubuntu':'Linux',
        'Ubuntu14.04':'Linux',
        'RHEL7.2': 'Linux',
        'Ubuntu16.04': 'Linux',
        'Debian8.4':'Linux',
        'Fedora23':'Linux',
        'Fedora24':'Linux',
        'OSX':'OSX',
        'Windows_NT':'Windows_NT',
        'FreeBSD':'FreeBSD',
        'CentOS7.1': 'Linux',
        'OpenSUSE13.2': 'Linux',
        'OpenSUSE42.1': 'Linux',
        'LinuxARMEmulator': 'Linux']
    def osGroup = osGroupMap.get(os, null)
    assert osGroup != null : "Could not find os group for ${os}"
    return osGroupMap[os]
}

// Setup perflab tests runs
[true, false].each { isPR ->
    ['Windows_NT'].each { os ->
        ['x64', 'x86'].each { arch ->
            [true, false].each { isSmoketest ->
                def architecture = arch
                def jobName = isSmoketest ? "perf_perflab_${os}_${arch}_smoketest" : "perf_perflab_${os}_${arch}"

                if (arch == 'x86jit32')
                {
                    architecture = 'x86'
                    testEnv = '-testEnv %WORKSPACE%\\tests\\x86\\compatjit_x86_testenv.cmd'
                }
                else if (arch == 'x86')
                {
                    testEnv = '-testEnv %WORKSPACE%\\tests\\x86\\ryujit_x86_testenv.cmd'
                }

                def newJob = job(Utilities.getFullJobName(project, jobName, isPR)) {
                    // Set the label.
                    label('windows_server_2016_clr_perf')
                    wrappers {
                        credentialsBinding {
                            string('BV_UPLOAD_SAS_TOKEN', 'CoreCLR Perf BenchView Sas')
                        }
                    }

                    if (isPR)
                    {
                        parameters
                        {
                            stringParam('BenchviewCommitName', '\${ghprbPullTitle}', 'The name that you will be used to build the full title of a run in Benchview.  The final name will be of the form <branch> private BenchviewCommitName')
                        }
                    }
                    if (isSmoketest)
                    {
                        parameters
                        {
                            stringParam('XUNIT_PERFORMANCE_MAX_ITERATION', '2', 'Sets the number of iterations to two.  We want to do this so that we can run as fast as possible as this is just for smoke testing')
                            stringParam('XUNIT_PERFORMANCE_MAX_ITERATION_INNER_SPECIFIED', '2', 'Sets the number of iterations to two.  We want to do this so that we can run as fast as possible as this is just for smoke testing')
                        }
                    }
                    else
                    {
                        parameters
                        {
                            stringParam('XUNIT_PERFORMANCE_MAX_ITERATION', '21', 'Sets the number of iterations to twenty one.  We are doing this to limit the amount of data that we upload as 20 iterations is enought to get a good sample')
                            stringParam('XUNIT_PERFORMANCE_MAX_ITERATION_INNER_SPECIFIED', '21', 'Sets the number of iterations to twenty one.  We are doing this to limit the amount of data that we upload as 20 iterations is enought to get a good sample')
                        }
                    }
                    def configuration = 'Release'
                    def runType = isPR ? 'private' : 'rolling'
                    def benchViewName = isPR ? 'coreclr private %BenchviewCommitName%' : 'coreclr rolling %GIT_BRANCH_WITHOUT_ORIGIN% %GIT_COMMIT%'
                    def uploadString = isSmoketest ? '' : '-uploadToBenchview'

                    steps {
                        // Batch

                        batchFile("powershell wget https://dist.nuget.org/win-x86-commandline/latest/nuget.exe -OutFile \"%WORKSPACE%\\nuget.exe\"")
                        batchFile("if exist \"%WORKSPACE%\\Microsoft.BenchView.JSONFormat\" rmdir /s /q \"%WORKSPACE%\\Microsoft.BenchView.JSONFormat\"")
                        batchFile("\"%WORKSPACE%\\nuget.exe\" install Microsoft.BenchView.JSONFormat -Source http://benchviewtestfeed.azurewebsites.net/nuget -OutputDirectory \"%WORKSPACE%\" -Prerelease -ExcludeVersion")
                        //Do this here to remove the origin but at the front of the branch name as this is a problem for BenchView
                        //we have to do it all as one statement because cmd is called each time and we lose the set environment variable
                        batchFile("if \"%GIT_BRANCH:~0,7%\" == \"origin/\" (set \"GIT_BRANCH_WITHOUT_ORIGIN=%GIT_BRANCH:origin/=%\") else (set \"GIT_BRANCH_WITHOUT_ORIGIN=%GIT_BRANCH%\")\n" +
                        "set \"BENCHVIEWNAME=${benchViewName}\"\n" +
                        "set \"BENCHVIEWNAME=%BENCHVIEWNAME:\"=%\"\n" +
                        "py \"%WORKSPACE%\\Microsoft.BenchView.JSONFormat\\tools\\submission-metadata.py\" --name \"%BENCHVIEWNAME%\" --user-email \"dotnet-bot@microsoft.com\"\n" +
                        "py \"%WORKSPACE%\\Microsoft.BenchView.JSONFormat\\tools\\build.py\" git --branch %GIT_BRANCH_WITHOUT_ORIGIN% --type ${runType}")
                        batchFile("py \"%WORKSPACE%\\Microsoft.BenchView.JSONFormat\\tools\\machinedata.py\"")
                        batchFile("set __TestIntermediateDir=int&&build.cmd ${configuration} ${architecture}")

                        if (arch == 'x86jit32')
                        {
                            // Download package and copy compatjit into Core_Root
                            batchFile("C:\\Tools\\nuget.exe install runtime.win7-${architecture}.Microsoft.NETCore.Jit -Source https://dotnet.myget.org/F/dotnet-core -OutputDirectory \"%WORKSPACE%\" -Prerelease -ExcludeVersion\n" +
                            "xcopy \"%WORKSPACE%\\runtime.win7-x86.Microsoft.NETCore.Jit\\runtimes\\win7-x86\\native\\compatjit.dll\" \"%WORKSPACE%\\bin\\Product\\${os}.${architecture}.${configuration}\" /Y")
                        }

                        batchFile("tests\\runtest.cmd ${configuration} ${architecture} GenerateLayoutOnly")

                        // Run with just stopwatch: Profile=Off
                        batchFile("tests\\scripts\\run-xunit-perf.cmd -arch ${arch} -configuration ${configuration} -testBinLoc bin\\tests\\${os}.${architecture}.${configuration}\\performance\\perflab\\Perflab -library -generateBenchviewData \"%WORKSPACE%\\Microsoft.Benchview.JSONFormat\\tools\" ${uploadString} -runtype ${runType} -stabilityPrefix \"START \"CORECLR_PERF_RUN\" /B /WAIT /HIGH /AFFINITY 0x2\"")
                        batchFile("tests\\scripts\\run-xunit-perf.cmd -arch ${arch} -configuration ${configuration} -testBinLoc bin\\tests\\${os}.${architecture}.${configuration}\\Jit\\Performance\\CodeQuality -generateBenchviewData \"%WORKSPACE%\\Microsoft.Benchview.JSONFormat\\tools\" ${uploadString} -runtype ${runType} -stabilityPrefix \"START \"CORECLR_PERF_RUN\" /B /WAIT /HIGH /AFFINITY 0x2\"")

                        // Run with the full set of counters enabled: Profile=On
                        batchFile("tests\\scripts\\run-xunit-perf.cmd -arch ${arch} -configuration ${configuration} -testBinLoc bin\\tests\\${os}.${architecture}.${configuration}\\performance\\perflab\\Perflab -library -generateBenchviewData \"%WORKSPACE%\\Microsoft.Benchview.JSONFormat\\tools\" ${uploadString} -runtype ${runType} -collectionFlags default+BranchMispredictions+CacheMisses+InstructionRetired+gcapi -stabilityPrefix \"START \"CORECLR_PERF_RUN\" /B /WAIT /HIGH /AFFINITY 0x2\"")
                        batchFile("tests\\scripts\\run-xunit-perf.cmd -arch ${arch} -configuration ${configuration} -testBinLoc bin\\tests\\${os}.${architecture}.${configuration}\\Jit\\Performance\\CodeQuality -generateBenchviewData \"%WORKSPACE%\\Microsoft.Benchview.JSONFormat\\tools\" ${uploadString} -runtype ${runType} -collectionFlags default+BranchMispredictions+CacheMisses+InstructionRetired+gcapi -stabilityPrefix \"START \"CORECLR_PERF_RUN\" /B /WAIT /HIGH /AFFINITY 0x2\"")
                    }
                }

                if (isSmoketest)
                {
                    Utilities.setMachineAffinity(newJob, "Windows_NT", '20170427-elevated')
                }
                // Save machinedata.json to /artifact/bin/ Jenkins dir
                def archiveSettings = new ArchivalSettings()
                archiveSettings.addFiles('Perf-*.xml')
                archiveSettings.addFiles('Perf-*.etl')
                archiveSettings.addFiles('Perf-*.log')
                archiveSettings.addFiles('machinedata.json')
                Utilities.addArchival(newJob, archiveSettings)

                Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")

                newJob.with {
                    wrappers {
                        timeout {
                            absolute(240)
                        }
                    }
                }

                if (isPR) {
                    TriggerBuilder builder = TriggerBuilder.triggerOnPullRequest()
                    if (isSmoketest)
                    {
                        builder.setGithubContext("${os} ${arch} CoreCLR Perf Tests Correctness")
                    }
                    else
                    {
                        builder.setGithubContext("${os} ${arch} CoreCLR Perf Tests")
                        builder.triggerOnlyOnComment()
                        builder.setCustomTriggerPhrase("(?i).*test\\W+${os}\\W+${arch}\\W+perf.*")
                    }
                    builder.triggerForBranch(branch)
                    builder.emitTrigger(newJob)
                }
                else {
                    // Set a push trigger
                    TriggerBuilder builder = TriggerBuilder.triggerOnCommit()
                    builder.emitTrigger(newJob)
                }
            }
        }
    }
}

// Create the Linux/OSX/CentOS coreclr test leg for debug and release and each scenario
[true, false].each { isPR ->
    ['Ubuntu16.04'].each { os ->
        def newJob = job(Utilities.getFullJobName(project, "perf_${os}", isPR)) {

            label('ubuntu_1604_clr_perf')
            wrappers {
                credentialsBinding {
                    string('BV_UPLOAD_SAS_TOKEN', 'CoreCLR Perf BenchView Sas')
                }
            }

            if (isPR)
            {
                parameters
                {
                    stringParam('BenchviewCommitName', '\${ghprbPullTitle}', 'The name that you will be used to build the full title of a run in Benchview.  The final name will be of the form <branch> private BenchviewCommitName')
                }
            }

            // Cap the maximum number of iterations to 21.
            parameters {
                stringParam('XUNIT_PERFORMANCE_MAX_ITERATION', '21', 'Sets the number of iterations to twenty one.  We are doing this to limit the amount of data that we upload as 20 iterations is enought to get a good sample')
                stringParam('XUNIT_PERFORMANCE_MAX_ITERATION_INNER_SPECIFIED', '21', 'Sets the number of iterations to twenty one.  We are doing this to limit the amount of data that we upload as 20 iterations is enought to get a good sample')
            }

            def osGroup = getOSGroup(os)
            def architecture = 'x64'
            def configuration = 'Release'
            def runType = isPR ? 'private' : 'rolling'
            def benchViewName = isPR ? 'coreclr private \$BenchviewCommitName' : 'coreclr rolling \$GIT_BRANCH_WITHOUT_ORIGIN \$GIT_COMMIT'

            steps {
                shell("./tests/scripts/perf-prep.sh")
                shell("./init-tools.sh")
                shell("./build.sh ${architecture} ${configuration}")
                shell("GIT_BRANCH_WITHOUT_ORIGIN=\$(echo \$GIT_BRANCH | sed \"s/[^/]*\\/\\(.*\\)/\\1 /\")\n" +
                "python3.5 \"\${WORKSPACE}/tests/scripts/Microsoft.BenchView.JSONFormat/tools/submission-metadata.py\" --name \" ${benchViewName} \" --user-email \"dotnet-bot@microsoft.com\"\n" +
                "python3.5 \"\${WORKSPACE}/tests/scripts/Microsoft.BenchView.JSONFormat/tools/build.py\" git --branch \$GIT_BRANCH_WITHOUT_ORIGIN --type ${runType}")
                shell("""./tests/scripts/run-xunit-perf.sh \\
                --testRootDir=\"\${WORKSPACE}/bin/tests/Windows_NT.${architecture}.${configuration}\" \\
                --testNativeBinDir=\"\${WORKSPACE}/bin/obj/${osGroup}.${architecture}.${configuration}/tests\" \\
                --coreClrBinDir=\"\${WORKSPACE}/bin/Product/${osGroup}.${architecture}.${configuration}\" \\
                --mscorlibDir=\"\${WORKSPACE}/bin/Product/${osGroup}.${architecture}.${configuration}\" \\
                --coreFxBinDir=\"\${WORKSPACE}/corefx/bin/${osGroup}.AnyCPU.${configuration};\${WORKSPACE}/corefx/bin/Unix.AnyCPU.${configuration};\${WORKSPACE}/corefx/bin/AnyOS.AnyCPU.${configuration}\" \\
                --coreFxNativeBinDir=\"\${WORKSPACE}/corefx/bin/${osGroup}.${architecture}.${configuration}\" \\
                --runType=\"${runType}\" \\
                --benchViewOS=\"${os}\" \\
                --generatebenchviewdata=\"\${WORKSPACE}/tests/scripts/Microsoft.BenchView.JSONFormat/tools\" \\
                --stabilityPrefix=\"taskset 0x00000002 nice --adjustment=-10\" \\
                --uploadToBenchview""")
            }
        }

        // Save machinedata.json to /artifact/bin/ Jenkins dir
        def archiveSettings = new ArchivalSettings()
        archiveSettings.addFiles('Perf-*.log')
        archiveSettings.addFiles('Perf-*.xml')
        archiveSettings.addFiles('machinedata.json')
        Utilities.addArchival(newJob, archiveSettings)

        Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")

        // For perf, we need to keep the run results longer
        newJob.with {
            // Enable the log rotator
            logRotator {
                artifactDaysToKeep(7)
                daysToKeep(300)
                artifactNumToKeep(25)
                numToKeep(1000)
            }
        }
        if (isPR) {
            TriggerBuilder builder = TriggerBuilder.triggerOnPullRequest()
            builder.setGithubContext("${os} Perf Tests")
            builder.triggerOnlyOnComment()
            builder.setCustomTriggerPhrase("(?i).*test\\W+${os}\\W+perf.*")
            builder.triggerForBranch(branch)
            builder.emitTrigger(newJob)
        }
        else {
            // Set a push trigger
            TriggerBuilder builder = TriggerBuilder.triggerOnCommit()
            builder.emitTrigger(newJob)
        }
    } // os
} // isPR