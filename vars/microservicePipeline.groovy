def call() {
  
   // Centralized mapping for Environments and Kubernetes Namespaces
    def getEnvConfig = {
        switch(env.BRANCH_NAME) {
            case ['main', 'master']:
                return [env: 'prod', namespace: 'prod-jenkins-agents']

            case 'staging':
                return [env: 'staging', namespace: 'staging-jenkins-agents']

            case 'develop':
                return [env: 'dev', namespace: 'dev-jenkins-agents']

            default:
                return [env: 'ci', namespace: 'ci-jenkins-agents']
        }
    }

    // Helper function for consistent status and UI descriptions
    def updateStageStatus = { String stageName, String details = "" ->
        env.FAILED_STAGE = stageName

        def prefix = env.DEPLOY_ENV ?
            "[${env.DEPLOY_ENV.toUpperCase()}]" :
            "[${env.BRANCH_NAME ?: 'INIT'}]"

        def info = details ? " | ${details}" : ""

        currentBuild.description =
            "${prefix} Stage: ${stageName}${info}"
    }

  pipeline {
      agent {
          kubernetes {
              namespace getEnvConfig().namespace
              defaultContainer 'jnlp'
              yaml """
              apiVersion: v1
              kind: Pod
              metadata:
                labels:
                  app: jenkins-build-agent
              spec:
                serviceAccountName: jenkins-ecr-builder-sa
                restartPolicy: Never
                securityContext:
                  runAsNonRoot: true
                  runAsUser: 1000
                  runAsGroup: 1000
                  fsGroup: 1000
                containers:
                  - name: jnlp
                    image: jenkins/inbound-agent:jdk21
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    resources:
                      requests: { cpu: "100m", memory: "128Mi" }
                      limits:   { cpu: "500m", memory: "512Mi" }
                      
                  - name: node
                    image: node:20-alpine
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    command: [/bin/sh, -c, "cat"]
                    tty: true
                    resources:
                      requests: { cpu: "100m", memory: "128Mi" }
                      limits:   { cpu: "1", memory: "1Gi" }

                  - name: golang
                    image: golang:1.22-alpine
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    command: [/bin/sh, -c, "cat"]
                    tty: true
                    resources:
                      requests: { cpu: "100m", memory: "128Mi"}
                      limits:   { cpu: "1", memory: "1Gi" }

                  - name: maven
                    image: maven:3.9-eclipse-temurin-17
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    command: [/bin/sh, -c, "cat"]
                    tty: true
                    resources:
                      requests: { cpu: "100m", memory: "128Mi" }
                      limits:   { cpu: "1", memory: "1Gi" }

                  - name: kaniko
                    image: gcr.io/kaniko-project/executor:v1.20.0-debug
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    command: [/busybox/cat]
                    tty: true
                    resources:
                      requests: { cpu: "500m", memory: "512Mi" }
                      limits:   { cpu: "4", memory: "4Gi" }

                  - name: trivy
                    image: aquasec/trivy:0.50.1
                    imagePullPolicy: IfNotPresent
                    workingDir: /home/jenkins
                    command: [/bin/sh, -c, "cat"]
                    tty: true
                    resources:
                      requests: { cpu: "100m", memory: "128Mi" }
                      limits:   { cpu: "1", memory: "1Gi" }

            """
          }
      }

      options {
          disableConcurrentBuilds()
          timeout(time: 60, unit: 'MINUTES')
          buildDiscarder(logRotator(numToKeepStr: '10'))
      }

      environment {
          APP_REPO           = "https://github.com/10ajaykumar/test_travelbooking.git"
          GITOPS_REPO        = "https://github.com/10ajaykumar/test_travelbooking-gitops.git"
          GIT_CREDENTIALS    = "github-user"
          AWS_ACCOUNT_ID     = "605199373656"
          AWS_DEFAULT_REGION = "us-east-1"
          ECR_REGISTRY       = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com"
          FAILED_STAGE       = "Initialization"
      }

      stages {
          stage('Clean Workspace') {
              steps {
                  script {
                      updateStageStatus("Clean Workspace", "Clearing workspace directory")
                      cleanWs(deleteDirs: true)
                  }
              }
          }

          stage('Checkout') {
              steps {
                  script {
                      updateStageStatus("Checkout", "Checking out repository")
                      dir('application') {
                          git url: APP_REPO, branch: env.BRANCH_NAME, credentialsId: GIT_CREDENTIALS
                      }
                  }
              }
          }

          stage('Determine Environment & Config') {
              steps {
                  script {
                      env.DEPLOY_ENV = getEnvConfig().env

                      // Read configuration files ONCE into memory
                      env.OWNERS_CONFIG   = readFile('application/ci/service-owners.yaml')
                      env.SERVICES_CONFIG = readFile('application/services.yaml')

                      env.GIT_SHORT = sh(script: "git -C application rev-parse --short=10 HEAD", returnStdout: true).trim()
                      def rawReleaseTag = sh(script: "git -C application tag --points-at HEAD", returnStdout: true).trim()
                      env.IMAGE_TAG = rawReleaseTag ? rawReleaseTag.replaceFirst("^v", "") : "${env.GIT_SHORT}-${env.BUILD_NUMBER}"

                      updateStageStatus("Determine Environment & Config", "Env: ${env.DEPLOY_ENV} | Tag: ${env.IMAGE_TAG}")
                  }
              }
          }

          stage('Detect Changed Services') {
              steps {
                  dir('application') {
                      script {
                          updateStageStatus("Detect Changed Services", "Evaluating git diff")
                          def serviceConfig = readYaml text: env.SERVICES_CONFIG
                          
                          String previousCommit = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: "HEAD~1"
                          String currentCommit = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
                          
                          def changedFiles = sh(script: "git diff --name-only ${previousCommit} ${currentCommit} || git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()

                          if (!changedFiles) {
                              updateStageStatus("Detect Changed Services", "No files changed - Skipping build")
                              currentBuild.result = 'SUCCESS'
                              return
                          }

                          def changedServices = serviceConfig.services.findAll { service ->
                              changedFiles.split("\n").any { file -> file.startsWith("${service.path}/") }
                          }

                          if (changedServices.isEmpty()) {
                              updateStageStatus("Detect Changed Services", "No microservices modified - Skipping build")
                              currentBuild.result = 'SUCCESS'
                              return
                          }

                          env.CHANGED_SERVICES = changedServices.collect { it.name }.join(",")
                          updateStageStatus("Detect Changed Services", "Changed: ${env.CHANGED_SERVICES}")
                      }
                  }
              }
          }


          stage('Build & Push Images') {
              when {
                  allOf {
                      expression { env.DEPLOY_ENV != "ci" }
                      expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != "" }
                  }
              }
              steps {
                  container('kaniko') {
                      script {
                          updateStageStatus("Build & Push Images", "Kaniko building: ${env.CHANGED_SERVICES}")
                          def serviceConfig = readYaml text: env.SERVICES_CONFIG
                          def changedList = env.CHANGED_SERVICES.split(",")

                          serviceConfig.services.findAll { changedList.contains(it.name) }.each { service ->
                              def ecrRepo = service.ecrRepo ?: "${env.DEPLOY_ENV}-${service.name}"

                              sh """
                                  /kaniko/executor \
                                  --context=dir://${WORKSPACE}/application/${service.path} \
                                  --dockerfile=${WORKSPACE}/application/${service.path}/Dockerfile \
                                  --destination=${ECR_REGISTRY}/${ecrRepo}:${IMAGE_TAG} \
                                  --destination=${ECR_REGISTRY}/${ecrRepo}:latest \
                                  --cleanup \
                                  --cache=true
                              """
                          }
                      }
                  }
              }
          }

          stage('Trivy Image Scan') {
              when {
                  allOf {
                      expression { env.DEPLOY_ENV != "ci" }
                      expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != "" }
                  }
              }
              steps {
                  container('trivy') {
                      script {
                          updateStageStatus("Trivy Image Scan", "Scanning: ${env.CHANGED_SERVICES}")
                          def serviceConfig = readYaml text: env.SERVICES_CONFIG
                          def changedList = env.CHANGED_SERVICES.split(",")

                          serviceConfig.services.findAll { changedList.contains(it.name) }.each { service ->
                              def ecrRepo = service.ecrRepo ?: "${env.DEPLOY_ENV}-${service.name}"

                              sh """
                                  trivy image \
                                  --ignore-unfixed \
                                  --severity HIGH,CRITICAL \
                                  --exit-code 1 \
                                  ${ECR_REGISTRY}/${ecrRepo}:${IMAGE_TAG}
                              """
                          }
                      }
                  }
              }
          }

          stage('Deployment Approval') {
              agent none
              when {
                  allOf {
                      expression { env.DEPLOY_ENV in ['staging', 'prod'] }
                      expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != "" }
                  }
              }
              steps {
                  script {
                      updateStageStatus("Deployment Approval", "Awaiting input for ${env.DEPLOY_ENV}")
                      
                      def owners = readYaml text: env.OWNERS_CONFIG
                      def approvalEmails = owners.approval[env.DEPLOY_ENV]

                      emailext(
                          to: approvalEmails.join(","),
                          subject: "Deployment Approval Required - ${env.JOB_NAME}",
                          body: "Approval required for ${env.CHANGED_SERVICES} to ${env.DEPLOY_ENV} with tag ${env.IMAGE_TAG}."
                      )

                      try {
                          timeout(time: 30, unit: 'MINUTES') {
                              def approver = input(
                                  message: "Deploy to ${env.DEPLOY_ENV}?\nServices: ${env.CHANGED_SERVICES}\nTag: ${env.IMAGE_TAG}",
                                  ok: "Deploy",
                                  submitterParameter: "APPROVED_BY"
                              )
                              updateStageStatus("Deployment Approval", "Approved by ${approver}")
                          }
                      } catch (err) {
                          updateStageStatus("Deployment Approval", "Rejected or Timed Out for ${env.DEPLOY_ENV}")
                          error "Deployment approval was either rejected or timed out after 30 minutes."
                      }
                  }
              }
          }

          stage('Update Helm Values (GitOps)') {
              when {
                  allOf {
                      expression { env.DEPLOY_ENV != "ci" }
                      expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != "" }
                  }
              }
              steps {
                  script {
                      updateStageStatus("Update Helm Values (GitOps)", "Waiting for GitOps lock: ${env.DEPLOY_ENV}")

                      lock(resource: "gitops-${env.DEPLOY_ENV}") {
                          updateStageStatus("Update Helm Values (GitOps)", "GitOps lock acquired for ${env.DEPLOY_ENV}")

                          withCredentials([
                              usernamePassword(
                                  credentialsId: GIT_CREDENTIALS,
                                  usernameVariable: 'GIT_USER',
                                  passwordVariable: 'GIT_TOKEN'
                              )
                          ]) {
                              dir('gitops-repo') {
                                  deleteDir()

                                  git(
                                      url: GITOPS_REPO,
                                      branch: "main",
                                      credentialsId: GIT_CREDENTIALS
                                  )

                                  def serviceConfig = readYaml text: env.SERVICES_CONFIG
                                  def changedList = env.CHANGED_SERVICES.split(",")

                                  serviceConfig.services.findAll { changedList.contains(it.name) }.each { service ->
                                      def ecrRepo = service.ecrRepo ?: "${env.DEPLOY_ENV}-${service.name}"
                                      def valuesFile = "environments/${env.DEPLOY_ENV}/${service.name}/values.yaml"

                                      echo """
                                          ========================================
                                          GitOps Update
                                          ========================================
                                          Environment : ${env.DEPLOY_ENV}
                                          Service     : ${service.name}
                                          ECR Repo    : ${ecrRepo}
                                          Image Tag   : ${env.IMAGE_TAG}
                                          Values File : ${valuesFile}
                                          ========================================
                                          """

                                      sh """
                                          test -f '${valuesFile}' || {
                                              echo "ERROR: Values file not found: ${valuesFile}"
                                              exit 1
                                          }

                                          sed -i 's/^\\(\\s*tag:\\s*\\).*/\\1"${IMAGE_TAG}"/' '${valuesFile}'
                                      """
                                  }

                                  sh '''
                                      set -e

                                      git config user.name "Jenkins CI"
                                      git config user.email "jenkins-ci@your-org.com"

                                      git add .

                                      if git diff --cached --quiet; then
                                          echo "No GitOps changes detected."
                                          exit 0
                                      fi

                                      git commit -m "chore(gitops): update ${CHANGED_SERVICES} tag to ${IMAGE_TAG} for ${DEPLOY_ENV} [skip ci]"

                                      git config --local http.extraheader "AUTHORIZATION: basic $(echo -n ${GIT_USER}:${GIT_TOKEN} | base64)"

                                      for i in {1..5}; do
                                          echo "GitOps push attempt ${i}/5"

                                          git fetch origin main
                                          git rebase origin/main

                                          if git push origin HEAD:main; then
                                              echo "GitOps push successful."
                                              exit 0
                                          fi

                                          echo "Push failed. Retrying in 5 seconds..."
                                          sleep 5
                                      done

                                      echo "ERROR: Failed to push GitOps changes."
                                      exit 1
                                  '''
                              }
                          }

                          updateStageStatus("Update Helm Values (GitOps)", "GitOps update completed successfully")
                      }
                  }
              }
          }
      }

      post {
          failure {
              script {
                  currentBuild.description = "[${env.DEPLOY_ENV ?: 'FAILED'}] Failed at Stage: ${env.FAILED_STAGE}"
                  if (env.OWNERS_CONFIG) {
                      try {
                          def owners = readYaml text: env.OWNERS_CONFIG
                          def email = owners.stage_owners[env.FAILED_STAGE]?.join(",") ?: owners.approval['prod']?.join(",")
                          if (email) {
                              emailext(
                                  subject: "Pipeline Failed - ${env.JOB_NAME}",
                                  body: """
                                      Jenkins Pipeline Failed
                                      Job Name      : ${env.JOB_NAME}
                                      Build Number  : ${env.BUILD_NUMBER}
                                      Failed Stage  : ${env.FAILED_STAGE}
                                      Status        : ${currentBuild.description}
                                      Branch        : ${env.BRANCH_NAME}
                                      Image Tag     : ${env.IMAGE_TAG ?: 'N/A'}
                                      Services      : ${env.CHANGED_SERVICES ?: 'N/A'}

                                      Please check the attached reports and logs.

                                      Build URL:
                                      ${env.BUILD_URL}
                                  """,
                                  to: email,
                                  attachLog: true,
                                  attachmentsPattern: '**/*.html, **/*.xml'
                              )
                          }
                      } catch (Exception e) {
                          echo "Email notification error: ${e.message}"
                      }
                  }
              }
          }
          success {
              script {
                  currentBuild.description = "[${env.DEPLOY_ENV}] SUCCESS | ${env.CHANGED_SERVICES ?: 'No changes'} (${env.IMAGE_TAG})"
              }
          }
          always {
              cleanWs(deleteDirs: true)
          }
      }
  }
}