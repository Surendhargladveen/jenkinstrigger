pipeline {
    agent any

    parameters {

        // RDS maintenance
        choice(name: 'AWS_REGION', 
               choices: ['us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 
                         'af-south-1', 'ap-east-1', 'ap-south-1', 'ap-south-2', 
                         'ap-southeast-1', 'ap-southeast-2', 'ap-southeast-3', 
                         'ap-southeast-4', 'ap-northeast-1', 'ap-northeast-2', 
                         'ap-northeast-3', 'ca-central-1', 'cn-north-1', 
                         'cn-northwest-1', 'eu-central-1', 'eu-central-2', 
                         'eu-north-1', 'eu-south-1', 'eu-south-2', 
                         'eu-west-1', 'eu-west-2', 'eu-west-3', 
                         'me-central-1', 'me-south-1', 
                         'sa-east-1', 'us-gov-east-1', 'us-gov-west-1'],
               description: 'Select AWS Region')

        string(name: 'DB_INSTANCE_IDENTIFIER', defaultValue: '', description: 'Enter RDS DB instance identifier')
        string(name: 'DB_ENDPOINT', defaultValue: '', description: 'Enter SQL Server RDS Endpoint')
        choice(name: 'TAKE_SNAPSHOT', choices: ['No', 'Yes'], description: 'Take snapshot before cleanup/restart?')
                
        choice(name: 'CLEAN_Query', choices: ['No', 'Yes'], description: 'Clean idle or long-running sessions?')

        choice(name: 'RESTART_DB', choices: ['No', 'Yes'], description: 'Restart the RDS instance?')
    }

    environment {
        SNAPSHOT_IDENTIFIER = "snapshot-${env.BUILD_NUMBER}-${new Date().format('yyyyMMddHHmmss')}"
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.DB_ENDPOINT?.trim() || !params.DB_INSTANCE_IDENTIFIER?.trim()) {
                        error("DB_ENDPOINT and DB_INSTANCE_IDENTIFIER are required.")
                    }
                }
            }
        }

        stage('Take DB Snapshot (if selected)') {
            when {
                expression { params.TAKE_SNAPSHOT == 'Yes' }
            }
            steps {
                script {
                    echo "Taking snapshot: ${SNAPSHOT_IDENTIFIER}"
                    sh """
                        aws rds create-db-snapshot \
                            --db-snapshot-identifier ${SNAPSHOT_IDENTIFIER} \
                            --db-instance-identifier ${params.DB_INSTANCE_IDENTIFIER} \
                            --region ${params.AWS_REGION}
                    """
                }
            }
        }

        stage('Wait for Snapshot Completion') {
            when {
                expression { params.TAKE_SNAPSHOT == 'Yes' }
            }
            steps {
                script {
                    echo "Waiting for snapshot to complete..."
                    sh """
                        aws rds wait db-snapshot-available \
                            --db-snapshot-identifier ${SNAPSHOT_IDENTIFIER} \
                            --region ${params.AWS_REGION}
                    """
                }
            }
        }

        stage('Clean Unnecessary SQL Sessions') {
            when {
                expression { params.CLEAN_QUERIES == 'Yes' }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'sqlserver-rds-credentials', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')]) {
                    script {
                        echo "Cleaning idle or long-running sessions on ${params.DB_ENDPOINT}..."

                        sh """
                            sqlcmd -S ${params.DB_ENDPOINT} -U $DB_USER -P $DB_PASS -Q "
                                DECLARE @sql NVARCHAR(MAX) = N'';

                                SELECT @sql += 'KILL ' + CAST(session_id AS NVARCHAR) + '; '
                                FROM sys.dm_exec_sessions
                                WHERE is_user_process = 1
                                  AND status IN ('sleeping')
                                  AND login_name != 'rdsadmin';

                                EXEC(@sql);
                            "
                        """
                    }
                }
            }
        }

        stage('Restart RDS Instance (if selected)') {
            when {
                expression { params.RESTART_DB == 'Yes' }
            }
            steps {
                script {
                    echo "Restarting RDS instance: ${params.DB_INSTANCE_IDENTIFIER}"
                    sh """
                        aws rds reboot-db-instance \
                            --db-instance-identifier ${params.DB_INSTANCE_IDENTIFIER} \
                            --region ${params.AWS_REGION}
                    """
                }
            }
        }
    }

    post {
        always {
            echo "RDS Restart Completed Successfully."
        }
    }
}