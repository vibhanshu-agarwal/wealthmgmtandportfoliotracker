# Infrastructure Bootstrap Runbook

## 1. Overview

This document outlines the manual "chicken and egg" steps required to initialize the AWS environment before the CI/CD pipeline can take over. These resources are not managed by the main Terraform stack to prevent circular dependencies.

## 2. Prerequisites

- AWS CLI installed and configured (`aws configure`)
- IAM permissions for S3 and DynamoDB

## 3. Step-by-Step Initialization

### 3.1 Create Terraform state S3 bucket

The state file tracks the current health and configuration of all AWS resources.

```bash
# Create the bucket (name must be globally unique).
aws s3api create-bucket \
    --bucket vibhanshu-tf-state-2026 \
    --region ap-south-1 \
    --create-bucket-configuration LocationConstraint=ap-south-1

# Enable versioning (critical for state recovery).
aws s3api put-bucket-versioning \
    --bucket vibhanshu-tf-state-2026 \
    --versioning-configuration Status=Enabled
```

### 3.2 Create Terraform lock table (DynamoDB)

This table prevents concurrent Terraform operations from corrupting state.

```bash
aws dynamodb create-table \
    --table-name vibhanshu-terraform-locks \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region ap-south-1
```

### 3.3 Configure GitHub Actions secrets

Once the resources above are created, add these repository secrets:

- `TF_STATE_BUCKET`: `vibhanshu-tf-state-2026`
- `TF_LOCK_TABLE`: `vibhanshu-terraform-locks`
- `AWS_ACCESS_KEY_ID`: IAM access key ID
- `AWS_SECRET_ACCESS_KEY`: IAM secret access key
- `AWS_REGION`: `ap-south-1`

## 4. Verification

Run Terraform init from `infrastructure/terraform`:

```bash
terraform init -reconfigure
```

If you see `Terraform has been successfully initialized!`, bootstrap is complete.
